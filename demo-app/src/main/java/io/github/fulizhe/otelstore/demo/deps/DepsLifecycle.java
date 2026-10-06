package io.github.fulizhe.otelstore.demo.deps;

import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 应用关闭时把内嵌的 Redis / Kafka / ZooKeeper / gRPC 都关掉。
 *
 * <p><b>为什么必须显式做这件事</b>：这些内嵌服务端是<b>独立进程或独立端口</b>，
 * JVM 退出不会替它们收尸。真机验收时就撞上过 —— 上一次跑残留的
 * {@code redis-server-2.8.19.exe} 一直蹲在 6379 上，于是下一次启动 Redis 那一跳整个降级，
 * 而它看起来像"代码坏了"。这类残留是最难归因的一种。
 *
 * <p>能覆盖到的场景：<b>Ctrl-C / SIGTERM</b>（Spring 优雅关停会跑 {@code @PreDestroy}）。
 * <b>覆盖不到</b>的是 {@code Stop-Process} 那种直接杀进程 —— 关停钩子不会执行
 * （AGENTS.md 里记过这条）。那种情况下靠 {@link RedisDependency} 的端口退让兜底：
 * 即使端口被上一轮残留占着，这一轮也能照常起来。
 */
@Component
public class DepsLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger("otelstore.demo.deps");

    private final RedisDependency redis;
    private final KafkaDependency kafka;
    private final GrpcDependency grpc;

    @Autowired
    public DepsLifecycle(final RedisDependency redis, final KafkaDependency kafka,
                         final GrpcDependency grpc) {
        this.redis = redis;
        this.kafka = kafka;
        this.grpc = grpc;
    }

    @PreDestroy
    public void shutdown() {
        // 逐个关，任何一个关不掉都不该挡住其它几个 —— 都要退了，别在这里抛
        closeQuietly("redis", new Runnable() {
            @Override
            public void run() {
                redis.stop();
            }
        });
        closeQuietly("grpc", new Runnable() {
            @Override
            public void run() {
                grpc.stop();
            }
        });
        closeQuietly("kafka", new Runnable() {
            @Override
            public void run() {
                kafka.stop();
            }
        });
    }

    private static void closeQuietly(final String what, final Runnable action) {
        try {
            action.run();
        } catch (final RuntimeException e) {
            LOG.warn("关闭内嵌 {} 时出错（进程要退了，忽略）", what, e);
        }
    }
}