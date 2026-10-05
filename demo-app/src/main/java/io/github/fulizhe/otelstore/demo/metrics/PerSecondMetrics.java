package io.github.fulizhe.otelstore.demo.metrics;

import io.github.fulizhe.otelstore.demo.stats.GeneratedSignals;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.Meter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每秒把<b>这一秒的真实计数</b>写成一个指标点。
 *
 * <p>存在的理由（ADR-6 第八节第 6 条）：业务指标的历史归业务层自己滚点，
 * 本地存储不替业务缓存历史。demo-app 就是这个"业务层"。
 *
 * <p><b>为什么不用 gauge 回调</b>：回调是<b>采集时轮询</b>的，不需要定时器 ——
 * 而"每秒一个点"是<b>写入</b>动作，没人定时发就没有点。
 * 所以这里用<b>同步 gauge</b>（{@code set()} 一次就写一个点），而不是异步回调。
 *
 * <p><b>不发假数据</b>：值来自 {@link GeneratedSignals} 的实际增量。
 * 没有任何请求时它就该是 0 —— 那是一个真实的事实，不是"没数据"。
 *
 * <h2>一个必须写进文档的副作用</h2>
 *
 * 86400 点/天 × 3 个指标 ≈ <b>26 万点/天</b>，而 {@code rows.metrics} 默认水位是
 * <b>200000</b>。所以跑一天之后就开始淘汰，读口上「已淘汰行数」会持续增长。
 * <b>这是水位在正常工作，不是故障</b> —— 不写清楚的话，第一次跑久的人会以为坏了。
 */
@Component
public class PerSecondMetrics {

    private static final Logger LOG = LoggerFactory.getLogger("otelstore.demo.per_second");

    /** 与其它 demo 指标同一个 scope，便于在读口按来源分组时归到一起。 */
    private static final String SCOPE = "otelstore-demo";
    private static final String SCOPE_VERSION = "0.1.0";

    private static final Attributes ATTRS =
            Attributes.of(io.opentelemetry.api.common.AttributeKey.stringKey("route"), "demo");

    private final GeneratedSignals stats;

    private final LongGauge requests;
    private final LongGauge spans;
    private final LongGauge metricPoints;

    @Autowired
    public PerSecondMetrics(final GeneratedSignals stats) {
        this.stats = stats;
        final Meter meter = GlobalOpenTelemetry.get().meterBuilder(SCOPE)
                .setInstrumentationVersion(SCOPE_VERSION).build();
        this.requests = meter.gaugeBuilder("demo.per_second.requests")
                .ofLongs().setDescription("这一秒的顶层请求数").setUnit("1").build();
        this.spans = meter.gaugeBuilder("demo.per_second.spans")
                .ofLongs().setDescription("这一秒的全部 span 数（含子 span）").setUnit("1").build();
        this.metricPoints = meter.gaugeBuilder("demo.per_second.metric_points")
                .ofLongs().setDescription("这一秒写入的指标点数").setUnit("1").build();
    }

    /**
     * 每秒一次。值 = 这一秒的实际增量。
     *
     * <p>单线程、固定频率：指标点的"每秒一个"这个口径靠的是<b>频率本身</b>，
     * 并发跑多个 tick 只会让同一个秒里写进两个点，把时间轴弄乱。
     */
    @Scheduled(fixedRate = 1000L, initialDelay = 1000L)
    public void tick() {
        try {
            final Map<String, Long> d = stats.drainPerSecondDelta();
            requests.set(d.get("requests").longValue(), ATTRS);
            spans.set(d.get("spans").longValue(), ATTRS);
            metricPoints.set(d.get("metric_points").longValue(), ATTRS);
        } catch (final RuntimeException e) {
            // 定时任务里抛异常会让后续 tick 一起停掉（Spring 会吞掉），
            // 而"指标停了"在读口上看起来像存储坏了。压在这里并记一笔。
            LOG.warn("每秒计数发送失败（下一 tick 继续）", e);
        }
    }
}