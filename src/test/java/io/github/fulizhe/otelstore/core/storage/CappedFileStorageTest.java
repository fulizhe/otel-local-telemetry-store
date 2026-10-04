package io.github.fulizhe.otelstore.core.storage;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CappedFileStorage 单元测试。
 * 覆盖：二进制与空载荷往返、跨尾、覆盖淘汰（过期）、GZIP、超限块、close/重开、损坏块头、并发。
 */
public class CappedFileStorageTest {

    private static final int FILE_HEADER_BYTES = 16;

    private File dir;
    private final List<CappedFileStorage> opened = new ArrayList<CappedFileStorage>();

    @BeforeEach
    public void setUp() {
        dir = new File(System.getProperty("java.io.tmpdir"), "capped-test-" + System.nanoTime());
        Assertions.assertTrue(dir.mkdirs());
    }

    @AfterEach
    public void tearDown() {
        for (CappedFileStorage s : opened) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                Assertions.assertTrue(f.delete() || !f.exists());
            }
        }
        Assertions.assertTrue(dir.delete() || !dir.exists());
    }

    private CappedFileStorage open(final long sizeBytes) throws IOException {
        final CappedFileStorage s = new CappedFileStorage(storageFile(), sizeBytes);
        opened.add(s);
        return s;
    }

    private File storageFile() {
        return new File(dir, "payload.capped.db");
    }

    private static byte[] payload(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void roundTrip_preservesBinaryAndEmptyPayload() throws IOException {
        final CappedFileStorage s = open(1024 * 64);
        final byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        final byte[] empty = new byte[0];

        final long binaryId = s.writeMessage(binary);
        final long emptyId = s.writeMessage(empty);

        Assertions.assertArrayEquals(binary, s.readMessage(binaryId));
        Assertions.assertArrayEquals(empty, s.readMessage(emptyId));
    }

    @Test
    public void compressiblePayload_fitsWhenRawPayloadExceedsCapacity() throws IOException {
        final CappedFileStorage s = open(128);
        final byte[] big = new byte[4096];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) 'a';
        }

        final long id = s.writeMessage(big);

        Assertions.assertArrayEquals(big, s.readMessage(id));
        Assertions.assertEquals(128, storageFile().length());
    }

    @Test
    public void wrapAround_repeatedWriteReadBack() throws IOException {
        final CappedFileStorage s = open(256);
        for (int i = 0; i < 200; i++) {
            final byte[] p = payload("block-" + i + "-" + repeat('x', 10 + (i % 40)));
            final long id = s.writeMessage(p);
            Assertions.assertArrayEquals(p, s.readMessage(id), "round trip at write " + i);
        }
        Assertions.assertEquals(256, storageFile().length());
    }

    @Test
    public void overwriteEviction_retainsOnlyCompleteSuffix() throws IOException {
        final CappedFileStorage s = open(512);
        final List<Long> ids = new ArrayList<Long>();
        final List<byte[]> payloads = new ArrayList<byte[]>();
        for (int i = 0; i < 300; i++) {
            final byte[] p = payload("evict-" + i + "-" + repeat('y', 30));
            ids.add(s.writeMessage(p));
            payloads.add(p);
        }

        final long oldestReadableId = Math.max(0L, s.getCurrIndex() - (512 - FILE_HEADER_BYTES));
        int readable = 0;
        for (int i = 0; i < ids.size(); i++) {
            final long id = ids.get(i);
            final boolean retained = id >= oldestReadableId;
            Assertions.assertEquals(retained, s.isOverwritten(id) == false);
            if (retained) {
                Assertions.assertArrayEquals(payloads.get(i), s.readMessage(id), "retained message " + i);
                readable++;
            } else {
                Assertions.assertNull(s.readMessage(id), "expired message " + i);
            }
        }

        Assertions.assertTrue(readable < ids.size(), "some records must expire");
        Assertions.assertTrue(readable > 0, "some records must remain");
        Assertions.assertEquals(512, storageFile().length());
    }

    @Test
    public void oversizedPayload_rejected_withoutChangingState() throws IOException {
        final CappedFileStorage s = open(256);
        final byte[] sentinel = payload("sentinel");
        final long sentinelId = s.writeMessage(sentinel);
        final long before = s.getCurrIndex();
        final byte[] big = new byte[4096];
        new Random(1).nextBytes(big);

        try {
            s.writeMessage(big);
            Assertions.fail("expected IOException for oversized payload");
        } catch (IOException expected) {
            Assertions.assertTrue(expected.getMessage().contains("payload too large"));
        }

        Assertions.assertEquals(before, s.getCurrIndex());
        Assertions.assertArrayEquals(sentinel, s.readMessage(sentinelId));
        final byte[] afterFailure = payload("after-failure");
        Assertions.assertArrayEquals(afterFailure, s.readMessage(s.writeMessage(afterFailure)));
    }

    @Test
    public void closeAndReopen_persistsHeaderAndData() throws IOException {
        final CappedFileStorage s = open(1024 * 16);
        final byte[] p = payload("persist-me");
        final long id = s.writeMessage(p);
        s.close();

        final CappedFileStorage reopened = open(1024 * 16);
        Assertions.assertArrayEquals(p, reopened.readMessage(id), "data should survive reopen");
        final byte[] p2 = payload("after-reopen");
        final long id2 = reopened.writeMessage(p2);
        Assertions.assertTrue(id2 > id, "new id must be greater");
        Assertions.assertArrayEquals(p2, reopened.readMessage(id2), "new data should be readable");
        Assertions.assertArrayEquals(p, reopened.readMessage(id), "old data must remain readable");
    }

    @Test
    public void reopenWithDifferentSize_reinitializesFile() throws IOException {
        final CappedFileStorage original = open(1024 * 16);
        final long oldId = original.writeMessage(payload("old-size"));
        original.close();

        final CappedFileStorage reopened = open(1024 * 8);
        Assertions.assertEquals(0L, reopened.getCurrIndex());
        Assertions.assertNull(reopened.readMessage(oldId));
        final byte[] current = payload("new-size");
        Assertions.assertArrayEquals(current, reopened.readMessage(reopened.writeMessage(current)));
        Assertions.assertEquals(1024L * 8L, storageFile().length());
    }

    @Test
    public void corruptedBlockHeader_returnsNull() throws IOException {
        final CappedFileStorage s = open(256);
        final long id = s.writeMessage(payload("corrupt-me"));
        s.close();

        final RandomAccessFile file = new RandomAccessFile(storageFile(), "rw");
        try {
            file.seek(FILE_HEADER_BYTES + id % (256 - FILE_HEADER_BYTES));
            file.writeLong(0L);
        } finally {
            file.close();
        }

        final CappedFileStorage reopened = open(256);
        Assertions.assertNull(reopened.readMessage(id));
    }

    @Test
    public void concurrentReadWrite_wrapsWithoutTearingData() throws Exception {
        final CappedFileStorage s = open(256);
        final byte[] anchorPayload = payload("anchor");
        final long anchorId = s.writeMessage(anchorPayload);
        final int readerCount = 3;
        final int workerCount = readerCount + 1;
        final int writes = 1000;
        final int reads = 1000;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(workerCount);
        final CountDownLatch writerPaused = new CountDownLatch(1);
        final CountDownLatch readersReady = new CountDownLatch(readerCount);
        final CyclicBarrier barrier = new CyclicBarrier(workerCount);
        final List<Throwable> errors = new CopyOnWriteArrayList<Throwable>();
        final AtomicInteger validReads = new AtomicInteger();
        final AtomicInteger expiredReads = new AtomicInteger();
        final long[] lastId = new long[1];
        final byte[][] lastPayload = new byte[1][];
        final List<Thread> workers = new ArrayList<Thread>();

        for (int i = 0; i < readerCount; i++) {
            final Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean ready = false;
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("start latch timed out");
                        }
                        barrier.await(10, TimeUnit.SECONDS);
                        if (!writerPaused.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("writer pause timed out");
                        }
                        final byte[] first = s.readMessage(anchorId);
                        Assertions.assertNotNull(first);
                        Assertions.assertArrayEquals(anchorPayload, first);
                        validReads.incrementAndGet();
                        readersReady.countDown();
                        ready = true;
                        for (int j = 1; j < reads; j++) {
                            final byte[] actual = s.readMessage(anchorId);
                            if (actual == null) {
                                expiredReads.incrementAndGet();
                            } else {
                                Assertions.assertArrayEquals(anchorPayload, actual);
                                validReads.incrementAndGet();
                            }
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        if (!ready) {
                            readersReady.countDown();
                        }
                        done.countDown();
                    }
                }
            }, "capped-reader-" + i);
            reader.setDaemon(true);
            workers.add(reader);
            reader.start();
        }

        final Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("start latch timed out");
                        }
                        barrier.await(10, TimeUnit.SECONDS);
                        for (int i = 0; i < writes; i++) {
                            if (i == 2) {
                                writerPaused.countDown();
                                if (!readersReady.await(10, TimeUnit.SECONDS)) {
                                    throw new AssertionError("reader barrier timed out");
                                }
                            }
                            final byte[] p = payload("writer-" + i + "-" + repeat('z', 20));
                            lastId[0] = s.writeMessage(p);
                            lastPayload[0] = p;
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        writerPaused.countDown();
                        done.countDown();
                    }
                }

        }, "capped-writer");
        writer.setDaemon(true);
        workers.add(writer);
        writer.start();

        start.countDown();
        final boolean finished = done.await(10, TimeUnit.SECONDS);
        if (!finished) {
            for (Thread worker : workers) {
                worker.interrupt();
            }
        }
        for (Thread worker : workers) {
            worker.join(1000L);
        }

        Assertions.assertTrue(finished, "workers did not finish");
        Assertions.assertTrue(errors.isEmpty(), "no errors expected: " + errors);
        Assertions.assertEquals(reads * readerCount, validReads.get() + expiredReads.get());
        Assertions.assertTrue(validReads.get() > 0, "readers must observe valid data before eviction");
        Assertions.assertTrue(s.getCurrIndex() > 256L, "writer must wrap the capped file");
        Assertions.assertTrue(s.isOverwritten(anchorId), "anchor must be expired");
        Assertions.assertNull(s.readMessage(anchorId));
        Assertions.assertArrayEquals(lastPayload[0], s.readMessage(lastId[0]));
        Assertions.assertEquals(256, storageFile().length());
    }

    @Test
    public void stats_trackWritesReadsEvictionAndGeometry() throws IOException {
        final long dataLen = 256L - FILE_HEADER_BYTES;
        final CappedFileStorage s = open(256);
        final long anchorId = s.writeMessage(payload("anchor-block-" + repeat('a', 200)));

        Assertions.assertEquals(dataLen, s.getDataLenBytes(), "数据区 = 文件大小 - 16B 文件头");
        Assertions.assertEquals(0L, s.getWrapCount(), "刚写过一块，还没绕圈");
        Assertions.assertTrue(s.getOldestLiveIndex() == 0L, "最老幸存 id 在未绕圈时应为 0");
        Assertions.assertTrue(s.getCurrIndex() > 0L, "写指针已前进");
        Assertions.assertEquals(1L, s.stats().getWriteCount());
        Assertions.assertTrue(s.stats().getCompressionRatio() > 0d, "可压缩载荷的压缩率应为正");
        Assertions.assertTrue(s.stats().getAverageMillisPerWrite() >= 0d, "单次写耗时非负");
        Assertions.assertTrue(s.getFilePath().endsWith("payload.capped.db"), "文件路径应可读");

        // 绕圈：写到游标超过一整圈，anchor 必被覆盖
        for (int i = 0; i < 40; i++) {
            s.writeMessage(payload("block-" + i + "-" + repeat('b', 200)));
        }
        Assertions.assertTrue(s.getWrapCount() >= 1L, "游标应已绕过至少一圈");
        Assertions.assertEquals(s.getCurrIndex() - dataLen,
                s.getOldestLiveIndex(), "绕圈后最老幸存 id = currIndex - dataLen");
        Assertions.assertTrue(s.isOverwritten(anchorId), "anchor 应已被覆盖");
        Assertions.assertNull(s.readMessage(anchorId), "被覆盖后读回 null");
        Assertions.assertEquals(1L, s.stats().getExpiredReadCount(), "过期读应被单独计数");
        Assertions.assertEquals(1L, s.stats().getMissReadCount(), "读不到合计 = 1 次过期");
        Assertions.assertEquals(0L, s.stats().getIoErrorCount(), "0 次写入失败");
        Assertions.assertEquals(0L, s.stats().getOversizedRejectedCount(), "0 次超容拒写");

        // 未写入的 id 读回 null，但原因不是"环覆盖"
        Assertions.assertNull(s.readMessage(s.getCurrIndex() + 1000L));
        Assertions.assertEquals(1L, s.stats().getExpiredReadCount(), "过期读数不应因'从未写入'而增加");
        Assertions.assertEquals(2L, s.stats().getMissReadCount());

        // 成功读回才计入 readCount
        final long lastId = s.writeMessage(payload("readback-" + repeat('c', 100)));
        Assertions.assertNotNull(s.readMessage(lastId));
        Assertions.assertTrue(s.stats().getReadCount() >= 1L, "读回次数应至少 1");
    }

    @Test
    public void stats_countOversizedRejectionAndIoErrorSeparately() throws IOException {
        final CappedFileStorage s = open(128);
        final byte[] incompressible = new byte[4096];
        new Random(7).nextBytes(incompressible);

        try {
            s.writeMessage(incompressible);
            Assertions.fail("expected IOException for oversized payload");
        } catch (IOException expected) {
            Assertions.assertTrue(expected.getMessage().contains("payload too large"));
        }

        Assertions.assertEquals(1L, s.stats().getOversizedRejectedCount(), "超容拒写单独计数");
        Assertions.assertEquals(0L, s.stats().getIoErrorCount(), "拒写不是 IO 错误");
        Assertions.assertEquals(0L, s.getCurrIndex(), "拒写不推进写指针");
    }

    private static String repeat(final char c, final int n) {
        final StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
