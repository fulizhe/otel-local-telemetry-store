package io.github.fulizhe.otelstore.demo.deps;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import kafka.server.KafkaConfig;
import kafka.server.KafkaServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.utils.Time;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;

/**
 * Kafka 那一跳：**内嵌 broker + 内嵌 ZooKeeper**（都在进程内，零外部进程）。
 *
 * <p><b>为什么不是 KRaft</b>：这是实测出来的，不是选型偏好 —— KRaft 在这台 Windows 上
 * <b>起不来</b>。{@code kafka.raft.KafkaRaftManager.buildRaftClient} 会把
 * {@code @metadata-0/quorum-state.tmp} 改名成 {@code quorum-state}，而源文件此时仍被本进程
 * 持有句柄，Windows 拒绝这个 rename（{@code FileSystemException: 另一个程序正在使用此文件}）。
 * Kafka 官方本就不支持 broker 跑在 Windows 上。ZK 模式不走那条状态文件，实测能起。
 *
 * <p>版本仍钉 2.8.2：它是 ZK 模式的末代之一（3.x 也可，但 2.8.2 与本地 kafka-clients 同源），
 * 字节码实测 52，仍支持 Java 8。
 *
 * <p><b>端口</b>：broker 9092 / ZooKeeper 2181。<b>避开 19092</b>（本机 docker 的 Kafka 占着）。
 *
 * <p><b>这一跳是五跳里唯一能同时看到两种 kind 的地方</b>：producer 产出
 * {@code kind=PRODUCER}、consumer 产出 {@code kind=CONSUMER}。
 * 段 B 的瀑布按 kind 上色时，只有这一跳能证明那两种颜色真的画得出来。
 */
public final class KafkaDependency implements DependencyProbe {

    public static final String KEY = DepsRegistry.KAFKA;
    private static final String TITLE = "Kafka（进程内 broker + 进程内 ZooKeeper）";

    private static final String TOPIC = "otelstore-demo";
    private static final int PARTITIONS = 1;

    private final String bootstrap;
    private final int zkPort;
    private final int callTimeoutMs;

    private volatile KafkaServer broker;
    private volatile NIOServerCnxnFactory zkFactory;
    private volatile ZooKeeperServer zkServer;

    public KafkaDependency() {
        this(9092, 2181, 8000);
    }

