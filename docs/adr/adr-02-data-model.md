# payload 存编码后的 OTLP bytes（自写 mapper），表头行只留可查询字段，resource 抽字典表

R0 实测确定了三件事，它们把这一题的选项收窄了：exporter 侧拿到的是**解码后的 `SpanData`**，不是 OTLP bytes；`ResourceSpans` / `ScopeSpans` 的分组**不在这一层**（那是编码器干的），所以拿到的是扁平列表；`io/opentelemetry/proto/` 在 agent 里**未重定位**。

**决策**：payload 一律存**编码后的 OTLP protobuf bytes**，用**自写 mapper** 从 `SpanData` / `LogRecordData` / `MetricData` 映射过去（公开的 `opentelemetry-proto` 类）。H2 侧只存可查询的表头列 + 一个指向环形文件的 `payload_id`。`Resource` 的属性抽成字典表，`InstrumentationScopeInfo` 反规范化成表头两列短字符串。

## 为什么 payload 不解码后存

payload 的唯一用途是**详情页读回单条记录**。查询全部走 H2 表头列。由此推出三条判据，编码 bytes 逐条都赢：

- **schema 不由我们定。** OTLP 的字段集与枚举是标准。存进去以后，若将来要接真后端，可以把这段 bytes 直接塞进 OTLP 请求；存解码结构则要先"解码成我们的中间结构、再编码成 OTLP"，多一轮损失。
- **紧凑。** span 的 attributes 是个 key-value 数组，二进制 protobuf 对它远小于 JSON，而这是环形文件能装多少条的决定因素。
- **只依赖公开 API。** `opentelemetry-exporter-otlp` 内部那套 `TraceProtoEncoder` 会自动跟随 SDK 版本，但它是 internal API，会被重定位、会随小版本变。扩展必须做到**只升级 agent 而不重编**，所以不能用它。

**代价（写实）**：mapper 要人工维护，升级 SDK 后要核对新增字段。但这个代价在本项目里是可接受的 —— 它对应的是"将来想看得见 OTLP 新字段"，而详情页显示什么是我们自己决定的，不存在这个需求。

## 表的划分

| 表 | 行单元 | 载荷去向 |
| --- | --- | --- |
| `span` | 单个 span | 环形文件（`payload_id`） |
| `log_record` | 单条日志记录 | 环形文件（`payload_id`） |
| `metric_point` | 单个数据点（按采集周期 × 指标 × 属性组合） | 同表内的列，不进环形文件 |
| `resource_dict` | 一个去重后的 `Resource` | 同表 |

`span` 的表头列：`id`（代理主键，供 FIFO 淘汰）、`trace_id`、`span_id`、`parent_span_id`、`name`、`kind`、`start_time`、`end_time`、`status_code`、`status_message`、`scope_name`、`scope_version`、`resource_id`、`attr_count`、`event_count`、`payload_id`。

`log_record` 的表头列：`id`、`trace_id`（可空）、`span_id`（可空）、`severity_number`、`severity_text`、`timestamp`、`observed_timestamp`、`body_preview`、`scope_name`、`scope_version`、`resource_id`、`attr_count`、`payload_id`。

`metric_point` 的表头列：`id`、`metric_name`、`data_type`（Gauge / Sum / Histogram / Summary 之一）、`ts`、`resource_id`、`scope_name`、`scope_version`、`attr_key`（属性组合的哈希）、以及按类型填充的 `value` / `count` / `sum` / `detail`（Histogram 的桶与 Summary 的分位放这里，详情页才读）。

> **实现时的两处偏离（Phase 4b）**，都不改语义，只改拼写与索引：
>
> 1. 三个标量列实际叫 `metric_value` / `metric_count` / `metric_sum`。`VALUE` 是 H2 2.x 的保留字，
>    想用就得处处加引号，而加了引号标识符就变成大小写敏感 —— 收益为零、坑一堆。
> 2. `metric_point` 另建了 `(metric_name, ts)` 与 `(attr_key)` 两个索引。上面的"只建两个索引"
>    说的是 `span` 与 `log_record`；metrics 没有 payload，时间序就是唯一的查法，
>    不建索引等于每次查指标都顺序扫。
>
> 另外 `metric_point` 多了 `unit` 与 `description` 两列：Prometheus 端点要它们，
> 而它们是**指标自身的属性**（写死在仪表定义里），放表头比放 payload 更直接。

