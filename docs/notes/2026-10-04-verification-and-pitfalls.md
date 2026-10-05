# Phase 4b 的验证信号、失败判据，以及只在仓库里没记的坑

> 本文原本是 `docs/HANDOFF.md` 的一部分。那份是**会话层**文档，不该出现在 GitHub 上，已删除；
> 里面唯一没被别处覆盖的内容搬到了这里 —— 按 `AGENTS.md` 的分工，实测与经验归 `notes`，
> 不塞进 `AGENTS.md`（那里每一条都该是纪律，不是背景）。
>
> 写于 2026-10-04，对应 Phase 4b 落地、60 个测试全绿的状态。

---

## 一、Phase 4b 端到端的验收信号

**扩展的日志不在 `demo.log` 里，在 `demo.err.log` 里。** agent 把 `java.util.logging`
改到了自己的 logger（输出到 stderr），`scripts/run-with-agent.ps1` 把 stdout / stderr
分别重定向到 `demo.log` / `demo.err.log` —— 所以验收信号要**看 stderr 那份**。

> 读 `demo.err.log` 时**别加 `-Encoding UTF8`**：agent 那份 JUL 输出用的是平台编码
> （本机是 GBK），用 UTF8 读会得到一片乱码（`已注册` 显示成 `��ע��`）。

起一次 `demo-app`、造点数据，**下面这两行日志就是全部验收信号**
（在 `demo-app\target\demo.err.log` 里）：

- 启动时：`已注册三条采集管线 … store=ready jmxReadout=io.github.fulizhe.otelstore:name=LocalStoreSummary`
- **每 60 秒自动一行**：`周期 dataDir=… | traces offered=… sinkErrors=… backlog=… | logs … | metrics … | store spans=N logs=N metricPoints=N resources=M`
- 优雅关停时再有一行：同格式，前缀是 `退出` 而不是 `周期`

**判据是 `store=` 后面那四个数**，其中 `metricPoints` 只有这两行能报 ——
metrics 是纯内存表，没有磁盘痕迹，进程一停就没了。

**为什么有"每 60 秒"这一行**：关停钩子**不是可靠信号**。`Stop-Process` /
`taskkill` / `kill` 走的是 `TerminateProcess`，**shutdown hook 根本不执行** ——
2026-10-04 首次端到端就是这样丢掉了唯一的 metrics 证据。
所以周期汇总不是"多打一行日志"，而是**把验收信号从"怎么停"上解耦**：
口径完全复用关停那一行（同一个 `logSummary`），不新增格式、不新增计数器，
避免"两套汇总口径"这种最容易对不上的东西。存储层不可用时**不打**这一行 ——
启动时已经有一条警告了，每分钟重复只是噪声。

**`store=ready` 是真正的启动判据**，不是 `已注册三条采集管线` —— 后者在存储层开不起来时
照样打（那行末尾就带着 `store=unavailable`）。

**`store spans=N` 应当 ≥ `GET /demo/stats` 的 span 计数。** 差额来自 agent 对
你发请求本身的 HTTP 仪表化（每个 curl 都产生 server span），而 `/demo/stats`
只数应用自己造的。**只有 N 明显小于 `/demo/stats` 才是真丢了。**

### 怎么停（关系到能不能拿到 `退出` 那一行）

```powershell
# 脚本用 -NoNewWindow 起，java 与脚本共用控制台：
#   在**跑脚本的那个窗口**按 Ctrl-C → 优雅关停 → 打出「退出 …」
Stop-Process -Id <pid>     # 强杀：无 shutdown hook，拿不到「退出」那一行（但有「周期」行）
```

### 失败判据

| 看到什么 | 说明什么 |
| --- | --- |
| 启动日志没有 `已注册三条采集管线` | 扩展 jar 没挂上。脚本已校验路径，所以更可能是 jar 内部坏了 |
| `store=unavailable` + `No suitable driver` | **H2 驱动没注册上**。`DriverManager` 靠 TCCL 扫 service 文件，agent 启动时 TCCL 看不见扩展 jar。已在 `LocalStore.ensureDriver()` 显式 `org.h2.Driver.load()` 修掉 |
| `store=unavailable` + 别的异常 | 存储层开不起来，**数据目录不可写** —— 看那一行后面的异常 |
| `store=ready` 但满屏 `载荷编码失败` + `ExceptionInInitializerError` / `NoClassDefFoundError: Could not initialize class io.opentelemetry.proto…` | **protobuf 版本冲突**：agent 自带未重定位的 protobuf，而扩展 ClassLoader 是父优先，我们那份没被加载。已在 pom 里 relocation 修掉，见 R0 笔记第十节 |
| `store=ready` 但 `store spans=0 logs=0 metricPoints=0` 一直不变 | 没造数据，或造的那条路径没接上。先确认 `/demo/spans` 真的被调用过 |
| 周期行的 `drained` 远小于 `offered`，`backlog` 不为 0 | drainer 跟不上（落盘慢或存储阻塞）。`backlog` 持续增长才是问题 |
| `sinkErrors` 不为 0 | 有记录落库失败。看第一次的 WARN（带栈），后面的是重复 |
| 期望 `N` 有值但某个信号恒为 0 | 先确认那个信号在 `demo-app` 里真的造了东西，再怀疑存储层 |

**两条已经真踩到的坑，共同形状是"进程内测试全绿、只有挂 agent 才炸"**：
一个靠 TCCL（`DriverManager`），一个靠 ClassLoader 委托顺序（protobuf 版本）。
细节与证据分别在下面两节与 R0 笔记第十节。

### `No suitable driver`：只在挂 agent 时出现的坑

