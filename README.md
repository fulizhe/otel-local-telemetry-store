# otel-local-telemetry-store

**把 OpenTelemetry 的 traces / logs / metrics 留在应用自己的进程内** —— 不发往任何远端，也不需要第二套 agent。

> **状态：存储已落地，端到端未验收。** 三条采集管线能把 traces / logs / metrics 写进本地库
> （H2 内存表头 + 堆外环形载荷），并通过 **JMX** 读回来。**HTTP 读口与 Prometheus 端点还没做**，
> 所以下面「目标用法」里只有存储与限额那几项配置**现在就有效果**，读口那几项要等下一阶段。
> 演示应用已经能跑，负责造三个信号并给出可断言的计数。
>
> 端到端验收信号、失败判据与踩坑记录见
> [`docs/notes/2026-10-04-verification-and-pitfalls.md`](./docs/notes/2026-10-04-verification-and-pitfalls.md)。

- **前提**：JDK 8+ 的目标应用 + `opentelemetry-javaagent`（本项目作为 agent 扩展挂载）。
- **许可**：Apache-2.0。
- **第三方声明**：本项目与 OpenTelemetry 基金会**无隶属关系**，`otel-` 前缀只是描述兼容目标，不代表官方组件。

## 为什么

| 现状 | 代价 |
| --- | --- |
| 发到后端 | 数据出机器，成本随流量无界增长 |
| 部署 Collector | 多一跳、多一个要运维的进程 |
| 挂 Glowroot 之类的 APM | 要引入并长期跟随第二套 agent |

本项目的取舍：**存储有界、降级可控、只挂一个 agent**。数据留在产生它的 JVM 里，读口用标准协议（Prometheus scrape）暴露 —— 数据不出机器，但看得见。

## 范围

**做**（单 JVM / 单体应用场景优先）：

- **traces** —— 事件流，表头行入库，明细载荷落堆外环形文件
- **logs** —— 事件流，同上；日志记录自带 trace / span 上下文，可按 trace 拉全量日志
- **metrics** —— 周期性快照按「时间序列形态」入库（采集周期 × 指标 × 属性组合 = 一行），不进环形文件。
  **分钟 / 小时 rollup 表未做**，本版只有行数水位（[ADR-2](docs/adr/adr-02-data-model.md)）

**不做**：

- **profiling** —— 需要 native agent（async-profiler），OTel 信号管线给不了。
  明确排除，不是"以后顺手加上"。研究问题已记在 [`docs/notes/2026-10-04-profiling-research.md`](./docs/notes/2026-10-04-profiling-research.md)。
- **Web UI** —— v1 只提供读口与 Prometheus 端点，产品级界面是独立里程碑。
- **raw SQL 查询端点** —— 永远不提供。查询全部参数化，读口因此是结构性只读。

## 使用前提与注意事项

**① 同一个 `dataDir` 只会有一个实例。**
这是硬前提，不是建议。表头在 H2 内存里天然进程私有，但**环形载荷文件在磁盘上、且没有文件锁**
（文件头只有写游标和大小，没有 pid 或实例标识）。若两个进程共用同一组 capped 文件，双方会从同一个
写游标起写、互相覆盖对方的块，各自表头指向的载荷指针会被对方覆盖 —— **读回的是别的进程的数据**。

症状是"偶尔读到不属于这个应用的 trace"，几乎无法自行归因。需要多实例时，**给每个实例配不同的 `dataDir`**。

**② 存储随进程存活，重启即清空。**
内存模式不落盘、不碰文件锁；两个存储层在启动时一起清空。重启后看不到之前的 trace 与指标，
读口的 uptime 从本次启动算起。

**③ 资源属性按白名单落库。**
`process.command_line`、`process.executable.path`、`process.pid`、`host.ip` 等**不入库** ——
完整命令行里可能带 token 或口令，落库等于把密钥写进本地文件。白名单外的键将来走配置追加。

