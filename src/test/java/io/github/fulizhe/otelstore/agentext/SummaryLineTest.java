package io.github.fulizhe.otelstore.agentext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.model.KeyValue;
import io.github.fulizhe.otelstore.core.model.ResourceDescriptor;
import io.github.fulizhe.otelstore.core.model.SpanRecord;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 把"验收信号那一行"钉住。
 *
 * <p>这条行现在是 Phase 4b <b>唯一</b>不需要工具就能拿到的库内计数出口
 * （关停钩子靠不住，见 {@code SUMMARY_INTERVAL_SECONDS} 的注释），
 * 所以它的形状必须被测住 —— 否则某次重构把它改成了另一种写法，
 * 验收就会变成"日志里好像有数字但对不上"，而没人知道是格式变了。
 */
class SummaryLineTest {

    /** 抓日志用；只收本类那一个 logger，避免被别的测试的 JUL 输出干扰。 */
    private static final class Capture extends Handler {
        private final List<String> messages = new ArrayList<String>();

        @Override
        public void publish(final LogRecord record) {
            if (record != null && record.getMessage() != null) {
                messages.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static Map<String, String> props(final File dataDir) {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("dataDir", dataDir.getAbsolutePath());
        p.put("capped.traces.bytes", "1048576");
        p.put("capped.logs.bytes", "1048576");
        return p;
    }

    private static SpanRecord span(final String name) {
        return new SpanRecord("abcdef0123456789abcdef0123456789", "0123456789abcdef", "", name, 2,
                1L, 2L, 0, null, "scope", "1.0",
                new ResourceDescriptor(java.util.Collections.singletonList(KeyValue.of("service.name", "demo"))),
                0, 0, ("payload-" + name).getBytes(java.nio.charset.Charset.forName("UTF-8")));
    }

    @Test
    @DisplayName("汇总行同时给出队列计数与库内行数，且带 dataDir")
    void summaryLineCarriesBothQueuesAndRows(@TempDir final File dataDir) throws Exception {
        final Logger logger = Logger.getLogger(TapHub.class.getName());
        final Capture capture = new Capture();
        logger.addHandler(capture);
        try (TapHub hub = LocalStoreCustomizerProvider.create(props(dataDir))) {
            final io.github.fulizhe.otelstore.core.storage.LocalStore store = hub.store();
            assertNotNull(store, "存储层应当开得起来");
            store.store(span("a"));
            store.store(span("b"));

            hub.logSummary("测试");
        } finally {
            logger.removeHandler(capture);
        }

        final String line = capture.messages.get(capture.messages.size() - 1);
        assertTrue(line.contains("测试"), line);
        assertTrue(line.contains("dataDir="), line);
        assertTrue(line.contains("traces offered="), line);
        assertTrue(line.contains("sinkErrors="), line);
        assertTrue(line.contains("store spans=2"), "库内行数必须在这一行里，实际：" + line);
        assertTrue(line.contains("logs=0"), line);
        assertTrue(line.contains("metricPoints=0"), line);
        assertTrue(line.contains("resources=1"), "三信号共用的 Resource 字典行数也要能看出来：" + line);
    }

    @Test
    @DisplayName("存储层不可用时汇总行说 off，而不是伪装成 0")
    void summaryLineSaysOffWhenStoreAbsent(@TempDir final File dataDir) throws Exception {
        final File blocker = new File(dataDir, "blocked");
        assertTrue(blocker.createNewFile());
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("dataDir", blocker.getAbsolutePath());

        final Logger logger = Logger.getLogger(TapHub.class.getName());
        final Capture capture = new Capture();
        logger.addHandler(capture);
        try (TapHub hub = LocalStoreCustomizerProvider.create(p)) {
            assertNull(hub.store());
            hub.logSummary("测试");
        } finally {
            logger.removeHandler(capture);
        }

        final String line = capture.messages.get(capture.messages.size() - 1);
        assertTrue(line.contains("store off(未开起来)"),
                "「没存」与「存了 0 条」必须能分开，否则一次部署配错会被当成没有数据：" + line);
    }

    @Test
    @DisplayName("token 不出现在汇总行里")
    void summaryLineNeverLeaksToken(@TempDir final File dataDir) throws Exception {
        final Map<String, String> p = props(dataDir);
        p.put("token", "super-secret-token-value");

        final Logger logger = Logger.getLogger(TapHub.class.getName());
        final Capture capture = new Capture();
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
        try (TapHub hub = LocalStoreCustomizerProvider.create(p)) {
            hub.logSummary("测试");
        } finally {
            logger.removeHandler(capture);
        }

        for (final String message : capture.messages) {
            assertFalse(message.contains("super-secret-token-value"), "汇总行泄漏了 token：" + message);
        }
    }
}
