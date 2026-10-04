package io.github.fulizhe.otelstore.readout.jmx;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.storage.LocalStore;
import io.github.fulizhe.otelstore.core.util.ThrottledLogger;
import io.github.fulizhe.otelstore.readout.TextRenderer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * JMX 读口的实现。<b>结构性只读</b>：这里没有任何写方法，也拿不到 {@link LocalStore} 的写路径。
 *
 * <p>持有的是 {@link LocalStore} 实例本身而不是某个更窄的查询接口 ——
 * 写方法是 {@code store(...)} 那一族，签名上就与读方法区分得很开，
 * 而为了"读口不该拿到写能力"去造一层接口转发，代价是一份永远跟着存储演进的重复代码。
 * 真正的防线是 {@code readout} 这个包的约定：<b>只读</b>，要加修改能力先写 ADR。
 *
 * <p><b>每个读方法都自己吞异常</b>：JMX 调用方在另一个 ClassLoader 里，
 * 我们抛过去的任何异常在那边都会变成一句看不懂的 stack trace；
 * 返回一行说明为什么读不到，对排障有用得多。
 */
public final class LocalStoreSummary implements LocalStoreSummaryMBean {

    private final LocalStoreConfig config;
    private final LocalStore store;
    /** 队列侧快照由 {@code agentext} 提供（队列在那边），用 Supplier 避免 readout 依赖 agentext。 */
    private final Supplier<Map<String, Object>> queuesSnapshot;

    public LocalStoreSummary(final LocalStoreConfig config, final LocalStore store,
            final Supplier<Map<String, Object>> queuesSnapshot) {
        this.config = config;
        this.store = store;
        this.queuesSnapshot = queuesSnapshot;
    }

    @Override
    public String summary() {
        if (store == null) {
            return TextRenderer.render(snapshotOf(null));
        }
        try {
            return TextRenderer.render(snapshotOf(store.snapshot()));
        } catch (final RuntimeException e) {
            return "读快照失败：" + e;
        }
    }

    private Map<String, Object> snapshotOf(final Map<String, Object> storeSnapshot) {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("config", config.describe());
        m.put("queues", queuesSnapshot == null ? null : queuesSnapshot.get());
        m.put("store", storeSnapshot);
        return m;
    }

    @Override
    public String config() {
        return TextRenderer.render(config.describe());
    }

    @Override
    public int spanRows() {
        return count(new Count() {
            @Override
            public int get() throws Exception {
                return store.countSpans();
            }
        });
    }

    @Override
    public int logRows() {
        return count(new Count() {
            @Override
            public int get() throws Exception {
                return store.countLogs();
            }
        });
    }

    @Override
    public int metricRows() {
        return count(new Count() {
            @Override
            public int get() throws Exception {
                return store.countMetrics();
            }
        });
    }

    @Override
    public int resourceRows() {
        return count(new Count() {
            @Override
            public int get() throws Exception {
                return store.countResources();
            }
        });
    }

    @Override
    public String recentSpans(final int limit) {
        if (store == null) {
            return "存储层未就绪";
        }
        try {
            final List<Map<String, Object>> rows = store.recentSpans(clamp(limit));
            final StringBuilder sb = new StringBuilder(TextRenderer.renderRows("span", rows));
            appendResources(sb, rows);
            return sb.toString();
        } catch (final Exception e) {
            return "读 span 失败：" + e;
        }
    }

    @Override
    public String recentLogs(final int limit) {
        if (store == null) {
            return "存储层未就绪";
        }
        try {
            final List<Map<String, Object>> rows = store.recentLogs(clamp(limit));
            final StringBuilder sb = new StringBuilder(TextRenderer.renderRows("log_record", rows));
            appendResources(sb, rows);
            return sb.toString();
        } catch (final Exception e) {
            return "读 log_record 失败：" + e;
        }
    }

    @Override
    public String spansOfTrace(final String traceId) {
        if (store == null) {
            return "存储层未就绪";
        }
        try {
            final List<Map<String, Object>> rows = store.spansOfTrace(traceId);
            final StringBuilder sb = new StringBuilder(TextRenderer.renderRows("trace " + traceId, rows));
            appendResources(sb, rows);
            return sb.toString();
        } catch (final Exception e) {
            return "读 trace 失败：" + e;
        }
    }

    @Override
    public String recentMetricPoints(final String metricName, final int limit) {
        if (store == null) {
            return "存储层未就绪";
        }
        try {
            return TextRenderer.renderRows("metric_point " + metricName,
                    store.recentMetricPoints(metricName, clamp(limit)));
        } catch (final Exception e) {
            return "读 metric_point 失败：" + e;
        }
    }

    @Override
    public String spanPayloadHex(final long spanId) {
        final byte[] payload = payload(spanId);
        return payload == null ? "" : toHex(payload);
    }

    @Override
    public int spanPayloadLength(final long spanId) {
        final byte[] payload = payload(spanId);
        return payload == null ? -1 : payload.length;
    }

    private byte[] payload(final long spanId) {
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

    /**
     * 把行里的 {@code resource_id} 就地展开成那一份 Resource 的规范化文本。
     *
     * <p>不展开的话读口只能给一串外键数字，排障时还得自己去查字典表 —— 而字典表的存在
     * 正是为了让 span 行变窄，不是为了让读口多跳一步。
     */
    private void appendResources(final StringBuilder sb, final List<Map<String, Object>> rows) {
        if (store == null || rows == null || rows.isEmpty()) {
            return;
        }
        final Map<Long, String> cache = new LinkedHashMap<Long, String>();
        for (final Map<String, Object> row : rows) {
            final Object id = row.get("resourceId");
            if (!(id instanceof Number)) {
                continue;
            }
            final long key = ((Number) id).longValue();
            if (cache.containsKey(key)) {
                continue;
            }
            try {
                cache.put(Long.valueOf(key), store.resourceAttributes(key));
            } catch (final Exception e) {
                cache.put(Long.valueOf(key), "<读不到 id=" + key + ">");
            }
        }
        if (cache.isEmpty()) {
            return;
        }
        sb.append("\nresource_dict: ").append(TextRenderer.render(cache));
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

    /**
     * 限制返回行数。
     *
     * <p>不限制的话一次 JMX 调用能把二十万行全拉出来 ——
     * 读口是用来"看一眼现在什么状态"的，不是用来导出数据的。
     */
    private static int clamp(final int limit) {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, 200);
    }

    private static String toHex(final byte[] bytes) {
        final char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int v = bytes[i] & 0xFF;
            hex[i * 2] = hexChar(v >>> 4);
            hex[i * 2 + 1] = hexChar(v & 0x0F);
        }
        return new String(hex);
    }

    private static char hexChar(final int v) {
        return (char) (v < 10 ? ('0' + v) : ('a' + v - 10));
    }
}
