package io.github.fulizhe.otelstore.agentext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalStoreCustomizerProviderTest {

    private static void assertFlat(final Object value, final String path) {
        if (value instanceof Map) {
            for (final Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                assertFlat(e.getValue(), path + "." + e.getKey());
            }
        } else {
            assertTrue(value instanceof String || value instanceof Long || value instanceof Integer
                            || value instanceof Boolean,
                    path + " 应是 JDK 原生类型，实际 " + value.getClass().getName());
        }
    }

    private static void collectPaths(final Object value, final String prefix, final List<String> out) {
        if (value instanceof Map) {
            for (final Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                collectPaths(e.getValue(), prefix + "." + e.getKey(), out);
            }
        } else {
            out.add(prefix);
        }
    }

    @Test
    @DisplayName("没有配置时也能建 hub，全部走默认值")
    void createsHubWithDefaults() {
        try (TapHub hub = LocalStoreCustomizerProvider.create(
                (Map<String, String>) null)) {
            assertNotNull(hub);
            assertEquals(LocalStoreConfig.DEFAULT_DATA_DIR, hub.config().getDataDir());
            assertEquals(LocalStoreConfig.DEFAULT_QUEUE_CAPACITY, hub.config().getQueueCapacity());
        }
    }

    @Test
    @DisplayName("快照扁平化，且三条信号与合计计数都在")
    void snapshotIsFlatAndComplete() {
        try (TapHub hub = LocalStoreCustomizerProvider.create(
                (Map<String, String>) null)) {
            final Map<String, Object> s = hub.snapshot();
            for (final String key : new String[]{"dataDir", "queueCapacity", "traces", "logs", "metrics",
                    "droppedTotal", "sinkErrorTotal"}) {
                assertTrue(s.containsKey(key), "快照缺 " + key);
            }
            assertEquals("traces", ((Map<?, ?>) s.get("traces")).get("signal"));
            assertEquals("logs", ((Map<?, ?>) s.get("logs")).get("signal"));
            assertEquals("metrics", ((Map<?, ?>) s.get("metrics")).get("signal"));
            assertEquals(Long.valueOf(0L), s.get("droppedTotal"));
            assertEquals(Long.valueOf(0L), s.get("sinkErrorTotal"));

            final List<String> paths = new ArrayList<String>();
            collectPaths(s, "", paths);
            assertFlat(s, "snapshot");
            assertTrue(paths.size() > 10, "快照字段太少：" + paths);
        }
    }

    @Test
    @DisplayName("queue.capacity 生效，且三条队列都按它建")
    void queueCapacityIsHonoured() {
        final Map<String, String> props = new LinkedHashMap<String, String>();
        props.put("queue.capacity", "64");
        props.put("dataDir", "/tmp/otelstore-probe");

        try (TapHub hub = LocalStoreCustomizerProvider.create(props)) {
            assertEquals(64, hub.config().getQueueCapacity());
            assertEquals("/tmp/otelstore-probe", hub.config().getDataDir());
            assertEquals(64, hub.traces().capacity());
            assertEquals(64, hub.logs().capacity());
            assertEquals(64, hub.metrics().capacity());
        }
    }

    @Test
    @DisplayName("非法配置值回落默认值，不抛异常")
    void illegalValuesFallBack() {
        final Map<String, String> props = new LinkedHashMap<String, String>();
        props.put("queue.capacity", "0");
        props.put("rows.traces", "-5");
        props.put("capped.traces.bytes", "not-a-number");

        try (TapHub hub = LocalStoreCustomizerProvider.create(props)) {
            assertEquals(LocalStoreConfig.DEFAULT_QUEUE_CAPACITY, hub.config().getQueueCapacity());
            assertEquals(LocalStoreConfig.DEFAULT_ROWS_TRACES, hub.config().getRowsTraces());
            assertEquals(LocalStoreConfig.DEFAULT_CAPPED_TRACES_BYTES, hub.config().getCappedTracesBytes());
        }
    }

    @Test
    @DisplayName("自监控 scope 前缀表覆盖三个信号的已知来源")
    void selfTelemetryPrefixesCoverKnownScopes() {
        final String[] p = LocalStoreCustomizerProvider.SELF_TELEMETRY_PREFIXES;
        boolean trace = false;
        boolean logs = false;
        boolean runtime = false;
        boolean exporters = false;
        for (final String s : p) {
            trace |= s.equals("io.opentelemetry.sdk.trace");
            logs |= s.equals("io.opentelemetry.sdk.logs");
            runtime |= s.startsWith("io.opentelemetry.runtime-telemetry");
            exporters |= s.startsWith("io.opentelemetry.exporters");
        }
        assertTrue(trace, "漏了 sdk.trace");
        assertTrue(logs, "漏了 sdk.logs");
        assertTrue(runtime, "漏了 runtime-telemetry");
        assertTrue(exporters, "漏了 exporters");
    }
}