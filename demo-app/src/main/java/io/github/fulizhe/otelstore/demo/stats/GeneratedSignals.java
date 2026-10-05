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

    /**
     * 五类依赖的计数器，键就是依赖的 key。
     *
     * <p><b>用 Map 而不是五个字段</b>：五跳会陆续接上，字段版每次都要改
     * "声明 + add + reset + snapshot"四处，而漏掉任何一处的后果是
     * 「计数不涨」——一个没人会去看的地方。
     */
    private final Map<String, AtomicLong> depCalls = new LinkedHashMap<String, AtomicLong>();
    private final Map<String, AtomicLong> depFailures = new LinkedHashMap<String, AtomicLong>();

    private final AtomicLong spans = new AtomicLong();
    private final AtomicLong childSpans = new AtomicLong();
    private final AtomicLong errorSpans = new AtomicLong();
    private final AtomicLong slowSpans = new AtomicLong();
    private final AtomicLong logLines = new AtomicLong();
    private final AtomicLong metricPoints = new AtomicLong();

    private final long startedAtMs = System.currentTimeMillis();

    /**
     * 每秒计数用的"上一秒读数"。{@link #drainPerSecondDelta()} 与它一起被
     * {@code perSecondLock} 保护 —— 定时任务与请求线程会同时碰这几个数。
     */
    private final Object perSecondLock = new Object();
    private long prevSpans;
    private long prevChildSpans;
    private long prevMetricPoints;

    /** 最近一次发出的每秒计数，给 {@code /demo/stats} 看，方便验收时不用等指标端点。 */
    private volatile Map<String, Long> lastPerSecond = new LinkedHashMap<String, Long>();

    /** 五类依赖的 key，声明在这里是为了让计数与依赖清单共用同一个来源。 */
    public static final String[] DEP_KEYS = {"h2", "redis", "kafka", "grpc", "mysql"};

    public GeneratedSignals() {
        for (final String key : DEP_KEYS) {
            depCalls.put(key, new AtomicLong());
            depFailures.put(key, new AtomicLong());
        }
    }

    /**
     * 记一次依赖调用。
     *
     * @param ok 这一跳真的跑通了，还是降级 / 失败了。
     *            <b>两者必须分开记</b> —— "少了一条 CLIENT span"可能是没埋点，
     *            也可能是库没起来，混成一个数就分不出来了。
     */
    public void addDepCall(final String key, final boolean ok) {
        bump(depCalls, key);
        if (!ok) {
            bump(depFailures, key);
        }
    }

    /** 未知 key 也要记进去，而不是悄悄丢掉 —— 否则计数器会与实际调用对不上。 */
    private static void bump(final Map<String, AtomicLong> counters, final String key) {
        AtomicLong c = counters.get(key);
        if (c == null) {
            c = new AtomicLong();
            counters.put(key, c);
        }
        c.incrementAndGet();
    }

    /**
     * 取走"自上次调用以来"的增量，并把读数推进到当下。
     *
     * <p>三个量的口径：
     * <ul>
     *   <li>{@code requests} = 这一秒新造的<b>顶层</b> span 数（每条业务请求一个）</li>
     *   <li>{@code spans} = 这一秒新造的<b>全部</b> span 数（含子 span）</li>
     *   <li>{@code metric_points} = 这一秒写入的指标点数</li>
     * </ul>
     *
     * <p><b>计数回退要夹到 0</b>：{@code reset()} 会把计数清零，而下一次 tick 读到的
     * 值就小于上一次 —— 直接相减会发出一个负数。负的"每秒请求数"比不发更坏，
     * 因为它会在趋势图上画出一个不存在的下跌。
     */
    public Map<String, Long> drainPerSecondDelta() {
        synchronized (perSecondLock) {
            final long s = spans.get();
            final long c = childSpans.get();
            final long mp = metricPoints.get();

            // spans 计的是**全部** span（子 span 也走 addSpan），所以顶层请求数要减掉子 span
            final long dRequests = nonNegative(s - c, prevSpans - prevChildSpans);
            final long dSpans = nonNegative(s, prevSpans);
            final long dMetricPoints = nonNegative(mp, prevMetricPoints);

            prevSpans = s;
            prevChildSpans = c;
            prevMetricPoints = mp;

            final Map<String, Long> d = new LinkedHashMap<String, Long>();
            d.put("requests", Long.valueOf(dRequests));
            d.put("spans", Long.valueOf(dSpans));
            d.put("metric_points", Long.valueOf(dMetricPoints));
            lastPerSecond = d;
            return d;
        }
    }

    /** 计数被 reset 过时给 0，而不是给负数。 */
    private static long nonNegative(final long current, final long previous) {
        return current >= previous ? current - previous : 0L;
    }

    /** 最近一次发出的每秒计数（验收时看这个就够了，不必去翻指标端点）。 */
    public Map<String, Long> lastPerSecond() {
        return new LinkedHashMap<String, Long>(lastPerSecond);
    }

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
        for (final AtomicLong c : depCalls.values()) {
            c.set(0);
        }
        for (final AtomicLong c : depFailures.values()) {
            c.set(0);
        }
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
        // 依赖计数：每个 key 一行，成与败分开。
        // 页面与脚本可以直接把这张表和 /api/traces 里 scopeName=jdbc / jedis 的条数对账。
        //
        // 遍历 map 而**不是遍历 DEP_KEYS**：bump() 会把没见过的 key 也记进去，
        // 只遍历 DEP_KEYS 的话那些键就在 snapshot 里消失了 —— 计数记了却读不出来，
        // 那比不记还难查（"我明明调了，为什么是 0"）。
        final Map<String, Object> calls = new LinkedHashMap<String, Object>();
        final Map<String, Object> failures = new LinkedHashMap<String, Object>();
        m.put("depCalls", flatten(depCalls));
        m.put("depFailures", flatten(depFailures));
        m.put("lastPerSecond", lastPerSecond());
        return m;
    }

    private static Map<String, Object> flatten(final Map<String, AtomicLong> counters) {
        final Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (final Map.Entry<String, AtomicLong> e : counters.entrySet()) {
            out.put(e.getKey(), Long.valueOf(e.getValue().get()));
        }
        return out;
    }
}