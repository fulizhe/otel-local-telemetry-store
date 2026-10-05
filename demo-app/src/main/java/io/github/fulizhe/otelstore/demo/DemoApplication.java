package io.github.fulizhe.otelstore.demo;

import io.github.fulizhe.otelstore.demo.deps.DepStatus;
import io.github.fulizhe.otelstore.demo.deps.DependencyProbe;
import io.github.fulizhe.otelstore.demo.deps.DepsDemoService;
import io.github.fulizhe.otelstore.demo.deps.DepsRegistry;
import io.github.fulizhe.otelstore.demo.deps.GrpcDependency;
import io.github.fulizhe.otelstore.demo.deps.H2Dependency;
import io.github.fulizhe.otelstore.demo.deps.KafkaDependency;
import io.github.fulizhe.otelstore.demo.deps.MysqlDependency;
import io.github.fulizhe.otelstore.demo.deps.RedisDependency;
import io.github.fulizhe.otelstore.demo.stats.GeneratedSignals;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 演示应用入口。
 *
 * <p>它做三件事：<b>按需造出三个信号</b>、<b>把自己造了多少暴露成可断言的计数</b>、
 * 以及<b>造出五类外部依赖的调用样例</b>。
 * 它<b>不</b>托管读口 —— 读口在 agent 扩展里，与本应用隔着 ClassLoader
 * （见 {@code docs/notes/2026-10-04-r0-extension-points.md} 第五节）。
 *
 * <p>没挂 agent 也能起，此时 OTel 是 no-op，页面照常工作。
 *
 * <p>{@code @EnableScheduling} 是给"每秒一个真实计数"那个任务用的
 * （ADR-6 第八节第 6 条：业务指标的历史归业务层自己滚点）。
 */
@SpringBootApplication
@EnableScheduling
public class DemoApplication {

    private static final Logger LOG = LoggerFactory.getLogger("otelstore.demo.deps");

    public static void main(final String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    /**
     * 计数 beans 由容器管，不在计数类上放 Spring 注解 —— 那个类要能脱离 Spring 单独测。
     */
    @Bean
    public GeneratedSignals generatedSignals() {
        return new GeneratedSignals();
    }

    @Bean
    public H2Dependency h2Dependency() {
        return new H2Dependency();
    }

    @Bean
    public RedisDependency redisDependency() {
        return new RedisDependency();
    }

    @Bean
    public KafkaDependency kafkaDependency() {
        return new KafkaDependency();
    }

    @Bean
    public GrpcDependency grpcDependency() {
        return new GrpcDependency();
    }

    @Bean
    public MysqlDependency mysqlDependency() {
        return new MysqlDependency();
    }

    /**
     * 五类依赖的探测清单，<b>按页面上展示的顺序</b>。
     *
     * <p>本票只把 H2 接上；其余四项如实报 {@code ready=false}，
     * 而且 detail 要写成<b>"还没接入"</b>而不是"起不来" ——
     * 这两者要处理的事完全不同，混成一句"不可用"会让人以为靶子坏了。
     */
    @Bean
    public List<DependencyProbe> dependencyProbes(final H2Dependency h2,
                                                  final RedisDependency redis,
                                                  final KafkaDependency kafka,
                                                  final GrpcDependency grpc,
                                                  final MysqlDependency mysql) {
        return new ArrayList<DependencyProbe>(Arrays.asList(h2, redis, kafka, grpc, mysql));
    }

    @Bean
    public DepsRegistry depsRegistry(final List<DependencyProbe> probes) {
        final DepsRegistry registry = new DepsRegistry(probes);
        for (final DepStatus s : registry.all()) {
            if (s.ready()) {
                LOG.info("依赖 {} 就绪：{}", s.key(), s.detail());
            } else {
                // 打成 WARN 而不是 ERROR：探不通是预期状态，靶子照常起
                LOG.warn("依赖 {} 不可用：{}", s.key(), s.detail());
            }
        }
        return registry;
    }

    @Bean
    public DepsDemoService depsDemoService(final DepsRegistry registry,
                                           final H2Dependency h2,
                                           final RedisDependency redis,
                                           final KafkaDependency kafka,
                                           final GrpcDependency grpc,
                                           final MysqlDependency mysql,
                                           final GeneratedSignals stats) {
        return new DepsDemoService(registry, h2, redis, kafka, grpc, mysql, stats);
    }
}