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
            if (m == null) {
                continue;
            }
            if (isSelfTelemetry(m)) {
                // 被前缀过滤掉的那批：按 ADR-6 第八节**只计数，不缓冲**。
                // 计数是 O(1) 内存，而"过滤掉了多少个点"本身就是排障信息 ——
                // 静默丢弃会让人以为根本没有自监控指标这个概念。
                droppedSelfTelemetry.incrementAndGet();
                lastSelfTelemetryDroppedAt.set(System.currentTimeMillis());
            } else {
                queue.offer(m);
            }
        }
        return CompletableResultCode.ofSuccess();
    }

    /** 被前缀过滤掉的自监控指标累计个数；{@code /api/summary} 的 {@code selfTelemetryDropped}。 */
    public long droppedSelfTelemetry() {
        return droppedSelfTelemetry.get();
    }

    /** 最近一次丢弃自监控指标的时刻；从未丢过为 0。 */
    public long lastSelfTelemetryDroppedAt() {
        return lastSelfTelemetryDroppedAt.get();
    }

    /**
     * 进程内唯一的计数器。
     *
     * <p>静态的而不是实例字段：整个进程只注册一个 {@code MetricTap}
     * （见 {@code LocalStoreCustomizerProvider}），而读口要拿到这个数字就得能跨实例读它 ——
     * 留一个静态引用比再加一条注入链便宜。若将来真有第二个 reader，
     * 正确的做法是把它并入 {@code TapHub} 的快照，不是继续加静态字段。
     */
    private static final java.util.concurrent.atomic.AtomicLong droppedSelfTelemetry =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong lastSelfTelemetryDroppedAt =
            new java.util.concurrent.atomic.AtomicLong();

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