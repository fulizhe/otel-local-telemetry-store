# 功能地图

一个功能一个文件。每个文件写清四件事：有哪些子功能、用户视角怎么到达、怎么用本技能的脚本驱动、坑在哪。

| feature | 端点 | 子命令 | 状态 |
| --- | --- | --- | --- |
| [summary](summary.md) | `/api/summary` | `summary` | 真机验过 |
| [metrics](metrics.md) | `/api/metrics` | `histogram` | 真机验过 |
| [self-log](self-log.md) | `/api/self-log` | `self-log` | 真机验过 |

状态只有三种：`断言已写`、`真机验过`、`有已知缺口`。改过端点就把状态退回第一种。

2026-11-12 在 demo-app 上的验收结果：

- `summary` 12/12
- `histogram` 7/7，191 条 explicit 直方图全部形状正确
- `self-log` 10/10

报告在 `docs/notes/verify-runs/2026-11-12/`。
