package io.github.fulizhe.otelstore.readout.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prometheus 渲染器的契约 —— 断言的是<b>产出的文本</b>。
 *
 * <p>这里钉的是三件容易错、且错了会直接画出错图的事：
 * 标签值转义、<b>直方图的 {@code _bucket} 必须是累计值</b>、以及撞名不能合并成一条线。
 */
class PrometheusTextTest {

    private static Map<String, Object> row(final String name, final String dataType, final Object value,
            final Object count, final Object sum, final String detail, final String attrKey) {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("metricName", name);
        m.put("dataType", dataType);
        m.put("metricValue", value);
        m.put("metricCount", count);
        m.put("metricSum", sum);
        m.put("detail", detail);
        m.put("attrKey", attrKey);
        m.put("ts", 1700000000000L);
        return m;
    }

    private static Map<String, Object> gauge(final String name, final String value, final String attrKey) {
        return row(name, "GAUGE", value, null, null, "double", attrKey);
    }

    @Test
    @DisplayName("空输入渲染出空 body —— 抓取器看到空 body 是正常的，不是故障")
    void emptyInput() {
        assertEquals("", PrometheusText.render(null));
        assertEquals("", PrometheusText.render(Collections.<Map<String, Object>>emptyList()));
    }

    @Test
    @DisplayName("标量：TYPE 与标签，数值原样")
    void scalarSamples() {
        final String out = PrometheusText.render(Collections.singletonList(gauge("jvm.memory.used", "1.5", "abc")));
        assertTrue(out.contains("# TYPE jvm_memory_used gauge"), out);
        assertTrue(out.contains("# HELP jvm_memory_used "), out);
        assertTrue(out.contains("jvm_memory_used{attr_key=\"abc\"} 1.5"), out);
    }

    @Test
    @DisplayName("SUM 不改名成 _total —— 我们没存 is_monotonic，改名就是撒谎")
    void sumIsNotRenamedToTotal() {
        final String out = PrometheusText.render(Collections.singletonList(
                row("orders.processed", "SUM", 3.0d, null, null, "long", "abc")));
        assertTrue(out.contains("# TYPE orders_processed counter"), out);
        assertTrue(out.contains("orders_processed{attr_key=\"abc\"} 3.0"), out);
        assertFalse(out.contains("_total"), "没有持久化单调性，不能声称自己是 counter_total：" + out);
    }

    @Test
    @DisplayName("直方图：_bucket 的 le 是累计值，不是每个桶自己的计数")
    void histogramBucketsAreCumulative() {
        final String out = PrometheusText.render(Collections.singletonList(row("http.duration", "HISTOGRAM",
                null, Long.valueOf(7L), Double.valueOf(12.5d),
                "explicit;min=1.0;max=9.0;bounds=[2.0, 5.0];counts=[3, 4]", "abc")));

        // 非累计是 [3,4]；累计必须是 [3,7]。少这一步画出来的直方图是错的
        assertTrue(out.contains("http_duration_bucket{attr_key=\"abc\",le=\"2.0\"} 3"), out);
        assertTrue(out.contains("http_duration_bucket{attr_key=\"abc\",le=\"5.0\"} 7"), out);
        assertTrue(out.contains("http_duration_sum{attr_key=\"abc\"} 12.5"), out);
        assertTrue(out.contains("http_duration_count{attr_key=\"abc\"} 7"), out);
        assertTrue(out.contains("# TYPE http_duration untyped"),
                "自己展开成 _bucket 时不能声明成 histogram，抓取器会判格式错误：" + out);
    }

    @Test
    @DisplayName("直方图的 detail 认不出来时退化成只有 _sum/_count —— 给不出错的桶比给错的好")
    void histogramFallsBackWhenDetailUnreadable() {
        final String out = PrometheusText.render(Collections.singletonList(row("weird", "HISTOGRAM",
                null, Long.valueOf(7L), Double.valueOf(12.5d), "认不出来的形态", "abc")));
        assertTrue(out.contains("weird_sum{attr_key=\"abc\"} 12.5"), out);
        assertTrue(out.contains("weird_count{attr_key=\"abc\"} 7"), out);
        assertFalse(out.contains("_bucket"), "认不出来就不出桶，而不是编一个：" + out);
    }

    @Test
    @DisplayName("摘要：分位数按 quantile 标签出，且仍带 _sum/_count")
    void summaryQuantiles() {
        final String out = PrometheusText.render(Collections.singletonList(row("latency", "SUMMARY",
                null, Long.valueOf(10L), Double.valueOf(3.5d),
                "quantiles;0.5=1.0;0.9=2.0;0.99=2.8", "abc")));
        assertTrue(out.contains("latency{attr_key=\"abc\",quantile=\"0.5\"} 1.0"), out);
        assertTrue(out.contains("latency{attr_key=\"abc\",quantile=\"0.9\"} 2.0"), out);
        assertTrue(out.contains("latency{attr_key=\"abc\",quantile=\"0.99\"} 2.8"), out);
        assertTrue(out.contains("latency_sum{attr_key=\"abc\"} 3.5"), out);
        assertTrue(out.contains("latency_count{attr_key=\"abc\"} 10"), out);
    }

    @Test
    @DisplayName("指标名里的非法字符换 _，数字开头加前缀")
    void nameSanitizing() {
        assertEquals("jvm_memory_used", PrometheusText.sanitize("jvm.memory.used"));
        assertEquals("a_b", PrometheusText.sanitize("a-b"));
        assertEquals("a_b", PrometheusText.sanitize("a b"));
        assertEquals("_9lives", PrometheusText.sanitize("9lives"));
        assertEquals("ok_name", PrometheusText.sanitize("ok_name"));
        assertEquals("_", PrometheusText.sanitize(""));
    }

