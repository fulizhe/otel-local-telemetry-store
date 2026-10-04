# 三个信号一律"就地落库、只读暴露"，接入点用 Provider builder 而非 exporter 装饰器

范围要在 in-agent 前提下同时吃下 traces / logs / metrics。R0 实测（`docs/notes/2026-10-04-r0-extension-points.md`）确认 agent 只给一个扩展点 `io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider`，11 个 customizer 全部可注册，但三者形态不同：`*ProviderCustomizer` 给的是**未 build 的 builder**，`*ExporterCustomizer` / `*ProcessorCustomizer` 给的是**autoconfigure 已经造好的对象**。

**决策**：主接入点用三个 `*ProviderCustomizer`，在 builder 上注册我们自己的 processor / reader；`*ExporterCustomizer` 只作为可选的 tee 模式。数据一律留在产生它的 JVM 里，默认不转发给 agent 配的那个 exporter。三条写入路径各自一条有界队列 + 独立 drainer 线程 + 丢弃计数，任何情况下不在采集线程内 inline 写存储。跨 ClassLoader 的只读通道只有 JMX 与扩展自起的 HTTP 读口，不提供应用可调的 toolkit 桩。

## Scope

**做**

- traces / logs / metrics 三个信号，单 JVM、单体应用优先
- 硬预算：traces 与 logs 各一个堆外定长环形文件（`capped.traces.bytes` / `capped.logs.bytes`），H2 侧各有一张表头表 + 行数水位（`rows.traces` / `rows.logs`）；metrics 走时间序列 rollup，不进环形文件
- 读口：JMX（跨 ClassLoader 的主通道）+ HTTP（含 Prometheus 文本端点）；默认开启、默认绑 `0.0.0.0`、token 鉴权、只注册 GET/HEAD
- 端口先试配置值，`BindException` 则退到随机端口并报出实际值，**绝不让端口冲突变成启动失败**

**不做**

- **profiling** —— 需要 native agent（async-profiler），OTel 信号管线给不了。研究问题见 `docs/notes/2026-10-04-profiling-research.md`
- **应用可调的 toolkit 桩** —— 拿不到 `java.lang.instrument.Instrumentation`（extension-api 里没有 `InstrumentationAccess`），JMX 是唯一通道
- **raw SQL 查询端点** —— 永远不提供，读口因此是结构性只读而非约定只读
- **产品级 Web UI** —— v1 只给读口与 Prometheus 端点

## 不可让的原则

1. **资源属性白名单 + 脱敏。** 实测 Resource 有 18 个属性且三信号完全相同，其中 `process.command_line` 是**完整命令行** —— 命令行里带 `-Dxxx.token=` 就等于把密钥存盘。落库只取白名单键。
2. **自监控指标按 instrumentation scope 过滤。** 实测 agent 自身的 `io.opentelemetry.sdk.trace` / `io.opentelemetry.sdk.logs` / `io.opentelemetry.runtime-telemetry-java8` / `io.opentelemetry.exporters.otlp-http` 指标也会流进我们的 reader；不过滤，用户库里一半是 agent 自监控数据。反过来，SDK 层的丢弃信号（`processedSpans[dropped=true]`、`processedLogs`、`queueSize`）是白送的，不必自己埋点。
3. **配置永不失败。** 非法取值回落默认值，不抛异常 —— 本扩展跑在客户进程里，一个配置笔误不该让那个应用起不来。
4. **区分"丢了"与"过期"。** 环形文件写满是预期行为不是故障，两者分开计数（细化见 ADR-3）。
5. **扩展 jar 只走 `otel.javaagent.extensions`，且不得与应用 classpath 同路径。** 指向不存在的路径时 agent **静默忽略、零告警**；同一个 jar 同时出现在扩展路径与应用 classpath 上会加载出两个不同的类。
6. **`io.opentelemetry.context.*` 一个都别 import。** agent 对该包做了重定位（`io.opentelemetry.javaagent.shaded.io.opentelemetry.context`），编译期看不到、运行期类型不同。一律编译 against 未重定位的普通 artifact；SPI 版本钉在 `1.66.0`（已核对与 agent 2.32.0 内嵌那份逐方法一致）。

