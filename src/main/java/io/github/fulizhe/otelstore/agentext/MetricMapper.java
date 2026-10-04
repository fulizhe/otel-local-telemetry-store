package io.github.fulizhe.otelstore.agentext;

import io.github.fulizhe.otelstore.core.model.MetricPointEntry;
import io.github.fulizhe.otelstore.core.model.MetricPointEntry.MetricKind;
import io.github.fulizhe.otelstore.core.model.ResourceDescriptor;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.metrics.data.SummaryPointData;
import io.opentelemetry.sdk.metrics.data.ValueAtQuantile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * {@link MetricData} → 一组 {@link MetricPointEntry}。
 *
 * <p><b>返回的是列表而不是单条</b>：行单元是"采集周期 × 指标 × 属性组合"（ADR-2），
 * 一个 {@code MetricData} 里带的是该指标所有属性组合的点（实测带属性的指标一次能出几十个点）。
 * 在这里拆开而不是在存储层拆，是因为"一行到底对应什么"是数据模型的事，不是存储的事。
 *
 * <p><b>没有载荷</b>：metrics 不走环形文件（ADR-2），桶与分位数直接落进 {@code detail} 列。
 *
 * <p><b>{@code detail} 的格式：第一个 token 永远是形态标记</b>，
 * 之后才是该形态自己的内容。这样"这一列是 null、还是 long、还是 explicit"永远不用猜：
 *
 * <pre>
 *   long | double                                  标量
 *   explicit;min=..;max=..;bounds=[..];counts=[..]  显式边界直方图
 *   exponential;scale=..;zeroCount=..;pos=off,[..];neg=off,[..]
 *   quantiles;0.5=1.0;0.9=2.0                      摘要分位数
 * </pre>
 */
final class MetricMapper {

    private MetricMapper() {
    }

    /**
     * @return 至少是空列表，永不为 null —— 上层 sink 直接遍历即可
     */
    static List<MetricPointEntry> map(final MetricData metric) {
        if (metric == null || metric.getData() == null) {
            return Collections.emptyList();
        }
        final Collection<? extends PointData> points = metric.getData().getPoints();
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }
        final MetricKind kind = kindOf(metric.getType());
        final InstrumentationScopeInfo scope = metric.getInstrumentationScopeInfo();
        final ResourceDescriptor resource = ResourceMapper.toDescriptor(metric.getResource());
        final String name = metric.getName() == null ? "" : metric.getName();

        final List<MetricPointEntry> out = new ArrayList<MetricPointEntry>(points.size());
        for (final PointData point : points) {
            if (point == null) {
                continue;
            }
            out.add(new MetricPointEntry(
                    name,
                    metric.getDescription(),
                    metric.getUnit(),
                    kind,
                    point.getEpochNanos(),
                    resource,
                    scope == null ? null : scope.getName(),
                    scope == null ? null : scope.getVersion(),
                    OtelAttributes.toCore(point.getAttributes()),
                    scalarValue(point),
                    sampleCount(point),
                    sampleSum(point),
                    detail(point)));
        }
        return out;
    }

    /**
     * 四类形态的归并（ADR-2 定的口径）。
     *
     * <p>SDK 分得更细：long/double 各成一路、指数直方图单列。这里一律压到四类，
     * 差异不另立口径 —— 多一列口径就多一处能与实际对不上的地方，
     * 而那点差异（int 还是 double）在 {@code detail} 的形态标记里已经留了痕迹。
     */
    private static MetricKind kindOf(final MetricDataType type) {
        if (type == null) {
            return MetricKind.GAUGE;
        }
        switch (type) {
            case LONG_SUM:
            case DOUBLE_SUM:
                return MetricKind.SUM;
            case HISTOGRAM:
            case EXPONENTIAL_HISTOGRAM:
                return MetricKind.HISTOGRAM;
            case SUMMARY:
                return MetricKind.SUMMARY;
            case LONG_GAUGE:
            case DOUBLE_GAUGE:
            default:
                return MetricKind.GAUGE;
        }
    }

    private static double scalarValue(final PointData point) {
        if (point instanceof LongPointData) {
            return ((LongPointData) point).getValue();
        }
        if (point instanceof DoublePointData) {
            return ((DoublePointData) point).getValue();
        }
        return Double.NaN;
    }

    private static long sampleCount(final PointData point) {
        if (point instanceof HistogramPointData) {
            return ((HistogramPointData) point).getCount();
        }
        if (point instanceof ExponentialHistogramPointData) {
            return ((ExponentialHistogramPointData) point).getCount();
        }
        if (point instanceof SummaryPointData) {
            return ((SummaryPointData) point).getCount();
        }
        return 0L;
    }

    private static double sampleSum(final PointData point) {
        if (point instanceof HistogramPointData) {
            return ((HistogramPointData) point).getSum();
        }
        if (point instanceof ExponentialHistogramPointData) {
            return ((ExponentialHistogramPointData) point).getSum();
        }
        if (point instanceof SummaryPointData) {
            return ((SummaryPointData) point).getSum();
        }
        return Double.NaN;
    }

    /** {@code detail} 的唯一构造点，格式见类注释。 */
    private static String detail(final PointData point) {
        if (point instanceof LongPointData) {
            return "long";
        }
        if (point instanceof DoublePointData) {
            return "double";
        }
        if (point instanceof HistogramPointData) {
            return explicitDetail((HistogramPointData) point);
        }
        if (point instanceof ExponentialHistogramPointData) {
            return exponentialDetail((ExponentialHistogramPointData) point);
        }
        if (point instanceof SummaryPointData) {
            return quantileDetail((SummaryPointData) point);
        }
        // 认不出来的点类型：留 null 而不是编一个值，详情页据此显示"未知形态"
        return null;
    }

    private static String explicitDetail(final HistogramPointData p) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("explicit;min=").append(p.hasMin() ? Double.toString(p.getMin()) : "none");
        sb.append(";max=").append(p.hasMax() ? Double.toString(p.getMax()) : "none");
        sb.append(";bounds=").append(p.getBoundaries());
        sb.append(";counts=").append(p.getCounts());
        return sb.toString();
    }

    private static String exponentialDetail(final ExponentialHistogramPointData p) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("exponential;scale=").append(p.getScale());
        sb.append(";zeroCount=").append(p.getZeroCount());
        sb.append(";min=").append(p.hasMin() ? Double.toString(p.getMin()) : "none");
        sb.append(";max=").append(p.hasMax() ? Double.toString(p.getMax()) : "none");
        appendBuckets(sb, "pos", p.getPositiveBuckets());
        appendBuckets(sb, "neg", p.getNegativeBuckets());
        return sb.toString();
    }

    private static void appendBuckets(final StringBuilder sb, final String label,
            final io.opentelemetry.sdk.metrics.data.ExponentialHistogramBuckets buckets) {
        sb.append(';').append(label).append('=');
        if (buckets == null) {
            sb.append("none");
            return;
        }
        sb.append(buckets.getOffset()).append(',').append(buckets.getBucketCounts());
    }

    private static String quantileDetail(final SummaryPointData p) {
        final StringBuilder sb = new StringBuilder(64);
        sb.append("quantiles");
        final List<ValueAtQuantile> values = p.getValues();
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                final ValueAtQuantile q = values.get(i);
                sb.append(';').append(q.getQuantile()).append('=').append(q.getValue());
            }
        }
        return sb.toString();
    }
}
