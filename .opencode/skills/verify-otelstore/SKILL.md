---
name: verify-otelstore
description: 断言 otel-local-telemetry-store 的 HTTP 读口，读真实字段（行数、token 掩码、降级原因、实际端口），不信页面状态。改完读口、存储或采集管线后用它自证，或在真机验收前跑一遍。
---

# 验证 otel-local-telemetry-store

这个仓库可编程驱动的面只有 HTTP 读口。`assert-readout.ps1` 把断言写成可重跑的脚本，一个子命令一组检查，输出一份报告。

## Launch：人执行，agent 不代跑

```powershell
pwsh -NoProfile -File scripts/run-with-agent.ps1
```

就绪的判据是 `<dataDir>/otelstore.port` 出现，且 `curl.exe --noproxy "*" http://127.0.0.1:<port>/api/summary` 返回 200。

不要为了验证再起第二个实例。同一个 `dataDir` 只能有一个实例，读会读到别的进程的数据（README ①）。端口 18081 归 Spring 管，撞了直接启动失败。

## Doctor

`assert-readout.ps1 summary` 同时是 doctor。连不上退出码 2，连上但字段不对退出码 1。任何子命令失败前先跑它。

它检查的是这个实例值不值得打：`actualPort` 与请求地址一致、`agentVersion` 非空、`storeErrors` 为 0、没有降级原因。

## Drive

在仓库根执行：

```powershell
pwsh -NoProfile -File .opencode/skills/verify-otelstore/scripts/assert-readout.ps1 summary
pwsh -NoProfile -File .opencode/skills/verify-otelstore/scripts/assert-readout.ps1 summary -Json
pwsh -NoProfile -File .opencode/skills/verify-otelstore/scripts/assert-readout.ps1 summary -BaseUrl http://127.0.0.1:17891
```

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `-DataDir` | 自动找 | 先找 `demo-app\otel-local-telemetry-store`，再找 `.\otel-local-telemetry-store`。端口与 token 文件都从这里读 |
| `-BaseUrl` | 空，从 port 文件推 | 显式传就跳过发现 |
| `-Token` | 空，读 `otelstore.token` | 只走 `X-Otel-Store-Token` 头 |
| `-Json` | 关 | 给机器读，人读默认是行文本 |

退出码 0 表示全过，1 表示有断言失败，2 表示连不上或用法错。

## Evidence

`-Json` 的输出落到 `docs/notes/verify-runs/<yyyy-MM-dd>/summary.json`，人读报告留在对话里。

报告里必须能看到 `actualPort`。没有它，分不清打的是配置端口还是退让后的随机端口，也就分不清失败是读口的错还是打错了实例。

## Cleanup

没有可清理的东西。脚本只发 GET，不写 dataDir，不起进程。

## Boundaries

- **只读**。造信号归 demo-app 的 POST 端点，这个技能不造数据。
- **不启停进程**。`run-with-agent.ps1` 归人。
- **不做页面断言**。页面的共享逻辑靠 `PageScriptSmokeTest`，这里只管 API。
- **405 是设计**。读口只注册 GET/HEAD，别把"POST 被拒"当成缺陷报。

## 功能地图

[`features/README.md`](features/README.md)。目前只有 `summary`。加新端点时先加 feature 文件，再加子命令，别反过来。
