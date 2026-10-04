package io.github.fulizhe.otelstore.core.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 环形封顶载荷文件（最小形态 + GZIP）。
 *
 * <p>
 * 单文件、<b>固定大小</b>；块格式 {@code <len:8B><gzip(payload)>}；块可跨文件尾分段写/读；
 * 写满后覆盖最旧块；被覆盖的逻辑 id 读回 {@code null}（过期）。读写共用一把锁。
 * </p>
 *
 * <p>
 * 设计来源（本类为其<b>最小形态移植</b>，去掉 resize / isInTheFuture / 压缩率统计，载荷改为每块独立 GZIP）：
 * <a href="https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/util/CappedDatabase.java">Glowroot CappedDatabase.java</a>
 * </p>
 *
 * <p>
 * 文件布局：文件头 16B = {@code currIndex(long 8B)} + {@code sizeBytes(long 8B)}；
 * 数据区 = {@code [16, sizeBytes)}；逻辑 index 到物理位置的映射为 {@code 16 + (index % dataLen)}。
 * </p>
 *
 * <p>
 * 观测：{@link #stats()} 暴露 {@link CappedFileStorageStats}（写指针、压缩率、过期读、fsync…），
 * 供读口回答"环形文件写到哪了、健康吗"——对应 Glowroot 的 {@code CappedDatabaseStats} + MBean 组合，
 * 本项目改为经读口暴露（JMX 那条路见 docs/notes/2026-10-04-r0-extension-points.md 第五节：
 * 应用侧读不到扩展的类，JMX 是唯一的跨 ClassLoader 通道）。
 * </p>
 *
 * <p>
 * 本类从 SkyWalking 侧的 logfile-reporter-plugin 原样搬来（见 {@code docs/adr/}），
 * 与 OTel 无关，因此不 import 任何 {@code io.opentelemetry.*}。
 * </p>
 */
class CappedFileStorage implements Closeable {

    /** 文件头：currIndex + sizeBytes */
    private static final int HEADER_SKIP_BYTES = 16;
    /** 块头：payload 长度 */
    private static final int BLOCK_HEADER_BYTES = 8;

    /** 每写满 N 次触发一次 fsync */
    private static final long FSYNC_EVERY_WRITES = 100L;
    /** 距上次 fsync 超过 T 毫秒也触发一次 */
    private static final long FSYNC_INTERVAL_MS = 1000L;

    private final File file;
    private final long sizeBytes;
    private final long dataLen;
    private final Object lock = new Object();

    private RandomAccessFile raf;
    /** 只增不减的逻辑写游标（跨多轮覆盖也不回绕） */
    private long currIndex;
    private boolean dirty;
    private long writesSinceFsync;
    private long lastFsyncTime;
    /** 运行统计（压缩率 / 耗时 / 过期读 / fsync），构造即有，读口只读 */
    private final CappedFileStorageStats stats = new CappedFileStorageStats();

    public CappedFileStorage(final File file, final long sizeBytes) throws IOException {
        this.file = file;
        this.sizeBytes = Math.max(sizeBytes, HEADER_SKIP_BYTES + BLOCK_HEADER_BYTES + 1L);
        this.dataLen = this.sizeBytes - HEADER_SKIP_BYTES;
        openAndInit();
    }

    private void openAndInit() throws IOException {
        final boolean existing = file.exists() && file.length() == sizeBytes;
        raf = new RandomAccessFile(file, "rw");
        if (existing) {
            raf.seek(0);
            // long长度是 64bit = 8 字节，这是头部和"块头"都用 long 的原因。
            currIndex = raf.readLong();
            raf.readLong(); // sizeBytes 字段；以文件长度为准，读后忽略
        } else {
            raf.setLength(sizeBytes);
            currIndex = 0;
            writeHeader();
        }
        this.lastFsyncTime = System.currentTimeMillis();
    }

    private void writeHeader() throws IOException {
        raf.seek(0);
        raf.writeLong(currIndex);
        raf.writeLong(sizeBytes);
    }

    /**
     * 逻辑 index → 物理位置（数据区内）。
     *
     * <p>设计要点：逻辑地址空间（{@link #currIndex} 等）只增不减、永不复位，给每个块分配
     * 独一无二的身份；物理落点完全由本方法对数据区大小取模保证——无论逻辑坐标多大，最终都
     * 压回构造时设置的 sizeBytes 范围内（数据区起点偏移 HEADER_SKIP_BYTES 起）。这就是
     * "**逻辑不绕圈、物理绕圈**"的解耦：写在环的哪个位置由这里决定，而该位置是否过期由
     * {@link #readMessage} 的窗口判定决定，两者缺一不可。
     */
    private long pos(final long index) {
        long m = index % dataLen;
        if (m < 0) {
            m += dataLen;
        }
        return HEADER_SKIP_BYTES + m;
    }

    /**
     * 写入一个载荷块（内部 GZIP 压缩），返回块起始逻辑 id（这块在整个"逻辑环"上占用的起始位置）。
     *
     * @throws IOException 载荷压缩后仍超过文件可容纳的单块大小时
     */
    public long writeMessage(final byte[] payload) throws IOException {
        if (payload == null) {
            return -1L;
        }
        // 端到端计时：压缩在锁外、IO 在锁内，两段合起来才是调用方感受到的单次写耗时
        final long startNanos = System.nanoTime();
        final byte[] compressed;
        try {
            compressed = gzip(payload);
        } catch (IOException e) {
            stats.recordIoError();
            throw e;
        }
        synchronized (lock) {
            if ((long) compressed.length + BLOCK_HEADER_BYTES > dataLen) {
                stats.recordOversizedRejected();
                throw new IOException("payload too large for capped file: compressed=" + compressed.length
                        + ", max=" + (dataLen - BLOCK_HEADER_BYTES));
            }
            final long blockStart = currIndex;
            try {
                writeAt(blockStart, longToBytes(compressed.length));
                writeAt(blockStart + BLOCK_HEADER_BYTES, compressed);
                currIndex = blockStart + BLOCK_HEADER_BYTES + compressed.length;
                writeHeader();
                dirty = true;
                maybeFsync();
            } catch (IOException e) {
                stats.recordIoError();
                throw e;
            }
            stats.recordWrite(payload.length, compressed.length, System.nanoTime() - startNanos);
            return blockStart;
        }
    }

    /**
     * 按逻辑 id 读回块（内部 GZIP 解压）。
     *
     * @return 原始载荷；id 尚未写入或被覆盖（过期）时返回 {@code null}
     */
    public byte[] readMessage(final long id) throws IOException {
        final long startNanos = System.nanoTime();
        synchronized (lock) {
            if (id < 0 || id >= currIndex) {
                // 从未写入（或 fsync 丢过游标）：不是环覆盖，故 expired=false
                stats.recordMiss(false);
                return null;
            }
            final long smallestNonOverwrittenId = Math.max(0L, currIndex - dataLen);
            // 与 pos() 配合：pos() 决定"往哪写"（取模绕圈），本窗口判定决定"能不能读"。
            // 物理位置被新块复用后，旧 id 若不做这道拦截就会读到新块的数据（脏读最坏路径）。
            // 落在窗口内的 id 必然完整可读，窗口外的必然整块被覆盖，没有中间态。
            if (id < smallestNonOverwrittenId) {
                stats.recordMiss(true); // 已被环覆盖 → 过期（预期行为，非故障）
                return null; // 已被环覆盖 → 过期
            }
            try {
                final byte[] lenBytes = readAt(id, BLOCK_HEADER_BYTES);
                if (lenBytes == null) {
                    stats.recordMiss(false);
                    return null;
                }
                final long len = bytesToLong(lenBytes);
                if (len <= 0 || len + BLOCK_HEADER_BYTES > dataLen) {
                    stats.recordMiss(false);
                    return null;
                }
                final byte[] compressed = readAt(id + BLOCK_HEADER_BYTES, (int) len);
                if (compressed == null) {
                    stats.recordMiss(false);
                    return null;
                }
                final byte[] payload = gunzip(compressed);
                stats.recordRead(System.nanoTime() - startNanos);
                return payload;
            } catch (IOException e) {
                stats.recordIoError();
                throw e;
            }
        }
    }

    /** 当前写游标（只增）。 */
    public long getCurrIndex() {
        synchronized (lock) {
            return currIndex;
        }
    }

    /**
     * 数据区字节数（{@code sizeBytes - 16}），即环的"周长"。
     * <p>与 {@link #getCurrIndex()} 相除即得覆盖轮次——读口用它算"环写过几圈了"。</p>
     */
    public long getDataLenBytes() {
        return dataLen;
    }

    /** 环形文件路径（读口展示用，便于运维直接找到那个文件）。 */
    public String getFilePath() {
        return file.getAbsolutePath();
    }

    /** 最老幸存块的逻辑 id（{@code max(0, currIndex - dataLen)}），即当前可读窗口的左端。 */
    public long getOldestLiveIndex() {
        synchronized (lock) {
            return Math.max(0L, currIndex - dataLen);
        }
    }

    /** 已完整覆盖的轮次（{@code currIndex / dataLen}）：0 = 还没绕过一圈。 */
    public long getWrapCount() {
        synchronized (lock) {
            return dataLen <= 0L ? 0L : currIndex / dataLen;
        }
    }

    /**
     * 运行统计（压缩率 / 耗时 / 过期读 / fsync…）。
     * <p>暴露实例而非快照，是为了让上层存储能把它并进自己的读口快照；
     * 真正跨 ClassLoader 出扩展的仍然是 {@code snapshot()} 的 JDK 原生 Map。</p>
     */
    CappedFileStorageStats stats() {
        return stats;
    }

    /** 指定逻辑 id 是否已被覆盖（过期）。 */
    public boolean isOverwritten(final long id) {
        synchronized (lock) {
            if (id < 0 || id >= currIndex) {
                return false;
            }
            return id < Math.max(0L, currIndex - dataLen);
        }
    }

    /** 固定文件大小。 */
    public long getSizeBytes() {
        return sizeBytes;
    }

    // ============================ low-level wrap-aware IO

    private void writeAt(final long index, final byte[] bytes) throws IOException {
        long p = pos(index);
        int offset = 0;
        int remaining = bytes.length;
        while (remaining > 0) {
            final int chunk = (int) Math.min((long) remaining, sizeBytes - p);
            raf.seek(p);
            raf.write(bytes, offset, chunk);
            offset += chunk;
            remaining -= chunk;
            p = HEADER_SKIP_BYTES; // 回绕到数据区起点
        }
    }

    private byte[] readAt(final long index, final int len) throws IOException {
        final byte[] out = new byte[len];
        long p = pos(index);
        int offset = 0;
        int remaining = len;
        while (remaining > 0) {
            final int chunk = (int) Math.min((long) remaining, sizeBytes - p);
            raf.seek(p);
            raf.readFully(out, offset, chunk);
            offset += chunk;
            remaining -= chunk;
            p = HEADER_SKIP_BYTES;
        }
        return out;
    }

    private void maybeFsync() throws IOException {
        writesSinceFsync++;
        final long now = System.currentTimeMillis();
        if (writesSinceFsync >= FSYNC_EVERY_WRITES || now - lastFsyncTime >= FSYNC_INTERVAL_MS) {
            force();
        }
    }

    private void force() throws IOException {
        if (dirty) {
            raf.getChannel().force(false);
            dirty = false;
            stats.recordFsync();
        }
        writesSinceFsync = 0;
        lastFsyncTime = System.currentTimeMillis();
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (raf != null) {
                force();
                raf.close();
                raf = null;
            }
        }
    }

    // ============================ helpers

    private static byte[] longToBytes(final long v) {
        final byte[] b = new byte[8];
        b[0] = (byte) (v >>> 56);
        b[1] = (byte) (v >>> 48);
        b[2] = (byte) (v >>> 40);
        b[3] = (byte) (v >>> 32);
        b[4] = (byte) (v >>> 24);
        b[5] = (byte) (v >>> 16);
        b[6] = (byte) (v >>> 8);
        b[7] = (byte) v;
        return b;
    }

    private static long bytesToLong(final byte[] b) {
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[i] & 0xFFL);
        }
        return v;
    }

    private static byte[] gzip(final byte[] data) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, data.length / 2));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] gunzip(final byte[] data) throws IOException {
        final ByteArrayInputStream bis = new ByteArrayInputStream(data);
        try (GZIPInputStream gz = new GZIPInputStream(bis)) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, data.length * 2));
            final byte[] buf = new byte[4096];
            int r;
            while ((r = gz.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        }
    }
}
