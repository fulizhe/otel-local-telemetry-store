package io.github.fulizhe.otelstore.readout;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.storage.LocalStore;
import io.github.fulizhe.otelstore.core.util.SelfLog;
import io.github.fulizhe.otelstore.core.util.ThrottledLogger;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 读口的**共享查询层**：所有对外读口（JMX、将来的 HTTP）都问它要数据。
 *
 * <p><b>它只产出 JDK 原生类型</b>（Map / List / String / 数字），不含任何渲染、
 * 不含任何协议、不含任何端口。渲染归 JMX（缩进文本）、编码归 HTTP（JSON），
 * 各管各的 —— 这样两个读口的**口径不可能漂移**，因为口径只有这一份。
 *
 * <p>**为什么现在才抽这一层**（Phase 5 开工前）：JMX 那个读口当初是把快照
 * <em>直接渲染成文本</em>的（{@code summary()} 返回 {@code String}），
 * 而 HTTP 需要原始 Map。若不抽层，每个查询都要在两个读口各写一遍 ——
 * 而"两处数字对不上"是这类存储最难查的故障（ADR-3 反复警告的口径 proliferation）。
 * 这一层是零行为变化的预重构：JMX 侧的输出与断言**一字不改**。
 *
 * <p><b>结构性只读</b>：本类只调 {@link LocalStore} 的读方法，一个写方法都不碰
 * （ADR-1 原则：readout 只读；要加修改能力先写决策记录）。
 *
 * <p><b>口径的保证方式是"只有一处实现"，不是"只有一个实例"</b>：JMX 与 HTTP 各自持有
 * 一个实例（它们生命周期不同、也不该互相持有），但查询与封顶逻辑全在这里 ——
 * 要漂移就得有人在新读口里重写一遍查询，而那会很明显。
 *
 * <p><b>limit 的封顶在这一层</b>，不在各读口：封顶是**查询口径**的一部分，
 * 放两处就等于有两个口径。
 */
public final class ReadoutQueries {

    /** 列表类查询的默认条数。 */
    public static final int DEFAULT_LIMIT = 20;

    /**
     * 列表类查询的条数上限。
     *
     * <p>不封顶的话一次调用能把二十万行全拉出来 —— 读口是"看一眼现在什么状态"的，
     * 不是导出数据的工具。
     */
    public static final int MAX_LIMIT = 200;

    /**
     * 指标点的上限，单独一档。
     *
     * <p>它是<b>看趋势</b>的：最近半小时 × 每秒一个点就是 1800 条，
     * 沿用 {@link #MAX_LIMIT} 会让"最近半小时"这个既定用途<b>根本取不出来</b>（ADR-6 第三节）。
     * 2000 是取整，不是"给多少都行" —— 再往上调要先回答"谁来读这 4000 条"。
     */
    public static final int MAX_LIMIT_METRICS = 2000;

    /**
     * 扩展自有日志尾部的缺省行数与上限（ADR-6 第三节/第九节）。
     *
     * <p>它是一整段文本而不是"看最近几条记录"，所以缺省比列表大（100）、上限也另立一档（500）。
     * 超过上限就截到 500 —— 读口是"看一眼最近出过什么事"，不是日志下载口。
     */
    public static final int DEFAULT_SELF_LOG_LINES = 100;
    public static final int MAX_SELF_LOG_LINES = 500;

    private final LocalStoreConfig config;
    private final LocalStore store;
    /** 队列侧快照由 {@code agentext} 提供（队列在那边），用 Supplier 避免 readout 依赖 agentext。 */
    private final Supplier<Map<String, Object>> queuesSnapshot;
    /**
     * 存储层开不起来的原因；由 {@code agentext} 提供（同一条理由：队列与降级原因都在那边）。
     *
     * <p>ADR-6 第一节：存储不可用时读口**照起**，就是为了把这个原因报出来。
     */
    private Supplier<String> storeDegradedReason;
    /** 本对象创建时刻 = 扩展这一轮的起点，用于 {@code startedAt} 与 {@code uptimeMs}。 */
    private final long startedAtMs = System.currentTimeMillis();

    /**
     * @param store 存储层；为 null 时读口要能说清"没有存储"而不是"没有数据"
     */
    public ReadoutQueries(final LocalStoreConfig config, final LocalStore store,
            final Supplier<Map<String, Object>> queuesSnapshot) {
        this(config, store, queuesSnapshot, null);
    }

