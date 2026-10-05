package io.github.fulizhe.otelstore.demo.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 依赖计数的形状。
 *
 * <p>只测能脱离 Spring 跑的那部分 —— demo-app 不在主工程 reactor 里，
 * 而真实依赖（真 Redis / 真 broker / 真 MySQL）**不做进程内测试**，
 * 那些由用户在真机上验收。这不是省事，是靶子的性质决定的：
 * 起一个真 broker 的测试跑得比它验的东西还久，挂了还要人去分辨是产品坏了还是环境坏了。
 */
class GeneratedSignalsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Long> depCallsOf(final GeneratedSignals g) {
        return (Map<String, Long>) g.snapshot().get("depCalls");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> depFailuresOf(final GeneratedSignals g) {
        return (Map<String, Long>) g.snapshot().get("depFailures");
    }

    @Test
    @DisplayName("五类依赖一开始都是 0，且键齐全 —— 少一个键就分不清是哪一跳没打")
    void allDepKeysPresentAtZero() {
        final Map<String, Long> calls = depCallsOf(new GeneratedSignals());
        for (final String key : GeneratedSignals.DEP_KEYS) {
            assertEquals(Long.valueOf(0L), calls.get(key), key + " 一开始就该是 0 且存在");
        }
    }

    @Test
    @DisplayName("成的与败的分开记：降级了不能算成'调用成功'")
    void failuresAreCountedSeparately() {
        final GeneratedSignals g = new GeneratedSignals();
        g.addDepCall("h2", true);
        g.addDepCall("h2", false);
        g.addDepCall("mysql", false);

        assertEquals(Long.valueOf(2L), depCallsOf(g).get("h2"));
        assertEquals(Long.valueOf(1L), depFailuresOf(g).get("h2"), "两跳里失败过一次");
        assertEquals(Long.valueOf(1L), depFailuresOf(g).get("mysql"), "MySQL 起不来也要计数");
        assertEquals(Long.valueOf(0L), depFailuresOf(g).get("redis"), "没调用过就不是失败");
    }

    @Test
    @DisplayName("未知 key 也要计数，而不是悄悄丢掉 —— 否则计数会与实际调用对不上")
    void unknownKeyIsStillCounted() {
        final GeneratedSignals g = new GeneratedSignals();
        g.addDepCall("somethingNew", true);
        assertEquals(Long.valueOf(1L), depCallsOf(g).get("somethingNew"));
    }

    @Test
    @DisplayName("reset 把依赖计数一起清零")
    void resetClearsDepCounters() {
        final GeneratedSignals g = new GeneratedSignals();
        g.addDepCall("h2", true);
        g.addDepCall("grpc", false);
        g.reset();
        assertEquals(Long.valueOf(0L), depCallsOf(g).get("h2"));
        assertEquals(Long.valueOf(0L), depFailuresOf(g).get("grpc"));
    }

    @Test
    @DisplayName("原有的六个计数形状没被改坏")
    void originalSixCountersStillThere() {
        final Map<String, Object> snap = new GeneratedSignals().snapshot();
        for (final String key : new String[]{"uptimeMs", "spans", "childSpans",
                "errorSpans", "slowSpans", "logLines", "metricPoints"}) {
            assertEquals(true, snap.containsKey(key), key + " 不该消失");
        }
    }
}