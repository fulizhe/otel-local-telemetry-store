package io.github.fulizhe.otelstore.demo.deps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kafka 那一跳：<b>真的起一个内嵌 broker + ZooKeeper，发一条收一条</b>，然后关掉。
 *
 * <p>这张票最该被钉住的就是「内嵌 Kafka 在进程里真的能收发」——
 * 而那只有连上才算数。端口随机，所以并行跑不撞端口。
 *
 * <p><b>为什么不是 KRaft</b>：KRaft 在这台 Windows 上起不来（quorum-state 的 rename
 * 撞上 Windows 的文件占用语义）。这条事实是用这个测试的方式探出来的：
 * 先让探测抛异常，把栈打出来看，才知道该换 ZK 模式。写在这里是免得后人再试一遍 KRaft。
 */
class KafkaDependencyTest {

    private static int freePort() throws Exception {
        try (final ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @DisplayName("内嵌 broker 能起能发能收（端口随机）")
    void embeddedBrokerServesOneRoundTrip() throws Exception {
        final KafkaDependency dep =
                new KafkaDependency(freePort(), freePort(), 20000);
        try {
            final DepStatus s = dep.probe();
            assertTrue(s.ready(), "内嵌 broker 应该起得来：" + s.detail());
            assertTrue(s.detail().contains("已收发一条"),
                    "探测要真的收发过一次，不能只探端口在听：" + s.detail());

            final Map<String, Object> ops = dep.sendAndReceive("hello");
            assertEquals("hello", ops.get("sent"));
            assertEquals("hello", ops.get("match"), ops.toString());
            assertTrue(Integer.parseInt(String.valueOf(ops.get("received"))) >= 1, ops.toString());
        } finally {
            dep.stop();
        }
    }

    @Test
    @DisplayName("key / title / embedded 与 bootstrap 地址要能对上")
    void identifiesItself() {
        final KafkaDependency dep = new KafkaDependency(9092, 2181, 1000);
        assertEquals(DepsRegistry.KAFKA, dep.key());
        assertTrue(dep.title().contains("ZooKeeper"),
                "标题要说清是 ZK 模式（不是 KRaft）：" + dep.title());
        assertTrue(dep.embedded(), "broker 与 ZK 都在进程内起，所以是 embedded");
        assertEquals("127.0.0.1:9092", dep.bootstrap());
        assertNotNull(dep.topic());
        assertTrue(dep.topic().length() > 0);
    }
}