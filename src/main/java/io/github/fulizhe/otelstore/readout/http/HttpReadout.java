package io.github.fulizhe.otelstore.readout.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.util.ThrottledLogger;
import io.github.fulizhe.otelstore.readout.ReadoutQueries;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP 读口。<b>结构性只读</b>：只注册 GET/HEAD，只有一份封闭端点清单。
 *
 * <p><b>为什么用 {@code com.sun.net.httpserver} 而不是手写 socket</b>：
 * 它是 JDK 自带的（模块 {@code jdk.httpserver}，JDK 8 起就有），
 * 所以"读口不引入任何需要 relocation 的三方依赖"这条成立。
 * 代价要说清：它**不属于 Java SE 规范**，严格说是内部 API —— 但它是 JDK 自带、
 * 不需要 relocation、也不会像"自己手写 HTTP/1.1"那样把 keep-alive 与 chunked
 * 的边界处理错。自己实现这一层大约多三百行，而错的地方都在"正常情况下测不出来"的路径上。
 *
 * <p><b>端口冲突不阻塞启动</b>（ADR-1）：先试配置值，{@link BindException} 则退到随机端口。
 * 实际端口写进数据目录下的端口文件并打进启动日志 —— 因为端口是随机的，
 * "怎么找到它"就是读口的第一件事。
 *
 * <p><b>默认不要求 token</b>（ADR-6 第四节）：本地自查最常见，不该被一道
 * "去文件里复制 token 粘进页面"挡住。但**无鉴权且绑定地址不是回环时必须打显著警告** ——
 * 那种情况下同一网络内任何机器都能读走全部载荷。
 */
public final class HttpReadout implements AutoCloseable {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(HttpReadout.class.getName());


    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 承载请求头的名字。刻意不用 {@code Authorization}：那是给"代理/服务端"用的标准头，
     *  而我们是给浏览器里的 fetch 用的自定义头，撞名会让中间代理产生奇怪期待。 */
    public static final String TOKEN_HEADER = "X-Otel-Store-Token";

    private final ReadoutQueries queries;
    private final String token;
    private final HttpServer server;
    private final ExecutorService executor;
    private final int actualPort;

    private HttpReadout(final ReadoutQueries queries, final String token,
            final HttpServer server, final ExecutorService executor, final int actualPort) {
        this.queries = queries;
        this.token = token;
        this.server = server;
        this.executor = executor;
        this.actualPort = actualPort;
    }

    /**
     * 起读口。
     *
     * <p>端口退让在这里做，**不在调用方** —— 调用方只该拿到"已经起好了的那个"。
     *
     * @param dataDir 数据目录，用来放端口文件与 token 文件
     */
public static HttpReadout start(final LocalStoreConfig config, final ReadoutQueries queries,
            final File dataDir) throws IOException {
        final boolean authEnabled = config.isAuthEnabled();
        final String token = authEnabled ? resolveToken(config, dataDir) : null;

        final HttpServer server = bind(config.getHost(), effectivePort(config.getPort()));
        final int actualPort = server.getAddress().getPort();

        final ExecutorService executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable r) {
                final Thread t = new Thread(r, "otelstore-http-" + seq.incrementAndGet());
                // daemon：读口绝不能挡住 JVM 退出
                t.setDaemon(true);
                return t;
            }
        });
        server.setExecutor(executor);

        final HttpReadout readout = new HttpReadout(queries, token, server, executor, actualPort);
        server.createContext("/", readout.new RootHandler());
        server.start();

        ReadoutAccess.writePort(dataDir, actualPort);
        LOGGER.info("[otel-local-telemetry-store] 读口就绪 host=" + config.getHost()
                + " port=" + actualPort + "（配置值 " + config.getPort() + "）"
                + " auth=" + (authEnabled ? "on" : "off")
                + " 端点清单见 docs/adr/adr-06-readout-http-surface.md");
        if (!authEnabled && !isLoopback(config.getHost())) {
            ThrottledLogger.warn("http-readout-unauthenticated",
                    "读口**未启用鉴权**且绑定在非回环地址 " + config.getHost()
                            + "：同一网络内的任何机器都能读走全部 trace 与日志载荷"
                            + "（里面装着 SQL 语句、HTTP header、请求体、日志原文）。"
                            + "要收紧：配 otel.localstore.auth=true（token 每进程随机、写在 "
                            + ReadoutAccess.TOKEN_FILE + "），"
                            + "或把 otel.localstore.host 配成 127.0.0.1");
        }
        return readout;
    }

