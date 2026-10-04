package io.github.fulizhe.otelstore.core.storage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 环形载荷文件的运行统计（{@link CappedFileStorage} 的"自我观测"）。
 *
 * <p>
 * 口径对齐 Glowroot
 * <a href="https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/util/CappedDatabaseStats.java">CappedDatabaseStats</a>：
 * 累计压缩前后字节、累计写耗时、累计写次数，并派生压缩率与单次平均字节/耗时。
 * 本类在其上补了四项本项目特有的健康信号——<b>过期读、超限拒写、fsync 次数、IO 错误</b>：
 * 前者回答"写指针在走、读回却全是空"（环覆盖过头），后者回答"文件写不动了"。
 * </p>
 *
 * <p>
 * <b>纯内存、实例级、重启即失</b>：与 ADR-04 的 H2 内存层同语义。写指针本身
 * （{@code currIndex}）另在文件头 16B 持久化，与本统计无关——重启后指针接着走，本统计从零计。
 * </p>
 *
 * <p>
 * <b>线程模型</b>：写打点在存储写线程、读打点在查询线程、{@link #snapshot()} 在读口线程，
 * 三者不同线程，故所有方法 {@code synchronized}（无竞争时是偏向锁，单次纳秒级；
 * 换来的是不需要"读一份可能撕裂的组合快照"）。
 * </p>
 *
 * <p>
 * 快照一律<b>扁平成 JDK 原生类型</b>（{@code Map}/{@code Long}/{@code Double}），
 * 不把本类实例外泄——读口要跨 ClassLoader 交给JMX 或 HTTP 侧做序列化。
 * </p>
 */
public class CappedFileStorageStats {

    /** 成功写入的块数 */
    private long writeCount;
    /** 压缩前累计字节（原始 payload 之和） */
    private long totalBytesBeforeCompression;
    /** 压缩后累计字节（gzip 块载荷之和，不含 8B 块头） */
    private long totalBytesAfterCompression;
    /** 累计写耗时（端到端：压缩 + 加锁 + IO + 刷头 + fsync 节流判定） */
    private long totalWriteNanos;
    /** 成功读回的块数 */
    private long readCount;
    /** 累计读耗时 */
    private long totalReadNanos;
    /** 读回 {@code null} 的次数（不存在 / 已被环覆盖 / 荒谬长度），其中"已被覆盖"另计 {@link #expiredReadCount} */
    private long missReadCount;
    /** 读时被判定为"已被环覆盖"的次数 */
    private long expiredReadCount;
    /** 因压缩后仍超过环容量而被拒写的次数 */
    private long oversizedRejectedCount;
    /** 真正执行过 {@code force()} 的次数（节流后的实际落盘次数） */
    private long fsyncCount;
    /** IO 异常次数（写、读、fsync、close 累计） */
    private long ioErrorCount;

    /**
     * 记一次成功写。
     *
     * @param bytesBeforeCompression 原始 payload 字节
     * @param bytesAfterCompression  gzip 后块载荷字节
     * @param nanos                 端到端耗时
     */
    synchronized void recordWrite(final long bytesBeforeCompression, final long bytesAfterCompression,
            final long nanos) {
        writeCount++;
        totalBytesBeforeCompression += bytesBeforeCompression;
        totalBytesAfterCompression += bytesAfterCompression;
        totalWriteNanos += nanos;
    }

    /** 记一次成功读回（已解压出原始 payload）。 */
    synchronized void recordRead(final long nanos) {
        readCount++;
        totalReadNanos += nanos;
    }

    /**
     * 记一次"读不到"。{@code expired} 为真表示原因是<b>已被环覆盖</b>（而非从未写入）。
     * <p>
     * 两个原因必须分开：过期是<b>预期行为</b>（环写满的正常结果），"从未写入"往往意味着
     * 写指针/fsync 出过问题——混成一个计数就看不出区别。
     * </p>
     */
    synchronized void recordMiss(final boolean expired) {
        missReadCount++;
        if (expired) {
            expiredReadCount++;
        }
    }

    /** 记一次超限拒写（压缩后仍塞不进数据区）。 */
    synchronized void recordOversizedRejected() {
        oversizedRejectedCount++;
    }

    /** 记一次实际落盘。 */
    synchronized void recordFsync() {
        fsyncCount++;
    }

    /** 记一次 IO 异常。 */
    synchronized void recordIoError() {
        ioErrorCount++;
    }

    public synchronized long getWriteCount() {
        return writeCount;
    }

    public synchronized long getReadCount() {
        return readCount;
    }

    public synchronized long getMissReadCount() {
        return missReadCount;
    }

    public synchronized long getExpiredReadCount() {
        return expiredReadCount;
    }

    public synchronized long getOversizedRejectedCount() {
        return oversizedRejectedCount;
    }

    public synchronized long getFsyncCount() {
        return fsyncCount;
    }

    public synchronized long getIoErrorCount() {
        return ioErrorCount;
    }

    /** 累计写耗时（毫秒）。 */
    public synchronized double getTotalWriteMillis() {
        return totalWriteNanos / 1000000.0d;
    }

    /** 累计读耗时（毫秒）。 */
    public synchronized double getTotalReadMillis() {
        return totalReadNanos / 1000000.0d;
    }

    /**
     * 压缩率 = {@code (压缩前 - 压缩后) / 压缩前}；无写入时为 {@code 0}。
     * <p>分母为 0 时返回 0 而不是 NaN/Infinity——读口要的是能直接渲染的数字。</p>
     */
    public synchronized double getCompressionRatio() {
        if (totalBytesBeforeCompression <= 0L) {
            return 0d;
        }
        return (totalBytesBeforeCompression - totalBytesAfterCompression) / (double) totalBytesBeforeCompression;
    }

    /** 单次写入的平均压缩前字节；无写入时为 {@code 0}。 */
    public synchronized double getAverageBytesPerWriteBeforeCompression() {
        return writeCount == 0L ? 0d : totalBytesBeforeCompression / (double) writeCount;
    }

    /** 单次写入的平均压缩后字节（即实测单块载荷 S 的运行值）；无写入时为 {@code 0}。 */
    public synchronized double getAverageBytesPerWriteAfterCompression() {
        return writeCount == 0L ? 0d : totalBytesAfterCompression / (double) writeCount;
    }

    /** 单次写入的平均耗时（毫秒）；无写入时为 {@code 0}。 */
    public synchronized double getAverageMillisPerWrite() {
        return writeCount == 0L ? 0d : totalWriteNanos / (1000000d * writeCount);
    }

    /** 单次读回的平均耗时（毫秒）；无读回时为 {@code 0}。 */
    public synchronized double getAverageMillisPerRead() {
        return readCount == 0L ? 0d : totalReadNanos / (1000000d * readCount);
    }

    /**
     * 组合快照（{@code LinkedHashMap}，保序便于读口直接渲染）。
     * <p>全程持锁取一致的组合值——否则会拿到"写次数已加、字节数还没加"的撕裂快照。</p>
     */
    public synchronized Map<String, Object> snapshot() {
        final Map<String, Object> m = new LinkedHashMap<String, Object>(24);
        m.put("writeCount", Long.valueOf(writeCount));
        m.put("readCount", Long.valueOf(readCount));
        m.put("missReadCount", Long.valueOf(missReadCount));
        m.put("expiredReadCount", Long.valueOf(expiredReadCount));
        m.put("oversizedRejectedCount", Long.valueOf(oversizedRejectedCount));
        m.put("fsyncCount", Long.valueOf(fsyncCount));
        m.put("ioErrorCount", Long.valueOf(ioErrorCount));
        m.put("totalBytesBeforeCompression", Long.valueOf(totalBytesBeforeCompression));
        m.put("totalBytesAfterCompression", Long.valueOf(totalBytesAfterCompression));
        m.put("totalWriteMillis", Double.valueOf(getTotalWriteMillis()));
        m.put("totalReadMillis", Double.valueOf(getTotalReadMillis()));
        m.put("compressionRatio", Double.valueOf(getCompressionRatio()));
        m.put("avgBytesPerWriteBefore", Double.valueOf(getAverageBytesPerWriteBeforeCompression()));
        m.put("avgBytesPerWriteAfter", Double.valueOf(getAverageBytesPerWriteAfterCompression()));
        m.put("avgMillisPerWrite", Double.valueOf(getAverageMillisPerWrite()));
        m.put("avgMillisPerRead", Double.valueOf(getAverageMillisPerRead()));
        return m;
    }
}
