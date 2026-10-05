# 架构决策记录

决策句做标题，正文只写"当时面对什么、怎么定、否掉了什么、后果是什么"。
事实与实测数据不进这里 —— 那些在 `docs/notes/`。

| 编号 | 决策 | 状态 |
| --- | --- | --- |
| [adr-01](adr-01-scope-and-principles.md) | 三个信号就地落库、只读暴露；接入点用 Provider builder 而非 exporter 装饰器 | 已定 |
| [adr-02](adr-02-data-model.md) | payload 存编码后的 OTLP bytes（自写 mapper）；表头行只留可查询字段；Resource 抽字典表 | 已定，一条推断待实测 |
| [adr-03](adr-03-four-ways-data-goes-missing.md) | "数据少了"有五种形态，各计各的不求和 | 已定 |
| [adr-04](adr-04-h2-in-memory-and-reset-on-startup.md) | H2 用内存模式、存储随进程存活，启动时显式重置环形文件 | 已定 |
| [adr-05](adr-05-shade-third-party-deps-into-extension-jar.md) | 三方依赖（H2 / protobuf / opentelemetry-proto）shade 进扩展 jar；protobuf 与 otel-proto 必须 relocation | 已定（Phase 4b 实测修正） |
| [adr-06](adr-06-readout-http-surface.md) | HTTP 读口只开一个端口，端点清单封闭、JSON 手写；自监控指标只走读口旁路不入库 | 已定（Phase 5 待实现） |
| [adr-07](adr-07-demo-app-embedded-deps-except-mysql.md) | demo-app 的依赖全部内嵌，唯一例外 MySQL 连外部实例且**降级不崩** | 已定 |

## 悬着的事

- **`start_time` 不建索引**（adr-02）—— 从 SkyWalking 侧继承的是**推断**，本项目表结构不同，需实测。
  Phase 4b 已落地，**现在就可以实测**；若结论相反就改 ADR-2。
- **`/metrics` 的标签只有 `attr_key` 哈希**（adr-06 第八节）—— `metric_point` 表只存了属性组合的
  哈希、没存属性本身，所以 Prometheus 端点目前输出的是 `{attr_key="a3f9c2e1"}` 而不是
  `{region="east"}`。**要看真标签就得加 `attr_text` 列**（`resource_dict` 已有同一套规范化可用）——
  建议与 metrics rollup 一起做，不要单独立项。
- **指标名的撞名处理**：非法字符换 `_`，撞名追加 8 位哈希后缀（adr-06 第八节）。
- **多实例共用 `dataDir`** —— 明确不支持（adr-04 的前提）。见到有人要支持，先写 ADR。
- **metrics 的分钟/小时 rollup** —— 本版只定义"时间序列形态 + 行数水位"，汇总表留到后面。
- **ADR-3 第 4 种（SDK 层截断）真的完全静默吗** —— ADR-3 的结论是"`SpanData` 上没有任何标记"，
  但当时验的是 `getAttributes().size()`。`SpanData.getTotalAttributeCount()` 是**公开 API**，
  Phase 4b 的 mapper 顺手把它写进了 `attr_count` 列与 OTLP 的 `dropped_attributes_count`。
  **尚未实测**：若限额下 `total - size()` 恒大于 0，第 4 种就变成可计数的，ADR-3 要改。
  下一步：用一次性探针在限额下验一次（探针目录见 [`AGENTS.md`](../../AGENTS.md) 的「仓库外的环境坐标」）。