第一次挂 agent（2026-10-04 20:41）存储层开不起来，报
`java.sql.SQLException: No suitable driver`。**同一次提交的全部单元测试都是绿的** ——
差别只在 ClassLoader：

- `DriverManager` 在**类初始化**时用一次 `ServiceLoader.load(Driver.class)` 扫
  `META-INF/services/java.sql.Driver`，而那次扫描用的是**线程上下文 ClassLoader**；
- agent 在 main 线程上初始化我们，此时 TCCL 是应用的 `AppClassLoader`，
  它**看不见 shade 进扩展 jar 的 H2**；
- 那次扫描一辈子只做一次，扫不到就永远扫不到。

**修法**：`LocalStore.ensureDriver()` 显式调 `org.h2.Driver.load()`（幂等）。

### 异常只打 `toString()` 会把排查时间拖长

第一次定位上面那两个坑时，日志里只有 `java.lang.ExceptionInInitializerError` 一行 ——
因为 `ThrottledLogger` 当时是 `warn(key, msg + e)`，**`Throwable.toString()` 不含 cause 链**，
真正的答案（`validateProtobufGencodeVersion` 抛的那一行）根本不在日志里。
最后是去 agent jar 里查 protobuf 版本才定位到的。

已改成 `warn(key, msg, cause)`，走 `LOGGER.log(level, msg, throwable)`。
**教训：观测路径上打异常一律用带 throwable 的重载**，靠字符串拼接省下的那一个参数不值。

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

- **测试数据必须照着真实 SDK 的形状写，否则绿灯只是在验证一个不会发生的世界。**
  直方图那条吃过一次亏：OTel 的 `getBoundaries()` 给的是 **N-1 个**边界、
  `getCounts()` 给 **N 个**计数，而解析器当时按"两者等长"判断，于是**真机上的每一个直方图
  都显示「认不出来」**（2026-10-04 21:00 验收时发现）。当时全绿的测试用的是
  「2 边界 + 2 计数」——**这个组合在真实 SDK 输出里根本不存在**。
  凡是"我按文档/直觉造了一份数据"，先问一句：真实产出的那一行长什么样？
- **"返回空"是最难被发现的 bug**：不报错、大部分断言都能过，看起来像"数据还没来"。

- **"能力在"不等于"会发生"。** 我曾断言"重启后新进程能读到旧进程的块"，被追问后才发现
  **唯一的调用方在内存模式下是空的**，没人触发。**写"会不会发生"之前先找调用方。**
- **同一份取舍要前后一致。** ADR-4 里我用"文件锁是硬伤"论证了选 H2 内存模式，
  却没把同一条论据套在**自家的环形文件**上（它同样没有 `FileLock`、文件头没有实例标识）。
  是被追问才暴露的。ADR-5 初版"不做 relocation"也是同一类错 ——
  见那篇的「已作废」小节。
- **决策不能靠推理定稿。** ADR-5 那条论证当时看着挺顺，只有真挂一次 agent 才暴露它是错的。
  **能在真环境里验的事实，就别用推理定案**；推理只配用来决定"哪些必须去验"。
- **主体与靶子别搞混。** `demo-app` 是验证主体用的工具与可视化验收面，不是交付物。

### 环境

- **`Stop-Process` 是强杀，不触发 shutdown hook。** 见第一节。
- **跑着的 java 进程会锁住 `target` 里的 jar**，`mvn clean` 因此失败
  （`Failed to delete …-shaded.jar`）。先停进程再 clean。
- **测试里的配置 map 漏写 `otel.localstore.` 前缀会静默生效为默认值。**
  `LocalStoreConfig` 按设计"解析永不失败"（ADR-1），所以漏前缀**不报错**，
  只会让 `dataDir` 落回 `./otel-local-telemetry-store` —— 于是测试在**仓库根目录**建出
  两个 256 MiB 的环文件。本项目因此踩了两次（`LocalStoreCustomizerProviderTest` 与
  `ReadoutQueriesTest` 各一次），两次都是"单独跑那个测试类看不出来，全量跑才发现"。
  `ReadoutQueriesTest` 里现在有一条 `configKeysNeedThePrefix` 专门钉这个陷阱。
- **改 Java 文件不要用 PowerShell 的行号切片**（`$l[406..440]` 那种）。
  本会话里它把 `HttpReadout.java` 的内部类结构、`LocalStore.java` 的方法头、
  `errorBody` 的方法体依次弄坏过三次，每次都要靠编译错误反推。
  **只用精确替换**（`edit` 工具或整段 `.Replace()`），或者改完立刻 `mvn -q compile`。
- **RandomAccessFile.writeLong 是大端**。想直接读环文件的 `currIndex` 自查，
  用 `BitConverter` 解出来是错的（会得到天文数字）；要么按大端解，要么干脆别解 ——
  那 16B 文件头的语义见 `CappedFileStorage` 的类注释。
- **端口 18080 被本机另一个项目占着**（RuoYi-Flowable-Plus）。`demo-app` 因此用 **18081**。
- 本机只放通 Maven Central；`opentelemetry.io` 与 `h2database.com` 连不上。
  完整的环境坐标见 [`AGENTS.md`](../../AGENTS.md) 的「仓库外的环境坐标」与「已知的本机环境坑」。

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
| 读口跨源取舍 | **已定**（adr-06）：浏览器直接开读口那侧，不开 CORS、demo-app 不加代理 | Phase 5 实现时按 adr-06 第五节 |

**已验完的**：ADR-3 原本待验的「SDK 截断有无信号」—— 已验为**完全静默**，
attributes / events / links 三种截断都不产生指标、不产生日志、`SpanData` 上无标记。
所以第 4 种与 sampling 同为黑箱，都只能靠与 `GET /demo/stats` 对照发现。