## Considered Options

- **挂自建 Collector 而非 in-agent**：拒绝。多一跳、多一个要运维的进程，直接违反"只挂一个 agent"这个唯一区分点。有界成本在单体场景下才是强命题，而 Collector 让"有界"变成"别人进程的内存"。
- **用 `*ExporterCustomizer` 装饰 agent 配好的 exporter 作为主路径**：拒绝。那是装饰器不是替换器（SPI 里没有 `setXxxExporter`），要"数据留本地"只能丢弃传入的 delegate —— 语义别扭，且高度依赖 autoconfigure 是否已配好 exporter（用户配 `otel.traces.exporter=none` 时就没有可装饰对象）。改用 Provider builder 后，注册我们自己的组件与用户是否配了 exporter 无关。
- **保留 SW 侧的"内存热层 + 双源对账"**：拒绝。对账存在的原因是"我魔改了上报通路，怕改坏数据"；走官方扩展点没有这个风险。反过来，丢弃与积压成为唯一风险，所以计数换成 drop / backlog / 环覆盖。
- **一个共享环形文件按百分比给三信号分配额**：拒绝。环写满后槽位不再携带"自己属于哪个信号"的信息，按信号核算用量做不到。改为 traces / logs 各一个环文件 + H2 侧各自行数水位 —— 配额变成三个独立旋钮，语义更直白，副作用是日志洪峰不会挤掉 trace。
- **给应用留一个可调的 toolkit 桩（对齐 SkyWalking 的宿主工具类）**：拒绝。前提不成立 —— extension-api 里没有 `InstrumentationAccess`，拿不到 `Instrumentation` 就无法 `appendToSystemClassLoaderSearch`；JDK 17 上反射 `ClassLoader.appendToClassPathForInstrumentation` 需要 `--add-opens`。改用 JMX：平台级，不受 ClassLoader 边界影响。
- **把 agent 自监控指标一并存进本地库**：拒绝。用户查自己的 trace 时看到一半是 `jvm.*` 与 `otlp.exporter.*`，等于把 SDK 内部状态混进业务数据。这些指标走 JMX 面板，不入库。

## Consequences

- Phase 4 的形状被这一条定死：`agentext` 里一个 `AutoConfigurationCustomizerProvider` 实现，在 `customize()` 里注册三个 lambda；每条路径的入队与落盘分离，采集线程只做一次 `offer`。
- **logs 的业务线程风险比预想的小，但没消失**：SDK 1.66 有 `BatchLogRecordProcessor`（autoconfigure 默认就装），所以 `onEmit` 只是入队。但我们自己的入队**仍不能阻塞** —— 队列满就丢并计数，不能 inline 压缩或写文件。
- 有界预算从"一个总数"变成"每信号一个独立上限"。好处是可控、可解释；代价是要用户分别调三个旋钮，文档必须写清总占用怎么估算。
- 读口默认对内网开放。即便有 token，这也**扩大了攻击面**：trace 与 log 的载荷里装着 SQL 语句、HTTP header、请求体、日志原文。因此 token 必须每进程随机，且绝不出现在任何日志 / 快照 / 异常消息里（`LocalStoreConfig.describe()` 已按此实现）。
- demo-app 不再像 SW 侧那样托管读口 —— 读口在扩展内部，与应用隔着三个 ClassLoader。它退化为"造信号 + 给出可断言的期望值"的靶子。
- R0 笔记里被推翻的两条早期判断（"logs 只有同步钩子"、"toolkit 桩可做"）已改正，本 ADR 不再依赖它们。
- 尚未决定、留给后续 ADR 的：三张表的具体列与索引、payload 存编码后的 OTLP bytes 还是解码后的结构、四种"数据少了"的口径定义。