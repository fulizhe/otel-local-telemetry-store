# R0 —— agent 扩展点、ClassLoader 边界与重定位

- **日期**：2026-10-04
- **证据来源**：① 静态解剖 agent jar；② 运行期探针（stock agent + 自定义扩展，真实造三个信号）。
- **静态解剖的做法**：`opentelemetry-javaagent-2.32.0.jar` 里的 `.classdata`
  **不是压缩流，就是改名的裸 class 字节**（首四字节 `CA FE BA BE`）。用 `DeflateStream` 去解会报
  "unsupported compression method" —— 这个坑不必再踩第二次。

## 一、问题与结论

范围是 traces / logs / metrics 三个信号，前提是 agent 的扩展点能定制三条管线。

**结论：三条管线全都能改，范围成立。** 且经运行期实测确认每一个 customizer 都会被调用。

## 二、SPI 的真身

```
io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider   （extends Ordered）
io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer
```

> **纠正一个错误记忆**：不是 `io.opentelemetry.javaagent.tooling.AutoConfigurationCustomizerProvider`。
> agent 2.32.0 的 `META-INF/services/` 下只有 `AgentExtension` / `BeforeAgentListener` /
> `LoggingCustomizer` / `BootstrapProxyProvider` / `ClassFileLocatorProvider`。
> handoff 与本项目早期计划里的那个 FQCN 是旧位置，按它写会直接编译失败。

| 管线 | 钩子 |
| --- | --- |
| traces | `addTracerProviderCustomizer` / `addSpanExporterCustomizer` / `addSpanProcessorCustomizer` / `addSamplerCustomizer` |
| metrics | `addMeterProviderCustomizer` / `addMetricExporterCustomizer` / `addMetricReaderCustomizer` |
| logs | `addLoggerProviderCustomizer` / `addLogRecordExporterCustomizer` / `addLogRecordProcessorCustomizer` |

另有 `addResourceCustomizer` / `addPropagatorCustomizer` / `addPropertiesSupplier` / `addPropertiesCustomizer`。

## 三、怎么挂上去（实测）

```
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=/abs/path/our-extension.jar \
     -jar app.jar
```

- **只认 jar 文件，不认目录** —— 指向 `target/classes` 时 provider 静默不加载，没有任何报错。
- jar 里必须有 `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider`。
- `customize()` 在 **main 线程、premain 阶段**就被调用。

## 四、autoconfigure 默认递给我们的东西（实测）

| customizer | 默认传入 |
| --- | --- |
| `addSamplerCustomizer` | `AlwaysOnSampler` |
| `addSpanExporterCustomizer` | `OtlpHttpSpanExporter` |
| `addSpanProcessorCustomizer` | `BatchSpanProcessor` |
| `addLogRecordExporterCustomizer` | `OtlpHttpLogRecordExporter` |
| **`addLogRecordProcessorCustomizer`** | **`BatchLogRecordProcessor`** |
| `addMetricExporterCustomizer` | `OtlpHttpMetricExporter` |
| `addMetricReaderCustomizer` | `PeriodicMetricReader` |
| `addResourceCustomizer` | `AutoValue_Resource` |

**修正本项目早期判断**：曾断言"logs 只有同步钩子、没有 batch 版"。**错** ——
SDK 1.66 有 `BatchLogRecordProcessor`，autoconfigure 默认就装上，且它自己就带
`processedLogs` / `queueSize` 两个指标（描述里明写 `dropped=true` 表示因吞吐被丢）。

**结论修正**：logs 的业务线程风险比预想的小。但**我们自己的写入仍然绝不能 inline 走 `onEmit`** ——
正确形态是"包住 BatchLogRecordProcessor，另接一条有界队列 + 独立 drainer 线程"。

## 五、ClassLoader 边界（实测，三者互相隔离）

| 谁 | ClassLoader |
| --- | --- |
| 我们的扩展代码 | `io.opentelemetry.javaagent.tooling.ExtensionClassLoader` |
| agent + SDK + 我们收到的所有对象 | `io.opentelemetry.javaagent.bootstrap.AgentClassLoader` |
| 应用代码 | `jdk.internal.loader.ClassLoaders$AppClassLoader` |

**同一个 jar 同时出现在扩展路径与应用 classpath 上时，两边加载出的是不同的类** ——
探针里应用侧访问扩展类的静态字段直接 `NoClassDefFoundError`。这是边界的直接证据。

