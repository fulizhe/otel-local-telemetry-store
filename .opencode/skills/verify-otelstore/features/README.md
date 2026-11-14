# 功能地图

一个功能一个文件。每个文件写清四件事：有哪些子功能、用户视角怎么到达、怎么用本技能的脚本驱动、坑在哪。

| feature | 端点 | 子命令 | 状态 |
| --- | --- | --- | --- |
| [summary](summary.md) | `/api/summary` | `summary` | 有已知缺口 |
| [metrics](metrics.md) | `/api/metrics` | `histogram` | 真机验过 |
| [self-log](self-log.md) | `/api/self-log` | `self-log` | 真机验过 |

状态只有三种：`断言已写`、`真机验过`、`有已知缺口`。改过端点就把状态退回第一种。

2026-11-12 在 demo-app 上的验收结果：

- `summary` 12/12
- `histogram` 7/7，191 条 explicit 直方图全部形状正确
- `self-log` 10/10

报告在 `docs/notes/verify-runs/2026-11-12/`。

## summary 的已知缺口（2026-11-14）

`summary` 从 12 条加到 14 条（开鉴权 21 条，再指定 `-ExpectConfiguredPort` 22 条），
新增的六组断言只在**假服务端**上验过，真机还没跑：

- 鉴权：`auth_mode` / `auth_challenge` / `auth_token_ok` / `auth_wrong_token` / `token_file_shape` / `token_absent_from_log` / `log_auth_on`
- 退让：`config_port` / `port_fallback`

假服务端五个用例（off、auth+退让、绕过鉴权、没退让、日志泄 token）都符合预期，但那只证明脚本逻辑对，
不证明读口这么回。真机跑过之后把它改成「真机验过」。