**④ 读口默认开启、默认绑定 `0.0.0.0`、默认不要求 token。**
⚠️ **默认状态下，同一网络内的任何机器都能读走全部 trace 与日志载荷** —— 里面装着 SQL 语句、
HTTP header（含 `Authorization` 与 `Cookie`）、请求体、日志原文。
这是"默认方便"换来的代价：挂在**本机**应用里自查时，一道"去文件里复制 token"只会碍事，
挡不住真实威胁。需要鉴权就显式配 `otel.localstore.auth=true`，此时 token 每进程随机生成、
写在 `*.token` 文件与启动日志里，**绝不出现在任何日志 / 快照 / 异常消息中**。
想让默认就安全，把 `otel.localstore.host` 配成 `127.0.0.1`（只本机可读）。

**⑤ agent 自监控指标不入库。**
按 instrumentation scope 前缀黑名单过滤。它们回答的是"SDK 健康吗"，与业务数据混在一张库里
既污染查询也误导排障。**看它们的地方是 JMX 与启动日志** —— 专门的指标面板随 HTTP 读口一起做。

**⑥ 不支持 profiling。** 需要 native agent，OTel 信号管线给不了。

**⑦ 端口冲突会让读口退到随机端口，不影响应用启动。**
但 `demo-app` 的端口是 Spring 管的，**撞端口会直接启动失败** —— 换端口即可。

## 演示应用

`demo-app/` 是一个 Spring Boot 应用，按需造出 traces / logs / metrics，
并把自己造了多少暴露成可断言的计数（`GET /demo/stats`）。

```powershell
pwsh -NoProfile -File scripts/run-with-agent.ps1
# 打开 http://localhost:18081/
```

`/demo/stats` 是**对账基准**：库里（停进程时日志里 `store spans=N` 那一行，或 JMX 的
`spanRows()`）的条数应当与它对得上，差额就是采样、SDK 截断与队列丢弃 ——
那几种"数据少了"在原理上不可计数，只能这样对照发现
（[ADR-3](docs/adr/adr-03-four-ways-data-goes-missing.md)）。

**网页上看不到库里存的东西** —— 它目前只显示自己造了多少。浏览器可读的数据要等 HTTP 读口。

细节见 [`demo-app/README.md`](./demo-app/README.md)。

## 目标用法

存储部分**现在就能跑**；读口那几项配置要等 HTTP 阶段。

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=otel-local-telemetry-store.jar \
     -Dotel.localstore.dataDir=/var/lib/otel-store \
     -Dotel.localstore.capped.traces.bytes=268435456 \
     -Dotel.localstore.rows.metrics=200000 \
     -jar your-app.jar
```

数据落在 `dataDir` 里（`traces.capped` / `logs.capped` 两个环形文件 + 内存里的表头）。

**怎么看它**（三条路，口径同源）：

| 途径 | 适合 |
| --- | --- |
| 应用日志里每 60 秒一行 `周期 dataDir=… \| store spans=N logs=N metricPoints=N resources=M` | 快速自查"有没有收到、存了多少" |
| 浏览器打开读口那侧 `http://<host>:17890/` | 交互式排查：索引页给各页入口；`traces.html` / `logs.html` / `metrics.html` 看三条线，`self.html` 看生效配置、库状态与自监控。点 span 或日志的行会弹出详情 |
| `jconsole` → MBeans → `io.github.fulizhe.otelstore` → `LocalStoreSummary` | 进程内的完整快照（`summary` / `recentSpans` / `spansOfTrace` / `spanPayloadHex`） |

那行周期汇总是本扩展**唯一默认的周期性日志输出**；不想看就把
`io.github.fulizhe.otelstore` 这个 logger 的级别调高。

### 端点清单（封闭清单）

全部只接受 `GET` / `HEAD`，全部受 `otel.localstore.auth` 控制（默认不要求）。
清单是封闭的：没有任意 SQL，也没有原始载荷下载口（见 [ADR-6](docs/adr/adr-06-readout-http-surface.md)）。

