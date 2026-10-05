package io.github.fulizhe.otelstore.readout.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 页面脚本的冒烟检查：<b>真的把 app.js 跑一遍</b>。
 *
 * <p>为什么必须真跑：读口这部分的 bug 有一整类共同的形状 ——
 * HTML 完全正常、Java 测试全绿、只在浏览器控制台里抛一行。已经踩了三次，
 * 每次表现都不一样：
 *
 * <ul>
 *   <li>缺一个 id → 整页不工作</li>
 *   <li>loaders 的键与 tbody 的 id 对不上 → 一格永远不动</li>
 *   <li>把<b>立即调用</b>的结果 push 进要跑函数的数组 → f is not a function</li>
 * </ul>
 *
 * <p>共同点是「人眼看页面看不出来、Java 断言也够不着」。所以这里用一个最小 DOM 桩
 * 把 app.js 真的执行一次，并检查 start() 不抛异常、且声明过的部分<b>真的动了数据</b>。
 *
 * <p>DOM 桩的 fetch <b>返回真数据</b>是有意的：只测「不抛异常」的话，
 * 「跑完了但一格都没填」照样算过。
 *
 * <p><b>缺 node 就跳过</b>，不因为开发机上没有 node 而让测试红。
 */
class PageScriptSmokeTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern ID = Pattern.compile("id=\"([^\"]+)\"");
    private static final Pattern START_CALL =
            Pattern.compile("Otl\\.start\\(\\s*'([^']+)'\\s*(?:,\\s*\\[([^\\]]*)\\])?\\s*\\);");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    private static final String[] PAGES = {
        "index.html", "traces.html", "logs.html", "metrics.html", "self.html"};

    @Test
    @DisplayName("每个页面都真的跑一遍 app.js：不抛异常，且声明过的部分真的动了数据")
    void everyPageRunsWithoutThrowingAndActuallyRenders(@TempDir final File tmp) throws Exception {
        final String node = findNode();
        if (node == null) {
            System.out.println("[skip] 没找到 node，跳过页面脚本冒烟检查");
            return;
        }

        final List<String> failures = new ArrayList<String>();
        for (final String page : PAGES) {
            final String html = resource(page);
            final Matcher m = START_CALL.matcher(html);
            assertTrue(m.find(), page + " 必须调 Otl.start(段名, [部分...])，且要能从 HTML 里读出来");
            final String declared = parts(m.group(2));

            final Path script = new File(tmp, page + ".smoke.js").toPath();
            Files.write(script, harness(resource("app.js"), idsOf(html), declared)
                    .getBytes(UTF8));
            final Exec r = exec(node, script.toString());
            if (r.status != 0 || !r.out.contains("SMOKE-OK")) {
                failures.add(page + "  声明[" + declared + "]\n" + r.out.trim());
            }
        }

        assertEquals(0, failures.size(),
                "这些页面跑起来抛异常或没渲染出数据：\n" + String.join("\n---\n", failures));
    }

    @Test
    @DisplayName("声明一个没知的部分要立刻抛错，而不是安静地什么都不做")
    void unknownPartFailsLoudly(@TempDir final File tmp) throws Exception {
        final String node = findNode();
        if (node == null) {
            System.out.println("[skip] 没找到 node");
            return;
        }
        final Path script = new File(tmp, "bad.js").toPath();
        Files.write(script, harness(resource("app.js"), idsOf(resource("index.html")),
                "tiles,nope").getBytes(UTF8));
        final Exec r = exec(node, script.toString());
        assertTrue(r.status != 0, "拼错部分名必须抛错：" + r.out);
        assertTrue(r.out.contains("没知的部分"), "报错要说出是哪个名字：" + r.out);
    }

    @Test
    @DisplayName("不声明任何部分就跑：不该抛异常（启动这一步要永远成立）")
    void startingWithNoPartsIsHarmless(@TempDir final File tmp) throws Exception {
        final String node = findNode();
        if (node == null) {
            System.out.println("[skip] 没找到 node");
            return;
        }
        final Path script = new File(tmp, "empty.js").toPath();
        Files.write(script, harness(resource("app.js"), idsOf(resource("index.html")), "")
                .replace("Otl.start('smoke', []);", "Otl.start('empty');")
                .getBytes(UTF8));
        final Exec r = exec(node, script.toString());
        assertEquals(0, r.status, "不声明部分也要能起来（注入外壳不能依赖任何 id）：" + r.out);
    }

    // ---------------- 辅助

    /** 每一段加载器都该动到的 tbody id —— 用来证明它真跑了而不只是没报错。 */
    private static String touchedIdOf(final String part) {
        if ("traces".equals(part)) {
            return "spans";
        }
        if ("logs".equals(part)) {
            return "logs";
        }
        if ("metrics".equals(part)) {
            return "metrics";
        }
        if ("tiles".equals(part)) {
            return "s-spans";
        }
        return "queues";
    }

    /** 从 start(...) 的第二个参数里取出部分名列表，形如 {@code tiles,library,}. */
    private static String parts(final String raw) {
        if (raw == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        final Matcher m = QUOTED.matcher(raw);
        while (m.find()) {
            sb.append(m.group(1)).append(',');
        }
        return sb.toString();
    }

    private static Set<String> idsOf(final String html) {
        final Set<String> ids = new LinkedHashSet<String>();
        final Matcher m = ID.matcher(html);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    private static String resource(final String name) throws IOException {
        try (InputStream in = HttpReadout.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("读不到 jar 内资源 " + name);
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), UTF8);
        }
    }

    /**
     * 生成一段跑 app.js 的脚本。DOM 桩只实现 app.js 真正用到的那几个面。
     *
     * <p>Proxy 记录「哪个元素被写过」—— 否则没法区分
     * 「加载器跑了但渲染出一个空结果」与「加载器压根没跑」。
     */
    private static String harness(final String appJs, final Set<String> ids, final String declared) {
        final StringBuilder els = new StringBuilder();
        for (final String id : ids) {
            els.append("el['").append(id).append("'] = mk('").append(id).append("');\n");
        }
        final StringBuilder asserts = new StringBuilder();
        for (final String part : declared.split(",")) {
            final String p = part.trim();
            if (!p.isEmpty()) {
                asserts.append("mustTouch('").append(touchedIdOf(p)).append("', '")
                        .append(p).append("');\n");
            }
        }

        return "var touched = {};\n"
            + "var DECLARED = [" + quoted(declared) + "];\n"
            + "var byId = {};\n"
            // 真浏览器里：元素一旦设了 id 并插进文档，getElementById 就能查到。
            // 桩必须有这个语义，否则脚本注入的 #auth-card / #detail-overlay
            // 全都"不存在"，抛的是一个跟真实问题无关的 TypeError。
            + "function mk(id) {\n"
            + "  var t = { id: id, textContent: '', innerHTML: '', value: '', style: {},\n"
            + "    setAttribute: function () {}, addEventListener: function () {},\n"
            + "    closest: function () { return null; } };\n"
            + "  var box = { node: t };\n"
            + "  t.insertBefore = function (child) {\n"
            + "    if (child && child.id) { box.id = child.id; byId[child.id] = child; }\n"
            + "    return child;\n"
            + "  };\n"
            + "  var p = new Proxy(t, { set: function (o, k, v) {\n"
            + "    o[k] = v;\n"
            + "    if (k === 'id') { box.id = v; byId[v] = p; }\n"
            + "    if (k === 'textContent' || k === 'innerHTML') { touched[box.id] = true; }\n"
            + "    return true;\n"
            + "  } });\n"
            + "  if (id) { box.id = id; byId[id] = p; }\n"
            + "  return p;\n"
            + "}\n"
            + "function mustTouch(id, part) {\n"
            + "  if (!touched[id]) { throw new Error('部分 ' + part + ' 声明了，但 #' + id + '"
            + " 一个字都没变 —— 它其实没跑'); }\n"
            + "}\n"
            + "var el = {};\n"
            + els
            + "var body = mk('body');\n"
            + "var document = { body: body,\n"
            + "  getElementById: function (id) { return byId[id] || null; },\n"
            + "  createElement: function (tag) { return mk(''); },\n"
            + "  querySelector: function () { return byId.wrap || body; },\n"
            + "  addEventListener: function () {} };\n"
            + "var sessionStorage = { v: '', getItem: function () { return this.v; },\n"
            + "  setItem: function (k, v) { this.v = v; }, removeItem: function () { this.v = ''; } };\n"
            + "var location = { search: '', href: '' };\n"
            + "var setInterval = function () {};\n"
            + "var asked = [];\n"
            + "var SUMMARY = { store: { spanRows: 11, logRows: 12, metricRows: 13,\n"
            + "    resources: { interned: 14 },\n"
            + "    traceRing: { signal: 'traces', file: 't.capped', currIndex: 1, wrapCount: 0,\n"
            + "      oldestLiveIndex: 0, rejectedTooLarge: 0, expiredReads: 0 } },\n"
            + "  queues: { traces: { offered: 1, drained: 1, dropped: 0, sinkErrors: 0, backlog: 0 } },\n"
            + "  config: { 'otel.localstore.port': 17890 } };\n"
            + "var ROW = [{ id: 1, traceId: 'abcdef0123456789abcdef0123456789', spanId: 'aa',\n"
            + "  parentSpanId: '', name: 'demo', startTime: 1700000000000000000,\n"
            + "  endTime: 1700000000500000000, statusCode: 1, scopeName: 'io.demo',\n"
            + "  resource: 'service.name=s:demo', timestamp: 1700000000000000000,\n"
            + "  severityText: 'INFO', bodyPreview: 'hi', metricName: 'm', unit: 'ms',\n"
            + "  dataType: 'HISTOGRAM', metricValue: null, metricCount: 3, metricSum: 1.5,\n"
            + "  detail: { flavor: 'explicit', leBoundaries: ['2.0','+Inf'], cumulative: [3,3] } }];\n"
            + "var fetch = function (url) {\n"
            + "  asked.push(url);\n"
            + "  var body = url.indexOf('/api/summary') >= 0 ? SUMMARY\n"
            + "    : (url.indexOf('/api/') === 0 ? ROW : {});\n"
            + "  return Promise.resolve({ ok: true, status: 200,\n"
            + "    json: function () { return Promise.resolve(body); } });\n"
            + "};\n"
            + appJs + "\n"
            // declared 是 "tiles,library," 这种裸名字列表，要补引号才是 JS 字面量
            + "Otl.start('smoke', [" + quoted(declared) + "]);\n"
            // fetch 是 Promise，渲染在微任务里；setTimeout 兜底等它跑完
            + "setTimeout(function () {\n"
            // 声明了部分就必须真的去取数据；一个都没声明时不该有任何请求
            + "  if (DECLARED.length && !asked.length) { throw new Error('声明了部分，"
            + "却一个 /api 都没请求'); }\n"
            + "  if (!DECLARED.length && asked.length) { throw new Error('没声明任何部分，"
            + "却有请求：' + asked.join(',')); }\n"
            + asserts
            + "  console.log('SMOKE-OK');\n"
            + "}, 0);\n";
    }

    /** {@code "tiles,library,"} → {@code "'tiles','library',"} */
    private static String quoted(final String declared) {
        final StringBuilder sb = new StringBuilder();
        for (final String part : declared.split(",")) {
            final String p = part.trim();
            if (!p.isEmpty()) {
                sb.append('\'').append(p).append("',");
            }
        }
        return sb.toString();
    }

    private static String findNode() {
        for (final String candidate : new String[]{"node", "node.exe"}) {
            try {
                final Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).start();
                try (OutputStream sink = p.getOutputStream()) {
                    sink.write(new byte[0]);
                }
                if (p.waitFor() == 0) {
                    return candidate;
                }
            } catch (final IOException e) {
                // 试下一个
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static final class Exec {
        final int status;
        final String out;

        Exec(final int status, final String out) {
            this.status = status;
            this.out = out;
        }
    }

    private static Exec exec(final String node, final String script) throws IOException {
        try {
            final Process proc = new ProcessBuilder(node, script)
                    .redirectErrorStream(true).start();
            final ByteArrayOutputStream captured = new ByteArrayOutputStream();
            try (OutputStream sink = proc.getOutputStream()) {
                sink.write(new byte[0]);
            }
            final Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (InputStream in = proc.getInputStream()) {
                        int n;
                        final byte[] buf = new byte[4096];
                        while ((n = in.read(buf)) != -1) {
                            captured.write(buf, 0, n);
                        }
                    } catch (final IOException e) {
                        // 进程死了由退出码说话
                    }
                }
            });
            reader.start();
            final int status = proc.waitFor();
            reader.join(5000L);
            return new Exec(status, new String(captured.toByteArray(), UTF8));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("跑 node 被中断", e);
        }
    }
}
