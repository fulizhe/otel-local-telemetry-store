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
 * 读回的结果：字节 + 它到底是"没有"还是"过期了"。
 *
 * <p>分这两件事不是为了多一个字段，是因为它们的<b>处置完全不同</b>：
 * 过期是环形文件写满的预期结果（ADR-3 第 2 种形态），而"没有载荷"往往是写入时被拒
 * 或编码失败（ADR-3 第 3 种）。读口若只拿到一个 {@code null}，就只能把这两件事
 * 一起报成"载荷不可用" —— 而用户看到"不可用"会去查磁盘，磁盘其实完全正常。
 */
static final class Read {

    private final byte[] bytes;
    private final boolean overwritten;

    private Read(final byte[] bytes, final boolean overwritten) {
        this.bytes = bytes;
        this.overwritten = overwritten;
    }

    byte[] bytes() {
        return bytes;
    }

    /** 读回 null 时才有意义：这个位置是否已被新块覆盖。 */
    boolean overwritten() {
        return overwritten;
    }

    /**
     * <b>注意：{@code bytes == null} 时也必须把 {@code overwritten} 带出去。</b>
     *
     * <p>写成 {@code bytes == null ? NONE : new Read(bytes, overwritten)} 会把覆盖标志
     * 在最需要它的时候丢掉 —— 因为"读不到"恰恰就是 {@code bytes == null} 的时候。
     * 后果是"过期"全部退化成"没有载荷"，而这两种情况的处置完全相反
     * （ADR-3 第 2 种与第 3 种）。这个 bug 由 {@code HttpReadoutTest} 的
     * "四种原因分得开"那条用例抓出来。
     */
    static Read of(final byte[] bytes, final boolean overwritten) {
        return new Read(bytes, bytes != null || overwritten);
    }
}

    /**
     * 读一个载荷块，并说清它是"没有"还是"过期了"。
     *
     * <p>见 {@link Read}：这个区分是给读口用的，存储自己并不需要。
     */
    Read read(final long id) {
        if (id == NO_BLOCK) {
            return Read.of(null, false);
        }
        try {
            final byte[] out = storage.readMessage(id);
            if (out != null) {
                return Read.of(out, false);
            }
            final boolean overwritten = storage.isOverwritten(id);
            if (overwritten) {
                expiredReadCount.incrementAndGet();
            }
            return Read.of(null, overwritten);
        } catch (final IOException e) {
            ThrottledLogger.warn("payload-read-failed-" + signal, signal + " 载荷读环失败", e);
            return Read.of(null, false);
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
