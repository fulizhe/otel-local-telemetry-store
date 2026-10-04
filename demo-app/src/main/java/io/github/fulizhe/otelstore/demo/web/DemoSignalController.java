package io.github.fulizhe.otelstore.demo.web;

import io.github.fulizhe.otelstore.demo.stats.GeneratedSignals;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 造信号的入口。
 *
 * <p>每个端点都做到<b>可断言</b>：给定参数造出确定数量的东西，并把数量记进
 * {@link GeneratedSignals}。自动化测试不需要解析日志或看图，直接查 {@code /demo/stats}。
 */
@RestController
@RequestMapping("/demo")
public class DemoSignalController {

    /** 故意用独立的 logger name，便于按 instrumentation scope 区分来源。 */
    private static final Logger LOG = LoggerFactory.getLogger("otelstore.demo.signal");

    private static final String SCOPE = "otelstore-demo";
    private static final String SCOPE_VERSION = "0.1.0";

    /**
     * 提为常量有两个理由：一是 {@code Attributes.of} 的泛型重载在 Java 8 下按内联实参调用会推断歧义；
     * 二是每个数据点都新建一个 Attributes 纯属浪费。
     */
    private static final Attributes DEMO_ATTRS =
            Attributes.of(io.opentelemetry.api.common.AttributeKey.stringKey("route"), "demo");

    private final GeneratedSignals stats;

    @Autowired
    public DemoSignalController(final GeneratedSignals stats) {
        this.stats = stats;
    }

/**
     * 造一串 span：每条下面挂 {@code childPerSpan} 个子 span，按比例标error 与 slow。
     */
    @PostMapping("/spans")
    public Map<String, Object> spans(@RequestParam(defaultValue = "3") int count,
                                     @RequestParam(defaultValue = "1") int childPerSpan,
                                     @RequestParam(defaultValue = "1") int errors,
                                     @RequestParam(defaultValue = "1") int slow,
                                     @RequestParam(defaultValue = "20") int slowMs) {
        if (count < 0 || count > 500) {
            count = 3;
        }
        if (childPerSpan < 0 || childPerSpan > 20) {
            childPerSpan = 1;
        }
        if (slowMs < 0 || slowMs > 5000) {
            slowMs = 20;
        }

        final Tracer tracer = tracer();
        int madeErrors = 0;
        int madeSlow = 0;
        int madeChildren = 0;

        for (int i = 0; i < count; i++) {
            final boolean isError = errors > 0 && i % Math.max(1, count / errors) == 0;
            final boolean isSlow = slow > 0 && i % Math.max(1, count / slow) == 0;

            final Span parent = tracer.spanBuilder("demo.request").startSpan();
            Scope parentScope = parent.makeCurrent();
            try {
                if (isSlow) {
                    sleep(slowMs);
                }
                if (isError) {
                    parent.setStatus(StatusCode.ERROR, "demo 故意制造的错误");
                    parent.setAttribute("demo.error", true);
                }
                for (int c = 0; c < childPerSpan; c++) {
                    final Span child = tracer.spanBuilder("demo.db.query").startSpan();
                    child.setAttribute("demo.row", c);
                    child.end();
                    stats.addSpan(false, false, true);
                    madeChildren++;
                }
            } finally {
                parentScope.close();
                parent.end();
            }
            stats.addSpan(isError, isSlow, false);
            if (isError) {
                madeErrors++;
            }
            if (isSlow) {
                madeSlow++;
            }
        }

        LOG.info("造出 {} 条 span（含 {} 子 span / {} error / {} slow）", count, madeChildren, madeErrors, madeSlow);

        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("spans", Integer.valueOf(count));
        m.put("childSpans", Integer.valueOf(madeChildren));
        m.put("errorSpans", Integer.valueOf(madeErrors));
        m.put("slowSpans", Integer.valueOf(madeSlow));
        m.put("totalSpans", Integer.valueOf(count + madeChildren));
        return m;
    }

    /** 造日志行。用日志桥（logback）走 agent 的日志仪表化，天然带上trace 上下文。 */
    @PostMapping("/logs")
    public Map<String, Object> logs(@RequestParam(defaultValue = "5") int count,
                                    @RequestParam(defaultValue = "INFO") final String level) {
        if (count < 0 || count > 2000) {
            count = 5;
        }
        final String lvl = "ERROR".equalsIgnoreCase(level) || "WARN".equalsIgnoreCase(level)
                ? level.toUpperCase() : "INFO";

        final Tracer tracer = tracer();
        int emitted = 0;
        for (int i = 0; i < count; i++) {
            final Span span = tracer.spanBuilder("demo.log.wrapper").startSpan();
            final Scope scope = span.makeCurrent();
            try {
                // 在 current span 内部打日志 —— 这样日志记录会带上 traceId/spanId
                if ("ERROR".equals(lvl)) {
                    LOG.error("演示日志 #{}：这是一条故意造的错误日志", i);
                } else if ("WARN".equals(lvl)) {
                    LOG.warn("演示日志 #{}：这是一条警告", i);
                } else {
                    LOG.info("演示日志 #{}：这是一条普通日志", i);
                }
                stats.addLogLine();
                emitted++;
            } finally {
                scope.close();
                span.end();
                stats.addSpan(false, false, false);
            }
        }

        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("level", lvl);
        m.put("logLines", Integer.valueOf(emitted));
        return m;
    }

    /** 造指标点：一个计数器、一个直方图、一个 gauge。 */
    @PostMapping("/metrics")
    public Map<String, Object> metrics(@RequestParam(defaultValue = "10") int count) {
        if (count < 0 || count > 10000) {
            count = 10;
        }
        final Meter meter = GlobalOpenTelemetry.get().meterBuilder(SCOPE)
                .setInstrumentationVersion(SCOPE_VERSION).build();

        final LongCounter counter = meter.counterBuilder("demo.requests")
                .setDescription("演示请求计数").setUnit("1").build();
        final LongHistogram latency = meter.histogramBuilder("demo.request.duration")
                .ofLongs()
                .setDescription("演示请求耗时").setUnit("ms").build();

        for (int i = 0; i < count; i++) {
            counter.add(1, DEMO_ATTRS);
            latency.record(i % 100, DEMO_ATTRS);
            stats.addMetricPoint();
            stats.addSpan(false, false, false);
        }

        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("counterName", "demo.requests");
        m.put("histogramName", "demo.request.duration");
        m.put("metricPoints", Integer.valueOf(count * 2));
        return m;
    }

    /** 一次真实的 HTTP 请求 —— 由 agent 的 Web 仪表化产生 server span。 */
    @GetMapping("/work")
    public Map<String, Object> work(@RequestParam(defaultValue = "30") int ms) throws InterruptedException {
        if (ms < 0 || ms > 5000) {
            ms = 30;
        }
        sleep(ms);
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("sleptMs", Integer.valueOf(ms));
        m.put("hint", "这条请求本身会产生一个 server span（由 agent 的 Web 仪表化产生）");
        return m;
    }

    @GetMapping("/stats")
    public Map<String, Object> snapshot() {
        return stats.snapshot();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        stats.reset();
        return stats.snapshot();
    }

    private static Tracer tracer() {
        return GlobalOpenTelemetry.get().tracerBuilder(SCOPE)
                .setInstrumentationVersion(SCOPE_VERSION).build();
    }

    private static void sleep(final long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}