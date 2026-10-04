package io.github.fulizhe.otelstore.readout.jmx;

/**
 * JMX 读口的操作契约。
 *
 * <p><b>属性一律是 {@code String} 或 {@code int}</b>，没有别的类型。这是跨 ClassLoader 的硬约束：
 * 本扩展的类在 {@code ExtensionClassLoader} 上，JMX 客户端通常在 {@code AppClassLoader} 上，
 * 属性类型只要是我们自己定义的类，客户端拿到的就只是一句 "unmappable class" 的报错。
 *
 * <p>{@code summary} 之所以是 {@code String} 而不是嵌套 Map，就是这个原因 ——
 * 它要能跨边界、跨工具（{@code jconsole}、{@code jcmd}、抓取器）都读得出来。
 *
 * <p><b>刻意没有"任意表 + 任意条件"的通用查询</b>（ADR-1 明确拒绝 raw SQL 端点）。
 * 每一种查询都是一个具名方法：口径写在方法名里，读口页面照着渲染，
 * 就不必让人去记"哪个表有什么列"。
 */
public interface LocalStoreSummaryMBean {

    /** 全部口径的组合快照（配置 + 队列 + 存储 + 环形文件），多行文本。 */
    String summary();

    /** 生效配置；<b>token 永远是掩码</b>，不出现在这里。 */
    String config();

    /** span 表行数。 */
    int spanRows();

    /** log_record 表行数。 */
    int logRows();

    /** metric_point 表行数。 */
    int metricRows();

    /** resource_dict 行数（三信号共用的去重字典）。 */
    int resourceRows();

    /** 最近若干条 span 的表头（不含载荷），按 id 倒序，编号从 0 起。 */
    String recentSpans(int limit);

    /** 最近若干条日志的表头（不含载荷）。 */
    String recentLogs(int limit);

    /** 某个 trace 的全部 span 表头，按开始时间排 —— 详情页把一个 trace 拼起来就靠它。 */
    String spansOfTrace(String traceId);

    /** 最近若干个指标点。 */
    String recentMetricPoints(String metricName, int limit);

    /**
     * 一条 span 的载荷（编码后的 OTLP protobuf bytes）的十六进制。
     *
     * <p>出十六进制而不是 Base64：这一条是<b>排障专用</b>的口子，
     * 复制出来就能直接喂给 {@code protoscope} 之类的工具看。
     *
     * @return 十六进制；没有载荷时为空串
     */
    String spanPayloadHex(long spanId);

    /**
     * 上一条的原始字节长度。
     *
     * <p>存在的理由：空串有两个含义（没有载荷 / 被环覆盖），而它们要分开的正是 ADR-3 的
     * 第 3 种与第 2 种。只给十六进制时，客户端看不出到底发生了哪一种。
     */
    int spanPayloadLength(long spanId);
}
