# Phase 4b 的验证信号、失败判据，以及只在仓库里没记的坑

> 本文原本是 `docs/HANDOFF.md` 的一部分。那份是**会话层**文档，不该出现在 GitHub 上，已删除；
> 里面唯一没被别处覆盖的内容搬到了这里 —— 按 `AGENTS.md` 的分工，实测与经验归 `notes`，
> 不塞进 `AGENTS.md`（那里每一条都该是纪律，不是背景）。
>
> 写于 2026-10-04，对应 Phase 4b 落地、60 个测试全绿的状态。

---

## 一、Phase 4b 端到端的验收信号

起一次 `demo-app`、造点数据、停掉进程，**这两行日志就是全部验收信号**：

- 启动时：`已注册三条采集管线 … store=ready jmxReadout=io.github.fulizhe.otelstore:name=LocalStoreSummary`
- 停进程时：`退出 … | store spans=N logs=N metricPoints=N resources=M`

**停进程那行的 N 应该与 `GET /demo/stats` 对得上。** 差额不是 bug，是 ADR-3 的那几种形态
（采样、SDK 截断、队列丢弃）—— `/demo/stats` 是"本应用造了多少"的期望值，
这个对照是发现"数据少了"的**唯一手段**（第 4 种与 sampling 都是黑箱，原理上不可计数）。

### 失败判据

| 看到什么 | 说明什么 |
| --- | --- |
| 启动日志没有 `已注册三条采集管线` | 扩展 jar 没挂上。脚本已校验路径，所以更可能是 jar 内部坏了 |
| 有 `store=unavailable` | 存储层开不起来，**数据目录不可写** —— 看那一行后面的异常 |
| 注册成功但退出那行 `store spans=0` | 翻译或落库在丢。看 JMX 里的 `sinkErrors` |
| `store spans=N` 与 `/demo/stats` 对不上 | 差额落在 ADR-3 的第 1 / 4 / 5 种上 |
| 期望 `N` 有值但某个信号恒为 0 | 先确认那个信号在 `demo-app` 里真的造了东西，再怀疑存储层 |

## 二、不看日志也能查：JMX 读口

JMX 是跨 ClassLoader 的**唯一**通道（ADR-1，应用侧读不到扩展的类）。Phase 4b 的
`LocalStoreSummary` 是目前唯一的对账口子。

`jconsole` 连上本进程 → MBeans → `io.github.fulizhe.otelstore` → `LocalStoreSummary`，
下面四个直接就能点：

| 方法 | 作用 |
| --- | --- |
| `summary` | 全量计数：offered / dropped / drained / sinkErrors / 各表行数 / 环的写游标与覆盖轮次 |
| `recentSpans(10)` | 最近 N 条 span 的表头行 |
| `spansOfTrace(<32 位 hex>)` | 按 trace_id 取回该 trace 的全部 span（一个 trace 几行就返几行） |
| `spanPayloadHex(<id>)` | 读回单条 span 的环形载荷（hex） |

两个坑：

- **命令行没有等价物。** `jcmd <PID> ManagementAgent.status` 只能确认 JMX 开着，看不到我们的属性；
  真要脚本化得引 JMX 连接器（`jmxterm` / `jmxinvoker` 之类）。
- **属性类型只有 String 与 int。** 这是刻意的 —— 跨 ClassLoader 传自定义类型必然 `NoClassDefFoundError`。
  代价是结构化查询只能靠具名方法，这也是为什么上面四个方法是一对一的查询而不是 raw SQL。

## 三、完整的本地验证命令

```powershell
cd D:\gitRepository\otel-local-telemetry-store
$env:JAVA_HOME="D:\apps\java\jdk-17.0.8"; $env:Path="$env:JAVA_HOME\bin;$env:Path"

mvn -o -q test                              # 预期：60 个测试，-q 下全绿静默
mvn -o -q -DskipTests package               # 预期：target 下多出一个约 5 MiB 的 uber jar
javap -verbose -cp target\classes io.github.fulizhe.otelstore.core.storage.LocalStore |
  Select-String "major version"             # 预期：52
jar tf target\otel-local-telemetry-store-0.1.0-SNAPSHOT.jar |
  Select-String "org/h2/engine/Engine.class|proto/trace/v1/Span.class|protobuf/CodedOutputStream.class"
                                               # 预期：三行都在（shade 进去了，ADR-5）
D:\apps\actionlint\actionlint.exe (Get-ChildItem .github\workflows\*.yml).FullName   # 预期：无输出

pwsh -NoProfile -File scripts/run-with-agent.ps1            # 端到端，长驻，Ctrl-C 停
```

