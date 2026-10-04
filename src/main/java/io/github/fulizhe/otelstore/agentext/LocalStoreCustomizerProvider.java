package io.github.fulizhe.otelstore.agentext;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * agent 扩展入口。
 *
 * <p>主接入点是三个 {@code *ProviderCustomizer} —— 它们给的是<b>未 build 的 builder</b>，
 * 我们在上面注册自己的组件。这样注册与"用户是否配了 exporter"无关：
 * 用户设 {@code otel.traces.exporter=none} 时照样有数据进本地库。
 *
 * <p>不用 {@code *ExporterCustomizer} 作主路径：那是装饰器不是替换器
 * （SPI 里没有 {@code setXxxExporter}），要"数据留本地"只能丢弃传入的 delegate。
 *
 * @see docs/adr/adr-01-scope-and-principles.md
 */
public final class LocalStoreCustomizerProvider implements AutoConfigurationCustomizerProvider {

    private static final Logger LOGGER = Logger.getLogger(LocalStoreCustomizerProvider.class.getName());

    /** 指标采集周期。OTel 默认 60s，对"本地自查"场景太钝；10s 是折中。 */
    static final long METRIC_INTERVAL_MS = 10_000L;

    /** agent 自身自监控指标的 instrumentation scope 前缀 —— 这些不入库（ADR-1 第 2 条原则）。 */
    static final String[] SELF_TELEMETRY_PREFIXES = {
            "io.opentelemetry.sdk.trace",
            "io.opentelemetry.sdk.logs",
            "io.opentelemetry.sdk.metrics",
            "io.opentelemetry.runtime-telemetry",
            "io.opentelemetry.exporters",
    };

    /** 进程内单例：三条队列必须共用同一个 hub，否则快照拼不起来。 */
    private static volatile TapHub hub;

    /** 供读口与测试取用；未初始化时返回 null。 */
    public static TapHub hub() {
        return hub;
    }

    @Override
    public void customize(final AutoConfigurationCustomizer customizer) {
        try {
            customizer.addTracerProviderCustomizer((builder, props) -> {
                builder.addSpanProcessor(new SpanTap(hubOrInit(props).traces()));
                return builder;
            });

            customizer.addLoggerProviderCustomizer((builder, props) -> {
                builder.addLogRecordProcessor(new LogTap(hubOrInit(props).logs()));
                return builder;
            });

            customizer.addMeterProviderCustomizer((builder, props) -> {
                builder.registerMetricReader(PeriodicMetricReader
                        .builder(new MetricTap(hubOrInit(props).metrics(), SELF_TELEMETRY_PREFIXES))
                        .setInterval(METRIC_INTERVAL_MS, TimeUnit.MILLISECONDS)
                        .build());
                return builder;
            });
        } catch (final RuntimeException e) {
            // 扩展自身的异常绝不能让应用起不来：注册失败只等于"本扩展本次不生效"，应用照常。
            LOGGER.warning("[otel-local-telemetry-store] 扩展注册失败，本扩展本次不生效：" + e);
        }
    }

    /**
     * 取 hub，没有就按 {@code otel.localstore.*} 建一个。
     *
     * <p>{@link ConfigProperties} 只在 lambda 参数里拿得到（{@code customize} 本身没有），
     * 所以初始化只能发生在第一个 lambda 里。三个 lambda 都会调它，靠单例保证只建一次。
     */
    private static TapHub hubOrInit(final ConfigProperties props) {
        TapHub h = hub;
        if (h != null) {
            return h;
        }
        synchronized (LocalStoreCustomizerProvider.class) {
            if (hub == null) {
                hub = create(props);
            }
            return hub;
        }
    }

    /**
     * 从 {@code otel.localstore.*} 键值对建 hub。
     *
     * <p>这是可测的核心；{@link #create(ConfigProperties)} 只是"从 agent 配置里把键读出来"的壳。
     * 键<b>不带</b> {@link LocalStoreConfig#PREFIX} 前缀。
     */
    static TapHub create(final Map<String, String> suffixToRawValue) {
        final Map<String, String> collected = new LinkedHashMap<String, String>();
        if (suffixToRawValue != null) {
            for (final Map.Entry<String, String> e : suffixToRawValue.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    collected.put(LocalStoreConfig.PREFIX + e.getKey(), e.getValue());
                }
            }
        }
        final LocalStoreConfig config = LocalStoreConfig.from(collected);
        final TapHub created = new TapHub(config);
        LOGGER.info("[otel-local-telemetry-store] 已注册三条采集管线"
                + " dataDir=" + config.getDataDir()
                + " queueCapacity=" + config.getQueueCapacity()
                + " cappedTracesBytes=" + config.getCappedTracesBytes()
                + " cappedLogsBytes=" + config.getCappedLogsBytes()
                + " metricIntervalMs=" + METRIC_INTERVAL_MS);
        return created;
    }

    /**
     * 从 agent 提供的 {@link ConfigProperties} 建 hub。
     *
     * <p>取不到的键用默认值（配置永不失败，见 ADR-1）。
     */
    static TapHub create(final ConfigProperties props) {
        final Map<String, String> collected = new LinkedHashMap<String, String>();
        final String[] keys = LocalStoreConfig.knownKeySuffixes();
        for (int i = 0; i < keys.length; i++) {
            final String raw = props == null ? null : props.getString(keys[i]);
            if (raw != null) {
                collected.put(keys[i], raw);
            }
        }
        return create(collected);
    }
}