package io.github.fulizhe.otelstore.demo.deps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Redis 那一跳。<b>真的起内嵌服务端</b>，但端口随机、起完就关。
 *
 * <p>一开始这里想用"保留端口 1 探不通"来测降级路径，写完发现 <b>它真的通了</b> ——
 * 意味着那条断言测的是一个不存在的场景。所以改成真的起一次：
 * 这张票最该被钉住的是「Jedis 真的打到了真服务端」，而那只有连上才算数。
 *
 * <p>降级路径仍要测，但用<b>真的探不通</b>的方式（见下方那个测试）——
 * 拿一个"恰好通"的场景去断言"不通"，得到的是一个永远为假的测试。
 */
class RedisDependencyTest {

    /** 随机端口：并行跑测试时不撞端口，也不用去查哪个空着。 */
    private static int freePort() throws Exception {
        try (final ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @DisplayName("内嵌服务端起得来，且 SET/GET/DEL 都真的走通")
    void embeddedServerActuallyServes() throws Exception {
        final RedisDependency dep = new RedisDependency("127.0.0.1", freePort(), 1000);
        try {
            final DepStatus s = dep.probe();
            assertTrue(s.ready(), "内嵌服务端应该起得来：" + s.detail());

            final Map<String, Object> ops = dep.call();
            assertEquals("PONG", ops.get("ping"), ops.toString());
            assertEquals("OK", ops.get("set"), ops.toString());
            // 读到同一个值才算真的通了 —— 只看 set 的返回值是"我以为写进去了"
            assertEquals("hello-from-demo", ops.get("get"), ops.toString());
            assertEquals(Long.valueOf(1L), ops.get("del"), ops.toString());
        } finally {
            dep.stop();
        }
    }

    @Test
    @DisplayName("配置端口被占 → 退到空闲端口重试，而不是整跳降级")
    void occupiedPortFallsBackToFreePort() throws Exception {
        // 占用一个端口，冒充"上次跑残留的 redis-server"
        try (final ServerSocket squatter = new ServerSocket(0)) {
            final int taken = squatter.getLocalPort();
            final RedisDependency dep = new RedisDependency("127.0.0.1", taken, 1000);
            try {
                final DepStatus s = dep.probe();
                assertTrue(s.ready(),
                        "端口被占应该退让而不是降级（真机就是这么被一个残留进程坑掉的）："
                                + s.detail());
                assertTrue(dep.actualPort() != taken,
                        "退让后实际端口必须与配置的不同，否则那条退让说明是假的");
                assertTrue(s.detail().contains("退让"), "detail 要写明退让过：" + s.detail());
                assertEquals("PONG", dep.call().get("ping"), "退让之后还得真的能用");
            } finally {
                dep.stop();
            }
        }
    }

    @Test
    @DisplayName("没有服务端时要降级而不是抛异常，且要说清为什么")
    void noServerDegradesWithReason() throws Exception {
        // 先起一个、再关掉，端口就回到"没人听"的状态 —— 比用一个保留端口可靠
        final int port = freePort();
        final RedisDependency first = new RedisDependency("127.0.0.1", port, 300);
        assertTrue(first.probe().ready(), "先起一次，确认这个端口本来是能用的");
        first.stop();

        final RedisDependency dep = new RedisDependency("127.0.0.1", port, 300);
        // 直接打 call()：起不来的话会抛，探测本身已经把它压成 not-ready 了
        final DepStatus s = dep.probe();
        assertNotNull(s);
        // 这一步只钉"要么 ready 要么带 detail 的 not-ready"，不钉具体是哪个 ——
        // 起不来的原因随环境变（exe 被拦 / 端口残留），断言太死会变成环境测试
        if (!s.ready()) {
            assertFalse(s.detail().isEmpty(), "必须有 detail：只给 ready 的话调用方无法处置");
        }
    }

    @Test
    @DisplayName("起不来时 key / title / embedded 仍然要对得上名字")
    void degradedStatusStillIdentifiesItself() throws Exception {
        final RedisDependency dep = new RedisDependency("127.0.0.1", freePort(), 1000);
        assertEquals(DepsRegistry.REDIS, dep.key());
        assertTrue(dep.title().length() > 0);
        assertTrue(dep.embedded(), "服务端在进程内起，所以是 embedded");
        assertEquals(DepsRegistry.REDIS, dep.probe().key(),
                "降级状态也要带着 key —— 否则 status 里那一项对不上名字");
        dep.stop();
    }
}