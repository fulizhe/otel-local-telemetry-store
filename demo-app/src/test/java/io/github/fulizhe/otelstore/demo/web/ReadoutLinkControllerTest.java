package io.github.fulizhe.otelstore.demo.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 读口黄页的读取口径。
 *
 * <p>为什么值得单测：这是"端口退让之后人怎么找到读口"的唯一入口，
 * 而它的失败模式全是"看起来像读口坏了"的半成品 —— 文件不在、文件是旧的、
 * 内容不是端口号。三种要分开说，别混成一句"读不到"。
 */
class ReadoutLinkControllerTest {

    private static File portFile(final File dir, final String content) throws Exception {
        final File f = new File(dir, ReadoutLinkController.PORT_FILE);
        Files.write(f.toPath(), content.getBytes(Charset.defaultCharset()));
        return f;
    }

    @Test
    @DisplayName("端口文件写的是默认端口时，给出可点的地址")
    void readsDefaultPort(@TempDir final File dir) throws Exception {
        portFile(dir, "17890\n");
        final Map<String, Object> m = ReadoutLinkController.describe(dir);
        assertEquals(Boolean.TRUE, m.get("found"));
        assertEquals(Integer.valueOf(17890), m.get("port"));
        assertEquals("http://localhost:17890/", m.get("url"));
    }

    @Test
    @DisplayName("退让后的端口要能读出来，并说明它不是默认值")
    void readsFallbackPortAndSaysSo(@TempDir final File dir) throws Exception {
        portFile(dir, "12580");
        final Map<String, Object> m = ReadoutLinkController.describe(dir);
        assertEquals(Integer.valueOf(12580), m.get("port"));
        assertTrue(String.valueOf(m.get("detail")).contains("退让"),
                "要说清这不是默认端口：" + m.get("detail"));
    }

    @Test
    @DisplayName("文件不在 → found=false 并说清可能的原因，而不是抛异常")
    void missingFileIsExplainedNotThrown(@TempDir final File dir) {
        final Map<String, Object> m = ReadoutLinkController.describe(new File(dir, "nope"));
        assertEquals(Boolean.FALSE, m.get("found"));
        assertTrue(String.valueOf(m.get("detail")).contains("dataDir"),
                "要说清可能是 dataDir 不是默认值：" + m.get("detail"));
    }

    @Test
    @DisplayName("内容不是端口号 → found=false 并说清拿到的是什么（半成品文件）")
    void garbageContentIsNotSilentlyZero(@TempDir final File dir) throws Exception {
        portFile(dir, "not-a-port");
        final Map<String, Object> m = ReadoutLinkController.describe(dir);
        assertEquals(Boolean.FALSE, m.get("found"));
        assertFalse(m.containsKey("port"), "认不出来就**不要**给一个端口，哪怕 0 也不要");
        assertTrue(String.valueOf(m.get("detail")).contains("not-a-port"),
                String.valueOf(m.get("detail")));
    }

    @Test
    @DisplayName("空文件 → found=false，不当成端口 0")
    void emptyFileIsNotPortZero(@TempDir final File dir) throws Exception {
        portFile(dir, "");
        final Map<String, Object> m = ReadoutLinkController.describe(dir);
        assertEquals(Boolean.FALSE, m.get("found"));
        assertFalse(m.containsKey("port"));
    }
}