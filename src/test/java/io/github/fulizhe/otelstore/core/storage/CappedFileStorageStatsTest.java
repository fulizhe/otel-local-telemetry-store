package io.github.fulizhe.otelstore.core.storage;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CappedFileStorageStats} 单元测试。
 *
 * <p>
 * 锁三件事：① 派生值（压缩率 / 单次均值）在分母为 0 时给 <b>0 而不是 NaN/Infinity</b>
 * ——读口要的是能直接渲染的数字；② "读不到"按<b>已被环覆盖</b>与<b>从未写入</b>分开计数；
 * ③ {@link CappedFileStorageStats#snapshot()} 只出 JDK 原生类型（它要跨 PluginClassLoader
 * 交给宿主侧 JSON 序列化，混进本类实例会炸）。
 * </p>
 */
public class CappedFileStorageStatsTest {

    @Test
    public void freshStats_derivationsAreZeroNotNaN() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        Assertions.assertEquals(0, s.getCompressionRatio(), 0d);
        Assertions.assertEquals(0d, s.getAverageBytesPerWriteBeforeCompression(), 0d);
        Assertions.assertEquals(0d, s.getAverageBytesPerWriteAfterCompression(), 0d);
        Assertions.assertEquals(0d, s.getAverageMillisPerWrite(), 0d);
        Assertions.assertEquals(0d, s.getAverageMillisPerRead(), 0d);
        Assertions.assertEquals(0L, s.getWriteCount());
        Assertions.assertEquals(0L, s.getMissReadCount());
    }

    @Test
    public void recordWrite_accumulatesBytesAndDerivesRatioAndAverages() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        s.recordWrite(1000L, 250L, 2_000_000L); // 2ms
        s.recordWrite(500L, 100L, 4_000_000L); // 4ms

        Assertions.assertEquals(2L, s.getWriteCount());
        // 压缩率 = (1500 - 350) / 1500
        Assertions.assertEquals(1150d / 1500d, s.getCompressionRatio(), 1e-9);
        Assertions.assertEquals(750d, s.getAverageBytesPerWriteBeforeCompression(), 1e-9);
        Assertions.assertEquals(175d, s.getAverageBytesPerWriteAfterCompression(), 1e-9);
        Assertions.assertEquals(3d, s.getAverageMillisPerWrite(), 1e-9);
        Assertions.assertEquals(6d, s.getTotalWriteMillis(), 1e-9);
    }

    @Test
    public void recordRead_derivesAverageOnlyWhenReadsHappened() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        s.recordRead(1_000_000L);
        s.recordRead(3_000_000L);
        Assertions.assertEquals(2L, s.getReadCount());
        Assertions.assertEquals(2d, s.getAverageMillisPerRead(), 1e-9);
        Assertions.assertEquals(4d, s.getTotalReadMillis(), 1e-9);
        // 没有写入时写侧均值仍必须是 0（不能被读侧数据带偏）
        Assertions.assertEquals(0d, s.getAverageMillisPerWrite(), 0d);
    }

    @Test
    public void recordMiss_splitsExpiredFromNeverWritten() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        s.recordMiss(true); // 已被环覆盖
        s.recordMiss(true);
        s.recordMiss(false); // 从未写入 / 长度荒谬

        Assertions.assertEquals(3L, s.getMissReadCount(), "两种原因合计");
        Assertions.assertEquals(2L, s.getExpiredReadCount(), "只有 2 次是环覆盖");
    }

    @Test
    public void healthCounters_accumulateIndependently() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        s.recordFsync();
        s.recordFsync();
        s.recordFsync();
        s.recordOversizedRejected();
        s.recordIoError();

        Assertions.assertEquals(3L, s.getFsyncCount());
        Assertions.assertEquals(1L, s.getOversizedRejectedCount());
        Assertions.assertEquals(1L, s.getIoErrorCount());
    }

    @Test
    public void snapshot_containsOnlyJdkNativeValues() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        s.recordWrite(1000L, 250L, 2_000_000L);
        s.recordRead(1_000_000L);
        s.recordMiss(true);

        final Map<String, Object> m = s.snapshot();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            final Object v = e.getValue();
            Assertions.assertTrue(
                    v instanceof Number || v instanceof String || v instanceof Boolean,
                    "值必须是 JDK 原生类型（不得外泄自定义类），实际: " + v.getClass());
        }
        Assertions.assertEquals(Long.valueOf(1L), m.get("writeCount"));
        Assertions.assertEquals(Long.valueOf(250L), m.get("totalBytesAfterCompression"));
        Assertions.assertEquals(Long.valueOf(1L), m.get("expiredReadCount"));
    }
}
