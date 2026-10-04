package io.github.fulizhe.otelstore.core.model;

/**
 * 一条日志记录的表头 + 载荷。
 *
 * <p>字段对应 ADR-2 的 {@code log_record} 表。三个"可空"的口径要分清，
 * 它们在 OTLP 里都是"长度为 0 / 不设置"，但在库里是三列：
 *
 * <ul>
 *   <li>{@code traceId} / {@code spanId} —— 日志可以完全脱离 trace（ADR-2 允许可空）</li>
 *   <li>{@code severityText} —— 可空；{@code severityNumber} 不可空，UNDEFINED 记 0</li>
 * </ul>
 */
public final class LogRecordEntry {

    private final String traceId;
    private final String spanId;
    /** OTLP {@code SeverityNumber} 数值：UNDEFINED=0，TRACE=1 … FATAL4=24。 */
    private final int severityNumber;
    private final String severityText;
    private final long timestampEpochNanos;
    private final long observedTimestampEpochNanos;
    /** 正文预览（截断后存），用于列表页展示；完整正文在 payload 里。 */
    private final String bodyPreview;
    private final String scopeName;
    private final String scopeVersion;
    private final ResourceDescriptor resource;
    private final int attributeCount;
    /** 编码后的 OTLP {@code LogRecord} protobuf bytes；允许为 null。 */
    private final byte[] payload;

    public LogRecordEntry(final String traceId, final String spanId, final int severityNumber,
                          final String severityText, final long timestampEpochNanos,
                          final long observedTimestampEpochNanos, final String bodyPreview,
                          final String scopeName, final String scopeVersion,
                          final ResourceDescriptor resource, final int attributeCount, final byte[] payload) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.severityNumber = severityNumber;
        this.severityText = severityText;
        this.timestampEpochNanos = timestampEpochNanos;
        this.observedTimestampEpochNanos = observedTimestampEpochNanos;
        this.bodyPreview = bodyPreview;
        this.scopeName = scopeName;
        this.scopeVersion = scopeVersion;
        this.resource = resource == null ? ResourceDescriptor.empty() : resource;
        this.attributeCount = attributeCount;
        this.payload = payload;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public int getSeverityNumber() {
        return severityNumber;
    }

    public String getSeverityText() {
        return severityText;
    }

    public long getTimestampEpochNanos() {
        return timestampEpochNanos;
    }

    public long getObservedTimestampEpochNanos() {
        return observedTimestampEpochNanos;
    }

    public String getBodyPreview() {
        return bodyPreview;
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

    public byte[] getPayload() {
        return payload;
    }
}