最后一条**必须用户跑**（`AGENTS.md` 的验证节奏：长验证交给用户，agent 不起长驻进程）。

## 四、只在本文里的坑（仓库其它地方没记的）

以下每一条都是真实返工换来的，**它们不在任何 ADR 或 notes 里**。

### 写测试

- **JUnit 5 的消息参数在末尾**，不是开头。写惯 JUnit 4 的人会反复写错 —— 我在这个仓库里错了三次，
  搬测试代码时还因此返工三轮。
- **用正则给 JUnit 断言搬家时会漏消息**。断言消息常是字符串拼接的（`"msg " + i,`），
  "闭合引号后紧跟逗号"的假设不成立，多行写法同样漏。**这类活儿干不过 5 处就别用正则了**，
  手工改。
- **存储层一旦真的写盘，测试必须自己指定 `dataDir`。** 默认值是 `./otel-local-telemetry-store`
  且两个环各 256 MiB —— 用默认值跑测试会在仓库里留下两个大稀疏文件。用 `@TempDir`。
- **`SpanData` 没有公开的构造入口**（SDK 1.66）。要造真的 `SpanData` 只能走
  `SdkTracerProvider.builder().addSpanProcessor(new SpanTap(queue))` 造一个真 span。
  `PipelineToStoreTest` 就是这么做的，比手搓对象可靠得多。

### 设计与写作

- **"能力在"不等于"会发生"。** 我曾断言"重启后新进程能读到旧进程的块"，被追问后才发现
  **唯一的调用方在内存模式下是空的**，没人触发。**写"会不会发生"之前先找调用方。**
- **同一份取舍要前后一致。** ADR-4 里我用"文件锁是硬伤"论证了选 H2 内存模式，
  却没把同一条论据套在**自家的环形文件**上（它同样没有 `FileLock`、文件头没有实例标识）。
  是被追问才暴露的。
- **主体与靶子别搞混。** `demo-app` 是验证主体用的工具与可视化验收面，不是交付物。

### 环境

- **端口 18080 被本机另一个项目占着**（RuoYi-Flowable-Plus）。`demo-app` 因此用 **18081**。
- 本机只放通 Maven Central；`opentelemetry.io` 与 `h2database.com` 连不上。
  完整的环境坐标见 [`AGENTS.md`](../../AGENTS.md) 的「仓库外的环境坐标」与「三条已知的本机环境坑」。

## 五、已在仓库里、不要重读的

- **agent 只认 jar，不认目录** —— `-Dotel.javaagent.extensions=target/classes` 会静默忽略、零告警。
- **扩展 jar 不能与应用 classpath 同路径** —— 会加载出两个不同的类。
- **agent 对 `io.opentelemetry.context.*` 做了重定位** —— 编译期看不到、运行期类型不同。
- **不要用 `opentelemetry-exporter-otlp` 的内部 encoder** —— internal API + 被重定位 + 随小版本变（ADR-2）。
- **三方依赖只能 shade 进扩展 jar**（ADR-5）—— 扩展 ClassLoader 看不见应用 classpath。
- **升级 H2 / protobuf 必须核它们自己的 class 版本是 52** —— shade 不重编译依赖，H2 2.3.x 已经是 55。

## 六、悬着的事（`docs/adr/index.md` 有清单，这里补"什么时候该做"）

| 事项 | 状态 | 什么时候该做 |
| --- | --- | --- |
| `start_time` 不建索引 | **推断**，从 SkyWalking 侧继承（那边实测写慢 26%，但表结构不同） | **Phase 4b 已落地，现在就可以实测**；若结论相反，改 ADR-2 |
| ADR-3 第 4 种可不可计数 | **未验**。`getTotalAttributeCount()` 是公开 API，可能让它从黑箱变成可计数 | 一次性探针，见 `index.md` |
| metrics 分钟/小时 rollup | 本版只定义"时间序列形态 + 行数水位"（`rows.metrics`） | 后面 |
| 多实例共用 `dataDir` | **明确不支持**，是声明的作用域前提（ADR-4） | 见到有人要支持，先写 ADR |
| 读口跨源取舍 | 未决（读口 17890 / demo-app 18081） | Phase 5；已记在 `demo-app/README.md` |

**已验完的**：ADR-3 原本待验的「SDK 截断有无信号」—— 已验为**完全静默**，
attributes / events / links 三种截断都不产生指标、不产生日志、`SpanData` 上无标记。
所以第 4 种与 sampling 同为黑箱，都只能靠与 `GET /demo/stats` 对照发现。