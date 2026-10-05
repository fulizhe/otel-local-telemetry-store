package io.github.fulizhe.otelstore.demo.deps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.ServerSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * gRPC 那一跳：<b>真的起 Netty server 并往返一次</b>，然后关掉。
 *
 * <p>这张票最该被钉住的两件事，都只有真连上才算数：
 * <ol>
 *   <li>server 真的在<b>端口</b>上监听（不是进程内直调那个 Echo 方法）——
 *       否则 SERVER span 不会产生，而"两侧都有 span"正是这一跳存在的理由。</li>
 *   <li>手搓的 identity marshaller 真的能往返（不用 protoc）——
 *       descriptor 写错时表现为调用方拿到空或抛错，而不是编译不过。</li>
 * </ol>
 */
class GrpcDependencyTest {

    private static int freePort() throws Exception {
        try (final ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @DisplayName("真端口 Netty server 能起，且 identity marshaller 往返一致")
    void nettyServerRoundTripsBytes() throws Exception {
        final GrpcDependency dep = new GrpcDependency("127.0.0.1", freePort(), 5000);
        try {
            final DepStatus s = dep.probe();
            assertTrue(s.ready(), "Netty server 应该起得来：" + s.detail());

            // 非 ASCII 也要原样回来：marshaller 若按默认字符集转，这里就会不一致
            assertEquals("hello-grpc-中文", dep.call("hello-grpc-中文"));
        } finally {
            dep.stop();
        }
    }

    @Test
    @DisplayName("key / title / embedded / port 要能对上")
    void identifiesItself() {
        final GrpcDependency dep = new GrpcDependency("127.0.0.1", 18900, 1000);
        assertEquals(DepsRegistry.GRPC, dep.key());
        assertTrue(dep.title().contains("Netty"), dep.title());
        assertTrue(dep.embedded(), "server 在进程内起，所以是 embedded");
        assertEquals(18900, dep.port());
        assertNotNull(dep.key());
    }
}