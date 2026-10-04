package io.github.fulizhe.otelstore.demo.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本应用<b>自己</b>造出多少信号的计数。
 *
 * <p>存在的理由：端到端验证需要一个<b>不依赖读口</b>的期望值来源。
 * 读口要到Phase 5 才存在，而"我造了 3 个 span / 5 条日志"这件事应用自己就知道。
 * 断言时拿它和读口报出来的条数对账 —— 这就是 SW 侧"影子对账"思路的轻量版。
 */
public final class GeneratedSignals {

    private final AtomicLong spans = new AtomicLong();
    private final AtomicLong childSpans = new AtomicLong();
    private final AtomicLong errorSpans = new AtomicLong();
    private final AtomicLong slowSpans = new AtomicLong();
    private final AtomicLong logLines = new AtomicLong();
    private final AtomicLong metricPoints = new AtomicLong();

    private final long startedAtMs = System.currentTimeMillis();

    public void addSpan(final boolean error, final boolean slow, final boolean child) {
        spans.incrementAndGet();
        if (child) {
            childSpans.incrementAndGet();
        }
        if (error) {
            errorSpans.incrementAndGet();
        }
        if (slow) {
            slowSpans.incrementAndGet();
        }
    }

    public void addLogLine() {
        logLines.incrementAndGet();
    }

    public void addMetricPoint() {
        metricPoints.incrementAndGet();
    }

    public long uptimeMs() {
        return System.currentTimeMillis() - startedAtMs;
    }

    public void reset() {
        spans.set(0);
        childSpans.set(0);
        errorSpans.set(0);
        slowSpans.set(0);
        logLines.set(0);
        metricPoints.set(0);
    }

    /** 快照一律扁平成 JDK 原生类型，方便直接 JSON 序列化。 */
    public Map<String, Object> snapshot() {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("uptimeMs", Long.valueOf(uptimeMs()));
        m.put("spans", Long.valueOf(spans.get()));
        m.put("childSpans", Long.valueOf(childSpans.get()));
        m.put("errorSpans", Long.valueOf(errorSpans.get()));
        m.put("slowSpans", Long.valueOf(slowSpans.get()));
        m.put("logLines", Long.valueOf(logLines.get()));
        m.put("metricPoints", Long.valueOf(metricPoints.get()));
        return m;
    }
}