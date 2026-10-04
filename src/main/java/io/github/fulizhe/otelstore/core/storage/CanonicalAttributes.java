package io.github.fulizhe.otelstore.core.storage;

import io.github.fulizhe.otelstore.core.model.KeyValue;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 属性集合的<b>规范化</b>：一份文本 + 一个哈希，两处必须用同一份实现。
 *
 * <p>为什么需要规范化（ADR-2「Resource 抽字典表」）：{@code Resource} 的属性是
 * {@code Map}，遍历顺序不保证；同一份 Resource 两次拿到可能顺序不同。若直接对
 * "遍历出来的顺序"取哈希，就会把同一份 Resource 算成两个不同的哈希，字典表去重失效、
 * 每条记录都多出一个外键。规范化把这件事变成确定的：<b>按键排序</b>、<b>值带类型标签</b>、
 * 整体编码后再取 SHA-256。
 *
 * <p>类型标签不能省：{@code Long 1}、{@code Double 1.0}、{@code String "1"} 三者
 * {@code toString()} 出来可能一样，但它们在 OTLP 里是三种 {@code AnyValue}，
 * 混成一个哈希就等于宣称它们相等。
 *
 * <p>本类包级私有 —— 它的输出格式是 {@code resource_dict.attributes} 列与
 * {@code metric_point.attr_key} 的实现细节，不属于对外契约。格式一旦发布就不要再改：
 * 改了等于同一份数据算出两个哈希。ADR-2 也正因为如此才明确"碰撞时不追求正确性，
 * 以 id 为准、hash 只作去重键"。
 */
final class CanonicalAttributes {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private CanonicalAttributes() {
    }

    /**
     * 规范化文本。格式：{@code key=tag:value} 用 {@code ;} 连接，数组写作 {@code [tag:value,...]}。
     *
     * <p>选文本而不是二进制编码，是因为这同一份字符串要直接落进
     * {@code resource_dict.attributes} 列给人看（排查时能一眼看出"这份 Resource 到底有哪些属性"）。
     * 为人可读牺牲几个字节是划算的：字典表的行数等于 Resource 的种类数，不是记录数。
     */
    static String render(final List<KeyValue> attributes) {
        final List<KeyValue> sorted = KeyValue.sortedCopy(attributes);
        final StringBuilder sb = new StringBuilder(64 + sorted.size() * 24);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            final KeyValue kv = sorted.get(i);
            sb.append(kv.getKey()).append('=');
            renderValue(sb, kv.getValue());
        }
        return sb.toString();
    }

    private static void renderValue(final StringBuilder sb, final Object value) {
        if (value instanceof String) {
            sb.append("s:").append((String) value);
        } else if (value instanceof Long) {
            sb.append("i:").append(value);
        } else if (value instanceof Double) {
            sb.append("d:").append(value);
        } else if (value instanceof Boolean) {
            sb.append("b:").append(value);
        } else {
            sb.append('[');
            final List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                renderValue(sb, list.get(i));
            }
            sb.append(']');
        }
    }

    /** 规范化文本的 SHA-256，小写十六进制。 */
    static String hash(final List<KeyValue> attributes) {
        final byte[] canonical = render(attributes).getBytes(UTF8);
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            // SHA-256 是 JLS 强制的算法，走到这里说明 JVM 坏了；抛出去比静默降级安全。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        final byte[] digest = md.digest(canonical);
        final char[] hex = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            final int v = digest[i] & 0xFF;
            hex[i * 2] = hexChar(v >>> 4);
            hex[i * 2 + 1] = hexChar(v & 0x0F);
        }
        return new String(hex);
    }

    private static char hexChar(final int v) {
        return (char) (v < 10 ? ('0' + v) : ('a' + v - 10));
    }
}
