package io.github.fulizhe.otelstore.readout.jmx;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.storage.LocalStore;
import io.github.fulizhe.otelstore.readout.ReadoutQueries;
import io.github.fulizhe.otelstore.readout.TextRenderer;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * JMX 读口。**只做一件事：把 {@link ReadoutQueries} 给的数据渲染成缩进文本。**
 *
 * <p>查询本身全在共享层（那是 Phase 5 抽出来的），因此本类**没有一行 SQL 也没有一个
 * "查询失败"的 try/catch** —— 它只管排版与措辞。加端点时改这里，加查询时改共享层，
 * 两件事不会再互相缠住。
 *
 * <p><b>每个读方法都自己吞异常</b>：JMX 调用方在另一个 ClassLoader 里，
 * 我们抛过去的任何异常在那边都会变成一句看不懂的 stack trace；
 * 返回一行说明为什么读不到，对排障有用得多。
 *
 * <p>持的是 {@link LocalStore} 实例而不是更窄的查询接口 —— 写方法是 {@code store(...)} 那一族，
 * 签名上就与读方法区分得很开；真正的防线是 {@code readout} 这个包的约定：<b>只读</b>。
 */
public final class LocalStoreSummary implements LocalStoreSummaryMBean {

    private final LocalStoreConfig config;
    private final LocalStore store;
    private final ReadoutQueries queries;

    public LocalStoreSummary(final LocalStoreConfig config, final LocalStore store,
            final Supplier<Map<String, Object>> queuesSnapshot) {
        this.config = config;
        this.store = store;
        this.queries = new ReadoutQueries(config, store, queuesSnapshot);
    }

    @Override
    public String summary() {
        return TextRenderer.render(queries.summary());
    }

    @Override
    public String config() {
        return TextRenderer.render(queries.config());
    }

    @Override
    public int spanRows() {
        return queries.spanRows();
    }

    @Override
    public int logRows() {
        return queries.logRows();
    }

    @Override
    public int metricRows() {
        return queries.metricRows();
    }

    @Override
    public int resourceRows() {
        return queries.resourceRows();
    }

    @Override
    public String recentSpans(final int limit) {
        return rows("span", queries.recentSpans(limit));
    }

    @Override
    public String recentLogs(final int limit) {
        return rows("log_record", queries.recentLogs(limit));
    }

    @Override
    public String spansOfTrace(final String traceId) {
        return rows("trace " + traceId, queries.spansOfTrace(traceId));
    }

    @Override
    public String recentMetricPoints(final String metricName, final int limit) {
        final List<Map<String, Object>> rows = queries.recentMetricPoints(metricName, limit);
        if (store == null) {
            return "存储层未就绪";
        }
        if (rows == null) {
            return "读 metric_point 失败";
        }
        return TextRenderer.renderRows("metric_point " + metricName, rows);
    }

    /**
     * 一张表的排版：行数 + 逐行展开 + 顺带把 {@code resourceId} 就地换成那一份 Resource 的文本。
     *
     * <p>口径上有个小区别值得留着：指标行**不**展开 resource —— 它没有时间也没有 span 名可对齐，
     * 展开它只会让输出变长而不增加可读性。这是有意的，不是漏了。
     */
    private String rows(final String caption, final List<Map<String, Object>> rows) {
        if (store == null) {
            return "存储层未就绪";
        }
        if (rows == null) {
            return "读 " + caption + " 失败";
        }
        final StringBuilder sb = new StringBuilder(TextRenderer.renderRows(caption, rows));
        final Map<Long, String> resources = queries.resourceAttributesOf(rows);
        if (!resources.isEmpty()) {
            sb.append("\nresource_dict: ").append(TextRenderer.render(resources));
        }
        return sb.toString();
    }

    @Override
    public String spanPayloadHex(final long spanId) {
        final byte[] payload = queries.spanPayload(spanId);
        return payload == null ? "" : toHex(payload);
    }

    @Override
    public int spanPayloadLength(final long spanId) {
        final byte[] payload = queries.spanPayload(spanId);
        return payload == null ? -1 : payload.length;
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