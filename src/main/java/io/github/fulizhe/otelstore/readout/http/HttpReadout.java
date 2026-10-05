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

        private void serveJson(final HttpExchange exchange, final Object payload) throws IOException {
            writeJson(exchange, 200, Json.write(payload));
        }

        private void sendError(final HttpExchange exchange, final int status, final String kind,
                final String message) throws IOException {
            writeJson(exchange, status, errorBody(kind, message));
        }
    }

    /**
     * 统一错误体：{@code error} 给机器（分类），{@code message} 给人。
 *
 * <p>刻意<b>不含栈</b>（ADR-6 第六节）：读口在另一个 ClassLoader 边界上，
 * 我们把栈抛过去对方也只会看到一句看不懂的话，而栈已经进我们自己的日志了。
 */
    private static String errorBody(final String kind, final String message) {
        final java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("error", kind);
        m.put("message", message == null ? "" : message);
        return Json.write(m);
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