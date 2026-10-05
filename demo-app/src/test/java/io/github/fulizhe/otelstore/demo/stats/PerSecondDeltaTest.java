package io.github.fulizhe.otelstore.demo.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 每秒增量的口径。
 *
 * <p>这里钉的是最容易出错的两件事：
 * <ol>
 *   <li><b>增量只算一次</b>：第二次 drain 在没有新动作时必须是 0，
 *       否则趋势图会把同一个数重复计一遍。</li>
 *   <li><b>reset 之后不能发出负数</b>：负的"每秒请求数"会在图上画出一个不存在的下跌，
 *       比不发更坏。</li>
 * </ol>
 */
class PerSecondDeltaTest {

    @Test
    @DisplayName("增量只算一次：没有新动作时下一次全是 0")
    void drainIsIdempotentWhenNothingHappens() {
        final GeneratedSignals g = new GeneratedSignals();
        g.addSpan(false, false, false);
        g.addSpan(false, false, true);
        g.addMetricPoint();

        final Map<String, Long> first = g.drainPerSecondDelta();
        assertEquals(Long.valueOf(1L), first.get("requests"), "一个顶层 span = 一次请求");
        assertEquals(Long.valueOf(2L), first.get("spans"), "顶层 + 子 span = 全部 span");
        assertEquals(Long.valueOf(1L), first.get("metric_points"));

        final Map<String, Long> second = g.drainPerSecondDelta();
        assertEquals(Long.valueOf(0L), second.get("requests"), "没有新动作就该是 0");
        assertEquals(Long.valueOf(0L), second.get("spans"));
        assertEquals(Long.valueOf(0L), second.get("metric_points"));
    }

    @Test
    @DisplayName("reset 之后不能发出负数 —— 那会在趋势图上画出不存在的下跌")
    void resetNeverProducesNegativeDelta() {
        final GeneratedSignals g = new GeneratedSignals();
        g.addSpan(false, false, false);
        g.addMetricPoint();
        g.drainPerSecondDelta();

        g.reset();
        final Map<String, Long> after = g.drainPerSecondDelta();
        for (final String key : new String[]{"requests", "spans", "metric_points"}) {
            assertTrue(after.get(key).longValue() >= 0L, key + " 不能是负数：" + after);
        }
    }

    @Test
    @DisplayName("最近一次的每秒计数能读到（验收不必去翻指标端点）")
    void lastPerSecondIsReadable() {
        final GeneratedSignals g = new GeneratedSignals();
        g.addSpan(false, false, true);
        g.drainPerSecondDelta();
        assertEquals(Long.valueOf(1L), g.lastPerSecond().get("spans"));
    }
}