package io.github.fulizhe.otelstore.agentext;

import io.github.fulizhe.otelstore.core.collection.RecordQueue;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.util.Collection;

/**
 * 指标采集口。
 *
 * <p>与前两条不同：{@link #export(Collection)} 收到的是<b>周期性聚合快照</b>，不是事件流。
 * 所以这里逐条 {@code offer} 的是 {@link MetricData}，落盘侧要按"时间序列"处理
 * （见 {@code docs/adr/adr-02-data-model.md}），不能当成事件流插进环形文件。
 *
 * <p>{@code export} 跑在采集周期线程上，不占业务线程。
 */
final class MetricTap implements MetricExporter {

    private final RecordQueue<Object> queue;
    /** 过滤掉 agent 自监控的 instrumentation scope（见 ADR-1 第 2 条原则）。 */
    private final String[] selfTelemetryPrefixes;

    MetricTap(final RecordQueue<Object> queue, final String[] selfTelemetryPrefixes) {
        this.queue = queue;
        this.selfTelemetryPrefixes = selfTelemetryPrefixes.clone();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(final InstrumentType instrumentType) {
        // SDK 会反复询问（实测一次运行十余次），所以这里必须廉价 —— 不做任何计算。
        return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public CompletableResultCode export(final Collection<MetricData> metrics) {
        if (metrics == null) {
            return CompletableResultCode.ofSuccess();
        }
        for (final MetricData m : metrics) {
            if (m != null && !isSelfTelemetry(m)) {
                queue.offer(m);
            }
        }
        return CompletableResultCode.ofSuccess();
    }

    private boolean isSelfTelemetry(final MetricData m) {
        final String scope = m.getInstrumentationScopeInfo() == null
                ? "" : m.getInstrumentationScopeInfo().getName();
        for (int i = 0; i < selfTelemetryPrefixes.length; i++) {
            if (scope.startsWith(selfTelemetryPrefixes[i])) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}