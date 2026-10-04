package io.github.fulizhe.otelstore.agentext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.storage.LocalStore;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 端到端（去掉 agent 那一层）：<b>真实 SDK 对象 → tap → 队列 → mapper → LocalStore</b>。
 *
 * <p>用真的 {@code SdkTracerProvider} / {@code SdkLoggerProvider} / {@code SdkMeterProvider} 造数据，
 * 而不是手搓 {@code SpanData}：SDK 1.66 没有公开的 {@code SpanData} 构造入口，
 * 而且真对象才带得出"Resource 有哪些属性""scope 叫什么"这些正是 ADR-2 在意的东西。
 *
 * <p>Phase 4a 只能验证"收到了多少"，这批测试验证的是另一件事：<b>真的存进去了</b>，
 * 且存进去的字节与 SDK 给的原对象对得上。
 */
class PipelineToStoreTest {

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private static LocalStoreConfig config(final File dataDir) {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put(LocalStoreConfig.PREFIX + "dataDir", dataDir.getAbsolutePath());
        p.put(LocalStoreConfig.PREFIX + "capped.traces.bytes", "4194304");
        p.put(LocalStoreConfig.PREFIX + "capped.logs.bytes", "4194304");
        p.put(LocalStoreConfig.PREFIX + "queue.capacity", "256");
        return LocalStoreConfig.from(p);
    }

    private static Map<String, String> props(final File dataDir) {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("dataDir", dataDir.getAbsolutePath());
        p.put("capped.traces.bytes", "4194304");
        p.put("capped.logs.bytes", "4194304");
        p.put("queue.capacity", "256");
        return p;
    }

    /** 带一个白名单键与一个明确禁止的键（完整命令行里带 token）。 */
    private static Resource leakyResource() {
        return Resource.create(Attributes.builder()
                .put(AttributeKey.stringKey("service.name"), "checkout")
                .put(AttributeKey.stringKey("host.name"), "node-1")
                .put(AttributeKey.stringKey("process.command_line"), "-Dapp.token=s3cr3t-value")
                .build());
    }

    /** 等 drainer 线程把队列排空。测试里绝不能死等 —— 到点就断言，给出可诊断的失败。 */
    private static void awaitRows(final LocalStore store, final String what, final int expected)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (count(store, what) < expected && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        assertEquals(expected, count(store, what),
                "等了 10 秒 " + what + " 还没到 " + expected + "；队列快照=" + store.snapshot());
    }

    private static int count(final LocalStore store, final String what) {
        try {
            if ("span".equals(what)) {
                return store.countSpans();
            }
            if ("log".equals(what)) {
                return store.countLogs();
            }
            return store.countMetrics();
        } catch (final Exception e) {
            return -1;
        }
    }

    @Test
    @DisplayName("span：SDK 造的真实 span 落库，表头与载荷都对得上")
    void spanReachesTheStore(@TempDir final File dataDir) throws Exception {
        try (TapHub hub = LocalStoreCustomizerProvider.create(props(dataDir))) {
            final LocalStore store = hub.store();
            assertNotNull(store, "存储层应该开得起来");

            final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .setResource(leakyResource())
                    .addSpanProcessor(new SpanTap(hub.traces()))
                    .build();
            try {
                tracerProvider.get("otelstore.demo").spanBuilder("GET /orders")
                        .setAttribute("http.method", "GET")
                        .startSpan()
                        .end();
            } finally {
                tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
            }

            awaitRows(store, "span", 1);
            final Map<String, Object> row = store.recentSpans(1).get(0);
            assertEquals("GET /orders", row.get("name"));
            assertEquals("otelstore.demo", row.get("scopeName"));
            assertNotNull(row.get("traceId"));
            assertEquals(Long.valueOf(1L), asLong(row.get("attrCount")));

            final byte[] payload = store.spanPayload(asLong(row.get("id")).longValue());
            assertNotNull(payload, "载荷必须落进环里");
            final Span decoded = Span.parseFrom(payload);
            assertEquals("GET /orders", decoded.getName());
            assertEquals(16, decoded.getTraceId().size(), "trace_id 在 OTLP 里是 16 字节");
            assertEquals(8, decoded.getSpanId().size());
            assertEquals(1, decoded.getAttributesCount());
            assertEquals("http.method", decoded.getAttributes(0).getKey());
        }
    }

