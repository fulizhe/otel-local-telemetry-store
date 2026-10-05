package io.github.fulizhe.otelstore.core.config;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地遥测存储的运行期配置。
 *
 * <p>从扁平键值对（{@code otel.localstore.*}）解析，每一项都有默认值。
 * <b>解析永不失败</b>：非法取值一律回落到默认值而不是抛异常 —— 本扩展跑在客户应用进程内，
 * 一个配置笔误不该让那个应用起不来。
 */
public final class LocalStoreConfig {

    /** 配置键前缀。刻意避开 OTel 自身的 {@code otel.traces.*} 等保留命名空间。 */
    public static final String PREFIX = "otel.localstore.";

    public static final String DEFAULT_HOST = "0.0.0.0";

    /** 避开 4317 / 4318 —— 那是 OTLP 的惯例端口，用了会让读口与 OTLP 语义混淆。 */
    public static final int DEFAULT_PORT = 17890;

    public static final String DEFAULT_DATA_DIR = "./otel-local-telemetry-store";
    public static final long DEFAULT_CAPPED_TRACES_BYTES = 256L * 1024L * 1024L;
    public static final long DEFAULT_CAPPED_LOGS_BYTES = 256L * 1024L * 1024L;
    public static final long DEFAULT_MAX_PAYLOAD_BYTES = 1024L * 1024L;
    public static final int DEFAULT_ROWS_TRACES = 200000;
    public static final int DEFAULT_ROWS_LOGS = 200000;
    public static final int DEFAULT_ROWS_METRICS = 200000;

    /**
     * <b>默认不要 token</b>（2026-10-04 决定，与初版相反）。
     *
     * <p>理由是这个扩展的定位是"<b>挂在本机应用里自查</b>"：默认要 token 会让最常见的用法
     * （本地起一个进程，浏览器看一眼）多出一道"去文件里复制 token 粘进页面"的工序，
     * 而那道工序挡住的不是真实威胁 —— 真要读你数据的人已经有进程内权限了。
     *
     * <p><b>代价必须说清</b>：{@code host} 默认仍是 {@code 0.0.0.0}（ADR-1），
     * 两者相加意味着<b>默认状态下，同一网络内的任何机器都能读走全部 trace 与日志载荷</b> ——
     * 里面装着 SQL 语句、HTTP header（含 {@code Authorization} 与 {@code Cookie}）、请求体、日志原文。
     * 因此配了 {@code auth=true} 时，token 仍必须每进程随机、且绝不进入任何日志 / 快照 / 异常消息。
     *
     * @see docs/adr/adr-06-readout-http-surface.md 第四节
     */
    public static final boolean DEFAULT_AUTH_ENABLED = false;

    /**
     * 每条信号各自的有界队列容量。
     *
     * <p>三个信号暂用同一个容量 —— 差异化的容量在有实测数据（哪条路径更容易打满）之前不猜。
     */
    public static final int DEFAULT_QUEUE_CAPACITY = 4096;

    /** 配置键的<b>后缀</b>列表（不含 {@link #PREFIX} 前缀）。采集侧据此从 {@code ConfigProperties} 取值。 */
    private static final String[] KEYS = {
            "host", "port", "auth", "token", "dataDir",
            "capped.traces.bytes", "capped.logs.bytes", "max.payload.bytes",
            "rows.traces", "rows.logs", "queue.capacity", "rows.metrics",
    };

    /** 供采集侧遍历的配置键后缀。返回副本，调用方改不动内部状态。 */
    public static String[] knownKeySuffixes() {
        return KEYS.clone();
    }

    private static final String MASKED_TOKEN = "***";

    private final String host;
    private final int port;
    private final boolean authEnabled;
    private final String token;
    private final String dataDir;
    private final long cappedTracesBytes;
    private final long cappedLogsBytes;
    private final long maxPayloadBytes;
    private final int rowsTraces;
    private final int rowsLogs;
    private final int queueCapacity;
    private final int rowsMetrics;

    private LocalStoreConfig(final String host, final int port, final boolean authEnabled, final String token,
                             final String dataDir, final long cappedTracesBytes, final long cappedLogsBytes,
                             final long maxPayloadBytes, final int rowsTraces, final int rowsLogs,
                             final int queueCapacity, final int rowsMetrics) {
        this.host = host;
        this.port = port;
        this.authEnabled = authEnabled;
        this.token = token;
        this.dataDir = dataDir;
        this.cappedTracesBytes = cappedTracesBytes;
        this.cappedLogsBytes = cappedLogsBytes;
        this.maxPayloadBytes = maxPayloadBytes;
        this.rowsTraces = rowsTraces;
        this.rowsLogs = rowsLogs;
        this.queueCapacity = queueCapacity;
        this.rowsMetrics = rowsMetrics;
    }

    /** 全默认配置。 */
    public static LocalStoreConfig defaults() {
        return from(Collections.<String, String>emptyMap());
    }