    /**
     * @param storeDegradedReason 存储层开不起来的原因；为 null（未提供）时该键报 null。
     *        保留三参构造是为了让测试不必凭空造一个降级原因。
     */
    public ReadoutQueries(final LocalStoreConfig config, final LocalStore store,
            final Supplier<Map<String, Object>> queuesSnapshot,
            final Supplier<String> storeDegradedReason) {
        this.config = config;
        this.store = store;
        this.queuesSnapshot = queuesSnapshot;
        this.storeDegradedReason = storeDegradedReason;
    }

    /**
     * 事后挂上降级原因来源。
     *
     * <p>不用构造参数的原因：原因只在 {@code TapHub} 开完库之后才知道，
     * 而查询层要先建好才能被 JMX 那侧取到。用 setter 而不是再加一条构造重载，
     * 是因为"原因"是这个对象上**唯一**可以后填的东西。
     */
    public void setStoreDegradedReasonSupplier(final Supplier<String> supplier) {
        this.storeDegradedReason = supplier;
    }

    /** 存储层是否可用。读口要能区分"没数据"与"没有存储"。 */
    public boolean isStoreAvailable() {
        return store != null;
    }

    /**
     * 全量组合快照：生效配置 + 三条队列 + 存储与两个环形文件。
     *
     * <p>三个来源各管一段，谁也不覆盖谁 —— 与启动日志那一行是**同一份数据**，
     * 所以日志里与页面上对不上就是 bug，不存在"各说各话"。
     */
    public Map<String, Object> summary() {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("config", config.describe());
        // 下面 4 个是"这个进程的运行时事实"，与 config（生效配置）分属两类 ——
        // 所以它们**平铺**在顶层，不嵌套成新的一组（那一组会长得很像刚被取消的 /api/self）。
        m.put("startedAt", Long.valueOf(startedAtMs));
        m.put("uptimeMs", Long.valueOf(System.currentTimeMillis() - startedAtMs));
        m.put("agentVersion", agentVersion());
        // store 为 null 时才有值；健康时为 null 而不是缺键 —— 缺键会让脚本分不清
        // "这版扩展没有这个字段"与"现在健康"
        m.put("storeDegradedReason", storeDegradedReason == null ? null : storeDegradedReason.get());
        m.put("queues", queuesSnapshot == null ? null : queuesSnapshot.get());
        m.put("store", storeSnapshot());
        return m;
    }

    /**
 * agent jar 的标识；不在 agent 里运行时为 {@code null}。
 *
 * <p>来源是 JVM 的 {@code -javaagent:} 启动参数 —— **不是** {@code BuildInfoAgent}
 * （{@code java.lang.management} 里没有 {@code BuildInfo} 这个类，别被它骗了）。
 * agent 自己不会把版本写进任何标准位置，而 OTel 的 API 我们在 readout 里不能用（分层规则），
 * 所以解析启动参数是这里唯一既零依赖又不越界的办法。
 *
 * <p>返回的是文件名（通常是 {@code opentelemetry-javaagent-2.32.0.jar}），
 * 所以它给的是"哪个 agent"，不是严格的版本号 —— 名字里带不带版本取决于发行包怎么命名。
 */
    static String agentVersion() {
        final java.lang.management.RuntimeMXBean bean = java.lang.management.ManagementFactory.getRuntimeMXBean();
        if (bean == null || bean.getInputArguments() == null) {
            return null;
        }
        for (final String arg : bean.getInputArguments()) {
            if (arg != null && arg.startsWith("-javaagent:")) {
                final String path = arg.substring("-javaagent:".length());
                final int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                final String name = cut >= 0 ? path.substring(cut + 1) : path;
                return name.isEmpty() ? null : name;
            }
        }
        return null;
    }

    /** 存储侧快照；没有存储时为 {@code null}（**不是**空 Map —— 两者要能分开）。 */
    public Map<String, Object> storeSnapshot() {
        if (store == null) {
            return null;
        }
        try {
            return store.snapshot();
        } catch (final RuntimeException e) {
            ThrottledLogger.warn("readout-snapshot", "读存储快照失败", e);
            return null;
        }
    }

    /** 生效配置；<b>token 永远是掩码</b>，不出现在这里。 */
    public Map<String, Object> config() {
        return config.describe();
    }

    /** span 表行数；没有存储时为 -1（-1 表示"读不到"，0 表示"确实没有"）。 */
    public int spanRows() {
        return count(new Count() {
            @Override
            public int get() throws Exception {
                return store.countSpans();
            }
        });
    }

    /** log_record 表行数。 */
    public int logRows() {
        return count(new Count() {
            @Override
            public int get() throws Exception {
                return store.countLogs();
            }
        });
    }

    /** metric_point 表行数。 */
    public int metricRows() {
        return count(new Count() {
            @Override
            public int get() throws Exception {
                return store.countMetrics();
            }
        });
    }