    @Test
    @DisplayName("命令行不进表头也不进载荷 —— 白名单是唯一的密钥防线")
    void commandLineNeverReachesDisk(@TempDir final File dataDir) throws Exception {
        try (TapHub hub = LocalStoreCustomizerProvider.create(props(dataDir))) {
            final LocalStore store = hub.store();
            final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .setResource(leakyResource())
                    .addSpanProcessor(new SpanTap(hub.traces()))
                    .build();
            try {
                tracerProvider.get("otelstore.demo").spanBuilder("with-leaky-resource").startSpan().end();
            } finally {
                tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
            }
            awaitRows(store, "span", 1);

            final Map<String, Object> row = store.recentSpans(1).get(0);
            final String dictionary = store.resourceAttributes(asLong(row.get("resourceId")).longValue());
            assertNotNull(dictionary);
            assertTrue(dictionary.contains("service.name=s:checkout"), "白名单内的键要留下：" + dictionary);
            assertFalse(dictionary.contains("s3cr3t"), "字典表里不能有命令行：" + dictionary);

            final byte[] payload = store.spanPayload(asLong(row.get("id")).longValue());
            assertFalse(new String(payload, LATIN1).contains("s3cr3t"),
                    "载荷里也不能有命令行：载荷同样落在磁盘上，活得比进程久");
        }
    }

    @Test
    @DisplayName("日志：SDK 真实 LogRecord 落库，trace 可空、正文预览在表头")
    void logReachesTheStore(@TempDir final File dataDir) throws Exception {
        try (TapHub hub = LocalStoreCustomizerProvider.create(props(dataDir))) {
            final LocalStore store = hub.store();
            final SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
                    .setResource(leakyResource())
                    .addLogRecordProcessor(new LogTap(hub.logs()))
                    .build();
            try {
                loggerProvider.get("otelstore.demo")
                        .logRecordBuilder()
                        .setBody("订单 42 处理完成")
                        .setSeverity(io.opentelemetry.api.logs.Severity.INFO)
                        .setSeverityText("INFO")
                        .emit();
            } finally {
                loggerProvider.shutdown().join(10, TimeUnit.SECONDS);
            }

            awaitRows(store, "log", 1);
            final Map<String, Object> row = store.recentLogs(1).get(0);
            assertEquals("订单 42 处理完成", row.get("bodyPreview"));
            assertEquals("INFO", row.get("severityText"));
            assertEquals(Long.valueOf(9L), asLong(row.get("severityNumber")), "INFO 在 OTLP 里是 9");
            assertNull(row.get("traceId"), "这条日志没有 span，上游就该是空的");

            final byte[] payload = store.logPayload(asLong(row.get("id")).longValue());
            assertNotNull(payload);
            assertTrue(new String(payload, LATIN1).contains("s3cr3t") == false, "载荷里不能有命令行");
        }
    }

    @Test
    @DisplayName("指标：一次采集按属性组合拆成多行，桶与分位数落进 detail")
    void metricReachesTheStore(@TempDir final File dataDir) throws Exception {
        try (TapHub hub = LocalStoreCustomizerProvider.create(props(dataDir))) {
            final LocalStore store = hub.store();
            final PeriodicMetricReader reader = PeriodicMetricReader.builder(
                    new MetricTap(hub.metrics(), LocalStoreCustomizerProvider.SELF_TELEMETRY_PREFIXES))
                    // 采集周期压到 200ms：测试等的是"数据到库"，不是"默认 60s 之后"。
                    // 生产值是 10s（LocalStoreCustomizerProvider.METRIC_INTERVAL_MS）。
                    .setInterval(200, TimeUnit.MILLISECONDS)
                    .build();
            final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                    .setResource(leakyResource())
                    .registerMetricReader(reader)
                    .build();
            try {
                final io.opentelemetry.api.metrics.LongCounter counter =
                        meterProvider.get("otelstore.demo").counterBuilder("orders.processed")
                                .setUnit("1").setDescription("处理过的订单数").build();
                counter.add(3, io.opentelemetry.api.common.Attributes.of(
                        AttributeKey.stringKey("region"), "east"));
                counter.add(5, io.opentelemetry.api.common.Attributes.of(
                        AttributeKey.stringKey("region"), "west"));
            } finally {
                meterProvider.shutdown().join(10, TimeUnit.SECONDS);
            }

            awaitRows(store, "metric", 2);
            final List<Map<String, Object>> rows = store.recentMetricPoints("orders.processed", 10);
            assertEquals(2, rows.size(), "两个属性组合 = 两行（ADR-2 的行单元）");
            for (final Map<String, Object> row : rows) {
                assertEquals("SUM", row.get("dataType"));
                assertEquals("otelstore.demo", row.get("scopeName"));
                assertEquals("long", row.get("detail"), "LongCounter 的 detail 是 long 形态标记");
                assertEquals("1", row.get("unit"));
                assertNotNull(row.get("attrKey"));
            }
            // 属性组合不同 → attrKey 必须不同，否则"按属性查时间序列"会串
            assertFalse(rows.get(0).get("attrKey").equals(rows.get(1).get("attrKey")));
        }
    }

