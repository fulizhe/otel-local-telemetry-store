package io.github.fulizhe.otelstore.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.model.KeyValue;
import io.github.fulizhe.otelstore.core.model.LogRecordEntry;
import io.github.fulizhe.otelstore.core.model.MetricPointEntry;
import io.github.fulizhe.otelstore.core.model.ResourceDescriptor;
import io.github.fulizhe.otelstore.core.model.SpanRecord;
import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 存储层的<b>不变量</b>测试，不是实现细节测试。
 *
 * <p>断言集中在四件"坏了会静默"的事上：
 * 载荷能不能原样读回、{@code payload_id} 为 NULL 时会不会被误读成第 0 块、
 * 淘汰是不是真的从最旧开始、三个信号是不是真的共用同一行 Resource。
 */
class LocalStoreTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 环要小：测试里 256 MiB 的稀疏文件既慢又占地方，而这里只需要能写几十块。 */
    private static LocalStoreConfig config(final File dataDir) {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put(LocalStoreConfig.PREFIX + "dataDir", dataDir.getAbsolutePath());
        p.put(LocalStoreConfig.PREFIX + "capped.traces.bytes", "1048576");
        p.put(LocalStoreConfig.PREFIX + "capped.logs.bytes", "1048576");
        return LocalStoreConfig.from(p);
    }

    private static ResourceDescriptor resource() {
        return new ResourceDescriptor(Collections.singletonList(KeyValue.of("service.name", "checkout")));
    }

    private static SpanRecord span(final String name, final String traceId, final byte[] payload) {
        return span(name, traceId, payload, resource());
    }

    private static SpanRecord span(final String name, final String traceId, final byte[] payload,
            final ResourceDescriptor resource) {
        return new SpanRecord(traceId, "0123456789abcdef", "", name, 2,
                1700000000000000000L, 1700000000000000500L, 0, null,
                "io.github.fulizhe.otelstore.demo", "1.0", resource, 1, 0, payload);
    }

    @Test
    @DisplayName("表头行进 H2、载荷进环，两边都能读回来")
    void spanRoundTrip(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = new LocalStore(config(dataDir), "test-roundtrip")) {
            final byte[] payload = "载荷-必须是原样".getBytes(UTF8);
            store.store(span("demo-span", "abcdef0123456789abcdef0123456789", payload));

            assertEquals(1, store.countSpans());
            final List<Map<String, Object>> rows = store.recentSpans(10);
            assertEquals(1, rows.size());
            final Map<String, Object> row = rows.get(0);
            assertEquals("demo-span", row.get("name"));
            assertEquals("abcdef0123456789abcdef0123456789", row.get("traceId"));
            assertEquals(Long.valueOf(2L), asLong(row.get("kind")));
            assertEquals(Long.valueOf(1L), asLong(row.get("attrCount")));

            final long id = asLong(row.get("id")).longValue();
            assertArrayEquals(payload, store.spanPayload(id),
                    "载荷必须能从环里原样读回");
        }
    }

    @Test
    @DisplayName("payload 为 null 时表头行照存，且不会被误读成环里第 0 块（ADR-2 第 1 条坑）")
    void nullPayloadIdIsNotBlockZero(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = new LocalStore(config(dataDir), "test-nullpayload")) {
            // 第一条没有载荷，第二条有 —— 若把 NULL 当成 0，第一条会读回第二条的内容
            store.store(span("no-payload", "00000000000000000000000000000001", null));
            final byte[] payload = "第二条的载荷".getBytes(UTF8);
            store.store(span("with-payload", "00000000000000000000000000000002", payload));

            final List<Map<String, Object>> rows = store.recentSpans(10);
            assertEquals(2, rows.size());
            final long noPayloadId = asLong(rows.get(1).get("id")).longValue();
            final long withPayloadId = asLong(rows.get(0).get("id")).longValue();

            assertNull(store.spanPayload(noPayloadId), "没有载荷必须读回 null");
            assertArrayEquals(payload, store.spanPayload(withPayloadId));
        }
    }

    @Test
    @DisplayName("超 max.payload.bytes 的载荷被拒，表头行仍然保住")
    void oversizedPayloadKeepsHeaderRow(@TempDir final File dataDir) throws Exception {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put(LocalStoreConfig.PREFIX + "dataDir", dataDir.getAbsolutePath());
        p.put(LocalStoreConfig.PREFIX + "capped.traces.bytes", "1048576");
        p.put(LocalStoreConfig.PREFIX + "max.payload.bytes", "64");
        try (LocalStore store = new LocalStore(LocalStoreConfig.from(p), "test-oversized")) {
            final byte[] tooBig = new byte[128];
            Arrays.fill(tooBig, (byte) 'x');
            store.store(span("big", "00000000000000000000000000000003", tooBig));

            assertEquals(1, store.countSpans(), "载荷被拒不该连带丢掉表头行");
            final long id = asLong(store.recentSpans(1).get(0).get("id")).longValue();
            assertNull(store.spanPayload(id));
            final Map<String, Object> ring = (Map<String, Object>) store.snapshot().get("traceRing");
            assertEquals(Long.valueOf(1L), ring.get("rejectedTooLarge"));
        }
    }

    @Test
    @DisplayName("超行数水位时从最旧开始淘汰，留下的都是新的")
    void fifoEvictionKeepsNewest(@TempDir final File dataDir) throws Exception {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put(LocalStoreConfig.PREFIX + "dataDir", dataDir.getAbsolutePath());
        p.put(LocalStoreConfig.PREFIX + "capped.traces.bytes", "1048576");
        p.put(LocalStoreConfig.PREFIX + "rows.traces", "3");
        try (LocalStore store = new LocalStore(LocalStoreConfig.from(p), "test-evict")) {
            for (int i = 0; i < 5; i++) {
                store.store(span("span-" + i, "0000000000000000000000000000000" + i,
                        ("payload-" + i).getBytes(UTF8)));
            }
            assertEquals(3, store.countSpans(), "存量必须回到水位");

            final List<String> names = new ArrayList<String>();
            for (final Map<String, Object> row : store.recentSpans(10)) {
                names.add(String.valueOf(row.get("name")));
            }
            assertEquals(Arrays.asList("span-4", "span-3", "span-2"), names);

            final Map<String, Object> evicted = (Map<String, Object>) store.snapshot().get("evicted");
            assertEquals(Long.valueOf(2L), evicted.get("spans"));
        }
    }

    @Test
    @DisplayName("三信号同一份 Resource 只占字典表一行，且 resource_id 相同")
    void resourceIsSharedAcrossSignals(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = new LocalStore(config(dataDir), "test-resource")) {
            store.store(span("s", "00000000000000000000000000000001", "p".getBytes(UTF8)));
            store.store(new LogRecordEntry("", "", 9, "INFO", 1L, 2L, "hello",
                    "scope", "1.0", resource(), 0, "lp".getBytes(UTF8)));
            store.store(new MetricPointEntry("m", null, "1", MetricPointEntry.MetricKind.GAUGE, 3L,
                    resource(), "scope", "1.0",
                    Collections.<KeyValue>emptyList(), 1.5d, 0L, Double.NaN, "double"));

            assertEquals(1, store.countResources(), "同一份 Resource 必须去重成一行");
            final long spanResource = asLong(store.recentSpans(1).get(0).get("resourceId")).longValue();
            final long logResource = asLong(store.recentLogs(1).get(0).get("resourceId")).longValue();
            final long metricResource =
                    asLong(store.recentMetricPoints("m", 1).get(0).get("resourceId")).longValue();
            assertEquals(spanResource, logResource, "ADR-2：三条路径必须指向字典表同一行");
            assertEquals(spanResource, metricResource);
            assertEquals("service.name=s:checkout", store.resourceAttributes(spanResource));
        }
    }

    @Test
    @DisplayName("属性相同但顺序不同的 Resource 命中同一行（规范化去重的意义就在这里）")
    void resourceDedupIgnoresOrder(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = new LocalStore(config(dataDir), "test-resource-order")) {
            final ResourceDescriptor one = new ResourceDescriptor(Arrays.asList(
                    KeyValue.of("service.name", "checkout"), KeyValue.of("host.name", "node-1")));
            final ResourceDescriptor two = new ResourceDescriptor(Arrays.asList(
                    KeyValue.of("host.name", "node-1"), KeyValue.of("service.name", "checkout")));
            store.store(span("a", "00000000000000000000000000000001", null, one));
            store.store(span("b", "00000000000000000000000000000002", null, two));
            assertEquals(1, store.countResources());
        }
    }

    @Test
    @DisplayName("同一个数据目录第二次打开：表头为空，环的覆盖轮次从 0 起算（ADR-4）")
    void restartClearsBothLayers(@TempDir final File dataDir) throws Exception {
        try (LocalStore first = new LocalStore(config(dataDir), "test-restart")) {
            for (int i = 0; i < 40; i++) {
                first.store(span("span-" + i, "0000000000000000000000000000000" + (i % 10),
                        ("payload-" + i).getBytes(UTF8)));
            }
            final Map<String, Object> ring = (Map<String, Object>) first.snapshot().get("traceRing");
            assertTrue(asLong(ring.get("currIndex")).longValue() > 0L, "第一个进程应该真的写过东西");
            assertNotNull(first.snapshot().get("spanRows"));
        }

        try (LocalStore second = new LocalStore(config(dataDir), "test-restart")) {
            assertEquals(0, second.countSpans(), "H2 内存库每次打开都是空的");
            final Map<String, Object> ring = (Map<String, Object>) second.snapshot().get("traceRing");
            assertEquals(Long.valueOf(0L), ring.get("currIndex"),
                    "环必须在启动时清零，否则上个进程的成绩被算成本进程的");
            assertEquals(Long.valueOf(0L), ring.get("wrapCount"));
            assertEquals(Long.valueOf(0L), ring.get("oldestLiveIndex"));
        }
    }

    @Test
    @DisplayName("环写满后最旧的载荷读不回来（过期），且过期与拒写分开计数")
    void overwrittenPayloadReadsBackNull(@TempDir final File dataDir) throws Exception {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put(LocalStoreConfig.PREFIX + "dataDir", dataDir.getAbsolutePath());
        // 只有 4 KiB 数据区：写几百块不可压缩的 1 KiB 载荷就必然绕圈
        p.put(LocalStoreConfig.PREFIX + "capped.traces.bytes", "4096");
        p.put(LocalStoreConfig.PREFIX + "max.payload.bytes", "2048");
        // 必须用**不可压缩**的内容：全 'z' 会被 gzip 压到几十字节，40 块写不满一圈，
        // 那样这个测试就变成"验证压缩率"而不是"验证覆盖"。
        final java.util.Random rnd = new java.util.Random(20261004L);
        final byte[][] chunks = new byte[100][];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = new byte[1024];
            rnd.nextBytes(chunks[i]);
        }

        try (LocalStore store = new LocalStore(LocalStoreConfig.from(p), "test-overwrite")) {
            final List<Long> ids = new ArrayList<Long>();
            for (int i = 0; i < chunks.length; i++) {
                store.store(span("span-" + i, "0000000000000000000000000000000" + (i % 10), chunks[i]));
                ids.add(Long.valueOf(asLong(store.recentSpans(1).get(0).get("id")).longValue()));
            }
            final Map<String, Object> ring = (Map<String, Object>) store.snapshot().get("traceRing");
            assertTrue(asLong(ring.get("wrapCount")).longValue() > 0L,
                    "100 KiB 写进 4 KiB 的环必然绕圈，实际 currIndex="
                            + ring.get("currIndex"));

            assertNull(store.spanPayload(ids.get(0).longValue()), "最早那条必然已被覆盖");
            assertNotNull(store.spanPayload(ids.get(ids.size() - 1).longValue()), "最新那条必须还在");
        }
    }

    @Test
    @DisplayName("换个看不见 H2 的 TCCL 也照样能开库（模拟 agent 启动时的环境）")
    void opensEvenWhenThreadContextClassLoaderCannotSeeH2(@TempDir final File dataDir) throws Exception {
        // 这条钉的是 2026-10-04 首次挂 agent 时踩到的坑：DriverManager 在类初始化时
        // 用 TCCL 扫一次 META-INF/services/java.sql.Driver，而 agent 在 main 线程上
        // 初始化我们时 TCCL 是 AppClassLoader —— 看不见 shade 进去的 H2，
        // 于是 "No suitable driver"，存储层开不起来。
        //
        // **这个用例证明不了那个 bug 已被修掉**：surefire 的 classloader 上有 H2，
        // DriverManager 那一次扫描早就注册成功了，换 TCCL 影响不到它。
        // 它能证明的是"我们不依赖 TCCL"—— 哪天有人改成靠 TCCL 找驱动，这里就会红。
        // 真正的验证只能是真跑一次 agent（见 notes/2026-10-04-verification-and-pitfalls.md）。
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        final ClassLoader blind = new ClassLoader(null) {
            // 故意不给 parent：连 bootstrap 之外什么都看不见，等价于 agent 视角
        };
        Thread.currentThread().setContextClassLoader(blind);
        try (LocalStore store = new LocalStore(config(dataDir), "test-tccl")) {
            store.store(span("under-blind-tccl", "00000000000000000000000000000007", "p".getBytes(UTF8)));
            assertEquals(1, store.countSpans());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static Long asLong(final Object o) {
        return o instanceof Number ? Long.valueOf(((Number) o).longValue()) : null;
    }
}