### 应用内可调的 toolkit 桩：**此路不通**

`opentelemetry-javaagent-extension-api:2.32.0-alpha` 里**没有** `InstrumentationAccess`，
也没有任何能拿到 `java.lang.instrument.Instrumentation` 的入口（已列目录核实）。

没有 `Instrumentation` 就不能 `appendToSystemClassLoaderSearch`；JDK 17 上反射
`ClassLoader.appendToClassPathForInstrumentation` 需要 `--add-opens`，不可接受。

**结论：应用侧读不到我们的类。跨边界只读通道只能是 JMX。**
（port 文件与启动日志作为运维通道。）

## 六、重定位边界（会改代码怎么写）

agent jar 里同时存在两套 OTel 类型：

- **未重定位**：`io/opentelemetry/{sdk,proto,exporter,extension,contrib}/…`
- **已重定位**：`io/opentelemetry/javaagent/shaded/io/opentelemetry/context/…`（整个 `context` 包），
  以及 `com` / `fdp` / `jctools` 等三方库

证据：`AutoConfigurationCustomizer` 里 `addPropagatorCustomizer` 的参数类型是
`…shaded…context.propagation.TextMapPropagator`，而同接口的 `addResourceCustomizer` /
`addTracerProviderCustomizer` 用的是未重定位的 `io.opentelemetry.sdk.resources.Resource` /
`io.opentelemetry.sdk.trace.SdkTracerProviderBuilder`。**同一接口里两种命名空间并存。**

**对我们的含义**：

1. 一律编译 against 未重定位的普通 artifact（`opentelemetry-sdk-extension-autoconfigure-spi`）。
2. **`io.opentelemetry.context.*` 不要 import** —— 运行期是重定位后的类型，编译期看不到。
   注意 `Span.makeCurrent()` / `Scope` 都属于这个包。
3. `io/opentelemetry/proto/` **未重定位** → "payload 存编码后 OTLP bytes"用公开的
   `opentelemetry-proto` 类表得通，不碰 internal encoder。
4. **应用侧若要用 OTel API 自己发信号，必须自己带 api / context / common 三个 jar**；
   agent 不会白给。（漏 `opentelemetry-common` 会报
   `NoClassDefFoundError: io/opentelemetry/common/ComponentLoader`，与本项目无关。）

## 七、版本对齐：已核对

`opentelemetry-sdk-extension-autoconfigure-spi:1.66.0` 与 agent 2.32.0 内嵌的那份
**逐方法完全一致**（15 个方法，default 与 abstract 标注也相同）→ 运行期不会 `NoSuchMethodError`。
pom 里把 `1.66.0` 显式钉住并声明 `provided`（编译直接引用的类型不该靠传递依赖）。

## 八、顺手拿到的运行期事实（省掉后面一轮探针）

- **`SpanData` 的取法**：`SpanProcessor.onEnd(ReadableSpan)` → `toSpanData()`。
  实现类 `io.opentelemetry.sdk.trace.AutoValue_SpanWrapper`。字段齐全（traceId/spanId/parentSpanId/
  kind/start/end/status/attributes/events/links/scope/resource）。
- **`LogRecordData` 的取法**：`LogRecordProcessor.onEmit(Context, ReadWriteLogRecord)` →
  `toLogRecordData()`。实现类 `AutoValue_SdkLogRecordData`。
  ⚠️ 入参是**内部可变类型** `ReadWriteLogRecord`，它**没有** `getResource()`；要拿 resource 得先转 `LogRecordData`。
- **日志带 trace 上下文** ✅ span 为 current 时，`traceId` / `spanId` 有效。
  → **"按 trace 拉出它的全部日志"开箱可用**，这是本项目最省事的一个强特性。
- **Resource 三信号共用，且完全相同**：实测 **18 个属性**
  （`host.*` / `os.*` / `process.*` / `service.*` / `telemetry.*`），每条记录重复一遍。
  → `resource_dict` 字典表**不是优化是必需**，且收益已量化。
  ⚠️ 其中含 `process.command_line` —— **完整命令行**。若命令行里带 `-Dxxx.token=...`，
  存进本地就等于把密钥存盘。资源属性需要**白名单 + 脱敏**，不能整体落库。