## 索引：只建两个

`span`：`PRIMARY KEY(id)` + `idx_trace(trace_id)`。**不给 `start_time` 建索引。**

`log_record`：`PRIMARY KEY(id)` + `idx_trace(trace_id)` + `idx_span(span_id)`。

**日志的时间范围查询不靠时间索引，靠 `id`。** 依据是：行按插入顺序写入、按最旧淘汰，所以 `id` 序≈ 时间序；一个带行数水位的 FIFO 表上，按 `id` 区间查等价于按时间区间查。span 侧同理不加时间索引。

> 这一条是从 SkyWalking 侧继承的**推断而非本项目的实测** —— 那边的原话是"给 `start_time` 建索引写入放大约 26%"（见其 commit `f9fd8c1`）。本项目表结构不同（多了 resource 字典表、payload 走独立文件），**Phase 4 落地后必须实测一次**，若结论相反就改这条并更新 ADR。

## 三条必须写进实现的坑

1. **`payload_id` 可为 NULL，而 0 恰好是合法的首个逻辑偏移。** 读它必须走 `wasNull()`，否则"没有载荷"会被误报成"指向环里最老那块"。这条是从 SkyWalking 侧原样继承的缺陷模式，本项目第一次实现就要避开。
2. **payload 一律走环形文件，H2 不内联。** 一旦允许"小载荷内联"，"哪些内联了"就成了一条新的口径，而口径 proliferation 是这类存储最容易出的错。
3. **时间用纳秒 epoch（`BIGINT`）。** OTLP 原生就是 epoch nanos，不做单位换算 —— 换算会在读回时引入一类无法从数据本身发现的偏差。

4. **（Phase 4b 补）载荷写不进去时，表头行仍然要保住。** 超 `max.payload.bytes`、环写不动、
   proto 编码抛异常 —— 这三种都只丢载荷、把 `payload_id` 记 NULL。
   反过来（表头丢了只留载荷）会造出"这条记录存在但查不到"的局面，那比缺载荷难查得多：
   缺载荷至少还能在列表页看见那一行。`LocalStoreTest` 里
   「payload 为 null 时表头行照存，且不会被误读成环里第 0 块」这条测试就是钉这一条。

5. **（Phase 4b 补）Resource 白名单要挡两次：表头与载荷。** 载荷"只是给人看的详情"，
   但它落在磁盘上、活得比进程久。只过滤表头等于把 `process.command_line` 原样写进环形文件。
   过滤因此放在翻译期（`agentext` 的 `ResourceMapper`）而不是存储层 ——
   放存储层就要写两遍，漏一遍就前功尽弃。

## Resource 抽字典表，Scope 不抽

实测 `Resource` 有 **18 个属性**且三信号完全相同 —— 不抽就是每行重复 18 个键值。字典表按"属性列表的规范化哈希"去重：`attributes_hash` 唯一索引 + `attributes` 列存原文，`id` 供业务表外键引用。

哈希必须**规范化**才能保证同一份 Resource 命中同一行：键排序、值带类型标签、整体编码后再取 SHA-256。哈希碰撞时不追求正确性 —— 碰撞会让两条 Resource 共用一行，导致一条记录少几个属性；因此**以 `id` 为准、`hash` 只作去重键**，并在读回时保留原始字节以便发现不一致。

`InstrumentationScopeInfo` 反过来不抽字典：它的基数低（实测一次运行 4 个 scope）、字段短（name + version），而按 scope 筛选是常见查询 —— 做成表头两列短字符串省掉一次 join。

## Resource 属性白名单

只落白名单键，其余一律不存。理由不是省空间而是**密钥泄漏面**：实测属性里有 `process.command_line`（完整命令行）—— 命令行里带 `-Dxxx.token=` 或 `-Ddb.password=`，落库就等于把密钥写进本地文件。

白名单：`service.name`、`service.namespace`、`service.instance.id`、`service.version`、`deployment.environment.name`、`host.name`、`host.arch`、`os.type`。