| 端点 | 返回 |
| --- | --- |
| `/` `/index.html` | **索引页**：概览 + 各页面入口。**不含任何数据** |
| `/traces.html` | span 列表页（不含数据，数据由页面 JS 带头去取） |
| `/logs.html` | 日志列表页，同上 |
| `/metrics.html` | 指标点页，同上 |
| `/self.html` | 扩展自身页：生效配置、库状态、自监控，同上 |
| `/self-log.html` | 扩展自己的日志页（结构化、按级别配色；刷新比自监控页快） |
| `/app.css` `/app.js` | 各页面共享的样式与脚本 |
| `/api/summary` | **唯一的自监控面**：生效配置 / 三条队列 / 各表行数 / 两个环的写游标与覆盖轮次 / 淘汰计数 / 启动时间与实际端口 / agent 版本 / 存储降级原因 / 自监控指标丢弃计数 |
| `/api/traces?limit=&traceId=` | span 表头行（**不含载荷**），按 id 倒序；给了 `traceId` 则按开始时间排 |
| `/api/logs?limit=&traceId=` | 日志表头行，同上 |
| `/api/metrics?name=&limit=` | 指标点的**时间序列**（给了 `name` 则只看那个指标；不给就是最近若干个）。`detail` 结构化返回：桶上界（含 `+Inf`）、累计计数、分位点 |
| `/api/traces/{id}` `/api/logs/{id}` | 单条详情：表头 + 载荷状态 + **解码后的** attributes / events / status。`{id}` 必须是纯数字 |
| `/metrics` | Prometheus 文本（每个指标每个属性组合的**当前值**） |
| `/api/self-log?lines=` | 扩展自己日志的尾部若干行（默认 100、上限 500）；只读扩展自己写的 `<dataDir>/otelstore.log`，不读应用的日志文件 |

页面**路径精确匹配**，没有无后缀别名（`/traces` 会 404，入口都在 `/`）。

`limit` 的上限**按用途分档**：列表 200、日志尾部 500、**指标点 2000**；缺省一律 20（日志尾部 100）。
`/metrics` 不限，它按序列给。指标那条抬到 2000 是因为它是**看趋势**的
（最近半小时 × 每秒一点 = 1800 条），不是"多给数据"。
**没有数据返回 200 + 空数组**，只有参数非法才 4xx —— 「没有数据」与「请求写错了」必须能分开。

**载荷可能读不出来，而四种原因的处置完全不同**（详情页的 `payload.reason` 会说清是哪一种）：

| `reason` | 含义 | 是故障吗 |
| --- | --- | --- |
| `expired` | 已被环形文件覆盖 | **否** —— 容量到顶的预期行为，调大 `capped.*.bytes` 可留住更久 |
| `no_payload` | 写入时超了 `max.payload.bytes` 或编码失败 | 看规律；表头行仍在库里，查询不受影响 |
| `corrupt` | 字节读到了但解不出 OTLP 结构 | **是** |
| 404 | 这一行不存在（被行数水位淘汰，或 id 写错） | 通常是 id 写错 |

```bash
curl.exe http://host:17890/api/summary
curl.exe "http://host:17890/api/traces?traceId=<32 位十六进制>"
curl.exe "http://host:17890/api/metrics?name=http.server.duration"
# 配了鉴权时：
curl.exe -H "X-Otel-Store-Token: <dataDir>/otelstore.token 里的内容" http://host:17890/api/summary
```

> **两个已知限制**（都在 ADR-6 里记着）：
> ① `/metrics` 的标签是 `attr_key` **哈希**而不是 `region="east"` —— 表里只存了属性组合的哈希。
> 各条线仍然是分开的，只是标签暂时读不懂。
> ② 读口**不开 CORS**，所以那个页面要单独打开，不能从别处 fetch。

下一阶段的形态是补齐其余端点（含 Prometheus 与载荷详情），届时读口相关配置全部生效：

端口被占用时自动退到随机端口并在日志里报实际值 —— **端口冲突不会让应用启动失败**。

## 配置