    /** resource_dict 行数（三信号共用的去重字典）。 */
    public int resourceRows() {
        return count(new Count() {
            @Override
            public int get() throws Exception {
                return store.countResources();
            }
        });
    }

    /** 最近的 span 表头行（不含载荷），按 id 倒序。 */
    public List<Map<String, Object>> recentSpans(final int limit) {
        if (store == null) {
            return null;
        }
        try {
            return store.recentSpans(clamp(limit));
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-spans", "读 span 表头失败", e);
            return null;
        }
    }

    /** 某个 trace 的全部 span 表头，按开始时间排 —— 详情页把一个 trace 拼起来就靠它。 */
    public List<Map<String, Object>> spansOfTrace(final String traceId) {
        if (store == null) {
            return null;
        }
        try {
            return store.spansOfTrace(traceId);
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-trace", "读 trace 失败 traceId=" + traceId, e);
            return null;
        }
    }

    /** 最近的日志表头行（不含载荷）。 */
    public List<Map<String, Object>> recentLogs(final int limit) {
        if (store == null) {
            return null;
        }
        try {
            return store.recentLogs(clamp(limit));
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-logs", "读 log_record 表头失败", e);
            return null;
        }
    }

    /**
     * 某个 trace 的全部日志记录，按时间排。
     *
     * <p>日志自带 trace / span 上下文（ADR-2），所以"按 trace 拉全量日志"是真实需求，
     * 不是顺手加的查询。没有它，日志与它所属的 span 就永远对不上。
     *
     * <p>不设 limit 与 {@link #spansOfTrace(String)} 保持一致：一个 trace 内的记录数
     * 由 trace 本身决定，不是无限增长的；真要防爆由
     * {@code rows.traces} / {@code rows.logs} 的行数水位兜底。
     */
    public List<Map<String, Object>> logsOfTrace(final String traceId) {
        if (store == null) {
            return null;
        }
        try {
            return store.logsOfTrace(traceId);
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-logs-of-trace", "读 trace 的日志失败 traceId=" + traceId, e);
            return null;
        }
    }

    /** 某个指标名最近的点（新到旧）；{@code metricName} 为空表示按时间倒序全部。 */
    public List<Map<String, Object>> recentMetricPoints(final String metricName, final int limit) {
        if (store == null) {
            return null;
        }
        try {
            return store.recentMetricPoints(metricName, clamp(limit, MAX_LIMIT_METRICS));
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-metrics", "读 metric_point 失败 name=" + metricName, e);
            return null;
        }
    }

    /** 一条 span 的载荷原始字节；没有载荷 / 已被环覆盖 / 读失败都是 {@code null}。 */
    public byte[] spanPayload(final long spanId) {
        if (store == null) {
            return null;
        }
        try {
            return store.spanPayload(spanId);
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-payload", "读 span 载荷失败 id=" + spanId, e);
            return null;
        }
    }

    /** 一条日志记录的载荷原始字节；语义同 {@link #spanPayload(long)}。 */
    public byte[] logPayload(final long logId) {
        if (store == null) {
            return null;
        }
        try {
            return store.logPayload(logId);
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-payload", "读日志载荷失败 id=" + logId, e);
            return null;
        }
    }

    /**
     * 每个（指标名，属性组合）序列的最新一点，供 Prometheus 端点渲染。
     *
     * <p><b>不加 limit 是刻意的</b>：Prometheus 要的是"当前值"，
     * 而按时间倒序截断会把某些序列的最新点挤出窗口、输出一个过期值。
     * 量的上界由 {@code rows.metrics} 行数水位与序列条数共同决定。
     */
    public List<Map<String, Object>> latestMetricPoints() {
        if (store == null) {
            return null;
        }
        try {
            return store.latestMetricPoints();
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-latest-metrics", "读各序列最新指标点失败", e);
            return null;
        }
    }

    /**
     * 扩展自有日志的尾部若干行（ADR-6 第九节）。
     *
     * <p><b>只读扩展自己写的那一个文件</b>（`&lt;dataDir&gt;/otelstore.log`），
     * 不碰应用的日志、也不猜 stderr 去了哪。
     *
     * <p>文件不存在/被截断/读失败都<b>不是错误</b>：返回空 {@code lines} 并给一句 {@code note}，
     * 让页面能说"暂无内容"而不是报 500 —— 运维删日志、进程刚起，都会碰到这个状态。
     *
     * @param lines 尾部行数；非正数走缺省，超过 {@link #MAX_SELF_LOG_LINES} 截到上限
     */
    public Map<String, Object> selfLog(final int lines) {
        final int n = clampSelfLogLines(lines);
        List<String> tail;
        try {
            tail = SelfLog.tail(new File(config.getDataDir()), n);
        } catch (final RuntimeException e) {
            ThrottledLogger.warn("readout-self-log", "读扩展日志尾部失败", e);
            tail = Collections.emptyList();
        }
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("lines", tail);
        m.put("file", SelfLog.FILE_NAME);
        m.put("note", tail.isEmpty()
                ? "暂无内容：扩展还没写过日志，或那个文件被外部删掉/截断了。"
                : "");
        return m;
    }

