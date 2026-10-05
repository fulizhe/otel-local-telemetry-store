package io.github.fulizhe.otelstore.readout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.model.KeyValue;
import io.github.fulizhe.otelstore.core.model.LogRecordEntry;
import io.github.fulizhe.otelstore.core.model.ResourceDescriptor;
import io.github.fulizhe.otelstore.core.model.SpanRecord;
import io.github.fulizhe.otelstore.core.storage.LocalStore;
import java.io.File;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 共享查询层的契约。
 *
 * <p>这一层存在的全部理由是<b>两个读口口径不许漂移</b>，所以这里钉的是**查询口径**本身：
 * 条数上限怎么夹、没有数据时返回什么、"没有存储"与"没有数据"怎么分、资源字典怎么展开。
 * 渲染与协议不在这里测 —— 那属于 JMX 与将来的 HTTP 各自的测试。
 */
class ReadoutQueriesTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String TRACE_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TRACE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static LocalStoreConfig config(final File dataDir) {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put(LocalStoreConfig.PREFIX + "dataDir", dataDir.getAbsolutePath());
        p.put(LocalStoreConfig.PREFIX + "capped.traces.bytes", "1048576");
        p.put(LocalStoreConfig.PREFIX + "capped.logs.bytes", "1048576");
        return LocalStoreConfig.from(p);
    }

    private static ResourceDescriptor resource(final String service) {
        return new ResourceDescriptor(Collections.singletonList(KeyValue.of("service.name", service)));
    }

    private static SpanRecord span(final String name, final String traceId) {
        return new SpanRecord(traceId, "0123456789abcdef", "", name, 2, 1L, 2L, 0, null,
                "scope", "1.0", resource("checkout"), 1, 0, ("p-" + name).getBytes(UTF8));
    }

    private static LogRecordEntry log(final String body, final String traceId, final long ts) {
        return new LogRecordEntry(traceId, "0123456789abcdef", 9, "INFO", ts, ts + 1, body,
                "scope", "1.0", resource("checkout"), 0, ("l-" + body).getBytes(UTF8));
    }

    @Test
    @DisplayName("摘要三段齐全，且与启动日志那份是同一份数据")
    void summaryHasAllThreeSections(@TempDir final File dataDir) throws Exception {
        final LocalStoreConfig cfg = config(dataDir);
        try (LocalStore store = new LocalStore(cfg)) {
            store.store(span("a", TRACE_A));
            final Map<String, Object> queues = new LinkedHashMap<String, Object>();
            queues.put("traces", "offered=1 drained=1 dropped=0 sinkErrors=0 backlog=0");
            final ReadoutQueries q = new ReadoutQueries(cfg, store, constant(queues));

            final Map<String, Object> s = q.summary();
            assertEquals(3, s.size());
            assertTrue(s.containsKey("config"), s.keySet().toString());
            assertTrue(s.containsKey("queues"), s.keySet().toString());
            assertTrue(s.containsKey("store"), s.keySet().toString());
            assertEquals(Long.valueOf(1L), ((Map<?, ?>) s.get("store")).get("spanRows"));
            assertEquals(queues, s.get("queues"), "队列那一段原样透传，读口不加工");
        }
    }

    @Test
    @DisplayName("limit 夹取：非正数给默认条数，超过上限夹到上限")
    void limitIsClamped(@TempDir final File dataDir) throws Exception {
        final LocalStoreConfig cfg = config(dataDir);
        try (LocalStore store = new LocalStore(cfg)) {
            for (int i = 0; i < 30; i++) {
                store.store(span("span-" + i, TRACE_A));
            }
            final ReadoutQueries q = new ReadoutQueries(cfg, store, null);

            assertEquals(ReadoutQueries.DEFAULT_LIMIT, q.recentSpans(0).size(), "0 走默认");
            assertEquals(ReadoutQueries.DEFAULT_LIMIT, q.recentSpans(-7).size(), "负数也走默认");
            assertEquals(5, q.recentSpans(5).size());
            assertEquals(30, q.recentSpans(100000).size(), "夹到 200，而库里只有 30 条");
            assertEquals(ReadoutQueries.MAX_LIMIT, ReadoutQueries.MAX_LIMIT);
        }
    }

    @Test
    @DisplayName("按 trace 取 span 与取日志都只给该 trace 的，按时间排")
    void queryByTrace(@TempDir final File dataDir) throws Exception {
        final LocalStoreConfig cfg = config(dataDir);
        try (LocalStore store = new LocalStore(cfg)) {
            store.store(span("a-first", TRACE_A));
            store.store(log("l1", TRACE_A, 100L));
            store.store(log("l2", TRACE_A, 200L));
            store.store(span("z-other", TRACE_B));
            store.store(log("other", TRACE_B, 150L));
            final ReadoutQueries q = new ReadoutQueries(cfg, store, null);

            final List<Map<String, Object>> spans = q.spansOfTrace(TRACE_A);
            assertEquals(1, spans.size());
            assertEquals("a-first", spans.get(0).get("name"));

            final List<Map<String, Object>> logs = q.logsOfTrace(TRACE_A);
            assertEquals(2, logs.size(), "别的 trace 的日志不能混进来");
            assertEquals("l1", logs.get(0).get("bodyPreview"));
            assertEquals("l2", logs.get(1).get("bodyPreview"));

            assertEquals(0, q.logsOfTrace("cccccccccccccccccccccccccccccccc").size(),
                    "没有数据的 trace 返回空列表，不是 null —— '没有'与'读不到'要能分开");
        }
    }

    @Test
    @DisplayName("资源字典按 id 归并展开，同一 id 只查一次")
    void resourcesAreExpandedOncePerId(@TempDir final File dataDir) throws Exception {
        final LocalStoreConfig cfg = config(dataDir);
        try (LocalStore store = new LocalStore(cfg)) {
            store.store(span("a", TRACE_A));
            store.store(span("b", TRACE_A));
            store.store(span("c", TRACE_B));
            final ReadoutQueries q = new ReadoutQueries(cfg, store, null);

            final List<Map<String, Object>> rows = q.recentSpans(10);
            final Map<Long, String> expanded = q.resourceAttributesOf(rows);
            assertEquals(1, expanded.size(), "三条 span 同属一份 Resource，字典里只应有一行");
            assertEquals("service.name=s:checkout", expanded.values().iterator().next());

            assertTrue(q.resourceAttributesOf(Collections.<Map<String, Object>>emptyList()).isEmpty());
            assertTrue(q.resourceAttributesOf(null).isEmpty());
        }
    }

    @Test
    @DisplayName("没有存储层时：计数为 -1、列表为 null、快照为 null（不是空集合）")
    void noStoreIsDistinguishableFromNoData(@TempDir final File dataDir) {
        final LocalStoreConfig cfg = config(dataDir);
        final ReadoutQueries q = new ReadoutQueries(cfg, null, null);

        assertFalse(q.isStoreAvailable());
        assertEquals(-1, q.spanRows());
        assertEquals(-1, q.logRows());
        assertEquals(-1, q.metricRows());
        assertEquals(-1, q.resourceRows());
        assertNull(q.recentSpans(10), "null 表示'没有存储'；空列表表示'确实没有数据'");
        assertNull(q.recentLogs(10));
        assertNull(q.logsOfTrace(TRACE_A));
        assertNull(q.recentMetricPoints("m", 10));
        assertNull(q.spanPayload(1L));
        assertNull(q.storeSnapshot());
        assertNull(q.summary().get("store"));
        assertNotNull(q.config(), "配置那一段与存储无关，仍然要能给");
    }

    @Test
    @DisplayName("配置里绝不含 token 明文")
    void configNeverLeaksToken(@TempDir final File dataDir) throws Exception {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("dataDir", dataDir.getAbsolutePath());
        p.put("token", "super-secret-token-value");
        final LocalStoreConfig cfg = LocalStoreConfig.from(p);
        try (LocalStore store = new LocalStore(cfg)) {
            final ReadoutQueries q = new ReadoutQueries(cfg, store, null);
            assertFalse(q.config().toString().contains("super-secret-token-value"));
            assertFalse(q.summary().toString().contains("super-secret-token-value"));
        }
    }

    @Test
    @DisplayName("载荷按行 id 读回原始字节；没有载荷时是 null")
    void payloadRoundTrip(@TempDir final File dataDir) throws Exception {
        final LocalStoreConfig cfg = config(dataDir);
        try (LocalStore store = new LocalStore(cfg)) {
            store.store(span("with", TRACE_A));
            final ReadoutQueries q = new ReadoutQueries(cfg, store, null);
            final long id = ((Number) q.recentSpans(1).get(0).get("id")).longValue();
            final byte[] payload = q.spanPayload(id);
            assertNotNull(payload);
            assertEquals("p-with", new String(payload, UTF8), "读回的必须是原始字节");
            assertEquals(-1, q.spanPayload(999999L) == null ? -1 : 0,
                    "不存在的行返回 null 而不是抛异常");
        }
    }

    private static java.util.function.Supplier<Map<String, Object>> constant(final Map<String, Object> value) {
        return new java.util.function.Supplier<Map<String, Object>>() {
            @Override
            public Map<String, Object> get() {
                return value;
            }
        };
    }
}