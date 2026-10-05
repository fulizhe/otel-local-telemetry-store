package io.github.fulizhe.otelstore.readout.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 {@code metric_point.detail} 那一列 —— <b>整个读口只有这一处懂它的语法</b>。
 *
 * <p>为什么要抽出来：{@code detail} 是我们自己定的紧凑文本（ADR-2），
 * 写它的是 {@code MetricMapper}，读它的至少有 Prometheus 渲染器与页面。
 * 两处各写一个解析器，就等于两套语法 —— 而"两处对不上"正是这类存储最难查的故障
 * （ADR-3 反复警告的口径 proliferation）。
 *
 * <p>五种形态（第一个 token 是形态标记，之后是该形态自己的内容）：
 *
 * <pre>
 *   long | double                                     标量，detail 里没有别的内容
 *   explicit;min=..;max=..;bounds=[..];counts=[..]    显式边界直方图
 *   exponential;scale=..;zeroCount=..;pos=off,[..]…   指数直方图
 *   quantiles;0.5=1.0;0.9=2.0                         摘要分位数
 * </pre>
 *
 * <p><b>认不出来时 flavor 为 {@code null} 且不抛异常</b>：调用方据此"不给桶"，
 * 而不是编一个看起来合理的桶 —— 给错的桶会让 `histogram_quantile` 算出错的分位数。
 * <p><b>{@code bounds} 比 {@code counts} 少一个</b> —— 这不是我们定的格式，是 OTel 的形状：
 * {@code HistogramPointData.getBoundaries()} 给出 N 个桶之间的 N-1 个边界，
 * 而 {@code getCounts()} 给出 N 个桶的计数。所以 {@code counts.size() == bounds.size() + 1}
 * 才是合法的直方图（"合法"指形状对得上；不代表每个桶都有数据）。
 *
 * <p>因此渲染 Prometheus 的 {@code _bucket} 时，**每个边界一个累计值，还要补一个 {@code +Inf} 桶**
 * （OTel 的最后一个桶没有上界，Prometheus 要求有）。少补那个 {@code +Inf}，
 * 抓取器算出来的总量会小于 {@code _count}。
 *
 * <p>写成"边界数 == 计数数"这种直觉判断的代价，本项目已经付过一次：
 * 真实数据（15 边界 / 16 计数）全被判成"认不出来"，而当时全绿的测试用的是
 * 「2 边界 / 2 计数」——<b>那个组合在真实 SDK 输出里根本不存在</b>。
 * 测试数据必须照着真实形状写，否则绿灯只是在验证一个不会发生的世界。
 */
final class MetricDetail {

    /** 认不出来的形态。 */
    static final String UNKNOWN = null;

    final String flavor;
    final Double min;
    final Double max;
    final Integer scale;
    final Long zeroCount;
    /** 显式边界直方图：桶边界与非累计计数，长度相同。 */
    final List<Double> bounds;
    final List<Long> counts;
    /** 摘要：分位点 → 值，保持出现顺序。 */
    final Map<Double, Double> quantiles;

    private MetricDetail(final String flavor, final Double min, final Double max, final Integer scale,
            final Long zeroCount, final List<Double> bounds, final List<Long> counts,
            final Map<Double, Double> quantiles) {
        this.flavor = flavor;
        this.min = min;
        this.max = max;
        this.scale = scale;
        this.zeroCount = zeroCount;
        this.bounds = bounds;
        this.counts = counts;
        this.quantiles = quantiles;
    }

    /** 标量：detail 只有形态标记。 */
    static MetricDetail scalar(final String flavor) {
        return new MetricDetail(flavor, null, null, null, null, null, null, null);
    }

    /** 解析。{@code null} 与空文本都得到"认不出来"。 */
    static MetricDetail parse(final String detail) {
        if (detail == null || detail.isEmpty()) {
            return new MetricDetail(UNKNOWN, null, null, null, null, null, null, null);
        }
        final String flavor = firstToken(detail);
        if ("long".equals(flavor) || "double".equals(flavor)) {
            return scalar(flavor);
        }
        if ("explicit".equals(flavor)) {
            final List<Double> bounds = doubles(field(detail, "bounds"));
            final List<Long> counts = longs(field(detail, "counts"));
            // N 个桶 + N-1 个边界（见类注释）。多或少都认不出来 ——
            // "第 i 个桶的边界"在形状不对时根本无从谈起，硬凑只会画出错的直方图。
            if (bounds == null || counts == null || counts.size() != bounds.size() + 1) {
                return new MetricDetail(UNKNOWN, null, null, null, null, null, null, null);
            }
            return new MetricDetail(flavor, dbl(field(detail, "min")), dbl(field(detail, "max")),
                    null, null, bounds, counts, null);
        }
        if ("exponential".equals(flavor)) {
            return new MetricDetail(flavor, dbl(field(detail, "min")), dbl(field(detail, "max")),
                    integer(field(detail, "scale")), longOf(field(detail, "zeroCount")),
                    null, null, null);
        }
        if ("quantiles".equals(flavor)) {
            return new MetricDetail(flavor, null, null, null, null, null, null,
                    parseQuantiles(detail));
        }
        return new MetricDetail(UNKNOWN, null, null, null, null, null, null, null);
    }

