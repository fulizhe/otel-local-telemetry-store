# ADR-8：依赖拓扑图 —— 组件级、由 span 的 kind 与 scopeName 推导，不伪造服务名

## 面对什么

到 2026-10-06，读口已经能看 span / 日志 / 指标、能画一条 trace 的瀑布，但**"这个进程在跟哪些
外部组件打交道"仍然要一条条 trace 点开看**。ADR-6 §十五当时把"服务拓扑图"明确推到"以后再说"
（理由：它要单独定义 endpoint×component 的聚合口径）。现在用户要这张图，所以先立这条决策。

关键约束：**没有共享存储**（ADR-4，每个进程一个内存库），所以**跨进程的 service→service 根本
拼不出来**；而且表头行里**没有** `db.system` / `net.peer.*` 这类能直接当"对端"用的属性
（ADR-2 只留了可查询字段）。能把边拼起来的只有：`span_id` / `parent_span_id`（同进程内的父子）、
`kind`、`scope_name`、以及 Resource 里的 `service.name`。

## 怎么定

加**只读**的依赖拓扑视图：页 `topology.html` + 端点 `GET /api/topology?limit=`。数据来自
`span` 表的最近 N 条（`MAX_LIMIT_TOPOLOGY = 2000`），在 `ReadoutQueries` 里内存聚合。

**节点**只有两类：

- `service` 节点：Resource 里的 `service.name`（没有就写「本进程」）。代表被观测应用自己。
- `component` 节点：CLIENT / PRODUCER span 的 `scope_name`（仪表化来源，如
  `io.opentelemetry.jdbc` / `jedis-3.0` / `kafka-clients-2.6` / `grpc-1.6`）。
  代表它伸出去调的那一类外部组件。**不把 scopeName 伪装成 service.name**（沿用 ADR-6 §十五）。

**边**（有向，按 `(from,to)` 聚合，带 calls / errors / avg / max / 操作样例）：

- 一个 span 的父在结果集里 → 边 `节点(父) → 节点(该 span)`。
- 父不在（被行数水位淘汰、或被 limit 截断）且该 span 是 CLIENT/PRODUCER → 退化成
  `service → component` 的边（至少不丢"这个出口发生过"）。

节点归属规则由此自然成立：SERVER / INTERNAL / CONSUMER span → service 节点；CLIENT / PRODUCER
span → component 节点。**同进程的 gRPC 因此能画出 `service → gRPC` 与 `gRPC → service` 一来一回**。

**画法**：用 **ECharts 的 `graph`**（力导向 / 缩放 / 拖动 / 边符号都是现成的，观感与样例一致）。
`echarts.min.js`（Apache-2.0，约 1 MB）**随读口一起发**：本地静态资源 `/echarts.min.js`，
**不走 CDN** —— 这样"读口跑在客户内网、不能假设有外网"这条底线仍然成立。ECharts 没加载出来时
回落到内联 SVG，不至于一片空白。**这是本项目唯一引入的前端三方库**，是 ADR-6 第十三节
"页面零依赖"的一个**明确例外**（起因：第一版自己手画的 SVG 用户反馈"不如样例"）。
边粗 ∝ 调用量，边色按错误率分档；下方一张边列表（可读的调用量 / 错误 / 平均 / 最大）。

**刷新**：默认 30s 自动刷（同其它数据页），不是自日志那种手动。

## Considered Options

| | 做法 | 为什么没选 |
| --- | --- | --- |
| 跨进程 service→service | 按 traceId 拼 client span 的对端 service | **没有共享存储**，对端进程的数据不在这个库里；拼不出来 |
| endpoint×component 聚合 | 在图上再放一层端点 | 需要新的聚合口径与更重的查询；用户第一版只要"依赖关系"，端点明细看边列表即可 |
| 把 `db.system` 提成 `span` 列再画 | 让 H2 / MySQL 在图上可分 | 会动 ADR-2 的表头行口径，**值得单独一条决策**；第一版接受同一 scopeName 归一个组件 |
| 自己手画内联 SVG | 与瀑布/趋势图同口径、零依赖 | 力导向 / 缩放 / 拖动的可用度差，真画出来"不如样例"（用户反馈）；把有限时间花在重复造轮子上不值 |
| 从 CDN 引 ECharts | 不占仓库体积 | 读口在客户内网，**没有外网**；引 CDN 会静默变空白 |
| 引 d3 而不是 ECharts | 更底层、更可控 | 同样的"组件图"要自己写布局；ECharts 的 graph 现成，且样例项目已在用 |
| 服务拓扑永远不做 | 保持原样 | 需求真实存在，且用现有字段就能给出**诚实**的组件级拓扑 |

## Consequences

- 端点清单**加一条** `/api/topology`（ADR-6 §三），页面清单**加一条** `topology.html`
  （ADR-6 第十三节）。两处都要同步改。
- **读口 jar 多约 1 MB 静态资源**（`echarts.min.js`，Apache-2.0，保留原始许可头）。
  它只在拓扑页加载（其它页不引），加载失败时该页回落到内联 SVG。
- **节点只到组件类型，不区分实例地址**：同一 scopeName 的多个实例并成一个节点。
  尤其 **H2 与 MySQL 都是 `jdbc`，在图上并成一个「JDBC」节点** —— 想区分得先把 `db.system`
  提成列（另一条决策）。边列表里的操作样例（SQL 原文）能让人看出打的是哪张表。
- **连接都没建起来的依赖不出现在图上**："调用已发生"才有 span；connection refused 在调用之前，
  没有边。某个组件缺席**不能**读成"没有这个调用"。
- 跨进程的入边拼不出来：只有**同进程内**父在结果集里的 SERVER/CONSUMER span 才会有入边。
- 数据是最近 N 条 span 的**快照聚合**，不是全量、也不是时间窗口内的精确分位（avg/max 是近似）。
