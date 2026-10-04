package io.github.fulizhe.otelstore.agentext;

import io.github.fulizhe.otelstore.core.collection.RecordQueue;
import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
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
 * <p>当前阶段的 sink 是**占位**：只计数不落盘。接上 H2 与环形文件是 Phase 4b，
 * 那时只需换 sink，tap 与队列都不用动。
 */
public final class TapHub implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(TapHub.class.getName());

    private final LocalStoreConfig config;
    private final RecordQueue<Object> traces;
    private final RecordQueue<Object> logs;
    private final RecordQueue<Object> metrics;

    public TapHub(final LocalStoreConfig config) {
        this.config = config;
        final int cap = config.getQueueCapacity();
        this.traces = new RecordQueue<Object>("traces", cap, new PlaceholderSink("traces"));
        this.logs = new RecordQueue<Object>("logs", cap, new PlaceholderSink("logs"));
        this.metrics = new RecordQueue<Object>("metrics", cap, new PlaceholderSink("metrics"));
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                logSummary("退出");
            }
        }, "otelstore-summary"));
    }

    public LocalStoreConfig config() {
        return config;
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
     * 三条管线的组合快照，扁平化成 JDK 原生类型。
     *
     * <p>读口要跨 ClassLoader 交给 JMX / HTTP 侧序列化，因此不外泄任何自定义类型
     * （见 {@code docs/adr/adr-01-scope-and-principles.md}）。
     */
    public Map<String, Object> snapshot() {
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
     * 打印一行汇总。
     *
     * <p>存在的理由不是"方便看"，而是**在没有读口的阶段也需要一个可核对的验收信号**：
     * 起一次进程、造点数据、停掉，日志里这一行就能回答"三条管线各收到多少、丢了多少"。
     */
    public void logSummary(final String when) {
        try {
            LOGGER.info("[otel-local-telemetry-store] " + when
                    + " dataDir=" + config.getDataDir()
                    + " | traces " + line(traces)
                    + " | logs " + line(logs)
                    + " | metrics " + line(metrics));
        } catch (final RuntimeException ignored) {
            // 观测路径自身的异常绝不外抛
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
        traces.close();
        logs.close();
        metrics.close();
    }

    /**
     * Phase 4a 的占位 sink：只计数。
     *
     * <p>它刻意<b>不</b>打印每条记录 —— 采集路径上的逐条日志会把业务日志淹掉，
     * 而"收了多少"已经有 {@link #logSummary(String)} 这一行可看。
     */
    private static final class PlaceholderSink implements java.util.function.Consumer<Object> {
        private final String signal;

        PlaceholderSink(final String signal) {
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