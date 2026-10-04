package io.github.fulizhe.otelstore.agentext;

import com.google.protobuf.ByteString;
import io.github.fulizhe.otelstore.core.model.LogRecordEntry;
import io.github.fulizhe.otelstore.core.model.ResourceDescriptor;
import io.github.fulizhe.otelstore.core.util.ThrottledLogger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.Body;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link LogRecordData} → {@link LogRecordEntry}（表头 + 编码后的 OTLP bytes）。
 *
 * <p>与 {@link SpanMapper} 同构，差异只在三处：
 *
 * <ul>
 *   <li>正文是 {@link Body} 而不是字符串 —— 取 {@code asString()}，结构化正文（map）会被拍平成文本。
 *       表头列 {@code body_preview} 只留前 {@value #PREVIEW_CHARS} 字，完整正文在载荷里。</li>
 *   <li>{@code traceId} / {@code spanId} 真的可能为空 —— 日志可以完全脱离 trace（ADR-2 允许）。</li>
 *   <li>没有 events / links，也就没有对应的 {@code dropped_*} 字段。</li>
 * </ul>
 */
final class LogMapper {

    /**
     * 表头 {@code body_preview} 的长度上限。
     *
     * <p>不是"够看就行"的随口取值：列表页要显示的是"这条日志说了什么"，
     * 一行放不下几百字；而正文全文在环里，点开详情看得到。
     */
    private static final int PREVIEW_CHARS = 512;

    private LogMapper() {
    }

    static LogRecordEntry map(final LogRecordData data) {
        final SpanContext ctx = data.getSpanContext();
        final InstrumentationScopeInfo scope = data.getInstrumentationScopeInfo();
        final ResourceDescriptor resource = ResourceMapper.toDescriptor(data.getResource());
        final String body = bodyText(data);

        return new LogRecordEntry(
                ctx == null || !ctx.isValid() ? "" : ctx.getTraceId(),
                ctx == null || !ctx.isValid() ? "" : ctx.getSpanId(),
                severityNumber(data.getSeverity()),
                data.getSeverityText(),
                data.getTimestampEpochNanos(),
                data.getObservedTimestampEpochNanos(),
                preview(body),
                scope == null ? null : scope.getName(),
                scope == null ? null : scope.getVersion(),
                resource,
                data.getTotalAttributeCount(),
                encode(data, body));
    }

    private static String bodyText(final LogRecordData data) {
        final Body body = data.getBody();
        if (body == null) {
            return "";
        }
        final String s = body.asString();
        return s == null ? "" : s;
    }

    /** 截断出预览；截断处留一个显式标记，避免"看起来是完整的"这种最坏情况。 */
    private static String preview(final String body) {
        if (body == null) {
            return null;
        }
        if (body.length() <= PREVIEW_CHARS) {
            return body;
        }
        return body.substring(0, PREVIEW_CHARS) + "…(共 " + body.length() + " 字)";
    }

    /** 表头的 {@code severity_number} 列：与 payload 里同一个 OTLP 枚举数值（UNDEFINED=0 … FATAL4=24）。 */
    private static int severityNumber(final Severity severity) {
        return severity == null ? 0 : severity.getSeverityNumber();
    }

    private static byte[] encode(final LogRecordData data, final String body) {
        try {
            final LogRecord.Builder b = LogRecord.newBuilder()
                    .setTimeUnixNano(data.getTimestampEpochNanos())
                    .setObservedTimeUnixNano(data.getObservedTimestampEpochNanos())
                    .setSeverityNumber(severityOf(data.getSeverity()))
                    .setSeverityText(data.getSeverityText() == null ? "" : data.getSeverityText())
                    .setBody(AnyValue.newBuilder().setStringValue(body).build());

            final SpanContext ctx = data.getSpanContext();
            if (ctx != null && ctx.isValid()) {
                final ByteString traceId = OtelAttributes.hexToBytes(ctx.getTraceId(), 16);
                if (traceId != null) {
                    b.setTraceId(traceId);
                }
                final ByteString spanId = OtelAttributes.hexToBytes(ctx.getSpanId(), 8);
                if (spanId != null) {
                    b.setSpanId(spanId);
                }
            }

            final List<io.opentelemetry.proto.common.v1.KeyValue> attributes =
                    new ArrayList<io.opentelemetry.proto.common.v1.KeyValue>();
            OtelAttributes.addAllToProto(attributes, data.getAttributes());
            b.addAllAttributes(attributes);
            b.setDroppedAttributesCount(Math.max(0,
                    data.getTotalAttributeCount() - (data.getAttributes() == null ? 0 : data.getAttributes().size())));

            return b.build().toByteArray();
        } catch (final RuntimeException | Error e) {
            // 同 SpanMapper：编码失败只丢载荷，表头行必须保住
            ThrottledLogger.warn("log-encode-failed",
                    "日志载荷编码失败，表头行照存、载荷丢弃：" + e);
            return null;
        }
    }

    /**
     * 数值 → OTLP 枚举。
     *
     * <p>{@code forNumber} 对越界的值返回 {@code UNRECOGNIZED}，而 protobuf 里
     * 一个 UNRECOGNIZED 值写出去是"客户端行为未定义"，所以这里换成 UNSPECIFIED：
     * 载荷宁可说"没有 severity"，也不能写出一个读不回来的数。
     */
    private static SeverityNumber severityOf(final Severity severity) {
        final SeverityNumber n = SeverityNumber.forNumber(severityNumber(severity));
        return n == SeverityNumber.UNRECOGNIZED ? SeverityNumber.SEVERITY_NUMBER_UNSPECIFIED : n;
    }
}
