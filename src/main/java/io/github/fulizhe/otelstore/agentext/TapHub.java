package io.github.fulizhe.otelstore.agentext;

import io.github.fulizhe.otelstore.core.collection.RecordQueue;
import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.storage.LocalStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 三条信号管线的持有者。
 *
 * <p>采集侧的 tap 只做一件事：把 SDK 给的对象丢进各自的 {@link RecordQueue}。
 * 落盘由队列自己的 drainer 线程做，因此采集回调永远不会被存储拖住。
 *
 * <p>drainer 上跑的 sink 做两件事：<b>翻译</b>（OTel 数据 → {@code core} 的入参）与
 * <b>交给 {@link LocalStore}</b>。翻译必须在 {@code agentext} 里 ——
 * {@code core} 不许 import 任何 {@code io.opentelemetry.*}（ADR-1 的分层规则）。
 *
 * <p><b>存储开不起来就退化成只计数</b>（而不是让应用起不来）：配置永不失败、扩展也不许
 * 把客户的进程搞挂（ADR-1 不可让原则第 3 条）。此时三个 sink 变成空实现，
 * {@link #snapshot()} 里 {@code store} 为 null，日志里有一行说明。
 */
public final class TapHub implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(TapHub.class.getName());

    private final LocalStoreConfig config;
    private final RecordQueue<Object> traces;
    private final RecordQueue<Object> logs;
    private final RecordQueue<Object> metrics;
    /** 存储层；开不起来时为 null，三条 sink 退化为空实现。 */
    private final LocalStore store;

    public TapHub(final LocalStoreConfig config) {
        this.config = config;
        final int cap = config.getQueueCapacity();
        this.store = openStore(config);
        this.traces = new RecordQueue<Object>("traces", cap, spanSink(store));
        this.logs = new RecordQueue<Object>("logs", cap, logSink(store));
        this.metrics = new RecordQueue<Object>("metrics", cap, metricSink(store));
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                logSummary("退出");
            }
        }, "otelstore-summary"));
    }

    /**
     * 开存储层，失败只记不抛。
     *
     * <p>失败最常见的原因是数据目录不可写（权限、路径被文件占了）。那属于"部署没配好"，
     * 不属于"应用有问题"—— 应用必须照常起来，我们只是没得存。
     */
    private static LocalStore openStore(final LocalStoreConfig config) {
        try {
            final LocalStore opened = new LocalStore(config);
            LOGGER.info("[otel-local-telemetry-store] 存储层就绪 dataDir=" + config.getDataDir()
                    + " ringFiles=traces.capped,logs.capped"
                    + " rows=" + config.getRowsTraces() + "/" + config.getRowsLogs() + "/"
                    + config.getRowsMetrics());
            return opened;
        } catch (final Exception e) {
            LOGGER.warning("[otel-local-telemetry-store] 存储层开不起来，本次只计数不落盘："
                    + e + "（数据目录=" + config.getDataDir() + "）");
            return null;
        }
    }

    private static java.util.function.Consumer<Object> spanSink(final LocalStore store) {
        if (store == null) {
            return new CountingOnlySink("traces");
        }
        return new java.util.function.Consumer<Object>() {
            @Override
            public void accept(final Object record) {
                // 翻译在 drainer 线程上做，不在采集线程上：SDK 对象的有效期不跨线程，
                // 而把它变成本项目的入参是一次纯 CPU 动作，不该让业务线程付钱。
                store.store(SpanMapper.map((io.opentelemetry.sdk.trace.data.SpanData) record));
            }
        };
    }

    private static java.util.function.Consumer<Object> logSink(final LocalStore store) {
        if (store == null) {
            return new CountingOnlySink("logs");
        }
        return new java.util.function.Consumer<Object>() {
            @Override
            public void accept(final Object record) {
                store.store(LogMapper.map((io.opentelemetry.sdk.logs.data.LogRecordData) record));
            }
        };
    }

    /**
     * 指标 sink 的入参是<b>一个 {@code MetricData} 拆出来的若干点</b>
     * （行单元 = 采集周期 × 指标 × 属性组合，见 ADR-2），所以这里遍历后逐点落库。
     */
    private static java.util.function.Consumer<Object> metricSink(final LocalStore store) {
        if (store == null) {
            return new CountingOnlySink("metrics");
        }
        return new java.util.function.Consumer<Object>() {
            @Override
            public void accept(final Object record) {
                for (final io.github.fulizhe.otelstore.core.model.MetricPointEntry point
                        : MetricMapper.map((io.opentelemetry.sdk.metrics.data.MetricData) record)) {
                    store.store(point);
                }
            }
        };
    }

    public LocalStoreConfig config() {
        return config;
    }

    /**
     * 存储层；开不起来时为 null（读口要能区分"没数据"与"没有存储"）。
     */
    public LocalStore store() {
        return store;
    }

    public RecordQueue<Object> traces() {
        return traces;
    }

    public RecordQueue<Object> logs() {
        return logs;
    }

    public RecordQueue<Object> metrics() {
        return metrics;
    }

    /**
     * 三条管线（队列）的组合快照，扁平化成 JDK 原生类型。
     *
     * <p>读口要跨 ClassLoader 交给 JMX / HTTP 侧序列化，因此不外泄任何自定义类型
     * （见 {@code docs/adr/adr-01-scope-and-principles.md}）。
     */
    public Map<String, Object> queuesSnapshot() {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dataDir", config.getDataDir());
        m.put("queueCapacity", Integer.valueOf(config.getQueueCapacity()));
        m.put("traces", traces.snapshot());
        m.put("logs", logs.snapshot());
        m.put("metrics", metrics.snapshot());
        final long dropped = traces.droppedCount() + logs.droppedCount() + metrics.droppedCount();
        final long sinkErrors = traces.sinkErrorCount() + logs.sinkErrorCount() + metrics.sinkErrorCount();
        m.put("droppedTotal", Long.valueOf(dropped));
        m.put("sinkErrorTotal", Long.valueOf(sinkErrors));
        return m;
    }

    /**
     * 队列 + 存储层的组合快照。
     *
     * <p>{@code store} 为 null 表示存储层没开起来 —— 与"库里是空的"是两回事，
     * 读口必须能分开说，否则一次部署配错会被当成"没有数据"。
     */
    public Map<String, Object> snapshot() {
        final Map<String, Object> m = queuesSnapshot();
        m.put("store", store == null ? null : store.snapshot());
        return m;
    }

    /**
     * 打印一行汇总。
     *
     * <p>存在的理由不是"方便看"，而是**在没有读口的阶段也需要一个可核对的验收信号**：
     * 起一次进程、造点数据、停掉，日志里这一行就能回答"三条管线各收到多少、丢了多少"。
     *
     * <p>三个 {@code drained} 现在能直接对上"库里存了多少"（读口/JMX 那一侧）——
     * 两者不等就说明存储路径上有东西在丢，而不只是队列在丢。
     */
    public void logSummary(final String when) {
        try {
            LOGGER.info("[otel-local-telemetry-store] " + when
                    + " dataDir=" + config.getDataDir()
                    + " | traces " + line(traces)
                    + " | logs " + line(logs)
                    + " | metrics " + line(metrics)
                    + " | store " + storedLine());
        } catch (final RuntimeException ignored) {
            // 观测路径自身的异常绝不外抛
        }
    }

    /**
     * 已落库的行数。
     *
     * <p>存查询失败时显示 {@code ?} 而不是 0 或抛异常：这一行在关停钩子里，
     * 它自己抛异常会让"退出汇总"这行整个打不出来 —— 那才是真正丢信息。
     */
    private String storedLine() {
        if (store == null) {
            return "off(未开起来)";
        }
        try {
            return "spans=" + store.countSpans()
                    + " logs=" + store.countLogs()
                    + " metricPoints=" + store.countMetrics()
                    + " resources=" + store.countResources();
        } catch (final Exception e) {
            return "?(" + e + ")";
        }
    }

    private static String line(final RecordQueue<Object> q) {
        final List<String> parts = new ArrayList<String>();
        parts.add("offered=" + q.offeredCount());
        parts.add("drained=" + q.drainedCount());
        parts.add("dropped=" + q.droppedCount());
        parts.add("sinkErrors=" + q.sinkErrorCount());
        parts.add("backlog=" + q.backlog());
        return String.join(" ", parts);
    }

    @Override
    public void close() {
        // 顺序要紧：先把三条队列排空（drainer 还在往存储里写），再关存储。
        traces.close();
        logs.close();
        metrics.close();
        if (store != null) {
            store.close();
        }
    }

    /**
     * 存储层开不起来时的退化 sink：只让计数照走。
     *
     * <p>它刻意<b>不</b>打印每条记录 —— 采集路径上的逐条日志会把业务日志淹掉，
     * 而"收了多少"已经有 {@link #logSummary(String)} 这一行可看。
     * 空记录仍然抛异常：那说明管线本身坏了，与存储无关，不该被这次退化掩盖掉。
     */
    private static final class CountingOnlySink implements java.util.function.Consumer<Object> {
        private final String signal;

        CountingOnlySink(final String signal) {
            this.signal = signal;
        }

        @Override
        public void accept(final Object record) {
            if (record == null) {
                throw new IllegalArgumentException(signal + " 收到空记录");
            }
        }
    }
}
