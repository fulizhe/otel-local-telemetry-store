# self-log

`/api/self-log` 只读扩展自己写的那一个文件 `<dataDir>/otelstore.log`。不碰应用日志，也不猜 stderr 去了哪（ADR-6 第九节）。

## Sub-features

- **尾部行数**：`?lines=` 默认 100，封顶 500（`clampSelfLogLines`）。
- **文件不存在不是错误**：返回 200 加空 `lines` 加一句 `note`。
- **`file` 字段**：固定 `otelstore.log`，告诉客户端读的是哪个文件。
- **滚动静默**：文件超过 1 MB 时轮转到 `otelstore.log.1`，端点不暴露备份。

## How to get to it (user POV)

页面 `self-log.html`，刷新频率与其它页不同，所以单独一页。启动日志和每分钟的 `周期` 汇总都写在这里。

## Driving it with assert-readout.ps1

```powershell
pwsh -NoProfile -File .opencode/skills/verify-otelstore/scripts/assert-readout.ps1 self-log
```

| id | 断言 | 失败通常意味着 |
| --- | --- | --- |
| `shape` | `lines`、`file`、`note` 三者齐全，`file=otelstore.log` | 端点结构变了 |
| `line_count` | 不超过请求的 5 行 | `lines` 参数没生效 |
| `line_format` | 每行匹配 `yyyy-MM-dd HH:mm:ss.SSS LEVEL ` | 混进了别处的日志，或格式改了 |
| `origin` | 至少一行含 `[otel-local-telemetry-store]` | 读的不是本扩展的日志 |
| `note_consistency` | 空 `lines` 与 `note` 非空互斥 | "没有内容"和"内容为空"混了 |
| `clamp_http` | `lines=100000` 返回 200 | 超大参数把端点打崩 |
| `clamp_limit` | 封顶请求不超过 500 行 | 封顶口径失效 |
| `bad_param_fallback` | `lines=abc` 返回 200 且不超过 100 行 | 非数字参数没回落 |

## Gotchas

- **封顶在本地测不出来。** 只有日志超过 500 行才能区分"封顶"和"本来就只有这么多"。当前断言只保证不超过 500，真到 500 以上要重新看一眼。
- **空文件是正常态。** 刚启动、被运维删掉、被截断，都返回空加 `note`，不是 500。
- **轮转不透明。** 超过 1 MB 后旧内容在 `otelstore.log.1`，端点只给主文件。要看更早的日志直接读文件。
- **格式断言依赖平台编码。** 日志用平台默认编码写，本机是 GBK。断言只匹配 ASCII 前缀，不依赖中文。
