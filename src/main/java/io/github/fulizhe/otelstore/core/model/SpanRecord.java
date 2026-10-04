package io.github.fulizhe.otelstore.core.model;

/**
 * 一条 span 的表头 + 载荷。
 *
 * <p>字段与 {@code docs/adr/adr-02-data-model.md} 的 {@code span} 表一一对应，
 * 唯独 {@code payload} 是 {@code byte[]} 而不是 {@code payload_id} ——
 * {@code payload_id} 由存储层写环形文件之后才知道（ADR-2 第 2 条坑：
 * payload 一律走环形文件，H2 不内联）。
 *
 * <p>因此本类型是"采集侧交给存储层的入参"，不是"库里的一行"。
 * {@code agentext} 造它，{@code core} 消费它，两边都不 import 对方的类型。
 */
public final class SpanRecord {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String name;
    /** OTLP {@code Span.SpanKind} 的数值（INTERNAL=1 … CONSUMER=5），与 payload 里那个枚举同源。 */
    private final int kind;
    private final long startEpochNanos;
    private final long endEpochNanos;
    /** OTLP {@code Status.StatusCode} 的数值：UNSET=0 / OK=1 / ERROR=2。 */
    private final int statusCode;
    private final String statusMessage;
    private final String scopeName;
    private final String scopeVersion;
    private final ResourceDescriptor resource;
    /** 属性条数（含被 SDK 截断而丢掉的），取 {@code SpanData.getTotalAttributeCount()}。 */
    private final int attributeCount;
    /** 事件条数（含被截断而丢掉的），取 {@code getTotalRecordedEvents()}。 */
    private final int eventCount;
    /** 编码后的 OTLP {@code Span} protobuf bytes；允许为 null（编码失败时仍然要保住表头行）。 */
    private final byte[] payload;

    public SpanRecord(final String traceId, final String spanId, final String parentSpanId, final String name,
                      final int kind, final long startEpochNanos, final long endEpochNanos,
                      final int statusCode, final String statusMessage,
                      final String scopeName, final String scopeVersion,
                      final ResourceDescriptor resource, final int attributeCount, final int eventCount,
                      final byte[] payload) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.name = name;
        this.kind = kind;
        this.startEpochNanos = startEpochNanos;
        this.endEpochNanos = endEpochNanos;
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
        this.scopeName = scopeName;
        this.scopeVersion = scopeVersion;
        this.resource = resource == null ? ResourceDescriptor.empty() : resource;
        this.attributeCount = attributeCount;
        this.eventCount = eventCount;
        this.payload = payload;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    /** 根 span 为空串而不是 null —— OTLP 里 parent_span_id 长度为 0 即表示根。 */
    public String getParentSpanId() {
        return parentSpanId;
    }

    public String getName() {
        return name;
    }

    public int getKind() {
        return kind;
    }

    public long getStartEpochNanos() {
        return startEpochNanos;
    }

    public long getEndEpochNanos() {
        return endEpochNanos;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public String getScopeName() {
        return scopeName;
    }

    public String getScopeVersion() {
        return scopeVersion;
    }

    public ResourceDescriptor getResource() {
        return resource;
    }

    public int getAttributeCount() {
        return attributeCount;
    }

    public int getEventCount() {
        return eventCount;
    }

    public byte[] getPayload() {
        return payload;
    }
}
