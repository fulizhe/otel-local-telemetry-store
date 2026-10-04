# 三方依赖必须 shade 进扩展 jar：ClassLoader 边界让"让用户放到应用 classpath"这条根本不存在

Phase 4b 第一次引入了真正的运行期三方依赖：H2（表头落地，adr-04）、protobuf-java 与
`opentelemetry-proto`（载荷编码成 OTLP bytes，adr-2）。它们不像 OTel SDK 那样由 agent 提供 ——
必须自己解决"怎么被加载"。

R0 实测的 ClassLoader 边界（`docs/notes/2026-10-04-r0-extension-points.md` 第五节）把选项收窄到只剩一个：

| 谁 | ClassLoader |
| --- | --- |
| 我们的扩展代码 | `ExtensionClassLoader` |
| agent + SDK + 我们收到的所有对象 | `AgentClassLoader` |
| 应用代码 | `AppClassLoader` |

**决策**：用 `maven-shade-plugin` 把 H2 / protobuf-java / opentelemetry-proto 打进扩展 jar，
产出单一 uber jar。**不做 relocation。**

## 为什么不是"让用户把这些 jar 放到应用 classpath"

`ExtensionClassLoader` 既不挂在 `AppClassLoader` 上，也拿不到 `AgentClassLoader` 里的类
（这是 ADR-1 拒绝"应用可调 toolkit 桩"的同一个理由）。所以应用 classpath 上的东西，
扩展**在运行期根本看不见** —— 不是"版本可能冲突"，是 `ClassNotFoundException`。

而 `-Dotel.javaagent.extensions=` 接受的是**逗号分隔的多个路径**（理论上可以把四个 jar 都列进去）。
否决它的理由是运维代价，不是技术可行性：用户要 copy 四个文件、按对的顺序、每次升级都重做一遍，
而漏掉任何一个的表现是 `NoClassDefFoundError` 抛在采集路径上 —— 恰好是最坏的那种失败。

## 为什么不做 relocation

应用自己那份 H2 / protobuf 与我们这份**分属两个 ClassLoader，本来就是两套类**，不存在冲突。
而 relocation 会连带破坏 H2 自己的资源与 service 查找（`META-INF/services`、版本化类目录），
换来的只是一个本来就不存在的问题的防护。

唯一要做的合并是 `META-INF/services`：SPI 声明文件来自我们自己的 jar，shade 默认会正确保留；
仍然显式挂了 `ServicesResourceTransformer` 兜底，因为 protobuf 与 H2 里都有 service 文件，
而 shade 不加这个 transformer 时同名文件是**后者覆盖前者**。

## 版本选择受一条硬约束：字节码必须是 52

本项目的产物字节码是 8（目标应用可能是 JDK 8），而 shade **不会**重编译三方依赖 ——
它把人家的 class 原样塞进来。所以选版本时必须核对方 jar 自己的 class 版本：

- H2 **2.2.224 = 52**；2.3.x 已经是 **55**（Java 11），会让"目标应用可能是 JDK 8"这个前提当场失效
- protobuf-java 4.36.2 = 52（protobuf-java 4.x 至今仍支持 Java 8）
- `opentelemetry-proto` 1.11.1-alpha（最新；只用作我们自己编码的 schema，不追求与 SDK 同步）

## Considered Options

- **让用户把依赖放到应用 classpath**：拒绝。见上，扩展看不见那里。
- **`-Dotel.javaagent.extensions=` 列四个 jar**：拒绝。技术上可行，运维上不可接受，且失败模式最坏。
- **shade + relocation**：拒绝。见上，破坏大于收益。
- **不用 H2，把表头也放环形文件**：拒绝。ADR-4 已否（查询要按 trace_id / scope / 时间过滤，顺序扫环做不到）。
- **不用 protobuf，payload 存 JSON 或自定义二进制**：ADR-2 已否（schema 会腐坏在我们手里、体积更大）。
  这条 ADR 只回答"依赖怎么被加载"，不重开"要不要这些依赖"。
- **把 H2 声明成 `provided`，让 demo-app 自带**：拒绝。这只把问题挪到 demo-app，而真实用户不会自带。
- **把 H2 换成无依赖的纯内存结构**（自己管表与索引）：**尚未评估**，本版不做。它能同时消掉 shade 与版本对齐两笔成本，代价是 adr-02 那些查询（按 trace_id、按 id 区间、按指标名）要靠自建索引逐条实现。若将来实测发现 uber jar 的体积或版本对齐成本真的疼，回到这条重新评估 —— 注意它与上面"不用 H2 把表头放环形文件"是**两个不同的方向**：那个是换存储介质，这个是换存储引擎。

## Consequences

- **发布物从"一个薄 jar"变成一个 5 MiB 的 uber jar**，`-Dotel.javaagent.extensions=` 仍然只填一个路径。
  运维文档里不必解释依赖从哪来 —— 没有第二个路径可填。
- **CI 必须继续断言字节码 52，而且要连带核依赖**。目前 CI 断言的是我们自己的类；
  升级 H2 / protobuf 时**必须**再核一次它们的 class 版本，否则会悄悄把 Java 8 前提打破。
- shade 会同时产出 `original-*.jar`。`scripts/run-with-agent.ps1` 用
  `otel-local-telemetry-store-*.jar` 通配，不会误取到它（`original-` 前缀不匹配）。
- 扩展 jar 变大意味着 agent 启动时多解析 5 MiB。实测可忽略，但若将来体积成了问题，
  第一个该看的是 protobuf（它带了 codegen 相关的类，而我们只用到 runtime 的一小部分）。
- 依赖升级变成"改 pom 里三个版本号 + 重跑那三个测试"（`CanonicalAttributesTest` /
  `LocalStoreTest` / `PipelineToStoreTest`），没有别的动作 —— 这是把依赖收进一个 jar 换来的。
- 与 ADR-1 原则 5 是**互补而非冲突**：原则 5 管的是**输入侧**（扩展 jar 该怎么被 agent 加载、
  为什么不能与应用 classpath 同路径），本文管的是**输出侧**（我们产出什么形态的 jar）。
  两者同源于同一条 ClassLoader 边界。
- 本决策**只对 JVM 成立**。换成 Go / Python 时 shade 的动机（ClassLoader 隔离）根本不存在 ——
  多语言与 Android 的可移植性调研见 `docs/notes/2026-10-04-portability-and-android.md`，
  其中 Android 一节尤其说明"把依赖打进库"在那边是常规做法而非被迫选择。
