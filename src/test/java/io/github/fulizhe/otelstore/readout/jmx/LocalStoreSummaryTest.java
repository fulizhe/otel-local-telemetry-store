package io.github.fulizhe.otelstore.readout.jmx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.model.KeyValue;
import io.github.fulizhe.otelstore.core.model.ResourceDescriptor;
import io.github.fulizhe.otelstore.core.model.SpanRecord;
import io.github.fulizhe.otelstore.core.storage.LocalStore;
import java.io.File;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 读口的<b>结构性只读</b>与<b>不泄漏 token</b>两条底线。
 *
 * <p>其余的（数字对不对、文本排版好不好看）由更底层的 {@code LocalStoreTest} 与
 * {@code PipelineToStoreTest} 兜着 —— 这里只测"读口自己的"那部分责任。
 */
class LocalStoreSummaryTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static LocalStoreConfig config(final File dataDir, final String token) {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put(LocalStoreConfig.PREFIX + "dataDir", dataDir.getAbsolutePath());
        p.put(LocalStoreConfig.PREFIX + "capped.traces.bytes", "1048576");
        if (token != null) {
            p.put(LocalStoreConfig.PREFIX + "token", token);
        }
        return LocalStoreConfig.from(p);
    }

    private static SpanRecord span(final String name) {
        return new SpanRecord("abcdef0123456789abcdef0123456789", "0123456789abcdef", "", name, 2,
                1L, 2L, 0, null, "scope", "1.0",
                new ResourceDescriptor(Collections.singletonList(KeyValue.of("service.name", "checkout"))),
                0, 0, ("payload-" + name).getBytes(UTF8));
    }

    @Test
    @DisplayName("计数属性与最近行都对得上")
    void countsAndRowsAreReadThrough(@TempDir final File dataDir) throws Exception {
        final LocalStoreConfig cfg = config(dataDir, null);
        try (LocalStore store = new LocalStore(cfg)) {
            store.store(span("a"));
            store.store(span("b"));

            final LocalStoreSummary summary =
                    new LocalStoreSummary(cfg, store, new java.util.function.Supplier<Map<String, Object>>() {
                        @Override
                        public Map<String, Object> get() {
                            final Map<String, Object> m = new LinkedHashMap<String, Object>();
                            m.put("traces", "offered=2 drained=2 dropped=0 sinkErrors=0 backlog=0");
                            return m;
                        }
                    });

            assertEquals(2, summary.spanRows());
            assertEquals(1, summary.resourceRows());
            assertTrue(summary.recentSpans(10).contains("a"));
            assertTrue(summary.recentSpans(10).contains("resource_dict"),
                    "resource_id 应当在读口就地展开，否则排查得多跳一步");
            assertTrue(summary.recentSpans(10).contains("service.name=s:checkout"));
            assertEquals(0, summary.logRows());
            assertEquals(0, summary.metricRows());

            final String snapshot = summary.summary();
            assertTrue(snapshot.contains("spanRows=2"), snapshot);
            assertTrue(snapshot.contains("offered=2 drained=2"), "队列侧快照要拼进来：" + snapshot);
        }
    }

    @Test
    @DisplayName("token 绝不出现在任何读口输出里")
    void tokenNeverLeaks(@TempDir final File dataDir) throws Exception {
        final LocalStoreConfig cfg = config(dataDir, "super-secret-token-value");
        try (LocalStore store = new LocalStore(cfg)) {
            store.store(span("a"));
            final LocalStoreSummary summary =
                    new LocalStoreSummary(cfg, store, null);

            for (final String out : new String[]{summary.summary(), summary.config(),
                    summary.recentSpans(5), summary.recentLogs(5), summary.spansOfTrace("x"),
                    summary.recentMetricPoints("m", 5)}) {
                assertFalse(out.contains("super-secret-token-value"), "读口泄漏了 token：" + out);
            }
            assertTrue(summary.config().contains("token=***"), "只该出现掩码：" + summary.config());
        }
    }

    @Test
    @DisplayName("载荷出十六进制，长度单独给 —— 空串的两种含义要能分开")
    void payloadHexAndLength(@TempDir final File dataDir) throws Exception {
        final LocalStoreConfig cfg = config(dataDir, null);
        try (LocalStore store = new LocalStore(cfg)) {
            store.store(span("with-payload"));
            store.store(new SpanRecord("00000000000000000000000000000009", "0123456789abcdef", "",
                    "no-payload", 2, 1L, 2L, 0, null, "scope", "1.0",
                    new ResourceDescriptor(Collections.<KeyValue>emptyList()), 0, 0, null));

            final LocalStoreSummary summary = new LocalStoreSummary(cfg, store, null);
            final java.util.List<Map<String, Object>> rows = store.recentSpans(10);
            // recentSpans 是 id 倒序：最后存的那条（没有载荷）在前
            final long noPayload = ((Number) rows.get(0).get("id")).longValue();
            final long withPayload = ((Number) rows.get(1).get("id")).longValue();

            assertTrue(summary.spanPayloadHex(withPayload).length() > 0);
            assertEquals("payload-with-payload".getBytes(UTF8).length,
                    summary.spanPayloadLength(withPayload));
            assertEquals("payload-with-payload".length() * 2, summary.spanPayloadHex(withPayload).length(),
                    "十六进制长度是字节数的两倍");
            assertEquals("", summary.spanPayloadHex(noPayload), "没有载荷就是空串");
            assertEquals(-1, summary.spanPayloadLength(noPayload));
        }
    }

    @Test
    @DisplayName("存储层为 null 时读口只说事实，不假装有数据")
    void storeAbsentIsReportedHonestly(@TempDir final File dataDir) {
        final LocalStoreConfig cfg = config(dataDir, null);
        final LocalStoreSummary summary = new LocalStoreSummary(cfg, null, null);
        assertEquals(-1, summary.spanRows());
        assertEquals("存储层未就绪", summary.recentSpans(5));
        assertTrue(summary.summary().contains("store=null"), summary.summary());
    }

    @Test
    @DisplayName("limit 被夹住：读口是给「看一眼」用的，不是导出数据的")
    void limitIsClamped(@TempDir final File dataDir) throws Exception {
        final LocalStoreConfig cfg = config(dataDir, null);
        try (LocalStore store = new LocalStore(cfg)) {
            for (int i = 0; i < 250; i++) {
                store.store(span("span-" + i));
            }
            final LocalStoreSummary summary = new LocalStoreSummary(cfg, store, null);
            final String out = summary.recentSpans(100000);
            int rows = 0;
            for (final String line : out.split("\n")) {
                if (line.trim().startsWith("#")) {
                    rows++;
                }
            }
            assertEquals(200, rows, "上限就是 200，实际 " + rows);
        }
    }

    @Test
    @DisplayName("重复注册不抛异常，第二次返回 false")
    void registerIsIdempotent(@TempDir final File dataDir) throws Exception {
        final LocalStoreConfig cfg = config(dataDir, null);
        unregister();
        try (LocalStore store = new LocalStore(cfg)) {
            final boolean first = JmxReadout.register(cfg, store, null);
            final boolean second = JmxReadout.register(cfg, store, null);
            assertTrue(first, "首次注册应当成功");
            assertFalse(second, "同名 MBean 已存在，第二次应当返回 false 而不是抛异常");
        } finally {
            unregister();
        }
    }

    /**
     * 注销可能存在的注册。
     *
     * <p>必须先做：surefire 一个 JVM 跑全部测试，而别的用例（走 provider 的那些）早就把
     * 读口注册上了 —— 不先清掉，本用例的"首次注册"就不成立，测的其实是自己排在第几个。
     */
    private static void unregister() throws Exception {
        final javax.management.MBeanServer server =
                java.lang.management.ManagementFactory.getPlatformMBeanServer();
        final javax.management.ObjectName name = new javax.management.ObjectName(JmxReadout.OBJECT_NAME);
        if (server.isRegistered(name)) {
            server.unregisterMBean(name);
        }
    }
}
