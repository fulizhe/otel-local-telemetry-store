package io.github.fulizhe.otelstore.demo.web;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「读口在哪」—— demo-app 的黄页。
 *
 * <p><b>解决什么</b>：读口默认在 17890，但端口被占时会退到随机端口，
 * 那时人只从 README 上看到 17890 会一头雾水。扩展会把<b>实际端口</b>写进
 * {@code <dataDir>/otelstore.port}（ADR-6 第一节），本端点读那个文件、给出可点的地址。
 *
 * <p><b>这是一个 demo-app 的便利，不是产品特性。</b> 扩展连不上本应用的类、
 * 也不该往客户应用的页面上塞东西（三个 ClassLoader，见 ADR-1 与 R0 笔记）。
 * 产品侧的发现手段只有两样：{@code .port} 文件与启动日志。这里只是替人读了那个文件。
 *
 * <p><b>耦合点（要承认）</b>：本端点按<b>默认</b> dataDir 相对路径去找端口文件。
 * 若有人用 {@code -Dotel.localstore.dataDir=…} 改了扩展的目录，这里就找不到 ——
 * 那时用 {@code -Ddemo.readout.dataDir=…} 告诉本应用，或直接看启动日志。
 */
@RestController
public class ReadoutLinkController {

    /** 与扩展默认 dataDir 同名。运行时两边的 cwd 都是 demo-app，所以相对路径对得上。 */
    static final String DEFAULT_DATA_DIR = "otel-local-telemetry-store";
    static final String PORT_FILE = "otelstore.port";

    @GetMapping("/demo/readout")
    public Map<String, Object> readout() {
        return describe(dataDir());
    }

    private static File dataDir() {
        final String v = System.getProperty("demo.readout.dataDir");
        final String dir = v == null || v.trim().isEmpty() ? DEFAULT_DATA_DIR : v.trim();
        return new File(dir);
    }

    /**
     * 读出读口地址。**只做只读判断**，任何异常都变成一句说明，不抛。
     *
     * <p>{@code found=false} 的三种原因要分开说：文件不在（读口没起 / dataDir 不是默认）、
     * 内容不是端口（文件被别的东西占了 / 写了一半）、读不动（权限）。
     * 混成一句「读不到」会让人以为读口坏了。
     */
    static Map<String, Object> describe(final File dataDir) {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        final File f = new File(dataDir, PORT_FILE);
        m.put("portFile", f.getAbsolutePath());

        if (!f.isFile()) {
            m.put("found", Boolean.FALSE);
            m.put("detail", "还没找到端口文件。读口可能没起来，或扩展的 dataDir 不是默认值"
                    + "（默认 " + DEFAULT_DATA_DIR + "/" + PORT_FILE + "；"
                    + "若用 -Dotel.localstore.dataDir 改过，用 -Ddemo.readout.dataDir 告诉本应用）");
            return m;
        }
        final String text;
        try {
            final List<String> lines = Files.readAllLines(f.toPath(), Charset.defaultCharset());
            text = lines.isEmpty() ? "" : lines.get(0).trim();
        } catch (final IOException e) {
            m.put("found", Boolean.FALSE);
            m.put("detail", "端口文件读不动：" + e.getMessage());
            return m;
        }
        final int port;
        try {
            port = Integer.parseInt(text);
        } catch (final NumberFormatException e) {
            m.put("found", Boolean.FALSE);
            m.put("detail", "端口文件里的内容不是端口号（拿到「" + text + "」）—— 可能写了一半或被别的东西占了");
            return m;
        }
        m.put("found", Boolean.TRUE);
        m.put("port", Integer.valueOf(port));
        m.put("url", "http://localhost:" + port + "/");
        // 带上写文件的时刻：进程崩过、文件是上一次留下的，这种时候人会想知道它旧不旧
        m.put("modifiedAt", Long.valueOf(f.lastModified()));
        m.put("detail", "从端口文件读到" + (port == 17890
                ? "（就是默认端口）"
                : "（不是默认的 17890，说明启动时端口被占、读口退让了）"));
        return m;
    }
}