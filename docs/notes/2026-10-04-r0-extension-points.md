# R0 —— agent 扩展点与重定位边界

- **日期**：2026-10-04
- **方法**：不解压不解包，直接解剖 jar。`opentelemetry-javaagent-2.32.0.jar` 里的 `.classdata`
  **不是压缩流，就是改名的裸 class 字节**（首四字节 `CA FE BA BE`）—— 用 `DeflateStream` 去解会报
  "unsupported compression method"，这个坑不必再踩第二次。
- **性质**：静态解剖结论。**运行期行为（各customizer 是否真被调用、顺序、ClassLoader 归属）
  尚未验证**，留给探针。

## 问题

范围是 traces / logs / metrics 三个信号。前提是 agent 的扩展点能定制三条管线。
**若 logs 或 metrics 定制不了，范围塌一到两个信号，README 与设计都要改。**

## 结论：三条管线全都能改，范围成立

SPI 的真身是

```
io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider   （extends Ordered）
io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer
```

> **纠正一个错误记忆**：不是 `io.opentelemetry.javaagent.tooling.AutoConfigurationCustomizerProvider`。
> agent 2.32.0 的 `META-INF/services/` 下只有 `AgentExtension` / `BeforeAgentListener` /
> `LoggingCustomizer` / `BootstrapProxyProvider` / `ClassFileLocatorProvider`，
> **没有 `io.opentelemetry.javaagent.tooling` 下的 customizer**。handoff 与本轮早期计划里的
> 那个 FQCN 是旧位置，按它写会直接编译失败。

三条管线的钩子：

| 管线 | 钩子 |
| --- | --- |
| traces | `addTracerProviderCustomizer` / `addSpanExporterCustomizer` / `addSpanProcessorCustomizer` / `addSamplerCustomizer` |
| metrics | `addMeterProviderCustomizer` / `addMetricExporterCustomizer` / `addMetricReaderCustomizer` |
| logs | `addLoggerProviderCustomizer` / `addLogRecordExporterCustomizer` / `addLogRecordProcessorCustomizer` |

另有 `addResourceCustomizer` / `addPropagatorCustomizer` / `addPropertiesSupplier` /
`addPropertiesCustomizer` —— 后两个是把自己的配置喂给 agent 的入口。

## 三个约束（都会改形态）

### 一、agent 对 OTel 做了**部分**重定位

agent jar 里同时存在两套 OTel 类型：

- **未重定位**：`inst/io/opentelemetry/{sdk,proto,exporter,extension,contrib}/…`
- **已重定位**：`io/opentelemetry/javaagent/shaded/io/opentelemetry/context/…`（整个 `context` 包），
  以及 `com` / `fdp` / `jctools` 等三方库

证据：`AutoConfigurationCustomizer` 里`addPropagatorCustomizer` 的参数类型是
`io.opentelemetry.javaagent.shaded.io.opentelemetry.context.propagation.TextMapPropagator`，
而同接口的 `addResourceCustomizer` / `addTracerProviderCustomizer` 用的是未重定位的
`io.opentelemetry.sdk.resources.Resource` / `io.opentelemetry.sdk.trace.SdkTracerProviderBuilder`。
**同一个接口里两种命名空间并存。**

**对我们的含义**：

1. 一律编译 against 未重定位的普通 artifact（`opentelemetry-sdk-extension-autoconfigure-spi`）。
2. **`io.opentelemetry.context.*` 不要 import** —— 运行期拿到的是重定位后的类型，编译期看不到，
   直接用会 `ClassNotFoundException` / `NoSuchMethodError`。
3. 好消息：`io/opentelemetry/proto/` **未重定位**。R1 的"payload 存编码后 OTLP bytes"
   用公开的 `opentelemetry-proto` 类表得通，不碰 internal encoder。

### 二、exporter 钩子是**装饰器**，不是替换器

签名一律是 `BiFunction<现有 exporter, ConfigProperties, 返回的 exporter>`。
SPI 里**没有** `setSpanExporter` 这种替换式方法（那是更早的 SDK autoconfigure API）。

**设计含义**："数据留在本地"要靠**丢弃传入的 delegate、自己返回本地 exporter** 实现；
想 tee（本地 + 远端）则包住 delegate 转发。这两种模式要在 ADR-1 里选一个作为默认。

### 三、logs 只有**同步**钩子

`addLogRecordProcessorCustomizer` 的入参是 `LogRecordProcessor`——
即 `onEmit(Context, LogRecord)`，**同步跑在调用 `log.info()` 的那个业务线程上**。
customizer 列表里**没有** batch 版（traces 才有 `BatchSpanProcessor`）。

**这是三信号里唯一的"会打到业务线程"的路径**，必须配独立的有界队列 + drop 计数，
不能照搬 traces 的单写入路径。

## 版本对齐：已核对，无需担心

`opentelemetry-sdk-extension-autoconfigure-spi:1.66.0` 与 agent 2.32.0 内嵌的那份
**逐方法完全一致**（15 个方法，default 与 abstract 的标注也相同）。

方法一致 = 运行期不会 `NoSuchMethodError`。pom 里把 `1.66.0` 显式钉住并声明为 `provided`
（此前只靠 `extension-api` 传递依赖拿到，编译直接引用的东西不该靠传递）。

## 还没验的（留给探针）

1. agent 是否真的加载外部扩展 jar —— 用哪个配置键（`otel.javaagent.extensions` 系统属性
   还是 `OTEL_JAVAAGENT_EXTENSIONS` 环境变量），jar 该放哪。
2. 三个 customizer 在运行期是否真被调用、调用顺序、与用户已配置的 exporter 的叠加关系。
3. 扩展 jar 的 ClassLoader 归属 —— 决定"应用内可调的 toolkit API"能否做成
   （对应 AGENTS.md 里预留的那条）。
4. `addMetricReaderCustomizer` 给的是 `MetricReader`，它与 `PeriodicMetricReader` 的关系：
   能不能拿到采集周期。