package io.github.fulizhe.otelstore.readout.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code detail} 的语法只有这一处实现（Prometheus 渲染器与页面都靠它），
 * 所以它的五种形态与各种残缺输入都要逐条钉住。
 *
 * <p><b>测试数据必须照着真实 SDK 的形状写</b>：直方图那条尤其重要 ——
 * OTel 给的是 N 个桶 + <b>N-1</b> 个边界。曾经这里写的是「2 边界 + 2 计数」
 * （真实 SDK 根本产不出这种组合），于是"边界数必须等于计数数"这个错判断全绿通过，
 * 而真机上的每一个直方图都显示"认不出来"。
 */
class MetricDetailTest {

    /** 一段真实形状的 detail：16 个桶、15 个边界（照 demo-app 的直方图配置抄的量级）。 */
    static final String REAL_EXPLICIT =
            "explicit;min=0.0;max=1.0;bounds=[0.0, 5.0, 10.0, 25.0, 50.0, 75.0, 100.0, 250.0,"
                    + " 500.0, 750.0, 1000.0, 2500.0, 5000.0, 7500.0, 10000.0];"
                    + "counts=[1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]";

    @Test
    @DisplayName("真实形状的直方图：边界比计数少一个，认得出来")
    void explicitHistogramWithRealShape() {
        final MetricDetail d = MetricDetail.parse(REAL_EXPLICIT);
        assertEquals("explicit", d.flavor, "真实数据必须认得出来：" + REAL_EXPLICIT);
        assertEquals(15, d.bounds.size());
        assertEquals(16, d.counts.size());
        assertEquals(Double.valueOf(0.0d), d.bounds.get(0));
        assertEquals(Double.valueOf(10000.0d), d.bounds.get(14));
        assertEquals(Long.valueOf(1L), d.counts.get(0));
        assertEquals(Long.valueOf(1L), d.counts.get(1));
        assertEquals(Long.valueOf(0L), d.counts.get(15));
        assertEquals(Double.valueOf(1.0d), d.max);
    }

    @Test
    @DisplayName("toMap 给出 leBoundaries（含 +Inf）与累计计数，页面不必自己累加")
    void toMapDerivesLeAndCumulative() {
        final Map<String, Object> m = MetricDetail.parse(REAL_EXPLICIT).toMap(REAL_EXPLICIT);
        final List<?> le = (List<?>) m.get("leBoundaries");
        final List<?> cumulative = (List<?>) m.get("cumulative");
        assertEquals(16, le.size(), "16 个桶 → 16 个 le（含 +Inf）");
        assertEquals("10000.0", le.get(14));
        assertEquals("+Inf", le.get(15), "最后一个桶没有上界；且 +Inf 不是合法 JSON 数值，必须是字符串");
        assertEquals(16, cumulative.size());
        assertEquals(Long.valueOf(1L), cumulative.get(0));
        assertEquals(Long.valueOf(2L), cumulative.get(1), "累计：第 2 个桶之后是 2");
        assertEquals(Long.valueOf(2L), cumulative.get(15), "总量应与 _count 一致");
    }

    @Test
    @DisplayName("标量：只有形态标记")
    void scalar() {
        assertEquals("long", MetricDetail.parse("long").flavor);
        assertEquals("double", MetricDetail.parse("double").flavor);
        assertNull(MetricDetail.parse("long").bounds);
    }

    @Test
    @DisplayName("显式边界直方图：形状不对就认不出来（边界数 ≠ 计数数 - 1）")
    void explicitHistogramShapeMustBeBucketsMinusOne() {
        final MetricDetail ok = MetricDetail.parse(
                "explicit;min=0.0;max=1.0;bounds=[1.0, 2.0];counts=[1, 2, 3]");
        assertEquals("explicit", ok.flavor, "3 桶 2 边界是合法形状");
        assertEquals(3, ok.counts.size());

        // 这些形状在真实 SDK 里不会出现，但出现了就说明数据不对 —— 不能硬凑
        for (final String bad : new String[]{
            "explicit;bounds=[1.0, 2.0];counts=[1, 2]",           // 等长（曾经的错假设）
            "explicit;bounds=[1.0];counts=[1]",                   // 1 边界 1 计数
            "explicit;bounds=[1.0, 2.0, 3.0];counts=[1, 2]",     // 差两个
            "explicit;bounds=[1.0, 2.0]",                        // 没有 counts
            "explicit;counts=[1, 2, 3]"}) {                       // 没有 bounds
            assertNull(MetricDetail.parse(bad).flavor, "应当认不出来：" + bad);
        }
    }

