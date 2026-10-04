package io.github.fulizhe.otelstore.core.config;

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
    public static final boolean DEFAULT_AUTH_ENABLED = true;

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

    public String getDataDir() {
        return dataDir;
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
        m.put("dataDir", dataDir);
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