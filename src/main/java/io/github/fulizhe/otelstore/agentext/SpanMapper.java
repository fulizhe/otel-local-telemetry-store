package io.github.fulizhe.otelstore.agentext;

import com.google.protobuf.ByteString;
import io.github.fulizhe.otelstore.core.model.ResourceDescriptor;
import io.github.fulizhe.otelstore.core.model.SpanRecord;
import io.github.fulizhe.otelstore.core.util.ThrottledLogger;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SpanData} → {@link SpanRecord}（表头 + 编码后的 OTLP bytes）。
 *
 * <p>payload 是 <b>编码后的 OTLP {@code Span} protobuf</b>，不是 JSON、不是解码后的自定义结构
 * （ADR-2 的核心决策）：schema 由标准定、紧凑、只依赖公开的 {@code opentelemetry-proto} 类。
 * 不用 {@code opentelemetry-exporter-otlp} 那套内部 encoder —— 它是 internal API、会被 agent
 * 重定位、随小版本变，用了就把扩展钉死在某个编译期 SDK 版本上，与"只升级 agent 而不重编"冲突。
 *
 * <p><b>编码失败不能连表头一起丢</b>：payload 编码不出来时返回 {@code null}，
 * 存储层照样插表头行、{@code payload_id} 记 NULL（ADR-2 第 1 条坑）。
 * 反过来（表头丢了只留载荷）会让"这条记录存在但查不到"，那比缺载荷更难排查。
 */
final class SpanMapper {

    private SpanMapper() {
    }

    static SpanRecord map(final SpanData data) {
        final SpanContext spanContext = data.getSpanContext();
        final SpanContext parent = data.getParentSpanContext();
        final StatusData status = data.getStatus();
        final InstrumentationScopeInfo scope = data.getInstrumentationScopeInfo();
        final Attributes attributes = data.getAttributes();
        final ResourceDescriptor resource = ResourceMapper.toDescriptor(data.getResource());

        return new SpanRecord(
                spanContext == null ? "" : spanContext.getTraceId(),
                spanContext == null ? "" : spanContext.getSpanId(),
                parent == null || !parent.isValid() ? "" : parent.getSpanId(),
                data.getName(),
                kindNumber(data.getKind()),
                data.getStartEpochNanos(),
                data.getEndEpochNanos(),
                statusCodeNumber(status == null ? null : status.getStatusCode()),
                status == null ? null : status.getDescription(),
                scope == null ? null : scope.getName(),
                scope == null ? null : scope.getVersion(),
                resource,
                data.getTotalAttributeCount(),
                data.getTotalRecordedEvents(),
                encode(data));
    }

    /**
     * 编码成 OTLP {@code Span} 的 protobuf bytes。
     *
     * <p>这里<b>不</b>编码 {@code Resource} 与 {@code InstrumentationScope}：ADR-2 定的行单元是
     * 单个 span，而 {@code Resource} 走字典表、scope 反规范化成表头两列。载荷里带上它们看着"更完整"，
     * 实际上让每个 span 的载荷重复一遍 18 个资源属性 —— 那正是字典表要消掉的重复。
     * 详情页要显示 resource 时，从表头的 {@code resource_id} 回字典表取。
     *
     * @return 编码失败时为 {@code null}，调用方照样要把表头行落库
     */
    private static byte[] encode(final SpanData data) {
        try {
            final Span.Builder b = Span.newBuilder()
                    .setName(nullToEmpty(data.getName()))
                    .setKind(kindOf(data.getKind()))
                    .setStartTimeUnixNano(data.getStartEpochNanos())
                    .setEndTimeUnixNano(data.getEndEpochNanos());

            final SpanContext ctx = data.getSpanContext();
            setIfPresent(b, OtelAttributes.hexToBytes(ctx == null ? null : ctx.getTraceId(), 16),
                    Span.Builder::setTraceId);
            setIfPresent(b, OtelAttributes.hexToBytes(ctx == null ? null : ctx.getSpanId(), 8),
                    Span.Builder::setSpanId);
            if (ctx != null && ctx.getTraceState() != null) {
                b.setTraceState(ctx.getTraceState().toString());
            }
            final SpanContext parent = data.getParentSpanContext();
            setIfPresent(b, OtelAttributes.hexToBytes(parent == null ? null : parent.getSpanId(), 8),
                    Span.Builder::setParentSpanId);

            final List<io.opentelemetry.proto.common.v1.KeyValue> attributes =
                    new ArrayList<io.opentelemetry.proto.common.v1.KeyValue>();
            OtelAttributes.addAllToProto(attributes, data.getAttributes());
            b.addAllAttributes(attributes);
            b.setDroppedAttributesCount(droppedAttributes(data));

            if (data.getEvents() != null) {
                for (int i = 0; i < data.getEvents().size(); i++) {
                    final EventData e = data.getEvents().get(i);
                    final Span.Event.Builder eb = Span.Event.newBuilder()
                            .setTimeUnixNano(e.getEpochNanos())
                            .setName(nullToEmpty(e.getName()));
                    final List<io.opentelemetry.proto.common.v1.KeyValue> ea =
                            new ArrayList<io.opentelemetry.proto.common.v1.KeyValue>();
                    OtelAttributes.addAllToProto(ea, e.getAttributes());
                    eb.addAllAttributes(ea);
                    b.addEvents(eb);
                }
            }
            b.setDroppedEventsCount(dropped(data.getTotalRecordedEvents(), data.getEvents()));

            if (data.getLinks() != null) {
                for (int i = 0; i < data.getLinks().size(); i++) {
                    final LinkData link = data.getLinks().get(i);
                    final SpanContext lc = link.getSpanContext();
                    final Span.Link.Builder lb = Span.Link.newBuilder();
                    setIfPresent(lb, OtelAttributes.hexToBytes(lc == null ? null : lc.getTraceId(), 16),
                            Span.Link.Builder::setTraceId);
                    setIfPresent(lb, OtelAttributes.hexToBytes(lc == null ? null : lc.getSpanId(), 8),
                            Span.Link.Builder::setSpanId);
                    if (lc != null && lc.getTraceState() != null) {
                        lb.setTraceState(lc.getTraceState().toString());
                    }
                    final List<io.opentelemetry.proto.common.v1.KeyValue> la =
                            new ArrayList<io.opentelemetry.proto.common.v1.KeyValue>();
                    OtelAttributes.addAllToProto(la, link.getAttributes());
                    lb.addAllAttributes(la);
                    b.addLinks(lb);
                }
            }
            b.setDroppedLinksCount(dropped(data.getTotalRecordedLinks(), data.getLinks()));

            b.setStatus(Status.newBuilder()
                    .setCode(codeOf(data.getStatus() == null ? null : data.getStatus().getStatusCode()))
                    .setMessage(nullToEmpty(data.getStatus() == null ? null : data.getStatus().getDescription()))
                    .build());

            return b.build().toByteArray();
        } catch (final RuntimeException | Error e) {
            // 表头行必须保住：payload 编码失败只降级成"这条没有载荷"
            ThrottledLogger.warn("span-encode-failed",
                    "span 载荷编码失败，表头行照存、载荷丢弃", e);
            return null;
        }
    }

