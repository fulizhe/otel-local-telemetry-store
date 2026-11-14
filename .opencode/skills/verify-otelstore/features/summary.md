# summary

`/api/summary` 是读口的体检页。它回答四件事：这个进程配了什么、跑了多久、挂的是哪个 agent、存储健不健康。

## Sub-features

- **config**：生效配置。`token` 只出现掩码，`actualPort` 不在这里。
- **运行事实**：`startedAt`、`uptimeMs`、`agentVersion` 平铺在顶层。
- **健康**：`storeDegradedReason` 健康时为 `null`，键始终在。
- **存储快照**：`store.spanRows / logRows / metricRows`、`storeErrors`、`evicted`、水位。
- **端口事实**：`actualPort`，由 handler 在绑定退让后补进来（`HttpReadout.java:240-243`）。

## How to get to it (user POV)

读口地址不写死。默认 17890，被占就退让到随机端口，真值在 `<dataDir>/otelstore.port`。

浏览器打开 `http://127.0.0.1:<port>/self.html` 看到的就是同一份数据。本功能验的是 API，不是页面。

## Driving it with assert-readout.ps1

```powershell
pwsh -NoProfile -File .opencode/skills/verify-otelstore/scripts/assert-readout.ps1 summary
```

开鉴权、并验端口退让时（22 条）：

```powershell
pwsh -NoProfile -File .opencode/skills/verify-otelstore/scripts/assert-readout.ps1 summary `
  -ExpectAuth -ExpectConfiguredPort 17890
```

断言清单，任何一条失败退出码为 1：

| id | 断言 | 失败通常意味着 |
| --- | --- | --- |
| `http_200` | 返回 200 | 读口没起、端口不对、开了 auth 但没带 token |
| `json_parse` | 体是合法 JSON | 路由串了，返回了 HTML |
| `keys` | 7 个顶层键齐全 | 版本不对，或 `/api/self` 时代的残留代码 |
| `store_present` | `store` 非空 | 存储没建起来 |
| `rows_nonneg` | 三个行数都是非负整数 | 存储快照读失败 |
| `evicted_shape` | `evicted` 的 spans/logs/metrics 都在 | 快照结构变了 |
| `store_errors` | `storeErrors` 为 0 | 存储写失败被吞了 |
| `degraded_null` | `storeDegradedReason` 为 `null` | 存储降级，`detail` 里有原因 |
| `token_masked` | `config.token` 是 `***` 或 `<generated-at-startup>` | 掩码破了，token 泄漏 |
| `token_not_echoed` | 带 token 请求时，响应体不含 token | 读口回显了凭据 |
| `agent_version` | `agentVersion` 含 `opentelemetry` | 没挂 agent，或在没挂 agent 的应用里跑 |
| `actual_port` | `actualPort` 等于请求地址的端口 | 打到了别的实例 |
| `uptime` | `startedAt + uptimeMs` 与当前时间相差 5 秒内 | 时钟不对，或字段被缓存过 |
| `config_port` | `config.port` 是数字；给了 `-ExpectConfiguredPort` 时必须等于它 | 配置快照丢了端口，或这次起的不是你以为的配置 |
| `port_fallback` | 只在给了 `-ExpectConfiguredPort` 时：`actualPort` 与之不等 | 端口没被真的占住，这条没验到（不是读口的问题） |
| `auth_mode` | 不带 token 探测：401 记 on，200 记 off；给了 `-ExpectAuth` 时 200 判失败 | `auth=true` 没生效，或鉴权被绕过 |
| `auth_challenge` | 401 带 `WWW-Authenticate: X-Otel-Store-Token`，体里 `error=unauthorized` | 客户端不知道往哪个头放凭据 |
| `auth_token_ok` | 带正确 token 返回 200 | token 文件过期（旧进程留下的），或头名写错 |
| `auth_wrong_token` | 带等长错值返回 401 | 比对逻辑坏了 |
| `token_file_shape` | `otelstore.token` 无空白、至少 16 字符、与本次请求一致 | token 没落盘，或读到了别的实例的文件 |
| `token_absent_from_log` | `/api/self-log` 的响应体不含 token | **凭据进了日志**，按安全事故处理 |
| `log_auth_on` | 就绪那行写着 `auth=on` | 服务端以为自己没开鉴权 |

## Gotchas

- **`storeDegradedReason` 的 `null` 与缺键不同。** 缺键说明这版没有该字段，`null` 说明健康。断言分开，别合并成一个。
- **`config.token` 永远不是真 token。** 开启鉴权时是 `<generated-at-startup>`，显式配置时是 `***`。看到 32 位十六进制就说明掩码破了。
- **`actualPort` 与 `config.port` 不等是正常的。** 前者是绑定结果，后者是配置值，不等就说明退让过。
- **本机代理会让 `Invoke-RestMethod` 超时。** 脚本走 `curl.exe --noproxy "*"`。
- **demo-app 的 dataDir 在它自己目录下。** `run-with-agent.ps1` 让两个进程都以 `demo-app` 为 cwd，所以端口文件是 `demo-app/otel-local-telemetry-store/otelstore.port`，不是仓库根下那个。脚本默认按这个顺序找。
- **401 不是连接失败。** 读 `otelstore.token` 并用 `X-Otel-Store-Token` 头，不要换成 `Authorization`。
- **鉴权开关靠探测，不靠 flag。** 不带 token 试一次，401 才算开了。所以同一份报告在 auth 关时只有 14 条 —— 少了 8 条不是漏跑。
- **`-ExpectConfiguredPort` 要填配置值，不是横幅上的端口。** 退让后横幅显示的是实际端口，填它会让 `port_fallback` 永远"没退让"。
- **`port_fallback` 红不一定是读口的错。** 端口没被占住时它也会红，detail 会直说"这条没验到"。先看 `hold-port.ps1` 那一窗还在不在。
- **token 比错值用等长串。** 长度不同会走"长度直接 false"那条短路，验不到比较循环。
