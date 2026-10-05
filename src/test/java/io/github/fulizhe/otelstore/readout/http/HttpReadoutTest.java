package io.github.fulizhe.otelstore.readout.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.model.KeyValue;
import io.github.fulizhe.otelstore.core.model.MetricPointEntry;
import io.github.fulizhe.otelstore.core.model.ResourceDescriptor;
import io.github.fulizhe.otelstore.core.model.SpanRecord;
import io.github.fulizhe.otelstore.core.storage.LocalStore;
import io.github.fulizhe.otelstore.readout.ReadoutQueries;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 主缝：<b>真实 socket 上的 HTTP</b>。
 *
 * <p>起在 {@code 127.0.0.1:0}（让 OS 挑端口），用 {@link HttpURLConnection} 真发请求。
 * 一处覆盖路由、方法白名单、鉴权、响应形状、错误形状与端口退让 ——
 * 这些都是"只有真发一次请求才算验过"的东西。
 *
 * <p><b>已知局限，必须记住</b>：进程内全绿不等于挂 agent 时能跑。Phase 4b 已经踩过两次
 * 那种坑（一个靠上下文 ClassLoader、一个靠 ClassLoader 委托顺序）。所以这一批测试过后，
 * 验收仍然必须真挂一次 agent。
 */
class HttpReadoutTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static LocalStoreConfig config(final File dataDir, final int port, final boolean auth,
            final String token) {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put(LocalStoreConfig.PREFIX + "dataDir", dataDir.getAbsolutePath());
        p.put(LocalStoreConfig.PREFIX + "host", "127.0.0.1");
        p.put(LocalStoreConfig.PREFIX + "port", String.valueOf(port));
        p.put(LocalStoreConfig.PREFIX + "capped.traces.bytes", "1048576");
        p.put(LocalStoreConfig.PREFIX + "capped.logs.bytes", "1048576");
        p.put(LocalStoreConfig.PREFIX + "auth", String.valueOf(auth));
        if (token != null) {
            p.put(LocalStoreConfig.PREFIX + "token", token);
        }
        return LocalStoreConfig.from(p);
    }

    /** 建一个装了一条 span 的存储层。 */
    private static LocalStore storeWithOneSpan(final File dataDir) throws Exception {
        final LocalStore s = new LocalStore(config(dataDir, 0, false, null));
        s.store(new SpanRecord("abcdef0123456789abcdef0123456789", "0123456789abcdef", "", "demo-span", 2,
                1L, 2L, 0, null, "scope", "1.0",
                new ResourceDescriptor(Collections.singletonList(KeyValue.of("service.name", "demo"))),
                1, 0, "payload".getBytes(UTF8)));
        return s;
    }

    /** 起一个读口；端口传 0 表示让 OS 挑。 */
    private static HttpReadout start(final File dataDir, final int port, final boolean auth,
            final String token, final LocalStore store) throws IOException {
        final LocalStoreConfig cfg = config(dataDir, port, auth, token);
        return HttpReadout.start(cfg, new ReadoutQueries(cfg, store, null), dataDir);
    }

    private static final class Response {
        final int status;
        final String body;
        final String contentType;
        final String allow;

        Response(final int status, final String body, final String contentType, final String allow) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
            this.allow = allow;
        }
    }

    private static Response call(final int port, final String path, final String method,
            final String headerName, final String headerValue) throws IOException {
        final HttpURLConnection conn =
                (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod(method);
        if (headerName != null) {
            conn.setRequestProperty(headerName, headerValue);
        }
        final int status = conn.getResponseCode();
        final InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            final byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            in.close();
        }
        return new Response(status, new String(out.toByteArray(), UTF8),
                conn.getHeaderField("Content-Type"), conn.getHeaderField("Allow"));
    }

    private static Response get(final int port, final String path) throws IOException {
        return call(port, path, "GET", null, null);
    }

    private static Response get(final int port, final String path, final String headerValue)
            throws IOException {
        return call(port, path, "GET", HttpReadout.TOKEN_HEADER, headerValue);
    }

    private static String read(final File file) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), UTF8).trim();
    }

    @Test
    @DisplayName("/api/summary 默认可直接读，三段齐全")
    void summaryIsReadableByDefault(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            final Response r = get(readout.getActualPort(), "/api/summary");
            assertEquals(200, r.status);
            assertTrue(r.contentType.startsWith("application/json"), r.contentType);
            assertTrue(r.body.contains("\"config\""), r.body);
            assertTrue(r.body.contains("\"queues\""), r.body);
            assertTrue(r.body.contains("\"store\""), r.body);
            assertTrue(r.body.contains("\"spanRows\":1"), "库里有一行，表头就要报 1：" + r.body);
        }
    }

    @Test
    @DisplayName("端口被占 → 退到随机端口，且实际端口落进文件")
    void fallsBackToRandomPortAndWritesPortFile(@TempDir final File dataDir) throws Exception {
        try (ServerSocket keep = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            final int taken = keep.getLocalPort();
            try (LocalStore store = storeWithOneSpan(dataDir);
                 HttpReadout readout = start(dataDir, taken, false, null, store)) {
                assertNotEquals(taken, readout.getActualPort(),
                        "被占的端口不能被成功占用 —— 那说明退让没生效");
                assertTrue(readout.getActualPort() > 0, "退让后必须落在某个具体端口上");
                assertEquals(200, get(readout.getActualPort(), "/api/summary").status,
                        "退让之后读口要真的能用");

                final File portFile = new File(dataDir, ReadoutAccess.PORT_FILE);
                assertTrue(portFile.isFile(), "实际端口必须落盘，否则用户找不到它");
                assertEquals(String.valueOf(readout.getActualPort()), read(portFile));
            }
        }
    }

    @Test
    @DisplayName("端口文件每次覆盖写：第二次起读口不留上一次的端口")
    void portFileIsOverwrittenEachStart(@TempDir final File dataDir) throws Exception {
        final File portFile = new File(dataDir, ReadoutAccess.PORT_FILE);
        final int firstPort;
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            firstPort = readout.getActualPort();
            assertEquals(String.valueOf(firstPort), read(portFile));
        }
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            assertEquals(String.valueOf(readout.getActualPort()), read(portFile),
                    "残留的旧端口比没有文件更坏 —— 它会让用户连错地方");
            assertTrue(read(portFile).length() > 0);
        }
    }

    @Test
    @DisplayName("只接受 GET/HEAD：POST 等方法一律 405 且带 Allow 头")
    void onlyGetAndHeadAreAccepted(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            final int port = readout.getActualPort();
            // 本段已落地的端点：/、/api/summary、/api/traces、/api/logs、/api/metrics、/metrics
            for (final String path : new String[]{"/", "/api/summary", "/api/traces", "/api/logs",
                    "/api/metrics", "/metrics"}) {
                assertEquals(200, get(port, path).status, path + " 用 GET 应当可达");
            }
            // 还没实现的必须 404 而不是 200 空壳 —— 否则会以为它通了。
            // /api/self 与 /api/self-log 是后两段的事；/api/payload 则是**永远不提供**的那个
            // （ADR-6 第七节）
            for (final String path : new String[]{"/api/self", "/api/self-log", "/api/payload/1"}) {
                assertEquals(404, get(port, path).status, path + " 还没实现，必须是 404");
            }
            for (final String method : new String[]{"POST", "PUT", "DELETE", "OPTIONS"}) {
                final Response r = call(port, "/api/summary", method, null, null);
                assertEquals(405, r.status, method + " 应当被拒");
                assertEquals("GET, HEAD", r.allow, method + " 的 405 要说明允许什么");
            }
        }
    }

    @Test
    @DisplayName("没有的端点 → 404 且是结构化错误体，不含栈")
    void unknownEndpointIsStructured404(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            // 用 /api/payload 当"没有的端点"：它是 ADR-6 第七节**明确不提供**的那个，
            // 拿它来钉 404 顺带把那条否决也钉住了
            final Response r = get(readout.getActualPort(), "/api/payload/1");
            assertEquals(404, r.status);
            assertTrue(r.body.contains("\"error\":\"not_found\""), r.body);
            assertFalse(r.body.contains("\tat "), "响应里绝不能含栈：" + r.body);
            assertTrue(r.body.contains("adr-06"), "404 要指出去向，别让人猜：" + r.body);
        }
    }

    @Test
    @DisplayName("span 与日志列表：返回表头行，且每行带 Resource 原文")
    void listEndpointsReturnHeaderRowsWithResource(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            final int port = readout.getActualPort();

            final String spans = get(port, "/api/traces?limit=5").body;
            assertTrue(spans.startsWith("[") && spans.endsWith("]"), "要的是数组：" + spans);
            assertTrue(spans.contains("\"name\":\"demo-span\""), spans);
            assertTrue(spans.contains("\"traceId\":\"abcdef0123456789abcdef0123456789\""), spans);
            assertTrue(spans.contains("\"resource\":\"service.name=s:demo\""),
                    "每行要带上 Resource 的规范化文本，页面不必再发一次请求：" + spans);

            // 行里是 ADR-2 的表头列 + 末尾追加的 resource，**没有载荷**。
            // 断言用"不需要解析 JSON"的方式：前缀钉住列顺序，resource 是最后一个键
            //（它由 withResource 追加），再钉住没有叫 payload 的键（payloadId 是表头列，
            // 与"载荷"不是一回事）。
            assertTrue(spans.startsWith("[{\"id\":1,\"traceId\":\"abcdef"), spans);
            assertTrue(spans.contains("\"resource\":\"service.name=s:demo\"}"),
                    "resource 必须是最后一个键（由 withResource 追加）：" + spans);
            assertFalse(spans.contains("\"payload\":"), "载荷不进列表：载荷只在详情页按需解码");
            assertFalse(spans.contains("base64"), spans);
            assertFalse(spans.contains("6164"), "载荷字节（\"payload\" 的十六进制）不能出现在列表里：" + spans);

            final String logs = get(port, "/api/logs?limit=5").body;
            assertEquals("[]", logs.trim(), "没有日志时空数组，不是 404 也不是 null");
        }
    }

    @Test
    @DisplayName("没有数据返回 200 + 空数组；参数非法才 400")
    void emptyResultIsNotAnError(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            final int port = readout.getActualPort();
            final String missing = "ffffffffffffffffffffffffffffffff";
            assertEquals(200, get(port, "/api/traces?traceId=" + missing).status);
            assertEquals("[]", get(port, "/api/traces?traceId=" + missing).body.trim(),
                    "合法但库里没有 → 200 + 空数组");
            assertEquals("[]", get(port, "/api/logs?traceId=" + missing).body.trim());

            // 格式非法才 400：拿它去查一次索引是"用户还没发现 bug，读口先替他查了一轮库"
            for (final String bad : new String[]{"abc", "zzz", "123", missing + "00"}) {
                final Response r = get(port, "/api/traces?traceId=" + bad);
                assertEquals(400, r.status, "traceId=" + bad + " 应当被拒");
                assertTrue(r.body.contains("\"error\":\"bad_request\""), r.body);
            }
        }
    }

    @Test
    @DisplayName("/api/metrics：时间序列 + 结构化的 detail，而不是原样吐文本")
    void metricPointsExposeStructuredDetail(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir)) {
            store.store(new MetricPointEntry("http.server.duration", "请求耗时", "ms",
                    MetricPointEntry.MetricKind.HISTOGRAM, 1700000000000L, null, "io.demo", "1.0",
                    Collections.singletonList(KeyValue.of("route", "/orders")),
                    Double.NaN, 7L, 12.5d,
                    // 真实形状：3 个桶 → **2** 个边界（OTel 的 getBoundaries 永远比 getCounts 少一个）
                    "explicit;min=1.0;max=9.0;bounds=[2.0, 5.0];counts=[3, 4, 0]"));
            store.store(new MetricPointEntry("http.server.duration", "请求耗时", "ms",
                    MetricPointEntry.MetricKind.HISTOGRAM, 1700000001000L, null, "io.demo", "1.0",
                    Collections.singletonList(KeyValue.of("route", "/cart")),
                    Double.NaN, 1L, 1.0d,
                    "explicit;min=1.0;max=1.0;bounds=[1.0];counts=[1, 0]"));
            try (HttpReadout readout = start(dataDir, 0, false, null, store)) {
                final String body = get(readout.getActualPort(), "/api/metrics?name=http.server.duration")
                        .body;
                assertTrue(body.contains("\"flavor\":\"explicit\""),
                        "真实形状必须认得出来：" + body);
                assertTrue(body.contains("\"leBoundaries\":[\"2.0\",\"5.0\",\"+Inf\"]"),
                        "le 要含 +Inf（最后一个桶没有上界）：" + body);
                assertTrue(body.contains("\"cumulative\":[3,7,7]"), "累计计数：" + body);
                assertTrue(body.contains("\"metricCount\":7"), body);
                assertFalse(body.contains("explicit;min="), "不该把 detail 原文当结构吐出去：" + body);

                // 两个属性组合 = 两个点，各带自己的哈希
                final List<String> hashes = attrKeysOf(body);
                assertEquals(2, hashes.size(), "两个属性组合应当有两个不同的哈希：" + hashes);

                // 标量的形态标记要保留：整数计数不该被显示成浮点
                store.store(new MetricPointEntry("orders.processed", null, "1",
                        MetricPointEntry.MetricKind.SUM, 1700000002000L, null, "io.demo", "1.0",
                        Collections.emptyList(), 42.0d, 0L, Double.NaN, "long"));
                final String withScalar = get(readout.getActualPort(),
                        "/api/metrics?name=orders.processed").body;
                assertTrue(withScalar.contains("\"flavor\":\"long\""), withScalar);
                assertTrue(withScalar.contains("\"metricValue\":42"), withScalar);
            } finally {
                store.close();
            }
        }
    }

    @Test
    @DisplayName("/api/metrics 缺 name 时给最近的数据点；没有指标时空数组")
    void metricPointsWithoutNameGivesRecent(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            final int port = readout.getActualPort();
            assertEquals("[]", get(port, "/api/metrics").body.trim(), "没有指标点时是空数组");
            assertEquals("[]", get(port, "/api/metrics?name=never.existed").body.trim());
        }
    }

    @Test
    @DisplayName("/api/metrics 受鉴权保护，且 detail 认不出来时带原文")
    void metricPointsAuthAndUnknownDetail(@TempDir final File dataDir) throws Exception {
        final String token = "m-token";
        try (LocalStore store = storeWithOneSpan(dataDir)) {
            store.store(new MetricPointEntry("weird", null, null, MetricPointEntry.MetricKind.HISTOGRAM,
                    1700000000000L, null, "io.demo", "1.0", Collections.emptyList(),
                    Double.NaN, 3L, 1.0d, "某种没覆盖的形态"));
            assertEquals(1, store.countMetrics(), "这个点必须真的进库了，否则下面测的是空库");
            try (HttpReadout readout = start(dataDir, 0, true, token, store)) {
                final int port = readout.getActualPort();
                assertEquals(401, get(port, "/api/metrics").status);
                assertEquals(401, get(port, "/api/metrics", "wrong").status);

                // 先确认这个点确实在库里，再谈它的 detail 解析
                final String all = get(port, "/api/metrics", token).body;
                assertTrue(all.contains("weird"), "这个指标点应当能被查到：" + all);

                final String body = get(port, "/api/metrics?name=weird", token).body;
                assertTrue(body.contains("\"flavor\":\"unknown\""), body);
                assertTrue(body.contains("\"raw\":\"某种没覆盖的形态\""),
                        "认不出来时必须带原文，否则页面上什么都判断不了：" + body);
                assertFalse(body.contains("\"bounds\""), "认不出来就不给桶：" + body);
            } finally {
                store.close();
            }
        }
    }

    /** 从响应里取出所有出现过的 attr_key 值（去重、保持顺序）。 */
    private static List<String> attrKeysOf(final String json) {
        final List<String> out = new ArrayList<String>();
        final String needle = "\"attrKey\":\"";
        int i = json.indexOf(needle);
        while (i >= 0) {
            final int from = i + needle.length();
            final String v = json.substring(from, json.indexOf('"', from));
            if (!out.contains(v)) {
                out.add(v);
            }
            i = json.indexOf(needle, from);
        }
        return out;
    }

    /** 一条带**真实 OTLP protobuf 载荷**的 span —— 详情页要解的就是它。 */
    private static SpanRecord spanWithOtlpPayload(final String name, final String traceId) {
        final io.opentelemetry.proto.trace.v1.Span otlp =
                io.opentelemetry.proto.trace.v1.Span.newBuilder()
                                        .setTraceId(bytes(traceId))
                        .setSpanId(bytes("0123456789abcdef"))
                        .setName(name)
                        .addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
                                .setKey("http.method")
                                .setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder()
                                        .setStringValue("GET"))
                                .build())
                        .build();
        return new SpanRecord(traceId, "0123456789abcdef", "", name, 2, 1L, 2L, 0, null,
                "scope", "1.0", null, 1, 0, otlp.toByteArray());
    }

    private static byte[] hexToBytes(final String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    | Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    private static com.google.protobuf.ByteString bytes(final String hex) {
        return com.google.protobuf.ByteString.copyFrom(hexToBytes(hex));
    }

    @Test
    @DisplayName("详情：表头 + 载荷状态 + 解码后的内容")
    void detailExposesDecodedPayload(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir)) {
            store.store(spanWithOtlpPayload("with-otlp", "11111111111111111111111111111111"));
            try (HttpReadout readout = start(dataDir, 0, false, null, store)) {
                final int port = readout.getActualPort();
                final String id = String.valueOf(
                        ((Number) store.recentSpans(1).get(0).get("id")).longValue());

                final String body = get(port, "/api/traces/" + id).body;
                assertTrue(body.contains("\"name\":\"with-otlp\""), body);
                assertTrue(body.contains("\"payload\""), body);
                assertTrue(body.contains("\"available\":true"), body);
                assertTrue(body.contains("\"state\":\"AVAILABLE\""), body);
                assertTrue(body.contains("\"decoded\""), "真实 OTLP 载荷必须能解出来：" + body);
                assertTrue(body.contains("http.method"), "解出的属性要有键：" + body);
                assertTrue(body.contains("\"type\":\"STRING_VALUE\""), "属性要带类型：" + body);
                assertFalse(body.contains("\"raw\""), "载荷原文不该出现在详情里：" + body);
            } finally {
                store.close();
            }
        }
    }

    @Test
    @DisplayName("载荷读不出来时原因要分得开：corrupt / no_payload / expired 各说各的")
    void payloadReasonsAreDistinguishable(@TempDir final File dataDir) throws Exception {
        // 注意键要带 otel.localstore. 前缀 —— 漏了会被静默忽略成默认值
        // （写默认值的后果：环是 256 MiB，永远绕不满圈，"expired"那条就测不到）
        final Map<String, String> tiny = new LinkedHashMap<String, String>();
        tiny.put(LocalStoreConfig.PREFIX + "dataDir", dataDir.getAbsolutePath());
        tiny.put(LocalStoreConfig.PREFIX + "host", "127.0.0.1");
        tiny.put(LocalStoreConfig.PREFIX + "capped.traces.bytes", "8192");
        tiny.put(LocalStoreConfig.PREFIX + "capped.logs.bytes", "8192");
        final LocalStoreConfig cfg = LocalStoreConfig.from(tiny);
        assertEquals(8192L, cfg.getCappedTracesBytes(), "配置必须真的生效，否则这个用例测不到东西");
        final java.util.Random rnd = new java.util.Random(7L);
        final byte[] incompressible = new byte[512];
        rnd.nextBytes(incompressible);

        try (LocalStore store = new LocalStore(cfg)) {
            // 顺序是关键：**先写要过期的，再写填充把环绕满，最后才写那两个"当下可读"的**。
            // 反过来写的话，corrupt 那行的块也会被填充块覆盖 —— 它就变成 expired，
            // 三种状态就不可能同时出现在一次快照里（这正是"过期会盖掉一切"的真实表现）。
            store.store(spanWithOtlpPayload("will-expire", "00000000000000000000000000000001"));
            for (int i = 0; i < 60; i++) {
                store.store(new SpanRecord("000000000000000000000000000000" + (10 + i),
                        "0123456789abcdef", "", "filler-" + i, 2, 1L, 2L, 0, null, "scope", "1.0",
                        null, 0, 0, incompressible));
            }
            store.store(new SpanRecord("00000000000000000000000000000002", "0123456789abcdef", "",
                    "no-payload", 2, 1L, 2L, 0, null, "scope", "1.0", null, 0, 0, null));
            store.store(new SpanRecord("00000000000000000000000000000003", "0123456789abcdef", "",
                    "corrupt-payload", 2, 1L, 2L, 0, null, "scope", "1.0", null, 0, 0,
                    "这不是 OTLP protobuf".getBytes(UTF8)));

            // 先确认环真的绕圈了 —— 否则下面那条 expired 断言测的是"根本没覆盖"
            final Map<String, Object> ring = (Map<String, Object>) store.snapshot().get("traceRing");
            assertTrue(((Number) ring.get("wrapCount")).longValue() > 0L,
                    "环必须已经绕圈才能测出 expired，实际：" + ring);

            try (HttpReadout readout = HttpReadout.start(cfg, new ReadoutQueries(cfg, store, null),
                    dataDir)) {
                final int port = readout.getActualPort();
                // 按 name 找 id，不硬编码 —— 填充块占了 2..61，硬编码的 id 一定会随写入顺序碎掉
                final String noPayload = get(port, "/api/traces/" + idOf(store, "no-payload")).body;
                assertTrue(noPayload.contains("\"reason\":\"no_payload\""), noPayload);
                assertTrue(noPayload.contains("max.payload.bytes"), "要说清为什么没有载荷：" + noPayload);

                final String corrupt = get(port, "/api/traces/" + idOf(store, "corrupt-payload")).body;
                assertTrue(corrupt.contains("\"reason\":\"corrupt\""),
                        "字节在但解不出来 = 故障，与过期必须分开：" + corrupt + " ring=" + ring);
                assertTrue(corrupt.contains("\"state\":\"AVAILABLE\""),
                        "字节确实读到了，只是解不开：" + corrupt);

                final String expired = get(port, "/api/traces/" + idOf(store, "will-expire")).body;
                assertTrue(expired.contains("\"reason\":\"expired\""), expired);
                assertTrue(expired.contains("这不是故障"),
                        "过期必须说清它不是故障，否则用户会去查磁盘：" + expired);

                assertTrue(get(port, "/api/traces/99999").body.contains("\"error\":\"not_found\""),
                        "行不存在是 404，与载荷读不到是两件事");
            }
        }
    }

    /** 按 span 名找它的行 id。 */
    private static long idOf(final LocalStore store, final String name) throws Exception {
        for (final Map<String, Object> row : store.recentSpans(500)) {
            if (name.equals(row.get("name"))) {
                return ((Number) row.get("id")).longValue();
            }
        }
        throw new AssertionError("库里没有名为 " + name + " 的 span");
    }

    @Test
    @DisplayName("详情端点的 id 只接受纯数字；多段与穿越式路径一律 400")
    void detailIdMustBePureDigits(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            final int port = readout.getActualPort();
            // 只做前缀匹配、不做多段 → 整串被当 id 解析 → 非数字 → 400。没有路径穿越的口子
            for (final String bad : new String[]{"abc", "1/2", "../x", "1.0", "-1", " 1",
                    "99999999999999999999", "%2e%2e%2f"}) {
                final Response r = get(port, "/api/traces/" + bad);
                assertEquals(400, r.status, "/api/traces/" + bad + " 应当被拒");
                assertTrue(r.body.contains("\"error\":\"bad_request\""), r.body);
            }
            assertEquals(200, get(port, "/api/traces/1").status, "合法 id 可用");
        }
    }

    @Test
    @DisplayName("详情端点也受鉴权保护")
    void detailIsGuarded(@TempDir final File dataDir) throws Exception {
        final String token = "detail-token";
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, true, token, store)) {
            final int port = readout.getActualPort();
            assertEquals(401, get(port, "/api/traces/1").status);
            assertEquals(401, get(port, "/api/traces/1", "wrong").status);
            assertEquals(200, get(port, "/api/traces/1", token).status);
        }
    }

    @Test
    @DisplayName("按 traceId 取回该 trace 的全部 span，按开始时间排")
    void spansOfOneTraceComeBack(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            final int port = readout.getActualPort();
            final String hit = get(port, "/api/traces?traceId=abcdef0123456789abcdef0123456789").body;
            assertTrue(hit.contains("demo-span"), hit);

            // limit 夹取：不给、超大、负数都要能用（共享层是封顶口径所在）
            for (final String q : new String[]{"", "?limit=1", "?limit=100000", "?limit=-3",
                    "?limit=not-a-number", "?limit="}) {
                assertEquals(200, get(port, "/api/traces" + q).status, "limit=" + q + " 应当能用");
            }
        }
    }

    @Test
    @DisplayName("traceId 的格式校验：32 位十六进制")
    void traceIdFormatValidation() {
        assertTrue(HttpReadout.isTraceId("abcdef0123456789abcdef0123456789"));
        assertTrue(HttpReadout.isTraceId("00000000000000000000000000000000"));
        assertTrue(HttpReadout.isTraceId("ABCDEF0123456789ABCDEF0123456789"), "大写也算十六进制");
        assertFalse(HttpReadout.isTraceId("abc"));
        assertFalse(HttpReadout.isTraceId("abcdef0123456789abcdef01234567890"), "33 位");
        assertFalse(HttpReadout.isTraceId("gbcdef0123456789abcdef0123456789"), "非十六进制字符");
        assertFalse(HttpReadout.isTraceId(null));
        assertFalse(HttpReadout.isTraceId(""));
    }

    @Test
    @DisplayName("启用鉴权后：缺 token 与错 token 都 401，带对 token 才 200")
    void authGuardsDataEndpoints(@TempDir final File dataDir) throws Exception {
        final String token = "test-token-value";
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, true, token, store)) {
            final int port = readout.getActualPort();
            assertEquals(401, get(port, "/api/summary").status, "缺 token");
            assertEquals(401, get(port, "/api/summary", "wrong").status, "错 token");
            assertEquals(401, get(port, "/api/summary", "").status, "空 token 也不行");
            assertEquals(200, get(port, "/api/summary", token).status, "带对 token 才能读");

            assertEquals(200, get(port, "/").status, "页面不含数据，所以不需要 token");

            final File tokenFile = new File(dataDir, ReadoutAccess.TOKEN_FILE);
            assertTrue(tokenFile.isFile(), "token 必须落盘，否则用户没法配 Prometheus");
            assertEquals(token, read(tokenFile));

            final String body = get(port, "/api/summary", token).body;
            assertFalse(body.contains(token), "响应里绝不能回显 token");
        }
    }

    @Test
    @DisplayName("未启用鉴权时读口不生成 token 文件")
    void noTokenFileWhenAuthDisabled(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            assertFalse(new File(dataDir, ReadoutAccess.TOKEN_FILE).isFile(),
                    "默认无鉴权就不该造一个 token 文件出来，让人误以为有鉴权");
        }
    }

    /** 只在断言消息里用：页面很长，全打出来没人看得完。 */
    private static String shortTail(final String text) {
        return text.length() <= 400 ? text : "…（前 400 字符）" + text.substring(0, 400);
    }

    @Test
    @DisplayName("/metrics 是 Prometheus 文本，不是 JSON")
    void metricsEndpointSpeaksPrometheusText(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir)) {
            store.store(new io.github.fulizhe.otelstore.core.model.MetricPointEntry(
                    "jvm.memory.used", "堆用了多少", "byte",
                    io.github.fulizhe.otelstore.core.model.MetricPointEntry.MetricKind.GAUGE,
                    1700000000000L, null, "io.demo", "1.0",
                    Collections.singletonList(KeyValue.of("area", "heap")), 1024.0d, 0L, Double.NaN, "double"));
            try (HttpReadout readout = start(dataDir, 0, false, null, store)) {
                final Response r = get(readout.getActualPort(), "/metrics");
                assertEquals(200, r.status);
                assertTrue(r.contentType.startsWith("text/plain"), r.contentType);
                assertTrue(r.contentType.contains("version=0.0.4"), "抓取器靠这个参数认版本：" + r.contentType);
                assertTrue(r.body.contains("# TYPE jvm_memory_used gauge"), r.body);
                assertTrue(r.body.contains("jvm_memory_used{"), r.body);
                assertTrue(r.body.contains("attr_key="), "标签里必须有属性组合的哈希：" + r.body);
                assertFalse(r.body.startsWith("{"), "绝不能是 JSON：" + r.body);
            } finally {
                store.close();
            }
        }
    }

    @Test
    @DisplayName("/metrics 在库里没指标时返回空 body 而不是报错")
    void metricsEndpointEmptyWhenNoMetrics(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            final Response r = get(readout.getActualPort(), "/metrics");
            assertEquals(200, r.status, "没有指标是正常状态，不是故障");
            assertEquals("", r.body.trim());
        }
    }

    @Test
    @DisplayName("/metrics 同样受鉴权保护")
    void metricsEndpointIsGuarded(@TempDir final File dataDir) throws Exception {
        final String token = "metrics-token";
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, true, token, store)) {
            final int port = readout.getActualPort();
            assertEquals(401, get(port, "/metrics").status);
            assertEquals(401, get(port, "/metrics", "wrong").status);
            assertEquals(200, get(port, "/metrics", token).status);
        }
    }

    @Test
    @DisplayName("/ 是只读页面：不含数据、带 token 输入框、声明不清缓存")
    void pageIsDataFreeShell(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            final Response r = get(readout.getActualPort(), "/");
            assertEquals(200, r.status);
            assertTrue(r.contentType.startsWith("text/html"), r.contentType);
            assertTrue(r.body.contains("<!doctype html>"), "应当是 HTML 页面");
            assertTrue(r.body.contains("X-Otel-Store-Token"),
                    "页面必须知道那个请求头名，否则启用鉴权时它连数据都取不到");
            assertTrue(r.body.contains("sessionStorage"), "token 只能放 sessionStorage");
                assertFalse(r.body.contains("?token="), "token 绝不能进 URL");
                assertFalse(r.body.contains("demo-span"), "页面本身不含数据 —— 数据由 JS 带头去取");

                // 动态生成的行里**不许有内联 onclick**。
                // 内联 onclick 只能靠字符串拼接把引号套出来（引号地狱），
                // 而且页面一旦有 CSP（script-src 不含 'unsafe-inline'）就全部点不动。
                // 事件委托（tbody 一个监听 + data-trace）两种问题都没有。
                assertTrue(r.body.contains("data-trace="), "trace 链接必须用 data 属性：" + shortTail(r.body));
                assertTrue(r.body.contains("wireTraceLinks"), "必须有事件委托的接线");
                assertFalse(r.body.contains("onclick=\"pickTrace("),
                        "动态行里不该有内联 onclick：" + shortTail(r.body));
        }
    }

    @Test
    @DisplayName("页面对嵌套的字段取深层路径，不把对象直接渲染成 [object Object]")
    void pageReadsNestedValuesInsteadOfStringifyingObjects(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            final String page = get(readout.getActualPort(), "/").body;
            // store.resources 是嵌套的一层（interned/reused/cachedHashes/collisions），
            // 字典行数就是 interned。直接渲染 store.resources 会得到 [object Object]。
            assertTrue(page.contains("(store.resources || {}).interned"),
                    "页面必须取深层字段，不能直接渲染对象：" + page);
            assertFalse(page.contains("num(store.resources)"), "那正是会渲染成 [object Object] 的写法");
        }
    }

    @Test
    @DisplayName("对外呈现的 dataDir 是绝对路径")
    void summaryShowsAbsoluteDataDir(@TempDir final File dataDir) throws Exception {
        final Map<String, String> p = new LinkedHashMap<String, String>();
        p.put(LocalStoreConfig.PREFIX + "host", "127.0.0.1");
        p.put(LocalStoreConfig.PREFIX + "capped.traces.bytes", "1048576");
        p.put(LocalStoreConfig.PREFIX + "capped.logs.bytes", "1048576");
        // 故意给一个相对路径：读口页面与日志里出现 "./xxx" 时人无法判断它相对于谁
        p.put(LocalStoreConfig.PREFIX + "dataDir", "./relative-store-probe");
        final LocalStoreConfig relative = LocalStoreConfig.from(p);
        // 这个目录会真的被建出来（存储层要往里写环文件），所以测完必须删 ——
        // 否则每次跑测试都在仓库根目录留一份垃圾。
        final File litter = new File(relative.getDataDirAbsolute());
        try (LocalStore store = new LocalStore(relative);
             HttpReadout readout = HttpReadout.start(relative,
                     new ReadoutQueries(relative, store, null), litter)) {
            final String body = get(readout.getActualPort(), "/api/summary").body;
            final String value = jsonString(body, "dataDir");
            assertNotNull(value, "响应里要有 dataDir：" + body);
            assertTrue(new File(value).isAbsolute(),
                    "dataDir 必须是绝对路径，实际：" + value);
            assertTrue(value.endsWith("relative-store-probe"),
                    "指向的仍是同一个目录：" + value);
        } finally {
            deleteRecursively(litter);
        }
    }

    /** 从 JSON 里取一个字符串字段的值（剥掉引号）。 */
    private static String jsonString(final String json, final String key) {
        final String needle = "\"" + key + "\":\"";
        final int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        final int from = i + needle.length();
        return json.substring(from, json.indexOf('"', from));
    }

    private static void deleteRecursively(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                deleteRecursively(children[i]);
            }
        }
        file.delete();
    }

    @Test
    @DisplayName("HEAD 与 GET 同样可达，但不带响应体")
    void headWorksLikeGetWithoutBody(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir);
             HttpReadout readout = start(dataDir, 0, false, null, store)) {
            final int port = readout.getActualPort();
            assertEquals(200, call(port, "/api/summary", "HEAD", null, null).status);
            assertEquals("", call(port, "/api/summary", "HEAD", null, null).body, "HEAD 不带体");
            assertTrue(get(port, "/api/summary").body.length() > 0, "GET 才带体");
        }
    }

    @Test
    @DisplayName("端口配置填 0 视为没配，回落默认值而不是随机端口")
    void portZeroFallsBackToDefault(@TempDir final File dataDir) throws Exception {
        try (LocalStore store = storeWithOneSpan(dataDir)) {
            final LocalStoreConfig cfg = config(dataDir, 0, false, null);
            try (HttpReadout readout = HttpReadout.start(cfg, new ReadoutQueries(cfg, store, null), dataDir)) {
                assertTrue(readout.getActualPort() > 0, "端口永远要是具体值");
            }
        }
    }

    @Test
    @DisplayName("回环地址的判定：localhost / 127.0.0.1 / ::1 是回环，其余不是")
    void loopbackDetection() {
        assertTrue(HttpReadout.isLoopback("127.0.0.1"));
        assertTrue(HttpReadout.isLoopback("localhost"));
        assertTrue(HttpReadout.isLoopback("::1"));
        assertFalse(HttpReadout.isLoopback("0.0.0.0"));
        assertFalse(HttpReadout.isLoopback("10.0.0.5"));
    }

    @Test
    @DisplayName("token 比对：长度不同直接否，内容逐字符比且不提前返回")
    void tokenMatching() {
        assertTrue(ReadoutAccess.tokenMatches("abc123", "abc123"));
        assertFalse(ReadoutAccess.tokenMatches("abc123", "abc124"));
        assertFalse(ReadoutAccess.tokenMatches("abc123", "abc12"), "长度不同");
        assertFalse(ReadoutAccess.tokenMatches("abc123", "abc1234"), "长度不同");
        assertFalse(ReadoutAccess.tokenMatches(null, "abc"));
        assertFalse(ReadoutAccess.tokenMatches("abc", null));
        assertFalse(ReadoutAccess.tokenMatches(null, null));
    }

    @Test
    @DisplayName("自动生成的 token 每次都不同，且是 48 位十六进制")
    void generatedTokensAreUnpredictable(@TempDir final File dataDir) throws IOException {
        final String a = ReadoutAccess.generateToken(dataDir);
        final String b = ReadoutAccess.generateToken(dataDir);
        assertEquals(48, a.length(), "实际长度：" + a.length());
        assertTrue(a.matches("[0-9a-f]{48}"), "不是十六进制：" + a);
        assertNotEquals(a, b, "每次启动必须是不同的 token");
        assertNotNull(read(new File(dataDir, ReadoutAccess.TOKEN_FILE)));
    }
}
