package io.github.fulizhe.otelstore.readout;

import java.util.List;
import java.util.Map;

/**
 * 把 {@code core} 的快照摊成缩进文本。
 *
 * <p><b>为什么读口出文本而不是对象</b>：JMX 是跨 ClassLoader 的通道，而本项目跑在
 * {@code ExtensionClassLoader} 上、应用在 {@code AppClassLoader} 上（ADR-1 原则 5、R0 笔记第五节）。
 * 跨边界的 MBean 属性只能是 JDK 原生类型或 {@code String}；把 {@code Map} 塞进属性，
 * 接收端拿到的只会是 {@code toString()} 的结果，反而不如我们自己排好版。
 *
 * <p>保序（{@code LinkedHashMap} 的迭代序）原样保留：排障时"上下相邻的两行"往往是同一件事，
 * 排序会把它们拆散。
 */
public final class TextRenderer {

    private TextRenderer() {
    }

    /**
     * 摊平任意快照。
     *
     * @param value {@code Map} / {@code List} / 标量 / null 都吃得下
     */
    public static String render(final Object value) {
        final StringBuilder sb = new StringBuilder(256);
        render(sb, value, 0);
        return sb.toString();
    }

    /**
     * 摊平一张表（行是 {@code Map}）。
     *
     * <p>每行前面加上序号是给人看的：详情页里"这是第几条"要能对上，
     * 而 {@code id} 是代理主键、按插入顺序分配，与"第几条"在淘汰未发生时一致 ——
     * 一旦发生淘汰两者会分叉，所以这里明确不假装它是 id。
     */
    public static String renderRows(final String caption, final List<? extends Map<String, Object>> rows) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append(caption).append(": ").append(rows == null ? 0 : rows.size()).append(" 行");
        if (rows == null || rows.isEmpty()) {
            return sb.toString();
        }
        int n = 0;
        for (final Map<String, Object> row : rows) {
            sb.append('\n').append("  #").append(n++).append(' ').append(render(row));
        }
        return sb.toString();
    }

    private static void render(final StringBuilder sb, final Object value, final int depth) {
        if (value instanceof Map) {
            renderMap(sb, (Map<?, ?>) value, depth);
        } else if (value instanceof List) {
            renderList(sb, (List<?>) value, depth);
        } else {
            sb.append(value);
        }
    }

    /**
     * 顶层（{@code depth == 0}）且不止一项时按多行摊开，其余情况一律单行内联。
     *
     * <p>理由很实际：顶层是给人通读的，一行几百字符没法看；而嵌套里的
     * {@code trace={offered=1, drained=1}} 就该短小紧凑，跟着父行的缩进走。
     */
    private static void renderMap(final StringBuilder sb, final Map<?, ?> map, final int depth) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        if (depth > 0 || map.size() == 1) {
            sb.append('{');
            boolean first = true;
            for (final Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(e.getKey()).append('=');
                render(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
            return;
        }
        final String pad = indent(1);
        sb.append('\n');
        for (final Map.Entry<?, ?> e : map.entrySet()) {
            sb.append(pad).append(e.getKey()).append('=');
            render(sb, e.getValue(), 1);
            sb.append('\n');
        }
    }

    private static void renderList(final StringBuilder sb, final List<?> list, final int depth) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            render(sb, list.get(i), depth + 1);
        }
        sb.append(']');
    }

    private static String indent(final int depth) {
        final StringBuilder sb = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }
}
