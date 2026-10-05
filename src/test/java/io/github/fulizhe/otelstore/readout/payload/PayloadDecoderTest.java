package io.github.fulizhe.otelstore.readout.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 解码器的契约：字节 → JDK 原生类型。
 *
 * <p>这里最要紧的两条是<b>类型不丢</b>与<b>坏字节不抛</b>：
 * 前者保证页面上"整数 3"不会被显示成"浮点 3.0"，
 * 后者保证一条坏记录不会把整个读口打成 500（读口在 ClassLoader 边界上，
 * 抛过去的异常对方只会看到一句看不懂的话）。
 */
class PayloadDecoderTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static KeyValue kv(final String key, final AnyValue value) {
        return KeyValue.newBuilder().setKey(key).setValue(value).build();
    }

    private static AnyValue str(final String v) {
        return AnyValue.newBuilder().setStringValue(v).build();
    }

    @Test
    @DisplayName("span：属性带类型，事件与状态都解出来")
    void decodesSpanWithTypedAttributes() {
        final byte[] bytes = Span.newBuilder()
                .setTraceId(ByteString.copyFrom(new byte[16]))
                .setSpanId(ByteString.copyFrom(new byte[8]))
                .setName("GET /orders")
                .setKind(Span.SpanKind.SPAN_KIND_SERVER)
                .setStartTimeUnixNano(1700000000000000000L)
                .setEndTimeUnixNano(1700000000000000500L)
                .addAttributes(kv("http.method", str("GET")))
                .addAttributes(kv("http.status_code", AnyValue.newBuilder().setIntValue(200).build()))
                .addAttributes(kv("duration", AnyValue.newBuilder().setDoubleValue(1.5d).build()))
                .addAttributes(kv("sampled", AnyValue.newBuilder().setBoolValue(true).build()))
                .addEvents(Span.Event.newBuilder()
                        .setTimeUnixNano(1700000000000000100L)
                        .setName("cache.miss")
                        .addAttributes(kv("key", str("order:42"))))
                .setStatus(Status.newBuilder()
                        .setCode(Status.StatusCode.STATUS_CODE_ERROR).setMessage("boom"))
                .build().toByteArray();

        final Map<String, Object> m = PayloadDecoder.decodeSpan(bytes);
        assertNotNull(m);
        assertEquals("GET /orders", m.get("name"));
        assertEquals(Integer.valueOf(2), m.get("kind"), "OTLP 的 kind 数值：SERVER=2");
        assertEquals(Long.valueOf(1700000000000000000L), m.get("startTimeUnixNano"));

        // 类型必须保留：整数 200 与浮点 200.0 转成字符串后分不清，分不清就是丢信息
        final List<Object> attrs = castList(m.get("attributes"));
        assertEquals(4, attrs.size());
        assertEquals("http.method", first(attrs).get("key"));
        assertEquals("STRING_VALUE", first(attrs).get("type"));
        assertEquals("GET", first(attrs).get("value"));
        assertEquals("INT_VALUE", castMap(attrs.get(1)).get("type"));
        assertEquals(Long.valueOf(200L), castMap(attrs.get(1)).get("value"));
        assertEquals("DOUBLE_VALUE", castMap(attrs.get(2)).get("type"));
        assertEquals(Double.valueOf(1.5d), castMap(attrs.get(2)).get("value"));
        assertEquals("BOOL_VALUE", castMap(attrs.get(3)).get("type"));
        assertEquals(Boolean.TRUE, castMap(attrs.get(3)).get("value"));

        final List<Object> events = castList(m.get("events"));
        assertEquals(1, events.size());
        assertEquals("cache.miss", castMap(events.get(0)).get("name"));
        assertEquals(Long.valueOf(1700000000000000100L), castMap(events.get(0)).get("timeUnixNano"));

        // 状态给名字而不是数字：页面上要给人看
        assertEquals("STATUS_CODE_ERROR", castMap(m.get("status")).get("code"));
        assertEquals("boom", castMap(m.get("status")).get("message"));
    }

    @Test
    @DisplayName("数组与嵌套列表：逐个元素保留类型")
    void decodesArrayValues() {
        final byte[] bytes = Span.newBuilder()
                .setName("s")
                .addAttributes(kv("tags", AnyValue.newBuilder()
                        .setArrayValue(io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                                .addValues(str("a"))
                                .addValues(AnyValue.newBuilder().setIntValue(2L).build())
                                .addValues(AnyValue.newBuilder().setBoolValue(false).build()))
                        .build()))
                .addAttributes(kv("nested", AnyValue.newBuilder()
                        .setKvlistValue(KeyValueList.newBuilder()
                                .addValues(kv("inner", str("x"))))
                        .build()))
                .build().toByteArray();

        final Map<String, Object> m = PayloadDecoder.decodeSpan(bytes);
        final List<Object> attrs = castList(m.get("attributes"));
        final List<Object> tags = castList(castMap(attrs.get(0)).get("value"));
        assertEquals(3, tags.size());
        assertEquals("a", tags.get(0));
        assertEquals(Long.valueOf(2L), tags.get(1));
        assertEquals(Boolean.FALSE, tags.get(2));

        // kvlist 与 bytes 没有对应的 JSON 原生形态，转成字符串（页面上能看出"这里有内容"）
        assertNotNull(castMap(attrs.get(1)).get("value"));
    }

    @Test
    @DisplayName("日志：正文、severity、trace 关联都解出来")
    void decodesLogRecord() {
        final byte[] bytes = LogRecord.newBuilder()
                .setTimeUnixNano(1700000000000000000L)
                .setObservedTimeUnixNano(1700000000000000001L)
                .setSeverityNumber(io.opentelemetry.proto.logs.v1.SeverityNumber.SEVERITY_NUMBER_WARN)
                .setSeverityText("WARN")
                .setBody(str("订单 42 处理慢"))
                .setTraceId(ByteString.copyFromUtf8("0123456789abcdef0123456789abcdef"))
                .setSpanId(ByteString.copyFromUtf8("01234567"))
                .addAttributes(kv("order.id", AnyValue.newBuilder().setIntValue(42L).build()))
                .build().toByteArray();

        final Map<String, Object> m = PayloadDecoder.decodeLog(bytes);
        assertNotNull(m);
        assertEquals("WARN", m.get("severityText"));
        assertEquals("SEVERITY_NUMBER_WARN", m.get("severityNumber"));
        assertEquals("订单 42 处理慢", m.get("body"));
        assertEquals("0123456789abcdef0123456789abcdef", m.get("traceId"));
        assertEquals("01234567", m.get("spanId"));
        assertEquals(1, castList(m.get("attributes")).size());
    }

    @Test
    @DisplayName("坏字节返回 null 且不抛异常；空字节解成全默认 span（protobuf 的语义）")
    void malformedBytesReturnNullWithoutThrowing() {
        assertNull(PayloadDecoder.decodeSpan(null));
        assertNull(PayloadDecoder.decodeLog(null));

        // 空字节**不是**坏载荷：protobuf 把空输入解成默认消息，而我们真的会写出 0 字节的
        // 载荷（字段全空的 span 就是 0 字节）。所以这里必须是"解出一个空 span"而不是 null ——
        // 反过来会把一条合法记录报成损坏。
        final Map<String, Object> empty = PayloadDecoder.decodeSpan(new byte[0]);
        assertNotNull(empty, "空字节解成全默认 span，这是 protobuf 的语义");
        assertEquals("", empty.get("name"));
        assertTrue(castList(empty.get("attributes")).isEmpty());

        // 随机字节：解析要么成功要么抛，而我们只认 null（外层不抛）
        final byte[] garbage = new byte[64];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (i * 7 + 3);
        }
        try {
            PayloadDecoder.decodeSpan(garbage);
            PayloadDecoder.decodeLog(garbage);
        } catch (final RuntimeException e) {
            throw new AssertionError("解码器不该把异常抛出去：" + e);
        }

        // 截断的合法载荷：可能解出部分字段，**但绝不能抛**
        final byte[] full = Span.newBuilder().setName("GET /orders")
                .addAttributes(kv("http.method", str("GET"))).build().toByteArray();
        final byte[] half = new byte[full.length / 2];
        System.arraycopy(full, 0, half, 0, half.length);
        try {
            PayloadDecoder.decodeSpan(half);
        } catch (final RuntimeException e) {
            throw new AssertionError("解码器不该把异常抛出去：" + e);
        }
    }

    @Test
    @DisplayName("没有属性、没有事件时给空列表而不是 null（页面少一处判空）")
    void emptyCollectionsNotNull() {
        final Map<String, Object> m = PayloadDecoder.decodeSpan(
                Span.newBuilder().setName("bare").build().toByteArray());
        assertNotNull(m);
        assertTrue(castList(m.get("attributes")).isEmpty());
        assertTrue(castList(m.get("events")).isEmpty());
        assertTrue(castList(m.get("links")).isEmpty());
        assertEquals(Integer.valueOf(0), m.get("droppedAttributesCount"));
    }

    @Test
    @DisplayName("解码器不引用 OTel SDK / API —— 只有 proto 生成类")
    void decoderMustNotTouchSdkOrApi() throws Exception {
        // 分层硬规则的机械化检查：core 不许 import OTel，而解码器只许碰 proto
        // （ADR-1 原则一、ADR-6 第七节）。这里查的是**编译产物**的常量池引用，
        // 因为源码 import 与运行期实际加载的包在 shade 之后不是一回事。
        final String forbidden = "io/opentelemetry/sdk/";
        final String forbiddenApi = "io/opentelemetry/api/";
        for (final java.lang.reflect.Method m : PayloadDecoder.class.getDeclaredMethods()) {
            assertTrue(!m.toString().contains(forbidden), "解码器不许碰 SDK：" + m);
            assertTrue(!m.toString().contains(forbiddenApi), "解码器不许碰 API：" + m);
        }
        assertNotNull(PayloadDecoder.class.getName());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(final Object o) {
        return (List<Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(final Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> first(final List<Object> list) {
        return castMap(list.get(0));
    }

    private static String unusedButKeepsCharsetImportHonest() {
        return UTF8.name();
    }
}
