package io.github.fulizhe.otelstore.demo.deps;

import java.util.LinkedHashMap;
import java.util.Map;
import redis.clients.jedis.Jedis;
import redis.embedded.RedisServer;

/**
 * Redis 那一跳：Jedis 裸客户端 + 进程内服务端。
 *
 * <p><b>为什么不用 {@code spring-boot-starter-data-redis}</b>：它管理的 Lettuce 是 6.x，
 * 而 agent 2.32.0 的 Lettuce 仪表化只支持到 5.x。落在范围外的后果是
 * <b>静默不生效</b> —— 代码跑得通、连接成功、span 就是没有，而页面上看不出任何异常。
 * 这不是"配置错了"，是选型错了，所以整个 starter 都不引。
 *
 * <p><b>服务端版本很老（redis-server 2.8.19）而且不用升级</b>：
 * Jedis 的 span 来自<b>客户端方法</b>，服务端是 2.8 还是 7.0 与埋点无关。
 * 看到"版本号这么低"就顺手升级是这个项目里最容易犯又最难查的一类多余动作。
 *
 * <p>库名是 {@code demo}，端口 6379 —— <b>避开 16379</b>，那是被本机 docker 容器占着的。
 */
public final class RedisDependency implements DependencyProbe {

    public static final String KEY = DepsRegistry.REDIS;
    private static final String TITLE = "Redis（进程内，Jedis 客户端）";

    private final String host;
    private final int port;
    private final int timeoutMs;

    private volatile RedisServer server;
    /** 实际起在哪个端口。配置端口被占时会退让，所以它与 {@link #port} 可能不同。 */
    private volatile int actualPort = -1;

    public RedisDependency() {
        this("127.0.0.1", 6379, 1000);
    }

    public RedisDependency(final String host, final int port, final int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
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

    public String host() {
        return host;
    }

    /** 配置的端口（未必是实际用的那个 —— 被占时会退让，见 {@link #actualPort()}）。 */
    public int port() {
        return port;
    }

    /** 实际监听的端口。没用退让时与 {@link #port()} 相同。 */
    public int actualPort() {
        return actualPort;
    }

    /**
     * 起服务端并<b>真的打一次 PING</b>。
     *
     * <p>只探"进程在不在"说明不了什么：内嵌服务端的端口在它 crash 之后仍然可能是通的
     * （被别的进程占着）。所以探到<b>PING 真的回 PONG</b>才算数。
     */
    @Override
    public DepStatus probe() {
        try {
            startConfiguredOrFallback();
            final String pong = new Jedis(host, actualPort, timeoutMs).ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                return DepStatus.notReady(KEY, TITLE, true,
                        "连上了但 PING 回的不是 PONG（拿到 " + pong + "）—— 那个端口上可能是别的服务");
            }
            final String where = actualPort == port
                    ? String.valueOf(port)
                    : actualPort + "（配置的 " + port + " 被占，已退让）";
            return DepStatus.ready(KEY, TITLE, true,
                    "进程内服务端就绪（起在 " + where + "，PING 已回 PONG）");
        } catch (final Exception e) {
            // 起不来的常见原因是 exe 被拦或端口被占，两者的处置完全不同，所以带上原因
            return DepStatus.notReady(KEY, TITLE, true,
                    "起不来（" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " — " + e.getMessage())
                            + "）。降级：这一跳不会出现在链路图上，进程照常启动（ADR-7）");
        }
    }

    /**
     * 先试配置端口，<b>被占就退到随机空闲端口重试一次</b>。
     *
     * <p>不是洁癖，是实测出来的：真机验收时配置的 6379 上蹲着一个<b>上次跑残留的
     * redis-server</b>，于是这一跳整个降级 —— 而它本可以照常工作。
     * 主体对读口端口立的就是"端口冲突不阻塞启动、退随机并报出实际值"，
     * 靶子这一跳没理由做得更差。
     *
     * <p>只重试一次、只换端口：换端口解决"被占"，解决不了"exe 被拦"，
     * 后者多试几次也是一样的结果。
     */
    private void startConfiguredOrFallback() throws Exception {
        if (server != null) {
            return;
        }
        try {
            startOn(port);
        } catch (final RuntimeException first) {
            server = null;                       // 起了一半也算没起，重试前先清
            startOn(freePort());
        }
    }

    private synchronized void startOn(final int p) {
        final RedisServer s = RedisServer.builder().port(p).setting("maxmemory 128M").build();
        s.start();
        server = s;
        actualPort = p;
    }

    private static int freePort() {
        try (final java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            return probe.getLocalPort();
        } catch (final java.io.IOException e) {
            throw new IllegalStateException("找不到空闲端口", e);
        }
    }

    /**
     * SET + GET + DEL。
     *
     * <p>只打 {@code PING} 是不够的：它能证明连上了，但证明不了 span 上得去的是
     * <b>带参数的那类命令</b>（Jedis 的仪表化按方法分派，两条路径不完全一样）。
     * 三条命令凑齐，才算把这一跳真的打透。
     */
    public Map<String, Object> call() {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        // 超时显式收紧：靶子起不来的最常见表现是端点挂在那里不返回
        try (final Jedis jedis = new Jedis(host, actualPort, timeoutMs)) {
            final String key = "otelstore:demo";
            final String value = "hello-from-demo";
            m.put("ping", jedis.ping());
            m.put("set", jedis.set(key, value));
            // 读到同一个值才算真的通了 —— 只看 set 的返回值是"我以为写进去了"
            m.put("get", jedis.get(key));
            m.put("del", Long.valueOf(jedis.del(key)));
        }
        return m;
    }

    /** 关掉服务端。进程退出时不必调 —— 内嵌进程随宿主一起走。 */
    void stop() {
        final RedisServer s = server;
        server = null;
        actualPort = -1;
        if (s != null) {
            try {
                s.stop();
            } catch (final Exception e) {
                // 关不掉不影响正确性：进程要退了，端口跟着释放
            }
        }
    }
}