    /**
     * 从扁平键值对解析。键需带 {@link #PREFIX} 前缀；缺失、非数字或非正数的项回落默认值。
     *
     * @param props 配置源，允许为 null（等价于 {@link #defaults()}）
     */
    public static LocalStoreConfig from(final Map<String, String> props) {
        final Map<String, String> p = props == null ? Collections.<String, String>emptyMap() : props;
        return new LocalStoreConfig(
                readString(p, "host", DEFAULT_HOST),
                readInt(p, "port", DEFAULT_PORT),
                readBoolean(p, "auth", DEFAULT_AUTH_ENABLED),
                readString(p, "token", null),
                readString(p, "dataDir", DEFAULT_DATA_DIR),
                readLong(p, "capped.traces.bytes", DEFAULT_CAPPED_TRACES_BYTES),
                readLong(p, "capped.logs.bytes", DEFAULT_CAPPED_LOGS_BYTES),
                readLong(p, "max.payload.bytes", DEFAULT_MAX_PAYLOAD_BYTES),
                readInt(p, "rows.traces", DEFAULT_ROWS_TRACES),
                readInt(p, "rows.logs", DEFAULT_ROWS_LOGS),
                readInt(p, "queue.capacity", DEFAULT_QUEUE_CAPACITY),
                readInt(p, "rows.metrics", DEFAULT_ROWS_METRICS));
    }

    private static String readString(final Map<String, String> p, final String key, final String fallback) {
        final String raw = p.get(PREFIX + key);
        if (raw == null) {
            return fallback;
        }
        final String trimmed = raw.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static int readInt(final Map<String, String> p, final String key, final int fallback) {
        final long v = readLong(p, key, fallback);
        if (v <= 0L || v > Integer.MAX_VALUE) {
            return fallback;
        }
        return (int) v;
    }

    private static long readLong(final Map<String, String> p, final String key, final long fallback) {
        final String raw = p.get(PREFIX + key);
        if (raw == null) {
            return fallback;
        }
        try {
            final long v = Long.parseLong(raw.trim());
            return v > 0L ? v : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(final Map<String, String> p, final String key, final boolean fallback) {
        final String raw = p.get(PREFIX + key);
        if (raw == null) {
            return fallback;
        }
        final String v = raw.trim();
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            return Boolean.parseBoolean(v);
        }
        return fallback;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    /** 访问 token；未显式配置时为 null，表示由读口在启动时随机生成。 */
    public String getToken() {
        return token;
    }

    /** 数据目录。相对路径原样保留在配置里，但对外呈现一律用 {@link #getDataDirAbsolute()}。 */
    public String getDataDir() {
        return dataDir;
    }

    /**
     * 数据目录的<b>绝对</b>路径。
     *
     * <p>为什么读口页面、启动日志、周期汇总都用它而不是配置里的原值：
     * 相对路径在别人的屏幕上是没有意义的 —— 它相对于**谁的**工作目录？
     * 而"文件在哪"恰恰是排障时要的第一件事。
     *
     * <p>它只做字符串层面的解析（不碰文件系统），所以配置层不会因为"目录还不存在"而失败。
     */
    public String getDataDirAbsolute() {
        return new File(dataDir).getAbsolutePath();
    }

    public long getCappedTracesBytes() {
        return cappedTracesBytes;
    }

    public long getCappedLogsBytes() {
        return cappedLogsBytes;
    }

    public long getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public int getRowsTraces() {
        return rowsTraces;
    }

public int getRowsLogs() {
        return rowsLogs;
    }

    /**
     * {@code metric_point} 的行数水位。
     *
     * <p>metrics 不走环形文件（ADR-2），所以它的"有界"只能靠行数 —— 没有这个旋钮，
     * 指标点会随进程存活无限增长，与 ADR-1 的有界预算直接冲突。
     */
    public int getRowsMetrics() {
        return rowsMetrics;
    }

    /** 每条信号的有界队列容量。 */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /** 生效配置的快照，供启动日志与 JMX 展示。token 永不出现在这里。 */
    public Map<String, Object> describe() {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("host", host);
        m.put("port", Integer.valueOf(port));
        m.put("auth", Boolean.valueOf(authEnabled));
        m.put("token", token == null ? "<generated-at-startup>" : MASKED_TOKEN);
        m.put("dataDir", getDataDirAbsolute());
        m.put("cappedTracesBytes", Long.valueOf(cappedTracesBytes));
        m.put("cappedLogsBytes", Long.valueOf(cappedLogsBytes));
        m.put("maxPayloadBytes", Long.valueOf(maxPayloadBytes));
        m.put("rowsTraces", Integer.valueOf(rowsTraces));
        m.put("rowsLogs", Integer.valueOf(rowsLogs));
        m.put("rowsMetrics", Integer.valueOf(rowsMetrics));
        m.put("queueCapacity", Integer.valueOf(queueCapacity));
        return m;
    }

    @Override
    public String toString() {
        return "LocalStoreConfig" + describe();
    }
}