    @Test
    @DisplayName("三信号同一份 Resource 只占字典表一行（ADR-2 最要紧的那条断言）")
    void threeSignalsShareOneResourceRow(@TempDir final File dataDir) throws Exception {
        try (TapHub hub = LocalStoreCustomizerProvider.create(props(dataDir))) {
            final LocalStore store = hub.store();

            final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .setResource(leakyResource())
                    .addSpanProcessor(new SpanTap(hub.traces()))
                    .build();
            final SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
                    .setResource(leakyResource())
                    .addLogRecordProcessor(new LogTap(hub.logs()))
                    .build();
            final PeriodicMetricReader reader = PeriodicMetricReader.builder(
                    new MetricTap(hub.metrics(), LocalStoreCustomizerProvider.SELF_TELEMETRY_PREFIXES))
                    .setInterval(200, TimeUnit.MILLISECONDS)
                    .build();
            final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                    .setResource(leakyResource())
                    .registerMetricReader(reader)
                    .build();
            try {
                tracerProvider.get("otelstore.demo").spanBuilder("s").startSpan().end();
                loggerProvider.get("otelstore.demo").logRecordBuilder().setBody("l").emit();
                meterProvider.get("otelstore.demo").counterBuilder("c").build().add(1);
            } finally {
                tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
                loggerProvider.shutdown().join(10, TimeUnit.SECONDS);
                meterProvider.shutdown().join(10, TimeUnit.SECONDS);
            }

            awaitRows(store, "span", 1);
            awaitRows(store, "log", 1);
            awaitRows(store, "metric", 1);
            assertEquals(1, store.countResources(), "三信号应当命中字典表同一行");

            final long spanResource = asLong(store.recentSpans(1).get(0).get("resourceId")).longValue();
            assertEquals(spanResource, asLong(store.recentLogs(1).get(0).get("resourceId")).longValue());
            assertEquals(spanResource,
                    asLong(store.recentMetricPoints("c", 1).get(0).get("resourceId")).longValue());
        }
    }

    @Test
    @DisplayName("同一个 trace 的多个 span 能按 id 查回来（详情页拼 trace 的前提）")
    void spansOfOneTraceComeBack(@TempDir final File dataDir) throws Exception {
        try (TapHub hub = LocalStoreCustomizerProvider.create(props(dataDir))) {
            final LocalStore store = hub.store();
            final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .setResource(leakyResource())
                    .addSpanProcessor(new SpanTap(hub.traces()))
                    .build();
            String traceId;
            try {
                final io.opentelemetry.api.trace.Span parent =
                        tracerProvider.get("otelstore.demo").spanBuilder("parent").startSpan();
                // makeCurrent() 只是为了让"当前 span"成为子 span 的父。
                // 主体代码里刻意不碰 io.opentelemetry.context.*（agent 会重定位它，ADR-1 原则 6）；
                // 这里能用是因为测试跑在普通 classpath 上、根本没有 agent，重定位不参与。
                try (io.opentelemetry.context.Scope ignored = parent.makeCurrent()) {
                    tracerProvider.get("otelstore.demo").spanBuilder("child").startSpan().end();
                }
                traceId = parent.getSpanContext().getTraceId();
                parent.end();
            } finally {
                tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
            }

            awaitRows(store, "span", 2);
            final List<Map<String, Object>> rows = store.spansOfTrace(traceId);
            assertEquals(2, rows.size(), "一个 trace 两个 span");
            final List<String> names = new ArrayList<String>();
            for (final Map<String, Object> row : rows) {
                names.add(String.valueOf(row.get("name")));
            }
            assertEquals(java.util.Arrays.asList("parent", "child"), names, "按开始时间排");
        }
    }

    @Test
    @DisplayName("存储层开不起来时退化成只计数，应用照常（不许把客户进程搞挂）")
    void brokenDataDirDegradesToCountingOnly(@TempDir final File dataDir) throws Exception {
        // 把 dataDir 指向一个**已被文件占住**的路径：建目录必然失败
        final File blocker = new File(dataDir, "blocked");
        assertTrue(blocker.createNewFile());
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("dataDir", blocker.getAbsolutePath());

        try (TapHub hub = LocalStoreCustomizerProvider.create(p)) {
            assertNull(hub.store(), "开不起来就该是 null，而不是抛异常");
            assertNull(hub.snapshot().get("store"), "快照里也要能看出没有存储");
            assertNotNull(hub.queuesSnapshot());
        }
    }

    @Test
    @DisplayName("OpenTelemetrySdk 门面也能驱动同一条管线（证明我们只依赖公开 API）")
    void worksThroughTheSdkFacade(@TempDir final File dataDir) throws Exception {
        try (TapHub hub = LocalStoreCustomizerProvider.create(props(dataDir))) {
            final LocalStore store = hub.store();
            final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .setResource(leakyResource())
                    .addSpanProcessor(new SpanTap(hub.traces()))
                    .build();
            final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
            try {
                sdk.getTracer("otelstore.demo").spanBuilder("via-sdk").startSpan().end();
            } finally {
                tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
            }
            awaitRows(store, "span", 1);
            assertEquals("via-sdk", store.recentSpans(1).get(0).get("name"));
        }
    }

    private static Long asLong(final Object o) {
        return o instanceof Number ? Long.valueOf(((Number) o).longValue()) : null;
    }
}
