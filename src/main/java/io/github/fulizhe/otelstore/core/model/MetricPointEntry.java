package io.github.fulizhe.otelstore.core.model;

import java.util.Collections;
import java.util.List;

/**
 * 一个指标数据点（采集周期 × 指标 × 属性组合 = 一行）。
 *
 * <p><b>metrics 不走环形文件</b>（ADR-2）：指标是周期性聚合快照不是事件，
 * 进环形文件会把"按指标名查时间序列"变成顺序扫描。所以本类型<b>没有</b> {@code payload} 字段，
 * 桶边界与分位数直接落进 {@code detail} 列。
 *
 * <p>{@link MetricKind} 是 ADR-2 定的四类形态。SDK 那边更细（long/double 各自成类、
 * 指数直方图单列），差异不另立口径：一律归到这四类里，把区分信息放进 {@code detail}。
 */
public final class MetricPointEntry {

    /** 形态。数值与 OTLP {@code Metric.DataCase} 不同源，是本项目自己的查询口径。 */
    public enum MetricKind {
        GAUGE, SUM, HISTOGRAM, SUMMARY
    }

    private final String metricName;
    private final String description;
    private final String unit;
    private final MetricKind kind;
    /** 该数据点的 epoch nanos（OTLP 原生单位，不做换算 —— ADR-2 第 3 条坑）。 */
    private final long timestampEpochNanos;
    private final ResourceDescriptor resource;
    private final String scopeName;
    private final String scopeVersion;
    /** 属性组合；{@code attrKey} 由存储层按规范化哈希算出，因此这里保留原文供回查。 */
    private final List<KeyValue> attributes;
    /** GAUGE / SUM 的值；其余形态为 {@link Double#NaN}。 */
    private final double value;
    /** HISTOGRAM / SUMMARY 的样本数；其余形态为 0。 */
    private final long count;
    /** HISTOGRAM / SUMMARY 的求和；其余形态为 {@link Double#NaN}。 */
    private final double sum;
    /** 桶 / 分位数的紧凑文本；标量形态为 null。 */
    private final String detail;

    public MetricPointEntry(final String metricName, final String description, final String unit,
                            final MetricKind kind, final long timestampEpochNanos,
                            final ResourceDescriptor resource, final String scopeName, final String scopeVersion,
                            final List<KeyValue> attributes, final double value, final long count,
                            final double sum, final String detail) {
        this.metricName = metricName;
        this.description = description;
        this.unit = unit;
        this.kind = kind;
        this.timestampEpochNanos = timestampEpochNanos;
        this.resource = resource == null ? ResourceDescriptor.empty() : resource;
        this.scopeName = scopeName;
        this.scopeVersion = scopeVersion;
        this.attributes = attributes == null
                ? Collections.<KeyValue>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<KeyValue>(attributes));
        this.value = value;
        this.count = count;
        this.sum = sum;
        this.detail = detail;
    }

    public String getMetricName() {
        return metricName;
    }

    public String getDescription() {
        return description;
    }

    public String getUnit() {
        return unit;
    }

    public MetricKind getKind() {
        return kind;
    }

    public long getTimestampEpochNanos() {
        return timestampEpochNanos;
    }

    public ResourceDescriptor getResource() {
        return resource;
    }

    public String getScopeName() {
        return scopeName;
    }

    public String getScopeVersion() {
        return scopeVersion;
    }

    public List<KeyValue> getAttributes() {
        return attributes;
    }

    public double getValue() {
        return value;
    }

    public long getCount() {
        return count;
    }

    public double getSum() {
        return sum;
    }

    public String getDetail() {
        return detail;
    }
}