    private interface ByteSetter<T extends com.google.protobuf.Message.Builder> {
        void set(T builder, ByteString value);
    }

    private static <T extends com.google.protobuf.Message.Builder> void setIfPresent(
            final T builder, final ByteString value, final ByteSetter<T> setter) {
        // OTLP 里"不设"就是全 0，与"填了别的东西"语义不同，所以长度不对就整个不设
        if (value != null && !value.isEmpty()) {
            setter.set(builder, value);
        }
    }

    /** 表头的 {@code kind} 列：与 payload 里同一个 OTLP 枚举的数值（INTERNAL=1 … CONSUMER=5）。 */
    private static int kindNumber(final SpanKind kind) {
        if (kind == null) {
            return 0;
        }
        switch (kind) {
            case INTERNAL:
                return 1;
            case SERVER:
                return 2;
            case CLIENT:
                return 3;
            case PRODUCER:
                return 4;
            case CONSUMER:
                return 5;
            default:
                return 0;
        }
    }

    private static Span.SpanKind kindOf(final SpanKind kind) {
        if (kind == null) {
            return Span.SpanKind.SPAN_KIND_UNSPECIFIED;
        }
        switch (kind) {
            case INTERNAL:
                return Span.SpanKind.SPAN_KIND_INTERNAL;
            case SERVER:
                return Span.SpanKind.SPAN_KIND_SERVER;
            case CLIENT:
                return Span.SpanKind.SPAN_KIND_CLIENT;
            case PRODUCER:
                return Span.SpanKind.SPAN_KIND_PRODUCER;
            case CONSUMER:
                return Span.SpanKind.SPAN_KIND_CONSUMER;
            default:
                return Span.SpanKind.SPAN_KIND_UNSPECIFIED;
        }
    }

    /** 表头的 {@code status_code} 列：UNSET=0 / OK=1 / ERROR=2。 */
    private static int statusCodeNumber(final StatusCode code) {
        if (code == StatusCode.OK) {
            return 1;
        }
        if (code == StatusCode.ERROR) {
            return 2;
        }
        return 0;
    }

    private static Status.StatusCode codeOf(final StatusCode code) {
        if (code == StatusCode.OK) {
            return Status.StatusCode.STATUS_CODE_OK;
        }
        if (code == StatusCode.ERROR) {
            return Status.StatusCode.STATUS_CODE_ERROR;
        }
        return Status.StatusCode.STATUS_CODE_UNSET;
    }

    /**
     * "记了多少"减去"到了多少"= 被 SDK 截断的条数，写进 OTLP 的 {@code dropped_*_count}。
     *
     * <p>这是 OTLP 自带的字段，不是我们发明的口径。
     * <b>但别把它当 ADR-3 第 4 种形态的计数器</b> —— 那一条已定为不可计数（ADR-3「关键约束：第 4 种已验为完全静默」），
     * 这里只是把 SDK 已经放在 {@code SpanData} 上的字段如实搬进载荷。
     */
    private static int droppedAttributes(final SpanData data) {
        final Attributes arrived = data.getAttributes();
        return dropped(data.getTotalAttributeCount(), arrived == null ? 0 : arrived.size());
    }

    private static int dropped(final int total, final List<?> arrived) {
        return dropped(total, arrived == null ? 0 : arrived.size());
    }

    private static int dropped(final int total, final int arrived) {
        final int d = total - arrived;
        return d > 0 ? d : 0;
    }

    private static String nullToEmpty(final String s) {
        return s == null ? "" : s;
    }
}