    @Test
    @DisplayName("指数直方图：scale 与 zeroCount 解出来，min/max 为 none 时是 null")
    void exponentialHistogram() {
        final MetricDetail d = MetricDetail.parse(
                "exponential;scale=-3;zeroCount=2;min=none;max=9.5;pos=4,[1, 2];neg=none");
        assertEquals("exponential", d.flavor);
        assertEquals(Integer.valueOf(-3), d.scale);
        assertEquals(Long.valueOf(2L), d.zeroCount);
        assertNull(d.min, "none 要解成 null，而不是字符串 \"none\"");
        assertEquals(Double.valueOf(9.5d), d.max);
    }

    @Test
    @DisplayName("摘要：分位点解出来且保持顺序")
    void summary() {
        final MetricDetail d = MetricDetail.parse("quantiles;0.5=1.0;0.9=2.0;0.99=2.8");
        assertEquals("quantiles", d.flavor);
        assertNotNull(d.quantiles);
        assertEquals(3, d.quantiles.size());
        assertEquals(Double.valueOf(1.0d), d.quantiles.get(Double.valueOf(0.5d)));
        assertEquals(Double.valueOf(2.8d), d.quantiles.get(Double.valueOf(0.99d)));
    }

    @Test
    @DisplayName("残缺与不认识：flavor 为 null，且**不抛异常** —— 给不出桶比给错桶好")
    void malformedBecomesUnknown() {
        for (final String bad : new String[]{null, "", "认不出来的形态",
                "explicit;bounds=[1.0, 2.0]",                       // 没有 counts
                "explicit;bounds=[1.0, 2.0];counts=[1]",           // 长度不等
                "explicit;bounds=[a, b];counts=[1, 2]",             // 不是数字
                "explicit;min=1.0;max=9.0;bounds=[2.0, 5.0];counts=[x, y, z]"}) {
            final MetricDetail d = MetricDetail.parse(bad);
            assertNull(d.flavor, "应当认不出来：" + bad);
            assertNull(d.bounds, "认不出来时不能给出半对的桶：" + bad);
        }
    }

    @Test
    @DisplayName("key 必须是完整字段名：min= 不该被 max= 命中")
    void fieldNamesAreExact() {
        final MetricDetail d = MetricDetail.parse("explicit;min=1.0;max=9.0;bounds=[];counts=[3]");
        assertEquals(Double.valueOf(1.0d), d.min);
        assertEquals(Double.valueOf(9.0d), d.max);
    }

    @Test
    @DisplayName("toMap：认不出来时带上原文，让人能判断是形态没覆盖还是数据坏了")
    void toMapCarriesRawWhenUnknown() {
        final Map<String, Object> unknown = MetricDetail.parse("????").toMap("????");
        assertEquals("unknown", unknown.get("flavor"));
        assertEquals("????", unknown.get("raw"), "只给空结构的话，页面上什么都判断不了");

        final Map<String, Object> hist = MetricDetail.parse(
                "explicit;min=1.0;max=9.0;bounds=[2.0, 5.0];counts=[3, 4, 0]").toMap("raw");
        assertEquals("explicit", hist.get("flavor"));
        assertTrue(hist.containsKey("leBoundaries"));
        assertTrue(hist.containsKey("counts"));
        assertTrue(hist.containsKey("min"));
        assertTrue(hist.containsKey("max"));
    }
}
