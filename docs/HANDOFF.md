# Handoff — otel-local-telemetry-store

> **交接说明**：本文只交代「从哪儿接着做」与「哪些坑已经踩过」。
> **已有结论一律给路径，不复述** —— 决策在 `docs/adr/`，实测数据在 `docs/notes/`。
> 本文写于 2026-10-04，仓库状态 `8bded75`，工作区干净。

---

## 0. 一句话现状

`D:\gitRepository\otel-local-telemetry-store`，`master`… 更正：**分支是 `main`**，与远端同步。

**已落地**：骨架 + 存储层（环形文件）+ 配置层 + 三条采集管线接线。**34 个测试全绿**，字节码 52。
**尚未落地**：H2 落库（Phase 4b）、读口（Phase 5）。所以**现在还存不下任何数据**，只能验证"收到了多少"。

代码量：`src/main` 9 个类 1240 行 · `src/test` 5 个类 853 行 · `docs` 7 篇。

---

## 1. 接着做之前必读（顺序别乱）

| 顺序 | 读什么 | 为什么 |
| --- | --- | --- |
| 1 | [`AGENTS.md`](../AGENTS.md) | 三条硬规则 + 验证节奏的禁令 |
| 2 | [`docs/adr/index.md`](./adr/index.md) | 四条决策 + 「悬着的事」三条 |
| 3 | [`docs/adr/adr-01-scope-and-principles.md`](./adr/adr-01-scope-and-principles.md) | Phase 4/5 的形状由它定死 |
| 4 | [`docs/notes/2026-10-04-r0-extension-points.md`](./notes/2026-10-04-r0-extension-points.md) | 实测事实的来源。**不要凭记忆替代它** |

---

## 2. 下一步：Phase 4b（接 H2 与环形文件）

**只改三个地方**，tap 与队列都不动：

1. `TapHub` 里三个 `PlaceholderSink` 换成真 sink
2. 新建 `core` 下的 H2 存储（表结构见 ADR-2）
3. 新建 `core` 下的 `ResourceDictionary`（ADR-2 的 `resource_dict`）

**验收信号**：demo-app 造完数据后，库里能查出记录；且 `TapHub.logSummary` 之外还需要一个能对账的口子
—— Phase 5 的读口没做之前，可以临时用 JMX（**唯一跨 ClassLoader 的通道**，见 R0 笔记第五节）。

### 之后：Phase 5（读口）

JMX 先、HTTP 后（含 Prometheus 文本端点）。**已知未决**：读口在 17890、demo-app 在 18081 属跨源，
要么读口开 CORS、要么 demo-app 加代理、要么直接用浏览器开读口那侧的页面 —— 取舍还没定，记在
`demo-app/README.md` 里。

---

## 3. 悬着的事（`index.md` 里有，这里补上下文）

| 事项 | 状态 | 什么时候该做 |
| --- | --- | --- |
| `start_time` 不建索引 | **推断**，从 SkyWalking 侧继承（那边实测写慢 26%，但表结构不同） | Phase 4b 落地后实测；若结论相反，改 ADR-2 |
| metrics 分钟/小时 rollup | 本版只定义"时间序列形态 + 行数水位" | 后面 |
| 多实例共用 `dataDir` | **明确不支持**，是声明的作用域前提（ADR-4） | 见到有人要支持，先写 ADR |
| 读口跨源取舍 | 未决 | Phase 5 |

**已验完的**：ADR-3 原本待验的「SDK 截断有无信号」—— 已验为**完全静默**，
attributes/events/links 三种截断都不产生指标、不产生日志、`SpanData` 上无标记。
所以第 4 种与 sampling 同为黑箱，都只能靠与 `demo-app /demo/stats` 对照发现。

---

## 4. 环境（不在仓库里）

```powershell
$env:JAVA_HOME="D:\apps\java\jdk-17.0.8"          # 构建用，产物字节码 8
D:\apps\opentelemetry-javaagent-2.32.0.jar        # agent 2.32.0（26MB）
D:\apps\actionlint\actionlint.exe                  # workflow 本地校验，1.7.12
gh 未安装                                           # GitHub 远程是手工建的
```

**临时探针目录**（不在仓库，可复用可销毁）：`C:\Users\lqzkc\AppData\Local\Temp\opencode\otel-probe`
—— R0 与截断验成都用它。里面已有一个可复用的 SPI provider 骨架。

### 两个会浪费时间的本机环境坑

- **PowerShell 7 的 `Invoke-RestMethod` / `Invoke-WebRequest` 不认 `NO_PROXY`** ——
  即使 `NO_PROXY=localhost,127.0.0.1` 已设好，访问 localhost 仍会超时。
  用 `curl.exe --noproxy "*"` 或加 `-NoProxy`。
