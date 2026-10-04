package io.github.fulizhe.otelstore.demo;

import io.github.fulizhe.otelstore.demo.stats.GeneratedSignals;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 演示应用入口。
 *
 * <p>它只做两件事：<b>按需造出三个信号</b>，以及<b>把自己造了多少暴露成可断言的计数</b>。
 * 它<b>不</b>托管读口 —— 读口在 agent 扩展里，与本应用隔着 ClassLoader
 * （见 {@code docs/notes/2026-10-04-r0-extension-points.md} 第五节）。
 *
 * <p>没挂 agent 也能起，此时 OTel 是 no-op，页面照常工作。
 */
@SpringBootApplication
public class DemoApplication {

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
}