    /**
     * @param brokerPort    broker 对外端口
     * @param zkPort        ZooKeeper 端口 —— 必须与 broker 那个<b>不同</b>
     * @param callTimeoutMs 端点调用的超时上限。<b>必须收紧</b>：broker 起来要几秒，
     *                      不收紧的话靶子会挂在那里像卡死了
     */
    public KafkaDependency(final int brokerPort, final int zkPort, final int callTimeoutMs) {
        this.bootstrap = "127.0.0.1:" + brokerPort;
        this.zkPort = zkPort;
        this.callTimeoutMs = callTimeoutMs;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public boolean embedded() {
        return true;
    }

    public String bootstrap() {
        return bootstrap;
    }

    public String topic() {
        return TOPIC;
    }

    /**
     * 起 ZK、起 broker、建 topic，并<b>真的收发一条</b>。
     *
     * <p>探测到"能收发"而不是"端口在听"：broker 与 controller 的选举是异步的，
     * 端口在听不等于它已经能接受生产 —— 那样探出来的 ready=true 会让第一个调用者白等。
     */
    @Override
    public DepStatus probe() {
        try {
            startZooKeeper();
            startBroker();
            ensureTopic();
            sendAndReceive("probe");
            return DepStatus.ready(KEY, TITLE, true,
                    "进程内 broker 就绪（broker " + bootstrap + " / ZK " + zkPort
                            + "，topic " + TOPIC + " 已建，且已收发一条）");
        } catch (final Exception e) {
            return DepStatus.notReady(KEY, TITLE, true,
                    "起不来（" + describe(e) + "）。降级：这一跳不会出现在链路图上，"
                            + "进程照常启动（ADR-7）");
        }
    }

    private synchronized void startZooKeeper() throws Exception {
        if (zkServer != null) {
            return;
        }
        final File root = tempDir("otelstore-zk");
        final File snapshot = new File(root, "snapshot");
        final File log = new File(root, "log");
        if (!snapshot.mkdirs() || !log.mkdirs()) {
            throw new IOException("建 ZooKeeper 数据目录失败：" + root);
        }
        final ZooKeeperServer server = new ZooKeeperServer(snapshot, log, 2000);
        final NIOServerCnxnFactory factory = new NIOServerCnxnFactory();
        factory.configure(new InetSocketAddress("127.0.0.1", zkPort), 20);
        factory.startup(server);
        zkServer = server;
        zkFactory = factory;
    }

    private synchronized void startBroker() throws Exception {
        if (broker != null) {
            return;
        }
        final File logs = new File(tempDir("otelstore-kafka"), "kafka-logs");
        // 目录先建出来：broker 只会往里写，不会替我们 mkdir
        if (!logs.mkdirs() && !logs.isDirectory()) {
            throw new IOException("建 broker 日志目录失败：" + logs);
        }

        final Properties p = new Properties();
        p.setProperty("broker.id", "1");
        p.setProperty("zookeeper.connect", "127.0.0.1:" + zkPort);
        p.setProperty("listeners", "PLAINTEXT://" + bootstrap);
        p.setProperty("log.dirs", logs.getAbsolutePath());
        // 单节点没有第二个副本可等：任何"等副本"的默认值都会让 topic 永远建不出来
        p.setProperty("offsets.topic.replication.factor", "1");
        p.setProperty("transaction.state.log.replication.factor", "1");
        p.setProperty("transaction.state.log.min.isr", "1");
        p.setProperty("num.partitions", "1");
        p.setProperty("default.replication.factor", "1");

        final KafkaServer server = new KafkaServer(
                new KafkaConfig(p), Time.SYSTEM, scala.Option.empty(), false);
        server.startup();
        broker = server;
    }

    private void ensureTopic() throws Exception {
        final Properties p = new Properties();
        p.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.setProperty(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(callTimeoutMs));
        p.setProperty(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, String.valueOf(callTimeoutMs));
        try (final AdminClient admin = AdminClient.create(p)) {
            admin.createTopics(Arrays.asList(new NewTopic(TOPIC, PARTITIONS, (short) 1))).all().get();
        } catch (final Exception e) {
            // 已存在不算错：探测与第一次调用可能都在建 topic
            if (String.valueOf(e.getMessage()).indexOf("TopicExists") < 0) {
                throw e;
            }
        }
    }

    /**
     * 发一条并收一条。
     *
     * <p><b>consumer 用 {@code assign()} 而不是 {@code subscribe()}</b>：后者要靠 group 协调
     * 与 rebalance 才拿到分区，在单节点刚起来的几秒里很容易等超时，而那个等待
     * 在端点上就表现成"卡住了"。
     *
     * <p><b>刻意不 close consumer</b>：参考项目记过 —— broker 不可达时 {@code close()}
     * 会无限阻塞。consumer 的资源随进程走，靶子不值得为"整洁"付这个代价。
     * 将来谁要补 close，必须同时说明为什么那个阻塞不会发生。
     */
    public Map<String, Object> sendAndReceive(final String text) throws Exception {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();

        final Properties prod = new Properties();
        prod.setProperty("bootstrap.servers", bootstrap);
        prod.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        prod.setProperty("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        prod.setProperty("request.timeout.ms", String.valueOf(callTimeoutMs));
        try (final KafkaProducer<String, String> p = new KafkaProducer<String, String>(prod)) {
            p.send(new ProducerRecord<String, String>(TOPIC, "k", text)).get();
            m.put("sent", text);
        }

        final Properties cons = new Properties();
        cons.setProperty("bootstrap.servers", bootstrap);
        cons.setProperty("group.id", "otelstore-demo-probe");
        cons.setProperty("key.deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer");
        cons.setProperty("value.deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer");
        cons.setProperty("auto.offset.reset", "earliest");
        final KafkaConsumer<String, String> c = new KafkaConsumer<String, String>(cons);
        // assign 直接给分区，跳过 rebalance
        c.assign(Arrays.asList(new org.apache.kafka.common.TopicPartition(TOPIC, 0)));
        c.seekToBeginning(c.assignment());
        final ConsumerRecords<String, String> all =
                c.poll(java.time.Duration.ofMillis(callTimeoutMs));
        final List<String> got = new ArrayList<String>();
        for (final ConsumerRecord<String, String> r : all) {
            got.add(r.value());
        }
        // 发出去了却没收到自己那条 —— 要当成失败。返回"发出成功"会让对账看起来正常，
        // 而 CONSUMER span 并不存在，那才是这张票真正要验的东西
        if (!got.contains(text)) {
            throw new IllegalStateException(
                    "发出去了但没收到（收到 " + got.size() + " 条，其中没有自己那条）");
        }
        m.put("received", Integer.valueOf(got.size()));
        m.put("match", text);
        return m;
    }

    /** 关掉 broker 与 ZK。进程退出时不必调 —— 内嵌进程随宿主一起走。 */
    synchronized void stop() {
        final KafkaServer b = broker;
        broker = null;
        if (b != null) {
            try {
                b.shutdown();
                b.awaitShutdown();
            } catch (final RuntimeException e) {
                // 关不掉不影响正确性：进程要退了，端口跟着释放
            }
        }
        final NIOServerCnxnFactory f = zkFactory;
        zkFactory = null;
        zkServer = null;
        if (f != null) {
            f.shutdown();
        }
    }

    private static String describe(final Throwable t) {
        final StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName());
        if (t.getMessage() != null) {
            sb.append(" — ").append(t.getMessage());
        }
        // 带最上面三帧：这一跳起不来的报错常常"不说路径"，只看类名与消息定位不到
        final StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 3; i++) {
            sb.append(" ← ").append(st[i]);
        }
        return sb.toString();
    }

    /** 临时目录：broker / ZK 的数据目录必须可写，且退出后不在项目目录里留东西。 */
    private static File tempDir(final String prefix) {
        try {
            final File f = File.createTempFile(prefix, "");
            if (!f.delete() || !f.mkdirs()) {
                throw new IllegalStateException("建临时目录失败：" + f);
            }
            f.deleteOnExit();
            return f;
        } catch (final IOException e) {
            throw new IllegalStateException("建临时目录失败", e);
        }
    }
}