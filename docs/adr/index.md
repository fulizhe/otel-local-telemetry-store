# 架构决策记录

决策句做标题，正文只写"当时面对什么、怎么定、否掉了什么、后果是什么"。
事实与实测数据不进这里 —— 那些在 `docs/notes/`。

| 编号 | 决策 | 状态 |
| --- | --- | --- |
| [adr-01](adr-01-scope-and-principles.md) | 三个信号就地落库、只读暴露；接入点用 Provider builder 而非 exporter 装饰器 | 已定 |
| [adr-02](adr-02-data-model.md) | payload 存编码后的 OTLP bytes（自写 mapper）；表头行只留可查询字段；Resource 抽字典表 | 已定，一条推断待实测 |
| [adr-03](adr-03-four-ways-data-goes-missing.md) | "数据少了"有五种形态，各计各的不求和 | 已定，一条待验 |
| [adr-04](adr-04-h2-in-memory-and-reset-on-startup.md) | H2 用内存模式、存储随进程存活，启动时显式重置环形文件 | 已定 |

## 悬着的事

- **SDK 层截断是否可计数**（adr-03 第 4 种）—— R0 没验。Phase 4 落地前必须有答案。
- **`start_time` 不建索引**（adr-02）—— 从 SkyWalking 侧继承的是**推断**，本项目表结构不同，需实测。
- **metrics 的分钟/小时 rollup** —— 本版只定义"时间序列形态 + 行数水位"，汇总表留到后面。