/**
     * 定下这个进程用哪个 token，并把它落到文件里。
     *
     * <p><b>显式配置优先</b>（{@code otel.localstore.token}）：运维要能预先把 token
     * 写进部署配置与 Prometheus 的 scrape 配置里 —— 那要求 token 在进程启动前就已知。
     * 没配才随机生成（ADR-1：每进程随机）。
     *
     * <p>两种情况都落文件：用户与 Prometheus 都可能从文件读它。
     *
     * <p>明文 token 只在这里出现一次，调用方拿着它去比对请求头，**不要打日志**。
     */
    private static String resolveToken(final LocalStoreConfig config, final File dataDir) {
        final String configured = config.getToken();
        if (configured != null && !configured.trim().isEmpty()) {
            final String trimmed = configured.trim();
            try {
                ReadoutAccess.writeToken(dataDir, trimmed);
            } catch (final IOException e) {
                ThrottledLogger.warn("token-file-failed",
                        "显式配置的 token 写不进文件（读口仍然要求 token），见 "
                                + ReadoutAccess.TOKEN_FILE, e);
            }
            return trimmed;
        }
        return ReadoutAccess.generateToken(dataDir);
    }

    /**
     * 绑定端口；被占则退到随机端口。
     *
     * <p>退让而不是失败：端口冲突是**部署环境的事**，不该变成应用起不来。
     */
    private static HttpServer bind(final String host, final int port) throws IOException {
        try {
            return HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (final BindException e) {
            ThrottledLogger.warn("http-port-conflict",
                    "端口 " + port + " 被占用，读口退到随机端口（应用照常启动）", e);
            return HttpServer.create(new InetSocketAddress(host, 0), 0);
        }
    }

    /**
     * 端口配置不许填 0。
     *
     * <p>绑定 0 让 OS 挑确实省事，但用户没法预先在防火墙上开口子 —— 而"能预先开口子"
     * 是自部署场景的刚需。所以 0 当成"没配"，回落默认值。
     */
    private static int effectivePort(final int configured) {
        return configured > 0 ? configured : LocalStoreConfig.DEFAULT_PORT;
    }

    static boolean isLoopback(final String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
    }

    /** 实际监听的端口（配置值被占用时它与配置值不同）。 */
    public int getActualPort() {
        return actualPort;
    }

    @Override
    public void close() {
        try {
            server.stop(0);
        } catch (final RuntimeException e) {
            ThrottledLogger.warn("http-readout-stop", "读口停止时出错", e);
        }
        executor.shutdownNow();
    }

    // ============================ 路由

    /**
     * 根处理器：先按方法与路径分流，再谈鉴权。
     *
     * <p>顺序有讲究：**方法先判**（不是 GET/HEAD 就 405），
     * 这样"用 POST 打读口"在任何路径下都是同一个答案，不会因为路径不同而漏出别的行为。
     */
    private final class RootHandler implements com.sun.net.httpserver.HttpHandler {

        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try {
                final String method = exchange.getRequestMethod();
                if (!"GET".equals(method) && !"HEAD".equals(method)) {
                    // 只注册 GET/HEAD 是 ADR-1 的明文；OPTIONS 同样不注册 ——
                    // 我们不开 CORS（ADR-6 第五节），注册它只会让人误以为支持跨源
                    exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                    sendError(exchange, 405, "method_not_allowed",
                            "读口只读，只接受 GET 与 HEAD");
                    return;
                }

                final String path = exchange.getRequestURI().getPath();
                if ("/".equals(path) || "/index.html".equals(path)) {
                    servePage(exchange);
                    return;
                }
                if ("/api/summary".equals(path)) {
                    if (!authorized(exchange)) {
                        return;
                    }
                    serveJson(exchange, queries.summary());
                    return;
                }
                if ("/metrics".equals(path)) {
                    if (!authorized(exchange)) {
                        return;
                    }
                    servePrometheus(exchange);
                    return;
                }
                if ("/api/traces".equals(path)) {
                    serveList(exchange, true);
                    return;
                }
                if ("/api/logs".equals(path)) {
                    serveList(exchange, false);
                    return;
                }
                if ("/api/metrics".equals(path)) {
                    serveMetricPoints(exchange);
                    return;
                }
                sendError(exchange, 404, "not_found",
                        "没有这个端点。读口的端点清单是封闭的，见 docs/adr/adr-06-readout-http-surface.md");
            } catch (final RuntimeException e) {
                // 读口自己的异常绝不外抛成堆栈（ADR-1）：客户端拿到一句说明，栈进我们自己的日志
                ThrottledLogger.warn("http-readout-handler", "处理请求时出错 " + exchange.getRequestURI(), e);
                sendError(exchange, 500, "internal", "读口内部错误，详情见扩展自己的日志");
            } finally {
                exchange.close();
            }
        }

        /** 鉴权。默认关闭（ADR-6 第四节）；启用时比对请求头。 */
        private boolean authorized(final HttpExchange exchange) throws IOException {
            if (token == null) {
                return true;
            }
            final String presented = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
            if (ReadoutAccess.tokenMatches(token, presented)) {
                return true;
            }
            final Headers h = exchange.getResponseHeaders();
            h.set("WWW-Authenticate", TOKEN_HEADER);
            // 401 而不是 403：缺凭据与凭据错都是"你没证明你是谁"，403 会被读成"我知道你是谁但不允许"
            writeJson(exchange, 401, errorBody("unauthorized",
                    "缺少或错误的 " + TOKEN_HEADER + "。token 见数据目录下的 "
                            + ReadoutAccess.TOKEN_FILE));
            return false;
        }

        private void servePage(final HttpExchange exchange) throws IOException {
            final byte[] body = loadPage();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            // 页面是不含数据的壳，所以不需要 token（ADR-6 第四节）。
            // 但它也不该被缓存住：token 输入框与自动刷新都要求每次回源。
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            write(exchange, body);
        }

        /**
         * 指标点：{@code ?name=} 指定指标名，缺省按时间倒序给最近若干个点。
         *
         * <p>{@code detail} 那一列是<b>结构化</b>返回的（桶边界、各桶计数、分位点），
         * 而不是原样吐文本：让页面与脚本各自再写一个 detail 解析器，
         * 就是两套语法 —— 而 {@code MetricDetail} 已经是唯一的实现。
         * 解析不出来时 {@code flavor} 为 {@code unknown} 且**带上原文**，
         * 这样页面还能让人判断"是这个形态没覆盖，还是数据坏了"。
         *
         * <p>与 {@code /metrics} 的区别：那个只给每个序列的<b>当前值</b>（Prometheus 语义），
         * 这个给<b>时间序列</b>（人要看趋势）。
         */
        private void serveMetricPoints(final HttpExchange exchange) throws IOException {
            if (!authorized(exchange)) {
                return;
            }
            final Map<String, String> q = queryParams(exchange.getRequestURI().getRawQuery());
            final String name = q.get("name");
            final List<Map<String, Object>> rows =
                    queries.recentMetricPoints(name == null ? "" : name, parseLimit(q.get("limit")));
            if (rows == null) {
                if (!queries.isStoreAvailable()) {
                    sendError(exchange, 503, "no_store",
                            "存储层不可用，本次只计数不落库。原因是数据目录不可写，详见扩展自己的日志。");
                } else {
                    sendError(exchange, 500, "internal", "读 metric_point 失败，详情见扩展自己的日志");
                }
                return;
            }
            final List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rows.size());
            for (final Map<String, Object> row : rows) {
                final Map<String, Object> copy = new LinkedHashMap<String, Object>(row);
                copy.put("detail", MetricDetail.parse(str(row.get("detail"))).toMap(str(row.get("detail"))));
                out.add(copy);
            }
            serveJson(exchange, out);
        }

        private void serveJson(final HttpExchange exchange, final Object payload) throws IOException {
            writeJson(exchange, 200, Json.write(payload));
        }

        /**
         * span 与日志列表：同一套形态，只有"取哪个表"与"有没有 traceId"不同。
         *
         * <p>三处口径（ADR-6 第十节）：
         * <ul>
         *   <li>参数非法 → 400；<b>合法但没有数据 → 200 + 空数组</b>。
         *       把"没有数据"报成 404 会让脚本分不清"不存在"与"我写错了"。</li>
         *   <li>{@code traceId} 做<b>格式</b>校验（32 位十六进制），明显非法立刻拒，
         *       不拿它去查一次索引。格式对但库里没有，那是 200 + 空数组 ——
         *       <b>校验格式与校验存在是两件事</b>。</li>
         *   <li>每一行就地带上 Resource 的规范化文本，页面不必再发一次请求。</li>
         * </ul>
         */
        private void serveList(final HttpExchange exchange, final boolean spans) throws IOException {
            if (!authorized(exchange)) {
                return;
            }
            final Map<String, String> q = queryParams(exchange.getRequestURI().getRawQuery());
            final String traceId = q.get("traceId");
            if (traceId != null && !traceId.isEmpty() && !isTraceId(traceId)) {
                sendError(exchange, 400, "bad_request",
                        "traceId 必须是 32 位十六进制（trace_id 恒为 16 字节），实际收到：" + traceId);
                return;
            }
            final boolean byTrace = traceId != null && !traceId.isEmpty();
            final int limit = parseLimit(q.get("limit"));

            List<Map<String, Object>> rows = byTrace
                    ? (spans ? queries.spansOfTrace(traceId) : queries.logsOfTrace(traceId))
                    : (spans ? queries.recentSpans(limit) : queries.recentLogs(limit));
            if (rows == null) {
                if (!queries.isStoreAvailable()) {
                    sendError(exchange, 503, "no_store",
                            "存储层不可用，本次只计数不落库。原因是数据目录不可写，详见扩展自己的日志。");
                } else {
                    sendError(exchange, 500, "internal",
                            "读 " + (spans ? "span" : "log_record") + " 表头失败，详情见扩展自己的日志");
                }
                return;
            }
            serveJson(exchange, withResource(rows, queries.resourceAttributesOf(rows)));
        }


        /**
         * Prometheus 文本端点。
         *
         * <p><b>不是 JSON</b>：抓取器的解析器不接受 JSON，"统一格式"的洁癖在这里
         * 只能换来一次抓取失败。
         *
         * <p>输入是"每个序列的最新一点"而不是"最近 N 行" —— 见
         * {@code LocalStore} 那条窗口函数查询的注释。
         */
        private void servePrometheus(final HttpExchange exchange) throws IOException {
            final String body = PrometheusText.render(queries.latestMetricPoints());
            final byte[] bytes = body.getBytes(UTF8);
            exchange.getResponseHeaders().set("Content-Type",
                    "text/plain; version=0.0.4; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            write(exchange, bytes);
        }

        private void sendError(final HttpExchange exchange, final int status, final String kind,
                final String message) throws IOException {
            writeJson(exchange, status, errorBody(kind, message));
        }
    }

    private static String str(final Object o) {
        return o == null ? "" : o.toString();
    }

    /**
     * 每一行补一个 {@code resource} 字段（字典表那一列的原文）。
     *
     * <p>就地展开而不是另给一个 {@code resource_dict} 段：页面渲染一行时需要知道
     * "这是哪个服务的 trace"，而让它为此再发一次请求没有理由（ADR-6 第十节）。
     * JMX 那个读口把 Resource 作为独立段落附在文本后面 —— 形态不同但数据同源。
     *
     * <p>它是外层的 static 方法而不是内部类里的：Java 8 不允许内部类声明 static 方法
     * （Java 16 才放开），而本项目的字节码基线是 8。
     */
    private static List<Map<String, Object>> withResource(final List<Map<String, Object>> rows,
            final Map<Long, String> resources) {
        final List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rows.size());
        for (final Map<String, Object> row : rows) {
            final Map<String, Object> copy = new LinkedHashMap<String, Object>(row);
            final Object id = row.get("resourceId");
            if (id instanceof Number) {
                final String text = resources.get(Long.valueOf(((Number) id).longValue()));
                copy.put("resource", text == null ? "" : text);
            }
            out.add(copy);
        }
        return out;
    }

    /**
     * 统一错误体：{@code error} 给机器（分类），{@code message} 给人。
     *
     * <p>刻意<b>不含栈</b>（ADR-6 第六节）：读口在另一个 ClassLoader 边界上，
     * 我们把栈抛过去对方也只会看到一句看不懂的话，而栈已经进我们自己的日志了。
     */
    private static String errorBody(final String kind, final String message) {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", kind);
        m.put("message", message == null ? "" : message);
        return Json.write(m);
    }

    /**
     * 解析查询串。
     *
     * <p><b>刻意不引任何查询串解析库</b>：我们只需要"取两个值"，
     * 而引一个库意味着 relocation、版本选择、字节码核对（ADR-5）。
     *
     * <p>{@code null} 视为空（{@code ?traceId} 这种写法不该 NPE）。
     */
    private static Map<String, String> queryParams(final String rawQuery) {
        final Map<String, String> out = new LinkedHashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (final String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            final int eq = pair.indexOf('=');
            final String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            final String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.put(key, value);
        }
        return out;
    }

    /** URL 解码。只处理 {@code %XX} 与 {@code +}，不碰 UTF-8 多字节的边界情况以外的东西。 */
    private static String decode(final String s) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return s;
        }
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '+') {
                bytes.write(' ');
            } else if (c == '%' && i + 2 < s.length()) {
                final int hi = Character.digit(s.charAt(i + 1), 16);
                final int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) | lo);
                    i += 2;
                } else {
                    bytes.write(c);
                }
            } else {
                // 非 ASCII 字符原样逐字节写入（UTF-8 字节在 Java 字符串里本来就是多字节）
                bytes.write(c & 0xFF);
            }
        }
        return new String(bytes.toByteArray(), UTF8);
    }

    /**
     * trace_id 恒为 32 位十六进制。
     *
     * <p>校验<b>格式</b>而不是<b>存在</b>：格式明显不对的值立刻 400，
     * 因为拿它去查一次索引是"用户还没发现自己的 bug，读口先替他查了一轮库"。
     */
    static boolean isTraceId(final String s) {
        if (s == null || s.length() != 32) {
            return false;
        }
        for (int i = 0; i < 32; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** limit 参数；非数字或缺省交给共享层夹取（那边才是封顶口径所在）。 */
    private static int parseLimit(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (final NumberFormatException e) {
            // 不是数字就当没给：夹到默认值比 400 更合适 ——
            // 抓取脚本里多带一个空参数不该让整个抓取失败
            return 0;
        }
    }

    private static void writeJson(final HttpExchange exchange, final int status, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes(UTF8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        write(exchange, bytes);
    }

    private static void write(final HttpExchange exchange, final byte[] body) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) {
            return;
        }
        final OutputStream out = exchange.getResponseBody();
        try {
            out.write(body);
        } finally {
            out.close();
        }
    }

    private static byte[] loadPage() throws IOException {
        final InputStream in = HttpReadout.class.getResourceAsStream("index.html");
        if (in == null) {
            // 页面是增强项，取不到就退成一行说明，而不是让整个读口 500
            return "<!doctype html><meta charset=\"utf-8\"><p>读口页面资源缺失，其余端点正常。</p>"
                    .getBytes(UTF8);
        }
        try {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
            final byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
