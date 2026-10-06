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
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 扩展自有日志：写入与读尾部、轮转有界、文件缺失/被截断时的降级。
 *
 * <p>不走真实的 {@code io.github.fulizhe.otelstore} logger —— 每个用例用独立的 logger 名，
 * 免得测试之间互相抢 handler，也免得在共享 JVM 里留下指向已删临时目录的 handler。
 */
class SelfLogTest {

    private static final Charset CS = Charset.defaultCharset();

    @AfterEach
    void detach() {
        SelfLog.detach();
    }

    @Test
    @DisplayName("写进去的能按尾部读回来：要最后 N 行、按旧→新")
    void writesThenReadsTail(@TempDir final File dir) {
        final String name = "iotest.selflog.tail";
        SelfLog.attach(name, dir, 100_000L);
        final Logger log = quiet(name);
        for (int i = 1; i <= 250; i++) {
            log.info("line-" + i);
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
        final String name = "iotest.selflog.rotation";
        final long limit = 1024L;
        SelfLog.attach(name, dir, limit);
        final Logger log = quiet(name);
        for (int i = 1; i <= 300; i++) {
            log.info("ROLL-" + i);
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

    /** 取一个不把日志再吐到 stderr 的 logger（否则测试输出会被几百行淹没）。 */
    private static Logger quiet(final String name) {
        final Logger log = Logger.getLogger(name);
        log.setUseParentHandlers(false);
        return log;
    }
}
