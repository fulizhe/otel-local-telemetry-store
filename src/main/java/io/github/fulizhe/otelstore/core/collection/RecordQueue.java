package io.github.fulizhe.otelstore.core.collection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 有界记录队列 + 独立 drainer 线程。
 *
 * <p>采集回调（span 结束、日志发射、指标采集）只做一次 {@code offer}，落盘由 drainer 线程做。
 * 三条信号各持一个实例，因此某条路径的 sink 变慢**不会**拖住另外两条，也**不会**拖住业务线程
 * —— 这是本项目的头号约束：监控只能是助力。
 *
 * <p><b>队列满即丢弃并计数</b>，不阻塞、不无限增长。丢弃数与"成功落盘数"分开计，
 * 理由见 {@code docs/adr/adr-03-four-ways-data-goes-missing.md} 第 1 种形态。
 *
 * <p>本类<b>不引用任何 {@code io.opentelemetry.*}</b>：它是纯队列逻辑，
 * 与采集端解耦才能被三条信号共用。
 *
 * @param <T> 记录类型
 */
public final class RecordQueue<T> implements AutoCloseable {

    /** drainer 空转一轮的等待时长。取值偏小是为了让关闭时的排空不被拖太久。 */
    private static final long POLL_MILLIS = 200L;

    private final String name;
    private final ArrayBlockingQueue<T> queue;
    private final Consumer<T> sink;

    private final AtomicLong offeredCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong drainedCount = new AtomicLong();
    private final AtomicLong sinkErrorCount = new AtomicLong();
    private final AtomicLong batchCount = new AtomicLong();

    private final Thread drainer;
    private volatile boolean running = true;

    /**
     * @param name 统计与日志用的名字（对应信号名）
     * @param capacity 队列容量；必须 &gt; 0
     * @param sink 落盘动作，在 drainer 线程上被调用。抛异常只计数、不影响队列与线程
     */
    public RecordQueue(final String name, final int capacity, final Consumer<T> sink) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0: " + capacity);
        }
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        this.name = name;
        this.queue = new ArrayBlockingQueue<T>(capacity);
        this.sink = sink;
        this.drainer = new Thread(new Runnable() {
            @Override
            public void run() {
                drainLoop();
            }
        }, "otelstore-drain-" + name);
        this.drainer.setDaemon(true);
        this.drainer.start();
    }

    /**
     * 入队一条记录。永不阻塞 —— 队列满就丢弃并计数。
     *
     * @return true = 已入队；false = 队列满被丢弃
     */
    public boolean offer(final T record) {
        if (!running) {
            return false;
        }
        final boolean ok = queue.offer(record);
        if (ok) {
            offeredCount.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
        }
        return ok;
    }

    private void drainLoop() {
        final ArrayList<T> batch = new ArrayList<T>(64);
        while (running || !queue.isEmpty()) {
            try {
                final T first = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, 1023);
                applyBatch(batch);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void applyBatch(final ArrayList<T> batch) {
        for (int i = 0; i < batch.size(); i++) {
            try {
                sink.accept(batch.get(i));
                drainedCount.incrementAndGet();
            } catch (final RuntimeException e) {
                // 落盘失败只计数：一条坏记录不该把整批拖掉，更不该让 drainer 线程死掉。
                sinkErrorCount.incrementAndGet();
            }
        }
        if (!batch.isEmpty()) {
            batchCount.incrementAndGet();
        }
    }

    /** 当前积压条数。 */
    public int backlog() {
        return queue.size();
    }

    public long offeredCount() {
        return offeredCount.get();
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    public long drainedCount() {
        return drainedCount.get();
    }

    public long sinkErrorCount() {
        return sinkErrorCount.get();
    }

    public long batchCount() {
        return batchCount.get();
    }

    /** 队列容量。 */
    public int capacity() {
        return queue.size() + queue.remainingCapacity();
    }

    /**
     * 组合快照，扁平化成 JDK 原生类型。
     *
     * <p>读口要跨 ClassLoader 交给JMX / HTTP 侧序列化，因此这里不外泄本类实例
     * （见 ADR-1 的读口约定）。
     */
    public Map<String, Object> snapshot() {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("signal", name);
        m.put("backlog", Integer.valueOf(queue.size()));
        m.put("capacity", Integer.valueOf(capacity()));
        m.put("offered", Long.valueOf(offeredCount.get()));
        m.put("dropped", Long.valueOf(droppedCount.get()));
        m.put("drained", Long.valueOf(drainedCount.get()));
        m.put("sinkErrors", Long.valueOf(sinkErrorCount.get()));
        m.put("batches", Long.valueOf(batchCount.get()));
        return m;
    }

    /** 停止 drainer 并把积压排空。 */
    @Override
    public void close() {
        running = false;
        drainer.interrupt();
        try {
            drainer.join(POLL_MILLIS * 5);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 线程若已退出但仍有积压（例如它正卡在 sink 上），在当前线程补一次排空，尽量不丢。
        if (!queue.isEmpty()) {
            final ArrayList<T> rest = new ArrayList<T>(queue.size() + 1);
            queue.drainTo(rest);
            applyBatch(rest);
        }
    }
}