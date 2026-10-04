package io.github.fulizhe.otelstore.core.storage;

import java.io.Closeable;
import java.io.File;
import io.github.fulizhe.otelstore.core.util.ThrottledLogger;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个信号的载荷环：环形文件 + 单块上限 + 启动时重置。
 *
 * <p>{@link CappedFileStorage} 提供的是"环形文件怎么写怎么读"，本类补上三件它不该管的事：
 *
 * <ol>
 *   <li><b>启动时显式重置</b>（ADR-4）。H2 内存模式重启即空，而环的文件头跨进程持久；
 *       不清零就会把上个进程的 {@code currIndex}、覆盖轮次算成本进程的成绩。</li>
 *   <li><b>单块上限</b>（{@code max.payload.bytes}）。环本身只拒绝"压缩后塞不进整个数据区"的块，
 *       那个上限大得没有实际意义；先按配置挡一道，才不会让一条巨型 payload 挤掉后面几千条。</li>
 *   <li><b>返回值口径</b>：本类返回 {@code >= 0} 的逻辑块 id，或 {@code -1} 表示"没写进去"。
 *       调用方据此决定 {@code payload_id} 写 NULL —— 而 {@code -1} 与"合法的首个偏移 0"
 *       必须能区分开，这是 ADR-2 第 1 条坑。</li>
 * </ol>
 *
 * <p>无状态可共享：{@code CappedFileStorage} 自带一把锁，三个 drainer 线程各写各的环，
 * 不需要外层再加锁。
 */
final class PayloadRing implements Closeable {

    /** "没写进去"。与 0（合法的首个逻辑偏移）严格区分。 */
    static final long NO_BLOCK = -1L;

    private final String signal;
    private final File file;
    private final long maxPayloadBytes;
    private final CappedFileStorage storage;

    private final AtomicLong rejectedTooLarge = new AtomicLong();
    private final AtomicLong rejectedError = new AtomicLong();
    private final AtomicLong expiredReadCount = new AtomicLong();

    /**
     * @param signal 信号名（traces / logs），只用于日志与快照
     * @param file   环文件路径；父目录必须已存在
     */
    PayloadRing(final String signal, final File file, final long sizeBytes, final long maxPayloadBytes)
            throws IOException {
        this.signal = signal;
        this.file = file;
        this.maxPayloadBytes = maxPayloadBytes;
        this.storage = new CappedFileStorage(file, sizeBytes);
        // ADR-4：每次启动数据目录都是空的，环也一起清。上一进程写过的块没人引用了。
        this.storage.reset();
    }

    /**
     * 写一个载荷块。
     *
     * @return 块 id；{@link #NO_BLOCK} 表示没写进去（超单块上限或 IO 失败）
     */
    long write(final byte[] payload) {
        if (payload == null) {
            return NO_BLOCK;
        }
        if (payload.length > maxPayloadBytes) {
            rejectedTooLarge.incrementAndGet();
            ThrottledLogger.warn("payload-too-large-" + signal,
                    signal + " 单条载荷 " + payload.length + "B 超过 max.payload.bytes="
                            + maxPayloadBytes + "B，表头行照存、载荷丢弃");
            return NO_BLOCK;
        }
        try {
            return storage.writeMessage(payload);
        } catch (final IOException e) {
            rejectedError.incrementAndGet();
            ThrottledLogger.warn("payload-write-failed-" + signal,
                    signal + " 载荷写环失败，表头行照存、载荷丢弃", e);
            return NO_BLOCK;
        }
    }

    /**
     * 读回一个载荷块。
     *
     * @return 原始载荷；{@code null} 表示已被环覆盖（<b>过期是预期行为</b>，不是故障）
     */
    byte[] read(final long id) {
        if (id == NO_BLOCK) {
            return null;
        }
        try {
            final byte[] out = storage.readMessage(id);
            if (out == null && storage.isOverwritten(id)) {
                expiredReadCount.incrementAndGet();
            }
            return out;
        } catch (final IOException e) {
            ThrottledLogger.warn("payload-read-failed-" + signal, signal + " 载荷读环失败", e);
            return null;
        }
    }

    /** 环写过几圈（0 = 还没绕过一整圈）。重置后从 0 起算。 */
    long wrapCount() {
        return storage.getWrapCount();
    }

    /** 最老幸存块的逻辑 id，即当前可读窗口左端。 */
    long oldestLiveIndex() {
        return storage.getOldestLiveIndex();
    }

    /** 环文件路径（快照展示用，便于运维直接找到那个文件）。 */
    String filePath() {
        return file.getAbsolutePath();
    }

    Map<String, Object> snapshot() {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("signal", signal);
        m.put("file", filePath());
        m.put("sizeBytes", Long.valueOf(storage.getSizeBytes()));
        m.put("currIndex", Long.valueOf(storage.getCurrIndex()));
        m.put("wrapCount", Long.valueOf(storage.getWrapCount()));
        m.put("oldestLiveIndex", Long.valueOf(storage.getOldestLiveIndex()));
        m.put("rejectedTooLarge", Long.valueOf(rejectedTooLarge.get()));
        m.put("rejectedError", Long.valueOf(rejectedError.get()));
        m.put("expiredReads", Long.valueOf(expiredReadCount.get()));
        m.put("fileStats", storage.stats().snapshot());
        return m;
    }

    @Override
    public void close() throws IOException {
        storage.close();
    }
}
