package io.github.fulizhe.otelstore.readout.http;

import java.util.List;
import java.util.Map;

/**
 * 手写 JSON 编码器 —— **零三方依赖**是这个读口能保持"不引入任何需要 relocation 的东西"的前提。
 *
 * <p>只支持我们**自己造出来的**形状：Map / List / String / Number / Boolean / null。
 * 读口输出的每一个值都来自 {@code ReadoutQueries}，也就是存储层的行与快照，
 * 没有任何"需要反射才能变成 JSON"的东西 —— 所以序列化框架在这里是纯负担：
 * 一次 relocation、一次版本选择、一次"字节码是不是 52"的核对。
 *
 * <p><b>遇到不认识的对象直接抛错</b>，不静默输出 {@code {}} ——
 * 将来有人想输出一个自定义类型时，他要看到的是"这里不支持"，
 * 而不是一个空对象让排查花半天。
 *
 * <p>三个容易写错、因此在这里被显式处理掉的点：
 * <ul>
 *   <li><b>{@code NaN} 与 {@code Infinity} 不是合法 JSON</b>。指标值会出现它们
 *       （非标量形态的行存的就是 NaN），直接写出去得到的是解析器会拒绝的文本。
 *       这里输出 {@code null} —— 宁可少一个字段，也不能产出一段解析不了的响应。</li>
 *   <li><b>控制字符必须转义</b>。span 名与日志正文里出现 {@code \n} 是常事，
 *       不转义就产出了"看起来合法、其实解析器会报错"的 JSON。</li>
 *   <li><b>非 ASCII 直接输出</b>（靠响应头声明 charset），不逐字符转义成
 *       U+XXXX 形式。可读性差一大截，收益为零。</li>
 * </ul>
 */
public final class Json {

    private Json() {
    }

    /**
     * 把值编码成 JSON 文本。
     *
     * @throws IllegalArgumentException 遇到不支持的类型（见类注释）
     */
    public static String write(final Object value) {
        final StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    private static void write(final StringBuilder sb, final Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Number) {
            writeNumber(sb, (Number) value);
        } else if (value instanceof Map) {
            writeMap(sb, (Map<?, ?>) value);
        } else if (value instanceof List) {
            writeList(sb, (List<?>) value);
        } else {
            throw new IllegalArgumentException("不支持的 JSON 类型：" + value.getClass().getName()
                    + "。读口只输出自己造的 Map/List/标量；"
                    + "要输出自定义对象，先给它一个明确的表示形式，别让它掉进 {} 里。");
        }
    }

    private static void writeMap(final StringBuilder sb, final Map<?, ?> map) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (final Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            write(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeList(final StringBuilder sb, final List<?> list) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            write(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void writeNumber(final StringBuilder sb, final Number n) {
        if (n instanceof Double || n instanceof Float) {
            final double d = n.doubleValue();
            // NaN / Infinity 不是合法 JSON。指标的非标量形态就存着 NaN，这里必须挡住。
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
                return;
            }
        }
        sb.append(n.toString());
    }

    private static void writeString(final StringBuilder sb, final String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        // 其余控制字符没有简写，必须用「反斜杠 + u」的形式；不转义就是一段解析器会拒绝的文本
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}