命名空间 `otel.localstore.*`，刻意避开 OTel 自身的 `otel.traces.*` / `otel.metrics.*` / `otel.logs.*`。
非法取值一律回落默认值，不抛异常。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `host` | `0.0.0.0` | 读口绑定地址，默认允许远程访问 |
| `port` | `17890` | 读口端口；避开 OTLP 惯例的 4317 / 4318。**被占用时自动退到随机端口**，实际端口写进 `<dataDir>/otelstore.port` 与启动日志 |
| `auth` | `false` | 是否要求访问 token。默认**不要求** —— 见上面的 ④。启用后读口数据端点要 token（`X-Otel-Store-Token` 头），token 落在 `<dataDir>/otelstore.token` |
| `token` | 进程启动时随机生成 | 仅当 `auth=true` 时有意义 |
| `dataDir` | `./otel-local-telemetry-store` | 数据目录 |
| `capped.traces.bytes` | 256 MiB | traces 环形文件容量 |
| `capped.logs.bytes` | 256 MiB | logs 环形文件容量 |
| `max.payload.bytes` | 1 MiB | 单条载荷上限，超限拒写并计数 |
| `rows.traces` / `rows.logs` / `rows.metrics` | 200000 | 表头行水位，超出按最旧淘汰。三个信号各自独立；metrics 行更轻，但未实测前不猜更小的值 |
| `queue.capacity` | 4096 | **每条信号**各自的有界队列深度；满了就丢弃并计数，不阻塞 |

标「未生效」的那几项不是配置坏了，是**读口本身还没实现** —— 它们现在以默认值生效，
HTTP 阶段接上后立刻可用。

## 代码分层

```
io.github.fulizhe.otelstore
├── core      与 OTel 无关：配置、存储、统计
├── agentext  OTel 接入：SPI provider 与三条信号管线
└── readout   读口：JMX、HTTP、Prometheus 渲染（**目前只有 JMX**）
```

`core` 不许 import 任何 `io.opentelemetry.*` —— 存储层与采集端解耦，是它能独立复用的唯一保证。

```
core/config/LocalStoreConfig              otel.localstore.* ；解析永不失败
core/collection/RecordQueue               有界队列 + drainer 线程 + 三类分开计数
core/model/{KeyValue,ResourceDescriptor,
            SpanRecord,LogRecordEntry,MetricPointEntry}
                                           core 的入参类型：JDK 原生，不 import OTel
core/storage/CappedFileStorage(+Stats)    堆外环形载荷文件（从 SW 侧搬来，带 reset）
core/storage/PayloadRing                  环 + 单块上限 + 启动重置；-1 表示"没写进去"
core/storage/LocalStore                   H2 内存库 + 四张表 + 行数水位 FIFO 淘汰
core/storage/ResourceDictionary           Resource 按规范化哈希去重
core/storage/CanonicalAttributes          规范化文本 + SHA-256（字典与 attr_key 共用）
core/util/ThrottledLogger                 限速日志（计数永远做，只挡"写几个字"）

agentext/LocalStoreCustomizerProvider     SPI 入口；三个 *ProviderCustomizer
agentext/{SpanTap,LogTap,MetricTap}       只做一次转换 + 一次 offer
agentext/TapHub                           三队列 + 真 sink（翻译 + 落库）+ 退出汇总
agentext/{OtelAttributes,ResourceMapper,
          SpanMapper,LogMapper,MetricMapper}
                                           OTel → core 入参 + OTLP protobuf 载荷

readout/TextRenderer                      快照 → 缩进文本
readout/jmx/{LocalStoreSummaryMBean,LocalStoreSummary,JmxReadout}
                                           JMX 读口：属性只有 String / int
```

## 文档

- [`AGENTS.md`](./AGENTS.md) —— 构建、验证与代码约定
- [`docs/adr/`](./docs/adr/) —— 架构决策记录
- [`docs/notes/`](./docs/notes/) —— 调研笔记

已落盘的决策：

- [ADR-1 三个信号就地落库、只读暴露](docs/adr/adr-01-scope-and-principles.md)
- [ADR-2 payload 存编码后的 OTLP bytes，Resource 抽字典表](docs/adr/adr-02-data-model.md)
- [ADR-3 "数据少了"有五种形态，各计各的](docs/adr/adr-03-four-ways-data-goes-missing.md)
- [ADR-4 H2 内存模式、存储随进程存活](docs/adr/adr-04-h2-in-memory-and-reset-on-startup.md)
- [ADR-5 三方依赖 shade 进扩展 jar](docs/adr/adr-05-shade-third-party-deps-into-extension-jar.md)

**存储的性质：随进程存活，重启即清空**（[ADR-4](docs/adr/adr-04-h2-in-memory-and-reset-on-startup.md)）。
内存模式不落盘、不碰文件锁，代价是重启后看不到之前的 trace 与指标 —— 这是声明的性质，不是缺陷。