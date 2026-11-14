# verify-otelstore：鉴权与端口退让（2026-11-14）

2026-11-12 那份记录留了四条没验的分支。这次做其中两条：**鉴权路径**与**端口退让**。
两条都需要换个配置重起，所以先把断言补齐、在假服务端上验过，再交给真机。

## 新增断言

`summary` 从 12 条加到 14 条，开鉴权 21 条，再指定 `-ExpectConfiguredPort` 22 条。

| id | 验什么 |
| --- | --- |
| `config_port` | `config.port` 是数字；给了 `-ExpectConfiguredPort` 时必须等于它 |
| `port_fallback` | `actualPort` 与配置值不等，且等于请求端口 |
| `auth_mode` | 不带 token 探测：401 记 on，200 记 off；`-ExpectAuth` 时 200 判失败 |
| `auth_challenge` | 401 带 `WWW-Authenticate: X-Otel-Store-Token`，`error=unauthorized` |
| `auth_token_ok` | 带正确 token 返回 200 |
| `auth_wrong_token` | 带等长错值返回 401 |
| `token_file_shape` | `otelstore.token` 无空白、至少 16 字符、与本次请求一致 |
| `token_absent_from_log` | `/api/self-log` 的响应体不含 token |
| `log_auth_on` | 就绪那行写着 `auth=on` |

`auth_mode` 是探测出来的，不是参数：auth 关时报告只有 14 条，少的那些不是漏跑。
`-ExpectAuth` 只用来把"我这次就是要验鉴权，结果它没开"变成失败。

## 假服务端上先验断言能红

`assert-readout.ps1` 只认 HTTP，所以可以用假服务端造形状。五个用例：

| 用例 | 假服务端行为 | 结果 |
| --- | --- | --- |
| `off` | 不鉴权、不退让 | 14/14，exit 0 |
| `on` | 鉴权 + `config.port=17890`、`actualPort=18992` | 22/22，exit 0 |
| `leak` | 说好鉴权，不带 token 却放行 | `auth_mode` 红，14/15，exit 1 |
| `nofallback` | 配置端口与实际端口相同 | `port_fallback` 红，14/15，exit 1 |
| `leaklog` | token 写进 self-log | `token_absent_from_log` 红，20/21，exit 1 |

这证明的是**断言会红**，不证明读口真的这么回 —— 所以下面还要真机。

## 真机怎么跑（两条一起，一次重起）

窗口 1 占住配置端口：

```powershell
pwsh -NoProfile -File .opencode/skills/verify-otelstore/scripts/hold-port.ps1
```

窗口 2 带着鉴权起（`-OtelProperty` 会原样转成 `-D<值>`）：

```powershell
$env:JAVA_HOME="D:\apps\java\jdk-17.0.8"; $env:Path="$env:JAVA_HOME\bin;$env:Path"
pwsh -NoProfile -File scripts/run-with-agent.ps1 -SkipBuild -OtelProperty otel.localstore.auth=true
```

横幅里应当出现两条：读口端口**不是** 17890，并且提示「启动时被占，读口退让了」。

然后：

```powershell
pwsh -NoProfile -File .opencode/skills/verify-otelstore/scripts/assert-readout.ps1 summary `
  -ExpectAuth -ExpectConfiguredPort 17890
```

预期 **PASS 22/22**、exit 0。看 `port_fallback` 的 detail 应是
`配置=17890 实际=<随机>（退让生效）`，`auth_mode` 是 `on（不带 token 返回 401）`。

失败怎么判：

- `auth_mode` 说 off —— `auth=true` 没生效。先看启动横幅有没有「覆盖配置 otel.localstore.auth=true」，
  再看 `demo.err.log` 里就绪那行是不是 `auth=off`。
- `auth_token_ok` 失败但 `auth_mode` 说 on —— token 文件读错了实例。确认 `-DataDir` 指向这次的数据目录。
- `port_fallback` 说「没退让」—— 窗口 1 的 hold-port 不在了，或者它绑的不是 0.0.0.0。
- `token_absent_from_log` 红 —— 凭据进了日志，按安全事故处理，先停进程再排查。

## 待办

- [ ] 真机跑出 22/22，把机读报告落成 `summary-auth-fallback.json`
- [ ] 顺手复跑 `histogram` 与 `self-log`（这两条不受本次改动影响，但换配置重起后值得复验）
- [ ] 更新 `features/README.md` 里 summary 的状态
