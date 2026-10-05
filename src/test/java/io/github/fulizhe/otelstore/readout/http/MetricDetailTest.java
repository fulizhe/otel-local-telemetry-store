package io.github.fulizhe.otelstore.readout.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code detail} 的语法只有这一处实现（Prometheus 渲染器与页面都靠它），
 * 所以它的五种形态与各种残缺输入都要逐条钉住。
 */
class MetricDetailTest {

    @Test
    @DisplayName("标量：只有形态标记")
    void scalar() {
        assertEquals("long", MetricDetail.parse("long").flavor);
        assertEquals("double", MetricDetail.parse("double").flavor);
        assertNull(MetricDetail.parse("long").bounds);
    }

    @Test
    @DisplayName("显式边界直方图：边界与计数都解出来")
    void explicitHistogram() {
        final MetricDetail d = MetricDetail.parse(
                "explicit;min=1.0;max=9.0;bounds=[2.0, 5.0];counts=[3, 4]");
        assertEquals("explicit", d.flavor);
        assertNotNull(d.bounds);
        assertNotNull(d.counts);
        assertEquals(2, d.bounds.size());
        assertEquals(Double.valueOf(2.0d), d.bounds.get(0));
        assertEquals(Double.valueOf(5.0d), d.bounds.get(1));
        assertEquals(Long.valueOf(3L), d.counts.get(0));
        assertEquals(Long.valueOf(4L), d.counts.get(1));
        assertEquals(Double.valueOf(1.0d), d.min);
        assertEquals(Double.valueOf(9.0d), d.max);
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
                "explicit;min=1.0;max=9.0;bounds=[2.0, 5.0];counts=[x, y]"}) {
            final MetricDetail d = MetricDetail.parse(bad);
            assertNull(d.flavor, "应当认不出来：" + bad);
            assertNull(d.bounds, "认不出来时不能给出半对的桶：" + bad);
        }
    }

    @Test
    @DisplayName("key 必须是完整字段名：min= 不该被 max= 命中")
    void fieldNamesAreExact() {
        final MetricDetail d = MetricDetail.parse("explicit;min=1.0;max=9.0;bounds=[];counts=[]");
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
                "explicit;min=1.0;max=9.0;bounds=[2.0];counts=[3]").toMap("raw");
        assertEquals("explicit", hist.get("flavor"));
        assertTrue(hist.containsKey("bounds"));
        assertTrue(hist.containsKey("counts"));
        assertTrue(hist.containsKey("min"));
        assertTrue(hist.containsKey("max"));
    }
}