    @Test
    @DisplayName("撞名不合并：a.b 与 a_b 变成两条线，各带自己的哈希后缀")
    void collidingNamesGetSuffixes() {
        final String out = PrometheusText.render(Arrays.asList(
                gauge("a.b", "1", "h1"), gauge("a_b", "2", "h2")));
        // 两个**都**加后缀，而不是"第一个保留原名"：
        // 谁先来谁占着原名会让输出名依赖行的顺序，而行顺序会随查询变。
        // 各带自己的哈希则是对称的、可复算的。
        final List<String> sampleLines = new ArrayList<String>();
        for (final String line : out.split("\n")) {
            if (line.startsWith("a_b") && !line.startsWith("#")) {
                sampleLines.add(line);
            }
        }
        assertEquals(2, sampleLines.size(), "必须是两条独立的线：" + out);
        assertFalse(sampleLines.get(0).equals(sampleLines.get(1)), sampleLines.toString());
        for (final String line : sampleLines) {
            assertTrue(line.matches("a_b_[0-9a-f]{8}\\{attr_key=\"h[12]\"\\} [12]"),
                    "每条都要带 8 位哈希后缀：" + line);
        }
        assertTrue(sampleLines.get(0).startsWith("a_b_") && sampleLines.get(1).startsWith("a_b_"), out);
        assertFalse(sampleLines.get(0).substring(0, 11).equals(sampleLines.get(1).substring(0, 11)),
                "后缀必须不同，否则还是同一条线：" + sampleLines);
    }

    @Test
    @DisplayName("不撞名的指标名保持原样（只换非法字符）")
    void nonCollidingNameStaysClean() {
        final String out = PrometheusText.render(Collections.singletonList(gauge("a.b", "1", "h1")));
        assertTrue(out.contains("a_b{attr_key=\"h1\"} 1"), out);
        assertFalse(out.contains("_a_b_"), "不撞名时不该加后缀：" + out);
    }

    @Test
    @DisplayName("标签值转义：反斜杠、双引号、换行")
    void labelValueEscaping() {
        assertEquals("a\\\\b", PrometheusText.escapeLabel("a\\b"));
        assertEquals("a\\\"b", PrometheusText.escapeLabel("a\"b"));
        assertEquals("a\\nb", PrometheusText.escapeLabel("a\nb"));
        assertEquals("", PrometheusText.escapeLabel(null));
    }

    @Test
    @DisplayName("数值：认得 NaN/+Inf/-Inf，认不出来给 NaN 而不是 0")
    void numberFormatting() {
        assertEquals("NaN", PrometheusText.number("NaN"));
        assertEquals("+Inf", PrometheusText.number("Infinity"));
        assertEquals("-Inf", PrometheusText.number("-Infinity"));
        assertEquals("1.5", PrometheusText.number("1.5"));
        assertEquals("3.0", PrometheusText.number("3.0"), "原样输出即可，Prometheus 接受 3.0");
        assertEquals("NaN", PrometheusText.number(""));
        assertEquals("NaN", PrometheusText.number(null));
        assertEquals("NaN", PrometheusText.number("不是数字"),
                "0 是一个假的确定值，NaN 至少说的是这里没有值");
    }

    @Test
    @DisplayName("非空的 unit 与 scope 进标签，空的不进")
    void optionalLabelsOnlyWhenPresent() {
        final Map<String, Object> full = gauge("m", "1", "abc");
        full.put("unit", "ms");
        full.put("scopeName", "io.demo");
        final String withAll = PrometheusText.render(Collections.singletonList(full));
        assertTrue(withAll.contains("unit=\"ms\""), withAll);
        assertTrue(withAll.contains("otel_scope_name=\"io.demo\""), withAll);

        final String bare = PrometheusText.render(Collections.singletonList(gauge("m2", "1", "abc")));
        assertFalse(bare.contains("unit="), "空的 unit 不该出一个空标签：" + bare);
        assertFalse(bare.contains("otel_scope_name="), bare);
    }

    @Test
    @DisplayName("标签按名字排序输出 —— 两次抓取的文本应当只差数值")
    void labelsAreSorted() {
        final Map<String, Object> row = gauge("m", "1", "abc");
        row.put("unit", "ms");
        row.put("scopeName", "io.demo");
        final String out = PrometheusText.render(Collections.singletonList(row));
        final int i = out.indexOf("{");
        final String labels = out.substring(i, out.indexOf('}', i) + 1);
        assertEquals("{attr_key=\"abc\",otel_scope_name=\"io.demo\",unit=\"ms\"}", labels, out);
    }

    @Test
    @DisplayName("HELP 里的换行换成空格 —— HELP 只到行尾，换行会破坏文本格式")
    void helpIsSingleLine() {
        final Map<String, Object> row = gauge("m", "1", "abc");
        row.put("description", "第一行\n第二行");
        final String out = PrometheusText.render(Collections.singletonList(row));
        assertTrue(out.contains("# HELP m 第一行 第二行"), out);
        for (final String line : out.split("\n")) {
            assertFalse(line.startsWith("第二行"), "HELP 必须是单行：" + out);
        }
    }

    @Test
    @DisplayName("指标名或形态缺失的行被跳过，不产出半截样本行")
    void incompleteRowsAreSkipped() {
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row("", "GAUGE", 1.0d, null, null, "double", "h"));
        rows.add(gauge("ok", "1", "h"));
        final String out = PrometheusText.render(rows);
        assertEquals(1, countSampleLines(out), out);
        assertTrue(out.contains("ok{attr_key=\"h\"} 1"), out);
    }

    private static int countSampleLines(final String text) {
        int n = 0;
        for (final String line : text.split("\n")) {
            if (!line.isEmpty() && !line.startsWith("#")) {
                n++;
            }
        }
        return n;
    }
}