- **指标的取法**：`MetricReader.register(CollectionRegistration)` 截获注册，
  `CollectionRegistration.collectAllMetrics()` 可**立刻**取一次，不必等采集周期。
  数据类 `AutoValue_ImmutableSumData` / `AutoValue_ImmutableGaugeData` 等。
- **`getAggregationTemporality(InstrumentType)` 会被反复询问**（一次运行问了十余次）
  → 实现必须廉价，别在里面做计算。
- **agent 自身的自监控指标也会流进我们的 reader**：
  `io.opentelemetry.sdk.trace`（`processedSpans` / `queueSize`）、
  `io.opentelemetry.sdk.logs`（`processedLogs` / `queueSize`）、
  `io.opentelemetry.runtime-telemetry-java8`（`jvm.*`）、
  `io.opentelemetry.exporters.otlp-http`（`otlp.exporter.seen` / `exported`）。
  → **SDK 层的丢弃信号白送**，不必自己埋点；且印证"自监控读口不该手搓七个面板"这个判断。
  ⚠️ 反面代价：这些指标默认也会被我们**存进本地存储**。要在存储层按 scope 过滤掉自监控，
  否则用户库里一半是 agent 的自监控数据。

## 九、设计约束汇总（进ADR-1）

1. **主钩子选 Provider builder**（`addTracerProviderCustomizer` /
   `addLoggerProviderCustomizer` / `addMeterProviderCustomizer`）——在那里注册自己的组件，
   不依赖 autoconfigure 是否已配好exporter。Exporter / Processor customizer 是次选（只能装饰）。
2. **exporter 钩子是装饰器不是替换器**（`BiFunction<现有, props, 返回值>`，无 `setXxxExporter`）。
   "数据留在本地"= 丢弃 delegate 返回本地实现；tee 模式 = 包住转发。ADR-1 需定默认。
3. **三条写入路径各自独立的有界队列 + drainer 线程 + 丢弃计数**，绝不 inline 写存储。
4. **应用侧读不到我们的类** → 跨边界只读通道用 JMX。
5. **资源属性白名单 + 脱敏**（`process.command_line` 是密钥泄漏面）。
6. **自监控指标按 scope 过滤**，不要存进用户库。
7. 扩展 jar **只**走 `otel.javaagent.extensions`，且不能与应用 classpath 同路径。

## 九、SpanLimits 截断是完全静默的（A/B 实测）

设 `-Dotel.span.attribute.count.limit=2 -Dotel.span.event.count.limit=1 -Dotel.span.link.count.limit=1`，
造一个带 6 个 attributes / 3 个 events / 3 个 links 的 span，与不限额对照：

| | 无限制 | 限额 2/1/1 |
| --- | --- | --- |
| 到达的 attributes | 6 | **2** |
| 到达的 events | 3 | **1** |
| 到达的 links | 3 | **1** |

三种截断都确实发生，但**没有任何可观测信号**：

- **指标**：两组运行采集到的指标名集合完全相同（差集为空）
- **日志/警告**：输出里搜 `limit` / `truncat` / `discard` / `dropped` 零命中
- **`SpanData` 标记**：`getAttributes().size()` 就是 2，从数据本身看不出本来有 6 个

唯一残留线索：SDK 内部类 `AttributesMap.totalAddedValues` 仍记着 6。但 `Attributes` **公共接口只有
`size()` 与 `asMap()`**，读它要依赖 internal API。

**结论**：截断与 head sampling 同类 —— 原理上不可计数，只能靠与外部期望值对照发现。
据此 [ADR-3](../adr/adr-03-four-ways-data-goes-missing.md) 第 4 种定案为"不可计数"。

## 十、还没验的

1. `OTEL_JAVAAGENT_EXTENSIONS` 环境变量是否与系统属性等价（本轮只验了系统属性）。
2. JDK 8 目标运行时是否一致（探针跑在 JDK 17 上）。
3. `addPropertiesSupplier` 注入的属性能否被 autoconfigure 读到（探针里回调被调到了，但没验证读回）。
4. `BatchSpanProcessor` / `BatchLogRecordProcessor` 的默认队列容量与批量大小具体值，
   以及装饰后能否读到它们（`BatchLogRecordProcessor` 是 public 类，理论上可读）。
5. 同一 jar 多扩展并存时的 `Ordered.order()` 排序行为。