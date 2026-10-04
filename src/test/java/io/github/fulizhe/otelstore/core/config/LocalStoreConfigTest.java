package io.github.fulizhe.otelstore.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            assertTrue(c.isAuthEnabled());
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
                "auth", "false",
                "token", "s3cret",
                "dataDir", "/var/lib/store",
                "capped.traces.bytes", "1048576",
                "capped.logs.bytes", "2097152",
                "max.payload.bytes", "4096",
                "rows.traces", "10",
                "rows.logs", "20"));

        assertEquals("127.0.0.1", c.getHost());
        assertEquals(19999, c.getPort());
        assertFalse(c.isAuthEnabled());
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
        assertTrue(c.isAuthEnabled());
        assertEquals(LocalStoreConfig.DEFAULT_CAPPED_TRACES_BYTES, c.getCappedTracesBytes());
        assertEquals(LocalStoreConfig.DEFAULT_CAPPED_LOGS_BYTES, c.getCappedLogsBytes());
        assertEquals(LocalStoreConfig.DEFAULT_MAX_PAYLOAD_BYTES, c.getMaxPayloadBytes());
        assertEquals(LocalStoreConfig.DEFAULT_ROWS_TRACES, c.getRowsTraces());
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