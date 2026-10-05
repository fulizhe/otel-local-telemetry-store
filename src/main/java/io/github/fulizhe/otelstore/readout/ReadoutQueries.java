package io.github.fulizhe.otelstore.readout;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.storage.LocalStore;
import io.github.fulizhe.otelstore.core.util.ThrottledLogger;
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

    private final LocalStoreConfig config;
    private final LocalStore store;
    /** 队列侧快照由 {@code agentext} 提供（队列在那边），用 Supplier 避免 readout 依赖 agentext。 */
    private final Supplier<Map<String, Object>> queuesSnapshot;

    /**
     * @param store 存储层；为 null 时读口要能说清"没有存储"而不是"没有数据"
     */
    public ReadoutQueries(final LocalStoreConfig config, final LocalStore store,
            final Supplier<Map<String, Object>> queuesSnapshot) {
        this.config = config;
        this.store = store;
        this.queuesSnapshot = queuesSnapshot;
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
        m.put("queues", queuesSnapshot == null ? null : queuesSnapshot.get());
        m.put("store", storeSnapshot());
        return m;
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
            return store.recentMetricPoints(metricName, clamp(limit));
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
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}