**明确排除**：`process.command_line`、`process.executable.path`、`process.runtime.*`、`process.pid`、`host.ip`、`os.description` —— 路径、命令行、IP 都不进本地库。白名单之外的键若将来要支持，走配置项追加，不改代码。

## 自监控指标的过滤

按 instrumentation scope **前缀黑名单**，而不是白名单：agent 自身的 `io.opentelemetry.sdk.trace` / `io.opentelemetry.sdk.logs` / `io.opentelemetry.runtime-telemetry-java8` / `io.opentelemetry.exporters.otlp-http` 不入库（实测它们占一次采集的 4~8 条 / 共 11~17 条）。

用黑名单而非白名单，是因为用户的自定义指标无法预先列举。白名单会把"用户自己的指标"也挡掉，那是更常见的需求。黑名单的代价是将来 agent 新增自监控 scope 会漏进来 —— 因此该列表**可配置**，且新增 SDK 版本时需复核一次。

这些指标**走 JMX 面板，不入库**：它们回答"SDK 健康吗"，与"业务数据"是两回事，混在一张库里既污染查询也误导排障。

## Considered Options

- **用 `opentelemetry-exporter-otlp` 的内部 encoder 编码**：拒绝。internal API + 会被 agent 重定位 + 随 SDK 小版本变，扩展会被钉死在某个编译期版本上，与"只升级 agent 不重编"直接冲突。
- **存解码后的结构（JSON 或我们自己的 proto）**：拒绝。JSON 体积在 span attributes 上明显更大；自定义 proto 等于我们自己维护 schema，OTLP 演进时腐坏在我们手里而不是别人手里。唯一保住的优点是"详情页读回不必解 proto"，但那个代价远小于体积损失。
- **payload 内联进 H2 行（小载荷）**：拒绝。见上面第 2 条坑。
- **给 `start_time` 建索引以支持时间范围查询**：暂不做，按上文"靠 `id` 序"推理。若 Phase 4 实测证明时间范围查询在真实负载下不可接受，这条推翻重来。
- **InstrumentationScopeInfo 也抽字典表**：拒绝。基数低、字段短、反规范化后省一次 join；而 resource 是 18 个属性含长字符串，必须抽。两者不该用同一种处理。
- **Resource 属性全量落库**：拒绝。理由见上面的密钥泄漏面，不是性能。
- **metrics 也走环形文件 + 事件流**：拒绝。metrics 是周期性聚合快照不是事件；进环形文件会让"按指标名查时间序列"变成顺序扫描。三张表里只有 `span` 与 `log_record` 有 `payload_id`。

## Consequences

- `agentext` 侧要写三个 mapper（span / log / metric），各约 150~300 行，且**升级 SDK 后要核对新增字段**。这是本决策最主要的长期维护成本，已在上面的判据里认了。
- 详情页读回一条记录 = 一次 H2 按 `id` 取行 + 一次环形文件按 `payload_id` 读块 + 一次 proto 解码。行单元选单 span（而非 SW 的"段"）意味着**同一个 trace 要按 N 行拼**，详情页的组装逻辑要自己写。
- `resource_dict` 是 SW 侧没有的新东西，也是本项目唯一新增的表。它让 span 行的宽度从"18 个属性"降到"一个外键"，量级差别见 R0 笔记第八节。
- **metrics 的 rollup 这一版不做**：本 ADR 只定义"时间序列形态 + 行数水位"，分钟/小时 rollup 表留到后面。ADR-1 里说的"rollup"指的是"不是事件流"这个形态判断，不是已经建了两级汇总表。
- 三个 mapper 的一致性要靠测试兜住：同一批输入下，三条路径的 `resource_id` 必须指向字典表里**同一行**。这条断言比单测任何一个 mapper 都重要。
- **上游佐证**：官方 `opentelemetry-disk-buffering` 落的就是 OTLP protobuf bytes
  （依赖 `wire-runtime` + `exporter-otlp-common`），与本决策一致。
  实测数据见 [`notes/2026-10-04-portability-and-android.md`](../notes/2026-10-04-portability-and-android.md)。