- **端口 18080 被本机另一个项目占着**（RuoYi-Flowable-Plus）。demo-app 因此用 **18081**。

---

## 5. 完整验证（**用户跑，agent 不跑**）

`AGENTS.md` 已写死这条禁令。长验证交给用户，别自己起进程然后 sleep 轮询。

```powershell
cd D:\gitRepository\otel-local-telemetry-store
$env:JAVA_HOME="D:\apps\java\jdk-17.0.8"; $env:Path="$env:JAVA_HOME\bin;$env:Path"

mvn -o -q test                              # 预期：34 个测试，-q 下全绿静默
javap -verbose -cp target\classes io.github.fulizhe.otelstore.agentext.LocalStoreCustomizerProvider |
  Select-String "major version"             # 预期：52
D:\apps\actionlint\actionlint.exe .github/workflows/*.yml    # 预期：无输出

pwsh -NoProfile -File scripts/run-with-agent.ps1            # 端到端，长驻，Ctrl-C 停
```

**端到端的验收信号**（Phase 4a 就有的）：
启动日志一行「已注册三条采集管线 …」，停进程时一行「退出 … | traces offered=N drained=N dropped=0 …」。
失败判据见 commit `8bded75` 的 message。

---

## 6. 别重新踩一遍的坑

### 已经在仓库里（读到即可）

- **agent 只认 jar，不认目录** —— `-Dotel.javaagent.extensions=target/classes` 会**静默忽略、零告警**。
  `scripts/run-with-agent.ps1` 已在起之前校验产物。
- **扩展 jar 不能与应用 classpath 同路径** —— 同一个 jar 出现在两处会加载出**两个不同的类**，
  跨 ClassLoader 的静态引用必然 `NoClassDefFoundError`。（我自己的探针就犯了这个。）
- **agent 对 `io.opentelemetry.context.*` 做了重定位** —— 编译期看不到、运行期类型不同。
  一律编译 against 未重定位的普通 artifact。
- **不要用 `opentelemetry-exporter-otlp` 的内部 encoder** —— internal API + 被重定位 + 随小版本变。

### 只在本文里（仓库没记）

- **JUnit 5 的消息参数在末尾**，不是开头。我在这个仓库里**反复写错**（JUnit 4 肌肉记忆），
  搬测试代码时还因此返工三轮。
- **用正则给 JUnit 断言搬家时，消息是字符串拼接的（`"msg " + i,`）会漏** ——
  "闭合引号后紧跟逗号"的假设不成立。多行写法同样漏。这类活儿干不过 5 处就别用正则了。
- **`readMessage(id)` 能力在 ≠ 会发生**。我曾把"重启后新进程能读到旧进程的块"写成"会偶然发生"，
  是错的 —— 能力在，但唯一的调用方（H2 的 `payload_id`）在内存模式下是空的，没人触发。
  **写"会不会发生"之前先找调用方。**
- **同一份取舍要前后一致**。ADR-4 里我用"文件锁是硬伤"论证了选 H2 内存模式，
  却没把同一条论据套在**自家的环形文件**上（它同样没有 `FileLock`、文件头没有实例标识）。
  是用户追问才暴露的。
- **主体与靶子别搞混**。`demo-app` 是验证主体用的工具与可视化验收面，不是交付物。
  本会话因此被纠正两次（写了 `AGENTS.md` 的「demo-app 是靶子，不是交付物」）。

---

## 7. 本仓库提到的路径

```
pom.xml                                    单模块；OTel 两个 artifact 均 provided
src/main/java/io/github/fulizhe/otelstore/
    core/config/LocalStoreConfig           otel.localstore.* ；解析永不失败
    core/collection/RecordQueue            有界队列 + drainer 线程 + 三类分开计数
    core/storage/CappedFileStorage(+Stats) 堆外环形载荷文件（从 SW 侧搬来，带测试）
    agentext/LocalStoreCustomizerProvider  SPI 入口；三个 *ProviderCustomizer
    agentext/{SpanTap,LogTap,MetricTap}    只做一次转换 + 一次 offer
    agentext/TapHub                        三队列 + 扁平快照 + 退出汇总
src/main/resources/META-INF/services/
    io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider
demo-app/                                  造信号的靶子（Spring Boot 2.7.18，Java 8）
scripts/run-with-agent.ps1                 构建 + 校验产物 + 探端口 + 后台起
.github/workflows/build.yml                compile + 字节码断言 52 + test
docs/adr/                                  四条决策 + index 的「悬着的事」
docs/notes/2026-10-04-r0-extension-points.md   全部实测事实的唯一来源
docs/notes/2026-10-04-profiling-research.md      profiling 明确 out of scope，研究问题在此
```