package io.github.fulizhe.otelstore.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 扩展自有日志：写入与读尾部、轮转有界、文件缺失/被截断时的降级。
 *
 * <p>走的是 {@link SelfLog} 门面（{@code info}/{@code warn}），不是给 logger 挂 Handler ——
 * 后者在挂了 OTel agent 的环境里收不到记录（见 {@code SelfLog} 类注释）。
 *
 * <p>每个用例用独立的 logger 名并把它的 JUL 级别关掉：文件写入不依赖 JUL，
 * 关掉只是为了不让测试输出被几百行日志淹没。
 */
class SelfLogTest {

    private static final Charset CS = Charset.defaultCharset();

    @AfterEach
    void closeWriter() {
        SelfLog.close();
    }

    @Test
    @DisplayName("写进去的能按尾部读回来：要最后 N 行、按旧→新")
    void writesThenReadsTail(@TempDir final File dir) {
        final String name = quiet("iotest.selflog.tail");
        SelfLog.init(dir, 100_000L);
        for (int i = 1; i <= 250; i++) {
            SelfLog.info(name, "line-" + i);
        }

        final List<String> tail = SelfLog.tail(dir, 100);
        assertEquals(100, tail.size(), "要的是一百行：" + tail);
        assertTrue(tail.get(99).contains("line-250"), "最后一行是最新的：" + tail.get(99));
        assertTrue(tail.get(0).contains("line-151"), "第一行是一百行窗口的开头：" + tail.get(0));

        assertEquals(250, SelfLog.tail(dir, 500).size(), "行数不足上限时全给");
    }

    @Test
    @DisplayName("超过单文件上限必须轮转：留 1 份备份，且文件总量被管住")
    void rotationKeepsOneBackupAndBoundsSize(@TempDir final File dir) {
        final String name = quiet("iotest.selflog.rotation");
        final long limit = 1024L;
        SelfLog.init(dir, limit);
        for (int i = 1; i <= 300; i++) {
            SelfLog.info(name, "ROLL-" + i);
        }

        final File current = new File(dir, SelfLog.FILE_NAME);
        final File backup = new File(dir, SelfLog.BACKUP_FILE_NAME);
        assertTrue(current.isFile(), "当前文件必须存在");
        assertTrue(backup.isFile(), "超过上限要轮转出 1 份备份，而不是无限增长（或只截断自己）");

        final long total = current.length() + backup.length();
        assertTrue(total <= 2L * limit + 4096L,
                "单文件 1 MiB、只留 1 份备份 —— 总量必须被管住，实际 " + total);

        final List<String> tail = SelfLog.tail(dir, 500);
        assertTrue(hasLineContaining(tail, "ROLL-300"), "最新的行必须读得到：" + tail);
        assertFalse(hasLineContaining(tail, "ROLL-1"),
                "最早的行应已被轮转覆盖，不该还在尾部窗口里：" + tail);
    }

    @Test
    @DisplayName("带异常的告警：异常栈也写进文件")
    void warnWritesStackTrace(@TempDir final File dir) {
        final String name = quiet("iotest.selflog.throw");
        SelfLog.init(dir, 100_000L);
        SelfLog.warn(name, "坏了", new IllegalStateException("根因在这里"));

        final List<String> tail = SelfLog.tail(dir, 100);
        assertTrue(hasLineContaining(tail, "坏了"), tail.toString());
        assertTrue(hasLineContaining(tail, "IllegalStateException"), tail.toString());
        assertTrue(hasLineContaining(tail, "根因在这里"), tail.toString());
    }

    @Test
    @DisplayName("文件不存在 / 是空的 / 行数参数非法 → 空列表，不抛")
    void missingOrEmptyFileIsEmptyNotAnError(@TempDir final File dir) throws IOException {
        // 还不存在的文件
        assertEquals(0, SelfLog.tail(dir, 100).size());
        // 被外部删/截断成 0 字节
        final File f = new File(dir, SelfLog.FILE_NAME);
        Files.write(f.toPath(), new byte[0]);
        assertEquals(0, SelfLog.tail(dir, 100).size());
        // 参数非法
        assertEquals(0, SelfLog.tail(dir, 0).size());
        assertEquals(0, SelfLog.tail(null, 100).size());
    }

    @Test
    @DisplayName("按 \\n 切行并去掉行尾 \\r（Windows 的日志是 CRLF）")
    void stripsCarriageReturn(@TempDir final File dir) throws IOException {
        Files.write(new File(dir, SelfLog.FILE_NAME).toPath(), "a\r\nb\r\nc".getBytes(CS));
        assertEquals(Arrays.asList("a", "b", "c"), SelfLog.tail(dir, 10));
    }

    private static boolean hasLineContaining(final List<String> lines, final String needle) {
        for (final String line : lines) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 取一个不把日志再吐到 stderr 的 logger（文件写入不依赖它，只为测试输出干净）。 */
    private static String quiet(final String name) {
        final Logger log = Logger.getLogger(name);
        log.setUseParentHandlers(false);
        log.setLevel(Level.OFF);
        return name;
    }
}
