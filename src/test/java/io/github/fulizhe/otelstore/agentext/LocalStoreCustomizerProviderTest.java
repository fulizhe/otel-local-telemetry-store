package io.github.fulizhe.otelstore.agentext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStoreCustomizerProviderTest {

    /**
     * 建一份"数据目录指向临时目录 + 环很小"的配置。
     *
     * <p>Phase 4b 之后建 hub 会真的开存储层：默认 {@code dataDir} 是 {@code ./otel-local-telemetry-store}、
     * 两个环各 256 MiB，测试若用默认值就会在仓库里留下两个大稀疏文件。
     * <b>存储层一旦开起来，测试就必须自己指定目录</b> —— 这也是"扩展真的在写盘"的副作用。
     */
    private static Map<String, String> props(final File dataDir) {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("dataDir", dataDir.getAbsolutePath());
        p.put("capped.traces.bytes", "1048576");
        p.put("capped.logs.bytes", "1048576");
        return p;
    }

    private static void assertFlat(final Object value, final String path) {
        if (value instanceof Map) {
            for (final Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                assertFlat(e.getValue(), path + "." + e.getKey());
            }
            return;
        }
        // null 是合法的 JDK 值：读口用它表示"读不到 / 没有可读记录"，
        // 而且 ADR-6 要求这种"没有"必须是 null 而不是缺键（缺键分不清"这版没有"与"现在没有"）。
        if (value == null) {
            return;
        }
        // Double 也在内：环形文件的统计里有压缩率与平均耗时（Phase 4a 时快照里还没有它们）
        assertTrue(value instanceof String || value instanceof Long || value instanceof Integer
                        || value instanceof Double || value instanceof Boolean,
                path + " 应是 JDK 原生类型，实际 " + value.getClass().getName());
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
    @DisplayName("数据目录与环大小按配置生效（没有配置项时走默认值）")
    void createsHubWithConfiguredDataDir(@TempDir final File dataDir) {
        try (TapHub hub = LocalStoreCustomizerProvider.create(props(dataDir))) {
            assertNotNull(hub);
            assertEquals(dataDir.getAbsolutePath(), hub.config().getDataDir());
            assertEquals(1048576L, hub.config().getCappedTracesBytes());
            assertEquals(LocalStoreConfig.DEFAULT_QUEUE_CAPACITY, hub.config().getQueueCapacity());
            assertNotNull(hub.store(), "存储层应当开得起来");
            assertTrue(new File(dataDir, "traces.capped").isFile(), "环文件应当被建出来");
        }
    }

    @Test
    @DisplayName("快照扁平化，且三条信号与合计计数都在")
    void snapshotIsFlatAndComplete(@TempDir final File dataDir) {
        try (TapHub hub = LocalStoreCustomizerProvider.create(props(dataDir))) {
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
    void queueCapacityIsHonoured(@TempDir final File dataDir) {
        final Map<String, String> p = props(dataDir);
        p.put("queue.capacity", "64");

        try (TapHub hub = LocalStoreCustomizerProvider.create(p)) {
            assertEquals(64, hub.config().getQueueCapacity());
            assertEquals(dataDir.getAbsolutePath(), hub.config().getDataDir());
            assertEquals(64, hub.traces().capacity());
            assertEquals(64, hub.logs().capacity());
            assertEquals(64, hub.metrics().capacity());
        }
    }

    @Test
    @DisplayName("非法取值回落默认值，不抛异常")
    void illegalValuesFallBack(@TempDir final File dataDir) {
        final Map<String, String> props = new LinkedHashMap<String, String>();
        // dataDir 必须给：这个用例走 provider 的 create()，它会真的开存储层，
        // 而默认的 ./otel-local-telemetry-store 会在**仓库根目录**建出两个 256 MiB 的环文件。
        // Phase 4b 之前这里没给 dataDir/storage 还不存在，所以没出事；现在会。
        props.put("dataDir", dataDir.getAbsolutePath());
        props.put("queue.capacity", "0");
        props.put("rows.traces", "-5");
        props.put("capped.traces.bytes", "not-a-number");

        try (TapHub hub = LocalStoreCustomizerProvider.create(props)) {
            assertEquals(LocalStoreConfig.DEFAULT_QUEUE_CAPACITY, hub.config().getQueueCapacity());
            assertEquals(LocalStoreConfig.DEFAULT_ROWS_TRACES, hub.config().getRowsTraces());
            assertEquals(LocalStoreConfig.DEFAULT_CAPPED_TRACES_BYTES, hub.config().getCappedTracesBytes());
            assertNotNull(hub.store(), "非法值只影响那几项，存储层照常开起来");
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

    /**
     * agent 侧配置键必须带 {@code otel.localstore.} 前缀。
     *
     * <p>退役的写法传的是裸后缀（{@code props.getString("dataDir")}），而 OTel 的
     * {@code ConfigProperties.getString} 只做"小写 + {@code '-'→'.'}"、**不补前缀** ——
     * 于是取到的一直是 null，整个 {@code otel.localstore.*} 命名空间静默失效、全部回落默认值。
     * 真机探针就是这样发现的。这条把"用带前缀的键去取"钉住。
     */
    @Test
    @DisplayName("agent 侧配置用 otel.localstore.* 前缀读（裸后缀永远取不到）")
    void agentConfigIsReadWithTheOtelLocalstorePrefix(@TempDir final File dataDir) {
        final Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(LocalStoreConfig.PREFIX + "dataDir", dataDir.getAbsolutePath());
        // 环必须给小值：走默认的 256 MiB 会在临时目录里建两个大文件
        values.put(LocalStoreConfig.PREFIX + "capped.traces.bytes", "1048576");
        values.put(LocalStoreConfig.PREFIX + "capped.logs.bytes", "1048576");

        try (TapHub hub = LocalStoreCustomizerProvider.create(propsOf(values))) {
            assertEquals(dataDir.getAbsolutePath(), hub.config().getDataDir(),
                    "provider 必须用带前缀的键从 ConfigProperties 取值，否则就是默认 dataDir");
        }
    }

    /** 最小 {@link ConfigProperties} 桩：只按传入的精确键取值，其余一律 null / 空。 */
    private static ConfigProperties propsOf(final Map<String, String> values) {
        return new ConfigProperties() {
            @Override
            public String getString(final String name) {
                return values.get(name);
            }

            @Override
            public Boolean getBoolean(final String name) {
                return null;
            }

            @Override
            public Integer getInt(final String name) {
                return null;
            }

            @Override
            public Long getLong(final String name) {
                return null;
            }

            @Override
            public Double getDouble(final String name) {
                return null;
            }

            @Override
            public java.time.Duration getDuration(final String name) {
                return null;
            }

            @Override
            public List<String> getList(final String name) {
                return java.util.Collections.emptyList();
            }

            @Override
            public Map<String, String> getMap(final String name) {
                return java.util.Collections.emptyMap();
            }
        };
    }
}