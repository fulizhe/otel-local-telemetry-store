package io.github.fulizhe.otelstore.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条属性：键 + 带类型的值。
 *
 * <p>存在的理由是 {@code core} 不许 import 任何 {@code io.opentelemetry.*}
 * （见 ADR-1 的分层规则），因此 OTel 的 {@code Attributes} 必须在 {@code agentext}
 * 侧被翻译成这种 JDK 原生类型才能进存储。
 *
 * <p>值的类型只保留五种，这是 OTLP {@code AnyValue} 的子集：
 * {@code String} / {@code Long} / {@code Double} / {@code Boolean} /
 * 以上四者的 {@code List}（数组值）。
 * <b>不保留 {@code null}</b>：OTel 属性没有 null 值，翻译时若遇到就整个丢掉这条属性。
 */
public final class KeyValue {

    private final String key;
    private final Object value;

    /**
     * @param key  属性键；不允许为 null 或空
     * @param value 属性值；必须是 {@code String} / {@code Long} / {@code Double} / {@code Boolean} 或它们的 List
     */
    public KeyValue(final String key, final Object value) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("属性键不允许为空");
        }
        if (!isSupportedType(value)) {
            throw new IllegalArgumentException("不支持的属性值类型 " + value.getClass().getName()
                    + "（key=" + key + "）");
        }
        this.key = key;
        this.value = value;
    }

    /** 便捷构造：字符串值。 */
    public static KeyValue of(final String key, final String value) {
        return new KeyValue(key, value);
    }

    /**
     * 宽松构造：把任意 {@code Object} 折成受支持的类型之一。
     *
     * <p>存在的理由是 {@code agentext} 拿到的是 OTel 的属性集，值的静态类型不保证；
     * 与其让它在翻译层抛异常把整条记录打掉，不如降级成字符串。
     *
     * @return 折不出受支持类型时返回 null，调用方应丢掉这条属性
     */
    public static KeyValue lenient(final String key, final Object raw) {
        if (key == null || key.isEmpty() || raw == null) {
            return null;
        }
        if (isSupportedType(raw)) {
            return new KeyValue(key, raw);
        }
        if (raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
            return new KeyValue(key, Long.valueOf(((Number) raw).longValue()));
        }
        if (raw instanceof Float) {
            return new KeyValue(key, Double.valueOf(((Number) raw).doubleValue()));
        }
        return new KeyValue(key, raw.toString());
    }

    private static boolean isSupportedType(final Object v) {
        if (v instanceof String || v instanceof Long || v instanceof Double
                || v instanceof Boolean) {
            return true;
        }
        if (v instanceof List) {
            final List<?> l = (List<?>) v;
            if (l.isEmpty()) {
                return true;
            }
            for (int i = 0; i < l.size(); i++) {
                final Object e = l.get(i);
                if (!(e instanceof String) && !(e instanceof Long)
                        && !(e instanceof Double) && !(e instanceof Boolean)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public String getKey() {
        return key;
    }

    public Object getValue() {
        return value;
    }

    /** 按 {@code key} 排序后的深拷贝，用于规范化哈希（ADR-2：键排序后才能保证同一份 Resource 命中同一行）。 */
    public static List<KeyValue> sortedCopy(final List<KeyValue> in) {
        final List<KeyValue> out = new ArrayList<KeyValue>(in.size());
        for (int i = 0; i < in.size(); i++) {
            out.add(in.get(i));
        }
        Collections.sort(out, new java.util.Comparator<KeyValue>() {
            @Override
            public int compare(final KeyValue a, final KeyValue b) {
                return a.key.compareTo(b.key);
            }
        });
        return out;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KeyValue)) {
            return false;
        }
        final KeyValue other = (KeyValue) o;
        return key.equals(other.key) && (value == null ? other.value == null : value.equals(other.value));
    }

    @Override
    public int hashCode() {
        return 31 * key.hashCode() + (value == null ? 0 : value.hashCode());
    }

    @Override
    public String toString() {
        return key + "=" + value;
    }
}