    /**
     * JSON 形态：把能解析的部分变成结构，没解析出来的 {@code detail} 原文仍然带出去。
     *
     * <p>带原文是有意的：结构化解析失败时，页面上还能显示原文让人判断"是这个指标的形态
     * 我没覆盖，还是数据坏了"。只给结构就变成一个空对象，什么都判断不了。
     */
    Map<String, Object> toMap(final String raw) {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("flavor", flavor == null ? "unknown" : flavor);
        if (min != null) {
            m.put("min", min);
        }
        if (max != null) {
            m.put("max", max);
        }
        if (scale != null) {
            m.put("scale", scale);
        }
        if (zeroCount != null) {
            m.put("zeroCount", zeroCount);
        }
        if (bounds != null) {
            // leBoundaries 是**字符串**列表，且最后一个是 "+Inf"：
            // ① Prometheus 的 le 本身就是标签字符串；
            // ② +Inf 不是合法 JSON 数值（会被编码器按 NaN/Infinity 规则写成 null），写字符串才对。
            final List<String> le = new ArrayList<String>(bounds.size() + 1);
            for (int i = 0; i < bounds.size(); i++) {
                le.add(String.valueOf(bounds.get(i)));
            }
            le.add("+Inf");
            m.put("leBoundaries", le);
            if (counts != null) {
                m.put("counts", counts);
                // 每个 le 的**累计**计数（页面直接显示，不用自己累加）
                final List<Long> cumulative = new ArrayList<Long>(counts.size());
                long sum = 0L;
                for (int i = 0; i < counts.size(); i++) {
                    sum += counts.get(i).longValue();
                    cumulative.add(Long.valueOf(sum));
                }
                m.put("cumulative", cumulative);
            }
        }
        if (quantiles != null && !quantiles.isEmpty()) {
            final List<Object> qs = new ArrayList<Object>(quantiles.size());
            for (final Map.Entry<Double, Double> e : quantiles.entrySet()) {
                final Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("quantile", e.getKey());
                one.put("value", e.getValue());
                qs.add(one);
            }
            m.put("quantiles", qs);
        }
        if (flavor == null) {
            m.put("raw", raw == null ? "" : raw);
        }
        return m;
    }

    private static String firstToken(final String detail) {
        final int semi = detail.indexOf(';');
        final String head = semi < 0 ? detail : detail.substring(0, semi);
        final int eq = head.indexOf('=');
        return (eq < 0 ? head : head.substring(0, eq)).trim();
    }

    /**
     * 取 {@code key=value} 里的 value；取不到返回 null。
     *
     * <p><b>键必须紧跟在分号之后</b>（{@code i == lastIndexOf(';', i) + 1}）。
     * 少了这一条，{@code max=} 会被 {@code …max=} 之类的键名误命中，
     * 而 {@code Count=} 会被 {@code zeroCount=} 命中 —— 两种都悄无声息地给出错的值。
     */
    private static String field(final String detail, final String key) {
        final String marker = key + "=";
        int from = 0;
        while (true) {
            final int i = detail.indexOf(marker, from);
            if (i < 0) {
                return null;
            }
            final int before = detail.lastIndexOf(';', i);
            if (i == before + 1) {
                final int start = i + marker.length();
                int end = detail.indexOf(';', start);
                if (end < 0) {
                    end = detail.length();
                }
                return detail.substring(start, end).trim();
            }
            from = i + marker.length();
        }
    }

    private static List<Double> doubles(final String list) {
        final List<String> parts = splitList(list);
        if (parts == null) {
            return null;
        }
        final List<Double> out = new ArrayList<Double>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            final Double d = parseDouble(parts.get(i));
            if (d == null) {
                return null;
            }
            out.add(d);
        }
        return out;
    }

    private static List<Long> longs(final String list) {
        final List<String> parts = splitList(list);
        if (parts == null) {
            return null;
        }
        final List<Long> out = new ArrayList<Long>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            final Long l = parseLong(parts.get(i));
            if (l == null) {
                return null;
            }
            out.add(l);
        }
        return out;
    }

    private static List<String> splitList(final String list) {
        if (list == null) {
            return null;
        }
        final int open = list.indexOf('[');
        final int close = list.indexOf(']', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }
        final String body = list.substring(open + 1, close).trim();
        final List<String> out = new ArrayList<String>();
        if (body.isEmpty()) {
            return out;
        }
        for (final String part : body.split(",")) {
            out.add(part.trim());
        }
        return out;
    }

    private static Map<Double, Double> parseQuantiles(final String detail) {
        final Map<Double, Double> out = new LinkedHashMap<Double, Double>();
        int i = detail.indexOf(';');
        i = i < 0 ? detail.length() : i + 1;
        while (i < detail.length()) {
            final int next = detail.indexOf(';', i);
            final String pair = detail.substring(i, next < 0 ? detail.length() : next);
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                final Double q = parseDouble(pair.substring(0, eq));
                final Double v = parseDouble(pair.substring(eq + 1));
                if (q != null && v != null) {
                    out.put(q, v);
                }
            }
            i = next < 0 ? detail.length() : next + 1;
        }
        return out;
    }

    private static Double dbl(final String s) {
        return s == null ? null : parseDouble(s);
    }

    private static Integer integer(final String s) {
        final Long l = s == null ? null : parseLong(s);
        return l == null ? null : Integer.valueOf(l.intValue());
    }

    private static Long longOf(final String s) {
        return s == null ? null : parseLong(s);
    }

    private static Double parseDouble(final String s) {
        if (s == null) {
            return null;
        }
        final String v = s.trim();
        if (v.isEmpty() || "none".equals(v)) {
            return null;
        }
        try {
            final double d = Double.parseDouble(v);
            return Double.isNaN(d) ? null : Double.valueOf(d);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(final String s) {
        if (s == null) {
            return null;
        }
        final String v = s.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(Long.parseLong(v));
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
