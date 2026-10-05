package io.github.fulizhe.otelstore.readout.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「每个序列的最新一点」渲染成 Prometheus 文本格式。
 *
 * <p><b>形态转换是这个渲染器的全部难点</b>：内部存的是"时间序列的一个点"，
 * 而 Prometheus 要的是<b>当前值</b>。所以输入必须是"每个（指标名，属性组合）各一行"
 * （由 {@code LocalStore} 的窗口函数查询保证），而不是"最近 N 行"。
 *
 * <p><b>三条口径（ADR-6 第八节）</b>，它们都是"宁可保守也不撒谎"：
 *
 * <ol>
 *   <li><b>{@code SUM} 不改名成 {@code _total}</b>。那个后缀隐含"单调递增"，
 *       而我们没有持久化 {@code is_monotonic} —— 改名就是撒谎。只用 {@code # TYPE} 声明类型。</li>
 *   <li><b>标签给 {@code attr_key} 哈希</b>，以及非空时的 {@code unit} 与 scope。
 *       属性本身没存（表里只有哈希），所以输出的是哈希而不是 {@code region="east"} ——
 *       这不好，但比"同名合并成一条线"正确得多（那会直接给出错误数据）。</li>
 *   <li><b>指标名非法字符换 {@code _}，撞名追加 8 位哈希后缀</b>，
 *       保证 {@code a.b} 与 {@code a_b} 不会变成同一条线。</li>
 * </ol>
 *
 * <p>直方图与摘要是<b>就地展开</b>的：{@code detail} 里存着桶边界与各桶计数
 * （ADR-2），这里把它还原成 {@code _bucket{le="…"}} + {@code _sum} + {@code _count}。
 */
public final class PrometheusText {

    /** 指标名里允许出现的字符（Prometheus 的约定）。其余一律换 {@code _}。 */
    private static final String LEGAL_NAME_CHARS = "abcdefghijklmnopqrstuvwxyz"
            + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_:";

    /** 撞名时追加的后缀长度（十六进制字符）。8 位对"名字撞了"这件事足够区分。 */
    private static final int SUFFIX_LEN = 8;

    /** Prometheus 认识的浮点特殊值 —— 它接受这两个字面量，所以不必跳过。 */
    private static final String NAN = "NaN";
    private static final String POS_INF = "+Inf";
    private static final String NEG_INF = "-Inf";

    private PrometheusText() {
    }

    /**
     * 渲染成完整的 Prometheus 文本响应。
     *
     * @param rows 每个序列最新一点；null 与空都渲染成空 body（抓取器看到空 body 是正常的）
     */
    public static String render(final List<Map<String, Object>> rows) {
        final StringBuilder sb = new StringBuilder(1024);
        if (rows == null || rows.isEmpty()) {
            return sb.toString();
        }

        // 第一遍：把原始指标名映射成输出名，并发现撞名 —— 必须先全部定名，
        // 否则"先来的占了名字、后来的被迫改名"会让输出名依赖行的顺序
        final Map<String, String> nameByRaw = assignNames(rows);
        final Set<String> families = new LinkedHashSet<String>();

        for (final Map<String, Object> row : rows) {
            final String raw = str(row.get("metricName"));
            if (raw.isEmpty()) {
                continue;
            }
            final String name = nameByRaw.get(raw);
            final String kind = kind(row);
            if (families.add(name)) {
                sb.append("# HELP ").append(name).append(' ')
                        .append(escapeHelp(help(row))).append('\n');
                sb.append("# TYPE ").append(name).append(' ').append(prometheusType(kind)).append('\n');
            }
            appendSamples(sb, row, name, kind);
        }
        return sb.toString();
    }

    /**
     * 原始指标名 → 输出名。
     *
     * <p>两步：先全部换成合法字符，再对**撞名的那些**追加哈希后缀。
     *
     * <p><b>撞名的每一个都加后缀</b>，而不是"先来的保留原名"：
     * 谁先来谁占着原名会让输出名**依赖行的顺序**，而行顺序会随查询变 ——
     * 那意味着 Grafana 里同一条线今天叫 `a_b`、明天叫 `a_b_1f3e9a2c`。
     * 各带自己的哈希则是对称的、可复算的。
     *
     * <p>因此必须**先把全部行看完再定名**，不能边遍历边输出。
     */
    private static Map<String, String> assignNames(final List<Map<String, Object>> rows) {
        final Map<String, String> sanitized = new LinkedHashMap<String, String>();
        final Map<String, Integer> hits = new LinkedHashMap<String, Integer>();
        for (final Map<String, Object> row : rows) {
            final String raw = str(row.get("metricName"));
            if (raw.isEmpty()) {
                continue;
            }
            final String base = sanitize(raw);
            sanitized.put(raw, base);
            final Integer n = hits.get(base);
            hits.put(base, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }
        final Map<String, String> out = new LinkedHashMap<String, String>();
        for (final Map.Entry<String, String> e : sanitized.entrySet()) {
            final String base = e.getValue();
            out.put(e.getKey(), hits.get(base).intValue() > 1 ? base + "_" + shortHash(e.getKey()) : base);
        }
        return out;
    }

    /** 非法字符换 {@code _}；以数字开头的加一个下划线前缀（Prometheus 不允许数字开头）。 */
    static String sanitize(final String raw) {
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            sb.append(LEGAL_NAME_CHARS.indexOf(c) >= 0 ? c : '_');
        }
        if (sb.length() == 0) {
            return "_";
        }
        final char first = sb.charAt(0);
        if (first >= '0' && first <= '9') {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    private static void appendSamples(final StringBuilder sb, final Map<String, Object> row,
            final String name, final String kind) {
        final String labels = baseLabels(row);
        if ("HISTOGRAM".equals(kind)) {
            appendHistogram(sb, name, labels, str(row.get("metricSum")),
                    longOf(row.get("metricCount")), MetricDetail.parse(detailOf(row)));
        } else if ("SUMMARY".equals(kind)) {
            appendSummary(sb, name, labels, str(row.get("metricSum")),
                    longOf(row.get("metricCount")), MetricDetail.parse(detailOf(row)));
        } else {
            sb.append(name).append(labels).append(' ').append(number(str(row.get("metricValue"))))
                    .append('\n');
        }
    }

    /**
     * 直方图：{@code _bucket{le="…"}}（累计）+ {@code _sum} + {@code _count}。
     *
     * <p>两件容易做错、且错了会直接画错图的事：
     * <ol>
     *   <li>桶计数是<b>非累计</b>的（每个桶自己的计数），而 Prometheus 要求
     *       {@code _bucket} 的 le 标签是<b>累计</b>值。</li>
     *   <li>边界比桶<b>少一个</b>（OTel 的形状，见 {@code MetricDetail}），
     *       所以必须补一个 {@code le="+Inf"} 的桶。少补它，抓取器算出来的总量
     *       会小于 {@code _count}。</li>
     * </ol>
     */
    private static void appendHistogram(final StringBuilder sb, final String name, final String labels,
            final String sum, final long count, final MetricDetail detail) {
        if (detail.bounds == null || detail.counts == null) {
            // 认不出来就退化成只有 _sum/_count：给不出错误的桶，比给错的好
            appendSimple(sb, name + "_sum", labels, sum);
            appendSimple(sb, name + "_count", labels, String.valueOf(count));
            return;
        }
        long cumulative = 0L;
        for (int i = 0; i < detail.counts.size(); i++) {
            cumulative += detail.counts.get(i).longValue();
            final String le = i < detail.bounds.size() ? format(detail.bounds.get(i)) : "+Inf";
            sb.append(name).append("_bucket").append(withLabel(labels, "le", le))
                    .append(' ').append(cumulative).append('\n');
        }
        appendSimple(sb, name + "_sum", labels, sum);
        appendSimple(sb, name + "_count", labels, String.valueOf(count));
    }

    /**
     * 摘要：分位数按 {@code quantile="…"} 标签出。
     *
     * <p><b>刻意不套用直方图的形态</b>：{@code _sum} + {@code _count} 是直方图约定，
     * 摘要是"客户端算好的分位数"，两者的语义不同 —— 混起来会让人以为分位数是可加的。
     */
    private static void appendSummary(final StringBuilder sb, final String name, final String labels,
            final String sum, final long count, final MetricDetail detail) {
        if (detail.quantiles != null) {
            for (final Map.Entry<Double, Double> q : detail.quantiles.entrySet()) {
                sb.append(name).append(withLabel(labels, "quantile", format(q.getKey())))
                        .append(' ').append(number(format(q.getValue()))).append('\n');
            }
        }
        appendSimple(sb, name + "_sum", labels, sum);
        appendSimple(sb, name + "_count", labels, String.valueOf(count));
    }

    private static String format(final Double d) {
        return d == null ? "" : d.toString();
    }

    private static void appendSimple(final StringBuilder sb, final String name, final String labels,
            final String value) {
        sb.append(name).append(labels).append(' ').append(number(value)).append('\n');
    }

    /**
     * 基标签：{@code attr_key} 恒有，非空的 {@code unit} 与 scope 才有。
     *
     * <p>标签集合<b>按名字排序</b>输出：Prometheus 自己不关心顺序，但稳定输出让
     * "两次抓取的文本只差数值"这种 diff 有意义。
     */
    private static String baseLabels(final Map<String, Object> row) {
        final Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("attr_key", str(row.get("attrKey")));
        putIfNotEmpty(labels, "unit", str(row.get("unit")));
        putIfNotEmpty(labels, "otel_scope_name", str(row.get("scopeName")));
        putIfNotEmpty(labels, "otel_scope_version", str(row.get("scopeVersion")));
        return renderLabels(labels);
    }

    /** 在已有标签集合后再加一个（用于 {@code le} 与 {@code quantile}）。 */
    private static String withLabel(final String labels, final String key, final String value) {
        final Map<String, String> all = new LinkedHashMap<String, String>();
        if (!labels.isEmpty() && labels.startsWith("{") && labels.endsWith("}")) {
            parseLabels(labels, all);
        }
        all.put(key, value);
        return renderLabels(all);
    }

    private static String renderLabels(final Map<String, String> labels) {
        if (labels.isEmpty()) {
            return "";
        }
        final List<String> names = new ArrayList<String>(labels.keySet());
        java.util.Collections.sort(names);
        final StringBuilder sb = new StringBuilder(64);
        sb.append('{');
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(names.get(i)).append("=\"").append(escapeLabel(labels.get(names.get(i)))).append('"');
        }
        return sb.append('}').toString();
    }

    private static void parseLabels(final String labels, final Map<String, String> out) {
        final String body = labels.substring(1, labels.length() - 1);
        if (body.isEmpty()) {
            return;
        }
        for (final String pair : body.split(",")) {
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                String v = pair.substring(eq + 2, pair.length() - 1);
                if (v.startsWith("\\\\")) {
                    v = v.substring(1);
                }
                out.put(pair.substring(0, eq), v);
            }
        }
    }

    /** 标签值转义：反斜杠、双引号、换行。少一个就产出一段解析器会拒绝的文本。 */
    static String escapeLabel(final String value) {
        if (value == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\n') {
                sb.append("\\n");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** HELP 里的换行会破坏文本格式（HELP 只到行尾），换成空格。 */
    private static String escapeHelp(final String text) {
        return text.replace("\r", " ").replace("\n", " ");
    }

    /**
     * 数值。
     *
     * <p>认得 Prometheus 的三个特殊字面量（{@code NaN} / {@code +Inf} / {@code -Inf}）——
     * 指标的非标量形态存的就是 NaN，原样输出即可，Prometheus 接受。
     * 认不出来的给 {@code NaN} 而不是 {@code 0}：<b>0 是一个假的确定值</b>，
     * 而 NaN 至少说的是"这里没有值"。
     */
    static String number(final String raw) {
        final String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return NAN;
        }
        if (NAN.equals(v) || POS_INF.equals(v) || NEG_INF.equals(v)) {
            return v;
        }
        final double d;
        try {
            d = Double.parseDouble(v);
        } catch (final NumberFormatException e) {
            return NAN;
        }
        if (Double.isNaN(d)) {
            return NAN;
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? POS_INF : NEG_INF;
        }
        return v;
    }

    private static String kind(final Map<String, Object> row) {
        final String k = str(row.get("dataType"));
        return k.isEmpty() ? "GAUGE" : k;
    }

    /** GAUGE / counter / untyped：形态对上 Prometheus 的 TYPE 关键字。 */
    private static String prometheusType(final String kind) {
        if ("GAUGE".equals(kind)) {
            return "gauge";
        }
        if ("SUM".equals(kind)) {
            return "counter";
        }
        // HISTOGRAM 与 SUMMARY 都要自己展开，TYPE 只能声明为 untyped ——
        // 声明成 histogram 而不给出全部 _bucket 会被抓取器判为格式错误
        return "untyped";
    }

    private static String help(final Map<String, Object> row) {
        final String d = str(row.get("description"));
        return d.isEmpty() ? str(row.get("metricName")) : d;
    }

    private static String detailOf(final Map<String, Object> row) {
        return str(row.get("detail"));
    }

    private static long longOf(final Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static void putIfNotEmpty(final Map<String, String> labels, final String key, final String value) {
        if (value != null && !value.isEmpty()) {
            labels.put(key, value);
        }
    }

    private static String str(final Object o) {
        return o == null ? "" : o.toString();
    }

    /** 稳定的短哈希：只用十六进制字符，长度固定 —— 名字里出现别的东西 Prometheus 会拒绝。 */
    private static String shortHash(final String raw) {
        try {
            final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            final byte[] d = md.digest(raw.getBytes(java.nio.charset.Charset.forName("UTF-8")));
            final StringBuilder sb = new StringBuilder(SUFFIX_LEN);
            for (int i = 0; i < d.length && sb.length() < SUFFIX_LEN; i++) {
                final int v = d[i] & 0xFF;
                sb.append(hex(v >>> 4)).append(hex(v & 0x0F));
            }
            return sb.substring(0, SUFFIX_LEN);
        } catch (final java.security.NoSuchAlgorithmException e) {
            return "00000000";
        }
    }

    private static char hex(final int v) {
        return (char) (v < 10 ? ('0' + v) : ('a' + v - 10));
    }
}
