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
产出单一 uber jar。**其中 protobuf-java 与 opentelemetry-proto 必须 relocation，
H2 不动。**

> **2026-10-04 修正（首次端到端实测后）**：本文初版写的是「**不做 relocation**」，
> 那条论证是**错的**，下面「为什么不做 relocation」一节保留着作为记录。
> 错的点是：我以为"两份分属不同 ClassLoader，本来就各是各的"——
> 只对**应用**那份成立，而漏了**agent 自己那份**：
> `ExtensionClassLoader` 没有覆写 `loadClass`，走 `URLClassLoader` 的**父优先**委托，
> 于是 `com.google.protobuf.*` 命中的是 `AgentClassLoader` 里的 **agent 自带版本**，
> 我们 uber jar 里那份**从来没被用过**。详见下面新增的一节。

## 为什么不是"让用户把这些 jar 放到应用 classpath"

`ExtensionClassLoader` 既不挂在 `AppClassLoader` 上，也拿不到 `AgentClassLoader` 里的类
（这是 ADR-1 拒绝"应用可调 toolkit 桩"的同一个理由）。所以应用 classpath 上的东西，
扩展**在运行期根本看不见** —— 不是"版本可能冲突"，是 `ClassNotFoundException`。

而 `-Dotel.javaagent.extensions=` 接受的是**逗号分隔的多个路径**（理论上可以把四个 jar 都列进去）。
否决它的理由是运维代价，不是技术可行性：用户要 copy 四个文件、按对的顺序、每次升级都重做一遍，
而漏掉任何一个的表现是 `NoClassDefFoundError` 抛在采集路径上 —— 恰好是最坏的那种失败。

## 为什么最初判断"不做 relocation"（已作废，留作记录）

应用自己那份 H2 / protobuf 与我们这份分属两个 ClassLoader，**表面上**不存在冲突；
而 relocation 会连带破坏 H2 自己的资源与 service 查找（`META-INF/services`、版本化类目录），
换来的只是一个本来就不存在的问题的防护。

**这段推理的漏洞**：`ExtensionClassLoader` 是**父优先**的，它上面还有 `AgentClassLoader`。
"不同 ClassLoader 就各是各的"只对**应用**那层成立，对 **agent 自带的同名类**不成立 ——
父优先意味着**父里有的先赢**，我们那份根本轮不到加载。
判断"会不会撞"时必须问的是**父优先链上有没有同名类**，而不只是"是不是同一个 CL"。

唯一要做的合并是 `META-INF/services`：SPI 声明文件来自我们自己的 jar，shade 默认会正确保留；
仍然显式挂了 `ServicesResourceTransformer` 兜底，因为 protobuf 与 H2 里都有 service 文件，
而 shade 不加这个 transformer 时同名文件是**后者覆盖前者**。

## 为什么现在必须 relocation（2026-10-04 实测后）

第一次挂 agent 就炸在这里，症状是**载荷编码全失败、但表头行照存**：
先 `ExceptionInInitializerError`，随后每次都是
`NoClassDefFoundError: Could not initialize class io.opentelemetry.proto.trace.v1.Span`。

原因是三件事撞在一起（**版本号与可复算的证据在
[`docs/notes/2026-10-04-r0-extension-points.md`](../notes/2026-10-04-r0-extension-points.md) 第九节**）：

1. agent 自带**未重定位**的 `com.google.protobuf`；
2. `ExtensionClassLoader` **父优先**，所以我们 uber jar 里那份 protobuf 永远不会被加载；
3. 我们用的 gencode 比 agent 那份 runtime **新**，而 protobuf-java 4.x 规定
   「gencode 不得比 runtime 新」，于是类初始化直接抛。

第 2 条是本文初版判断失误的根源：**"不同 ClassLoader 就各是各的"只对应用那层成立**，
对 agent 自带的同名类不成立 —— 父优先意味着**父里有的先赢**。

顺带解释了为什么**表头行还在**：`LocalStore` 的口径就是"载荷写不进去时保住表头行"
（ADR-2 的坑 4），所以库里有行、载荷全空 —— 降级设计第一次在真环境里被验证有效，
但它掩盖了一个致命错误，这也是下面那条改动要解决的问题。

### relocation 的范围与理由

| 依赖 | relocation | 为什么 |
| --- | --- | --- |
| `com.google.protobuf` → `…otelstore.shaded.com.google.protobuf` | **要** | agent 自带同包名且父优先，不移就用不到自己的 |
| `io.opentelemetry.proto` → `…otelstore.shaded.io.opentelemetry.proto` | **要** | agent 自带 `io.opentelemetry.proto.collector.*.v1.**internal**`（包名多一段 `internal`），今天不撞；但它证明 agent 确实占着这个命名空间，往后版本去掉 `internal` 就撞 |
| `org.h2` | **不** | agent 不含 H2；应用的 H2 在 `AppClassLoader`，与 `ExtensionClassLoader` 是**兄弟**（父是 `AgentClassLoader`），互相看不见。且 H2 的 trace 系统按**字符串类名**反射加载 `org.h2.trace.*`，relocate 会打断它 |

### 不 relocation 的替代方案，以及为什么都不行

- **把 protobuf-java 钉到与 agent 相同的 4.35.0**：gencode 仍是 4.36.2（比 runtime 新）→ **照样抛**。
- **把 opentelemetry-proto 降到 gencode ≤ 4.35 的版本**：能跑，但这条约束**跟着 agent 版本走**，
  agent 一升级就静默炸在 drainer 线程里 —— 而 drainer 线程的失败只体现为"载荷空、表头在"，
  没有异常冒到业务线程。**把可用性押在一个我们控制不了的版本号上**，不可接受。
- **不用 protobuf**（payload 存 JSON / 自定义编码）：要推翻 ADR-2，代价远大于 relocation。

relocation 的已知风险：protobuf-java 内部有少量按字符串拼包名的反射。
真跑一次端到端就能确认（载荷能编码、能读回、`spanPayloadHex` 有内容）。

## 版本选择受一条硬约束：字节码必须是 52

本项目的产物字节码是 8（目标应用可能是 JDK 8），而 shade **不会**重编译三方依赖 ——
它把人家的 class 原样塞进来。所以选版本时必须核对方 jar 自己的 class 版本：

- H2 **2.2.224 = 52**；2.3.x 已经是 **55**（Java 11），会让"目标应用可能是 JDK 8"这个前提当场失效
- protobuf-java 4.36.2 = 52（protobuf-java 4.x 至今仍支持 Java 8）
- `opentelemetry-proto` 1.11.1-alpha（最新；只用作我们自己编码的 schema，不追求与 SDK 同步）

## Considered Options

- **让用户把依赖放到应用 classpath**：拒绝。见上，扩展看不见那里。
- **`-Dotel.javaagent.extensions=` 列四个 jar**：拒绝。技术上可行，运维上不可接受，且失败模式最坏。
- **shade + relocation**：见上面「为什么现在必须 relocation」。当日否掉，次日实测证明必须做 ——
  这条留给未来的人：决策不是靠推理定稿的，是靠能复算的证据。
- **把 protobuf 钉到与 agent 相同版本 / 把 otel-proto 降到 gencode ≤ agent 的版本**：拒绝。
  见上面「不 relocation 的替代方案」。
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
