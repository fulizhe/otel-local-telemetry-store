package io.github.fulizhe.otelstore.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalStoreConfigTest {

    private static Map<String, String> props(final String... kv) {
        final Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(LocalStoreConfig.PREFIX + kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("空配置与 null 都拿到全默认值")
    void defaults() {
        final LocalStoreConfig a = LocalStoreConfig.defaults();
        final LocalStoreConfig b = LocalStoreConfig.from(null);

        for (final LocalStoreConfig c : new LocalStoreConfig[]{a, b}) {
            assertEquals(LocalStoreConfig.DEFAULT_HOST, c.getHost());
            assertEquals(LocalStoreConfig.DEFAULT_PORT, c.getPort());
            // 默认不要 token（2026-10-04 决定，与初版相反）：本地自查不该被一道
            // "去文件里复制 token"挡住。这条断言写死，是为了将来有人"顺手"把它改回 true 时
            // 必须同时意识到那是**安全姿态的变更**，而不只是改个默认值。
            assertFalse(c.isAuthEnabled());
            assertNull(c.getToken());
            assertEquals(LocalStoreConfig.DEFAULT_DATA_DIR, c.getDataDir());
            assertEquals(LocalStoreConfig.DEFAULT_CAPPED_TRACES_BYTES, c.getCappedTracesBytes());
            assertEquals(LocalStoreConfig.DEFAULT_CAPPED_LOGS_BYTES, c.getCappedLogsBytes());
            assertEquals(LocalStoreConfig.DEFAULT_MAX_PAYLOAD_BYTES, c.getMaxPayloadBytes());
            assertEquals(LocalStoreConfig.DEFAULT_ROWS_TRACES, c.getRowsTraces());
            assertEquals(LocalStoreConfig.DEFAULT_ROWS_LOGS, c.getRowsLogs());
        }
    }

    @Test
    @DisplayName("合法取值覆盖默认值，且首尾空白被裁掉")
    void overrides() {
        final LocalStoreConfig c = LocalStoreConfig.from(props(
                "host", " 127.0.0.1 ",
                "port", "19999",
                "auth", "true",
                "token", "s3cret",
                "dataDir", "/var/lib/store",
                "capped.traces.bytes", "1048576",
                "capped.logs.bytes", "2097152",
                "max.payload.bytes", "4096",
                "rows.traces", "10",
                "rows.logs", "20"));

        assertEquals("127.0.0.1", c.getHost());
        assertEquals(19999, c.getPort());
        assertTrue(c.isAuthEnabled());
        assertEquals("s3cret", c.getToken());
        assertEquals("/var/lib/store", c.getDataDir());
        assertEquals(1048576L, c.getCappedTracesBytes());
        assertEquals(2097152L, c.getCappedLogsBytes());
        assertEquals(4096L, c.getMaxPayloadBytes());
        assertEquals(10, c.getRowsTraces());
        assertEquals(20, c.getRowsLogs());
    }

    @Test
    @DisplayName("非法取值全部回落默认值 —— 解析永不失败")
    void illegalValuesFallBack() {
        final LocalStoreConfig c = LocalStoreConfig.from(props(
                "host", "   ",
                "port", "not-a-number",
                "auth", "yes-please",
                "capped.traces.bytes", "-1",
                "capped.logs.bytes", "0",
                "max.payload.bytes", "12.5",
                "rows.traces", "9999999999"));

        assertEquals(LocalStoreConfig.DEFAULT_HOST, c.getHost());
        assertEquals(LocalStoreConfig.DEFAULT_PORT, c.getPort());
        // 非法值回落**新的**默认（不要 token），不是回落成 true
        assertEquals(LocalStoreConfig.DEFAULT_AUTH_ENABLED, c.isAuthEnabled());
        assertEquals(LocalStoreConfig.DEFAULT_CAPPED_TRACES_BYTES, c.getCappedTracesBytes());
        assertEquals(LocalStoreConfig.DEFAULT_CAPPED_LOGS_BYTES, c.getCappedLogsBytes());
        assertEquals(LocalStoreConfig.DEFAULT_MAX_PAYLOAD_BYTES, c.getMaxPayloadBytes());
        assertEquals(LocalStoreConfig.DEFAULT_ROWS_TRACES, c.getRowsTraces());
    }

    @Test
    @DisplayName("对外呈现的 dataDir 一律是绝对路径（相对路径在别人屏幕上没有意义）")
    void presentedDataDirIsAbsolute() {
        final LocalStoreConfig relative = LocalStoreConfig.from(props("dataDir", "./otel-local-telemetry-store"));
        assertEquals("./otel-local-telemetry-store", relative.getDataDir(),
                "配置对象里保留用户给的原值");
        assertTrue(new File(relative.getDataDirAbsolute()).isAbsolute(),
                "但对外呈现的必须是绝对路径，实际：" + relative.getDataDirAbsolute());
        assertEquals(relative.getDataDirAbsolute(), relative.describe().get("dataDir"),
                "describe() 是读口与日志看到的那一份，必须已是绝对路径");
        assertTrue(relative.getDataDirAbsolute().endsWith("otel-local-telemetry-store"),
                "绝对路径仍要指向同一个目录，实际：" + relative.getDataDirAbsolute());
    }

    @Test
    @DisplayName("describe() 绝不含 token 明文")
    void describeNeverLeaksToken() {
        final LocalStoreConfig explicit = LocalStoreConfig.from(props("token", "super-secret-value"));
        assertFalse(explicit.describe().toString().contains("super-secret-value"));
        assertFalse(explicit.toString().contains("super-secret-value"));
        assertEquals("***", explicit.describe().get("token"));

        final String generated = LocalStoreConfig.defaults().describe().get("token").toString();
        assertFalse(generated.contains("null"));
    }
}