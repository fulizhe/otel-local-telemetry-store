package io.github.fulizhe.otelstore.agentext;

import com.google.protobuf.ByteString;
import io.github.fulizhe.otelstore.core.model.KeyValue;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * OTel 属性 → 两种目标形态的翻译。
 *
 * <p>翻译发生在这一层是因为 {@code core} 不许 import 任何 {@code io.opentelemetry.*}
 * （见 ADR-1 的分层规则）。同一个属性集合要落两处，形态不同：
 *
 * <ul>
 *   <li><b>{@code core} 的 {@link KeyValue}</b> —— 给表头的 {@code attr_count}、
 *       Resource 字典的规范化文本、metrics 的 {@code attr_key} 用；</li>
 *   <li><b>OTLP protobuf 的 {@code KeyValue}</b> —— 给环形文件里的载荷用。</li>
 * </ul>
 *
 * <p>两处都必须用同一套类型判定，否则会出现"表头说 3 个属性、载荷里 2 个"这种查不出来的偏差。
 * 所以这两个方法挨着放，且都以同一份类型分支为准。
 */
final class OtelAttributes {

    private OtelAttributes() {
    }

    /** 全部属性 → {@code core} 类型。 */
    static List<KeyValue> toCore(final Attributes attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Collections.emptyList();
        }
        final List<KeyValue> out = new ArrayList<KeyValue>(attributes.size());
        for (final Map.Entry<AttributeKey<?>, Object> e : attributes.asMap().entrySet()) {
            // lenient：折不出受支持的类型就丢掉这一条，而不是让整条记录写不进去
            final KeyValue kv = KeyValue.lenient(e.getKey().getKey(), e.getValue());
            if (kv != null) {
                out.add(kv);
            }
        }
        return out;
    }

    /** 按白名单过滤后 → {@code core} 类型（用于 Resource）。 */
    static List<KeyValue> toCoreWhitelisted(final Attributes attributes, final List<String> whitelist) {
        if (attributes == null || attributes.isEmpty()) {
            return Collections.emptyList();
        }
        final List<KeyValue> all = toCore(attributes);
        final List<KeyValue> out = new ArrayList<KeyValue>(Math.min(all.size(), whitelist.size()));
        for (int i = 0; i < all.size(); i++) {
            if (whitelist.contains(all.get(i).getKey())) {
                out.add(all.get(i));
            }
        }
        return out;
    }

    /** 全部属性 → OTLP protobuf，追加到 {@code target}。 */
    static void addAllToProto(final List<io.opentelemetry.proto.common.v1.KeyValue> target,
            final Attributes attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        for (final Map.Entry<AttributeKey<?>, Object> e : attributes.asMap().entrySet()) {
            target.add(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
                    .setKey(e.getKey().getKey())
                    .setValue(toAnyValue(e.getValue()))
                    .build());
        }
    }

    /**
     * 任意属性值 → OTLP {@code AnyValue}。
     *
     * <p>数组元素的类型是逐个判定的（OTel 允许 {@code string[]} 与 {@code long[]} 混在一个数组里），
     * 所以不能按第一个元素的类型一刀切。
     *
     * <p>遇到实在折不出的类型（理论上只有 {@code byte[]}）就当字符串写 —— 载荷是给人看的详情，
     * 少一个精确类型远好过让整条记录写不进去。
     */
    static AnyValue toAnyValue(final Object raw) {
        final AnyValue.Builder b = AnyValue.newBuilder();
        if (raw instanceof String) {
            return b.setStringValue((String) raw).build();
        }
        if (raw instanceof Long) {
            return b.setIntValue(((Long) raw).longValue()).build();
        }
        if (raw instanceof Double) {
            return b.setDoubleValue(((Double) raw).doubleValue()).build();
        }
        if (raw instanceof Boolean) {
            return b.setBoolValue(((Boolean) raw).booleanValue()).build();
        }
        if (raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
            return b.setIntValue(((Number) raw).longValue()).build();
        }
        if (raw instanceof Float) {
            return b.setDoubleValue(((Number) raw).doubleValue()).build();
        }
        if (raw instanceof List) {
            final List<?> list = (List<?>) raw;
            final ArrayValue.Builder arr = ArrayValue.newBuilder();
            for (int i = 0; i < list.size(); i++) {
                arr.addValues(toAnyValue(list.get(i)));
            }
            return b.setArrayValue(arr).build();
        }
        if (raw instanceof io.opentelemetry.api.common.Value) {
            final io.opentelemetry.api.common.Value<?> v = (io.opentelemetry.api.common.Value<?>) raw;
            return toAnyValue(v.getValue());
        }
        return b.setStringValue(String.valueOf(raw)).build();
    }

    /**
     * 十六进制 ID → protobuf 的 {@code bytes}。
     *
     * <p>trace_id / span_id 在 Java 侧是十六进制字符串、在 OTLP 里是字节序列。
     * 长度不对（不是 32 / 16 位十六进制）时返回 {@code null}，调用方就<b>不设</b>那个字段 ——
     * OTLP 里"不设"就是全 0，与"随便填点什么"语义不同。
     */
    static ByteString hexToBytes(final String hex, final int expectedBytes) {
        if (hex == null || hex.length() != expectedBytes * 2) {
            return null;
        }
        final byte[] out = new byte[expectedBytes];
        for (int i = 0; i < expectedBytes; i++) {
            final int hi = Character.digit(hex.charAt(i * 2), 16);
            final int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return ByteString.copyFrom(out);
    }
}
