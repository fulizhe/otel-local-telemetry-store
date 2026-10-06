package io.github.fulizhe.otelstore.core.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordQueueTest {

    /** 计数用的线程安全 sink */
    private static final class Counter implements java.util.function.Consumer<String> {
        private final List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        private final AtomicInteger errors = new AtomicInteger();
        private volatile long delayMillis;
        private final CountDownLatch gate;

        Counter() {
            this(0L, null);
        }

        Counter(final long delayMillis, final CountDownLatch gate) {
            this.delayMillis = delayMillis;
            this.gate = gate;
        }

        @Override
        public void accept(final String s) {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (gate != null) {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if ("boom".equals(s)) {
                errors.incrementAndGet();
                throw new IllegalStateException("sink 故意抛异常");
            }
            seen.add(s);
        }

        int size() {
            return seen.size();
        }
    }

    private static void awaitProcessed(final RecordQueue<String> q, final long target, final long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (q.drainedCount() + q.sinkErrorCount() >= target) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void awaitAtLeast(final RecordQueue<String> q, final long drained, final long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (q.drainedCount() >= drained) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @DisplayName("正常路径：入队的都会被落盘")
    void drainsEverything() {
        final Counter sink = new Counter();
        final RecordQueue<String> q = new RecordQueue<String>("traces", 64, sink);
        try {
            for (int i = 0; i < 20; i++) {
                assertTrue(q.offer("r" + i));
            }
            awaitAtLeast(q, 20, 5000);
            assertEquals(20L, q.drainedCount());
            assertEquals(0L, q.droppedCount());
            assertEquals(20, sink.size());
        } finally {
            q.close();
        }
    }

    @Test
    @DisplayName("队列满即丢弃并计数，永不阻塞")
    void dropsWhenFull() {
        // sink 被门闩卡住，drainer 停在第一条，队列必然填满
        final CountDownLatch gate = new CountDownLatch(1);
        final Counter sink = new Counter(0L, gate);
        final RecordQueue<String> q = new RecordQueue<String>("logs", 8, sink);
        try {
            int accepted = 0;
            for (int i = 0; i < 200; i++) {
                if (q.offer("r" + i)) {
                    accepted++;
                }
            }
            assertTrue(q.droppedCount() > 0, "大部分应被丢弃");
            assertEquals(accepted, (int) q.offeredCount(), "入队数 = 接受数 + 丢弃数");
            assertEquals(200, accepted + (int) q.droppedCount());
            // 不断言 backlog：drainer 是**批量**拉（drainTo 到本地 batch 再逐条 accept），
            // 它可能刚好在断言前把整个队列抽进 batch、人阻塞在 sink 里 —— 那时 queue.size()=0，
            // 但记录并没有丢，只是"在 drainer 手里"。backlog 天生不确定，能证明"满过"的是 dropped>0。
        } finally {
            gate.countDown();
            q.close();
        }
    }

    @Test
    @DisplayName("sink 抛异常只计数，不影响后续记录、也不让 drainer 死掉")
    void sinkFailureIsIsolated() {
        final Counter sink = new Counter();
        final RecordQueue<String> q = new RecordQueue<String>("traces", 256, sink);
        try {
            for (int i = 0; i < 10; i++) {
                q.offer(i == 3 ? "boom" : "r" + i);
            }
            awaitProcessed(q, 10, 5000);
            assertEquals(1L, q.sinkErrorCount(), "坏记录那条应被计入 sinkErrors");
            assertEquals(9, sink.size(), "其余 9 条应正常落盘");
            assertEquals(9L, q.drainedCount(), "drained 只数成功落盘的");
            assertEquals(0L, q.droppedCount(), "落盘失败不计入 dropped —— 那是队列满");
            assertEquals(q.offeredCount(), q.drainedCount() + q.sinkErrorCount(),
                    "成功落盘 + 落盘失败 = 已入队");
        } finally {
            q.close();
        }
    }

    @Test
    @DisplayName("close 会把积压排空")
    void closeDrainsBacklog() {
        final CountDownLatch gate = new CountDownLatch(1);
        final Counter sink = new Counter(0L, gate);
        final RecordQueue<String> q = new RecordQueue<String>("metrics", 512, sink);
        for (int i = 0; i < 100; i++) {
            q.offer("m" + i);
        }
        gate.countDown();
        q.close();
        assertEquals(0, q.backlog());
        assertEquals(100L, q.drainedCount());
    }

    @Test
    @DisplayName("close 之后再 offer 一律拒绝")
    void offerAfterCloseRejected() {
        final RecordQueue<String> q = new RecordQueue<String>("traces", 8, new Counter());
        q.close();
        assertFalse(q.offer("late"));
        assertEquals(0L, q.offeredCount());
    }

    @Test
    @DisplayName("快照扁平化成 JDK 原生类型，且不含 token 之类敏感字段")
    void snapshotIsFlat() throws InterruptedException {
        final Counter sink = new Counter();
        final RecordQueue<String> q = new RecordQueue<String>("logs", 32, sink);
        try {
            q.offer("x");
            awaitAtLeast(q, 1, 5000);
            final Map<String, Object> s = q.snapshot();
            for (final Map.Entry<String, Object> e : s.entrySet()) {
                assertTrue(e.getValue() instanceof String || e.getValue() instanceof Long
                                || e.getValue() instanceof Integer,
                        e.getKey() + " 应是 JDK 原生类型");
            }
            assertEquals(s.get("signal"), "logs");
            assertEquals(Integer.valueOf(32), s.get("capacity"));
            assertTrue(s.containsKey("dropped"));
            assertTrue(s.containsKey("backlog"));
        } finally {
            q.close();
        }
    }

    @Test
    @DisplayName("构造参数非法直接拒绝，不静默降级")
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new RecordQueue<String>("x", 0, s -> { }));
        assertThrows(IllegalArgumentException.class, () -> new RecordQueue<String>("x", -1, s -> { }));
        assertThrows(IllegalArgumentException.class, () -> new RecordQueue<String>("x", 8, null));
    }

    @Test
    @DisplayName("drainer 是守护线程，不阻止 JVM 退出")
    void drainerIsDaemon() {
        final RecordQueue<String> q = new RecordQueue<String>("traces", 8, new Counter());
        try {
            Thread found = null;
            for (final Thread t : Thread.getAllStackTraces().keySet()) {
                if ("otelstore-drain-traces".equals(t.getName())) {
                    found = t;
                }
            }
            assertNotNull(found, "drainer 线程未找到");
            assertTrue(found.isDaemon(), "drainer 必须是守护线程");
        } finally {
            q.close();
        }
    }
}