    /** 自有日志尾部行数的封顶口径；就这一处实现。 */
    public static int clampSelfLogLines(final int lines) {
        if (lines <= 0) {
            return DEFAULT_SELF_LOG_LINES;
        }
        return Math.min(lines, MAX_SELF_LOG_LINES);
    }

    /**
     * 一条 span 的表头行（详情页用，比列表多状态描述）。
     *
     * @return 那一行；行不存在（被水位淘汰 / id 写错）时为 {@code null}
     */
    public Map<String, Object> spanHeader(final long spanId) {
        if (store == null) {
            return null;
        }
        try {
            return store.spanHeader(spanId);
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-span-header", "读 span 表头失败 id=" + spanId, e);
            return null;
        }
    }

    /** 一条日志记录的表头行（详情页用，比列表多观测时间戳）。 */
    public Map<String, Object> logHeader(final long logId) {
        if (store == null) {
            return null;
        }
        try {
            return store.logHeader(logId);
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-log-header", "读日志表头失败 id=" + logId, e);
            return null;
        }
    }

    /**
     * 一条 span 的载荷<b>及其状态</b>。
     *
     * <p>状态是这一页的关键：载荷读不出来有四种原因，而"过期"是环形文件写满的**预期结果**、
     * 不是故障（ADR-3 第 2 种、ADR-6 第七节）。合并成一个 null 会让用户去查磁盘，
     * 而磁盘完全正常。
     *
     * @return 没有存储层时为 {@code null} —— 用它区分"没数据"与"读不了"
     */
    public LocalStore.PayloadResult spanPayloadOf(final long spanId) {
        if (store == null) {
            return null;
        }
        try {
            return store.spanPayloadOf(spanId);
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-span-payload", "读 span 载荷失败 id=" + spanId, e);
            return LocalStore.unreadable();
        }
    }

    /** 一条日志记录的载荷及其状态。 */
    public LocalStore.PayloadResult logPayloadOf(final long logId) {
        if (store == null) {
            return null;
        }
        try {
            return store.logPayloadOf(logId);
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-log-payload", "读日志载荷失败 id=" + logId, e);
            return LocalStore.unreadable();
        }
    }

    /**
     * 把行里的 {@code resourceId} 展开成那一份 Resource 的规范化文本，按 id 归并。
     *
     * <p>不展开的话读口只能给一串外键数字，排障时还得自己去查字典表 ——
     * 而字典表的存在正是为了让表头行变窄，不是为了让读口多跳一步。
     *
     * <p>同一 id 只查一次：一页几十行通常只对应两三种 Resource。
     *
     * @return id → 规范化文本；读不到时值是 {@code "<读不到 id=N>"}，不让整页失败
     */
    public Map<Long, String> resourceAttributesOf(final List<Map<String, Object>> rows) {
        final Map<Long, String> out = new LinkedHashMap<Long, String>();
        if (store == null || rows == null || rows.isEmpty()) {
            return out;
        }
        for (final Map<String, Object> row : rows) {
            final Object id = row.get("resourceId");
            if (!(id instanceof Number)) {
                continue;
            }
            final long key = ((Number) id).longValue();
            if (out.containsKey(key)) {
                continue;
            }
            try {
                out.put(Long.valueOf(key), store.resourceAttributes(key));
            } catch (final Exception e) {
                out.put(Long.valueOf(key), "<读不到 id=" + key + ">");
            }
        }
        return out;
    }

    private interface Count {
        int get() throws Exception;
    }

    private int count(final Count c) {
        if (store == null) {
            return -1;
        }
        try {
            return c.get();
        } catch (final Exception e) {
            ThrottledLogger.warn("readout-count", "计数查询失败", e);
            return -1;
        }
    }

    /** 非正数给默认条数，超过上限夹到上限。 */
    private static int clamp(final int limit) {
        return clamp(limit, MAX_LIMIT);
    }

    /** 指标点那一档单独封顶；其余端点仍走 {@link #MAX_LIMIT}。 */
    private static int clamp(final int limit, final int max) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, max);
    }
}