package io.github.fulizhe.otelstore.readout.payload;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把环里那段编码后的 OTLP protobuf **解成 JDK 原生类型**，好让读口能直接 JSON 化。
 *
 * <p><b>只依赖 {@code opentelemetry-proto} 的生成类，不 import 任何 OTel SDK / API 包。</b>
 * 源码里写的是 {@code io.opentelemetry.proto.*}，shade 时它们与 protobuf runtime 一起被
 * relocation 到 {@code …otelstore.shaded.…}（ADR-5）—— 所以运行期用的是**我们自己那份**，
 * 不会被 agent 自带的那个版本牵制（那正是 Phase 4b 踩过的坑）。
 *
 * <p>这么分层是为了让 ADR-1 的硬规则保持原样：{@code core} 不许碰 OTel，而解码本来就该
 * 与 OTel 数据模型待在一起。它也是"将来换一套编码"时唯一的改动点。
 *
 * <p><b>解码失败不抛</b>：返回 {@code null}，由调用方转成 {@code reason=corrupt}。
 * 读口在另一个 ClassLoader 边界上，抛过去的异常对方只会看到一句看不懂的话。
 */
public final class PayloadDecoder {

    private PayloadDecoder() {
    }

    /**
     * 解一条 span 的载荷。
     *
     * @return attributes / events / links / status 的原生结构；字节不是合法 Span 时返回 {@code null}
     */
    public static Map<String, Object> decodeSpan(final byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            final Span span = Span.parseFrom(bytes);
            final Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("traceId", span.getTraceId().toStringUtf8());
            m.put("spanId", span.getSpanId().toStringUtf8());
            m.put("parentSpanId", span.getParentSpanId().toStringUtf8());
            m.put("name", span.getName());
            m.put("kind", Integer.valueOf(span.getKindValue()));
            m.put("startTimeUnixNano", Long.valueOf(span.getStartTimeUnixNano()));
            m.put("endTimeUnixNano", Long.valueOf(span.getEndTimeUnixNano()));
            m.put("attributes", attributes(span.getAttributesList()));
            m.put("droppedAttributesCount", Integer.valueOf(span.getDroppedAttributesCount()));
            m.put("events", events(span.getEventsList()));
            m.put("droppedEventsCount", Integer.valueOf(span.getDroppedEventsCount()));
            m.put("links", links(span.getLinksList()));
            m.put("droppedLinksCount", Integer.valueOf(span.getDroppedLinksCount()));
            m.put("status", status(span));
            return m;
        } catch (final Exception e) {
            // com.google.protobuf 的解析异常是 InvalidProtocolBufferException 之类，
            // 这里连同 RuntimeException 一起兜住：读口不该因为一条坏记录而 500
            return null;
        }
    }

    /**
     * 解一条日志记录的载荷。
     *
     * @return 正文 / severity / 属性 / trace 关联；字节不合法时返回 {@code null}
     */
    public static Map<String, Object> decodeLog(final byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            final LogRecord record = LogRecord.parseFrom(bytes);
            final Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("timeUnixNano", Long.valueOf(record.getTimeUnixNano()));
            m.put("observedTimeUnixNano", Long.valueOf(record.getObservedTimeUnixNano()));
            m.put("severityNumber", record.getSeverityNumber().name());
            m.put("severityText", record.getSeverityText());
            m.put("body", anyValue(record.getBody()));
            m.put("attributes", attributes(record.getAttributesList()));
            m.put("droppedAttributesCount", Integer.valueOf(record.getDroppedAttributesCount()));
            m.put("traceId", record.getTraceId().toStringUtf8());
            m.put("spanId", record.getSpanId().toStringUtf8());
            return m;
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * 属性列表 → {@code [{key, type, value}]}。
     *
     * <p><b>带 {@code type}</b> 而不是把值都转成字符串：整数 {@code 3} 与浮点 {@code 3.0}
     * 转成字符串后分不清，而分不清就等于把信息丢了。
     */
    static List<Object> attributes(final List<KeyValue> list) {
        final List<Object> out = new ArrayList<Object>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final KeyValue kv = list.get(i);
            final Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("key", kv.getKey());
            final AnyValue v = kv.getValue();
            one.put("type", v.getValueCase().name());
            one.put("value", anyValue(v));
            out.add(one);
        }
        return out;
    }

    /**
     * {@code AnyValue} → 原生类型。
     *
     * <p>数组逐个元素判定类型；{@code KVLIST} 与 {@code BYTES} 转成字符串
     * （OTLP 的 {@code AnyValue} 本身也不允许 bytes 直接进 JSON）。
     */
    static Object anyValue(final AnyValue v) {
        switch (v.getValueCase()) {
            case STRING_VALUE:
                return v.getStringValue();
            case BOOL_VALUE:
                return Boolean.valueOf(v.getBoolValue());
            case INT_VALUE:
                return Long.valueOf(v.getIntValue());
            case DOUBLE_VALUE:
                return Double.valueOf(v.getDoubleValue());
            case ARRAY_VALUE:
                final List<AnyValue> items = v.getArrayValue().getValuesList();
                final List<Object> out = new ArrayList<Object>(items.size());
                for (int i = 0; i < items.size(); i++) {
                    out.add(anyValue(items.get(i)));
                }
                return out;
            default:
                return String.valueOf(v);
        }
    }

    private static List<Object> events(final List<Span.Event> list) {
        final List<Object> out = new ArrayList<Object>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final Span.Event e = list.get(i);
            final Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("timeUnixNano", Long.valueOf(e.getTimeUnixNano()));
            one.put("name", e.getName());
            one.put("attributes", attributes(e.getAttributesList()));
            out.add(one);
        }
        return out;
    }

    private static List<Object> links(final List<Span.Link> list) {
        final List<Object> out = new ArrayList<Object>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final Span.Link l = list.get(i);
            final Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("traceId", l.getTraceId().toStringUtf8());
            one.put("spanId", l.getSpanId().toStringUtf8());
            one.put("attributes", attributes(l.getAttributesList()));
            out.add(one);
        }
        return out;
    }

    /** 状态用<b>名字</b>而不是数字：页面上要给人看，`STATUS_CODE_ERROR` 比 `2` 有用。 */
    private static Map<String, Object> status(final Span span) {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", span.getStatus().getCode().name());
        m.put("message", span.getStatus().getMessage());
        return m;
    }
}
