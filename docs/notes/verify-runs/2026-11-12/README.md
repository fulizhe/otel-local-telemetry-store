# verify-otelstore 真机验收（2026-11-12）

对象是 `demo-app` 加 agent 2.32.0 加扩展 jar，由 `scripts/run-with-agent.ps1` 启动，PID 33040。

- 读口 `http://127.0.0.1:17890/`（配置端口，本次没有退让）
- 数据目录 `demo-app/otel-local-telemetry-store`
- 页面 `http://localhost:18081/`

## 结果

| 子命令 | 结果 | 机读报告 |
| --- | --- | --- |
| `summary` | 12/12 | [`summary.json`](summary.json) |
| `histogram` | 7/7 | [`histogram.json`](histogram.json) |
| `self-log` | 10/10 | [`self-log.json`](self-log.json) |

三个都退出码 0。

### summary

关键字段：

| 断言 | 值 |
| --- | --- |
| `rows_nonneg` | spans=734 logs=38 metrics=3073 |
| `store_errors` | 0 |
| `token_masked` | `<generated-at-startup>` |
| `agent_version` | `opentelemetry-javaagent-2.32.0.jar` |
| `actual_port` | expected=17890 actual=17890 |
| `uptime` | 与当前时间相差 69 ms |

### histogram

`/api/metrics?limit=2000` 里 **191 条 explicit 直方图**，全部满足：

- `leBoundaries`、`counts`、`cumulative` 三者都是 16 个元素，末位 `+Inf`
- 数值边界非降，累计单调不减
- 末桶累计等于 `metricCount`
- `flavor=unknown` 的点为 0

这直接复验了"直方图桶比边界少一个"那个历史 bug 的修复口径，样本量是 191 而不是单元测试里的 2。

**负向用例**：拿假服务端返回 `le=2 / counts=3` 加一条 `flavor=unknown`，结果 5/7、退出码 1，`explicit_shape` 与 `no_unknown` 各红一条。断言不是摆设。

### self-log

- 文件 `otelstore.log` 当时 414 行，`?lines=5` 返回 5 行
- 每行匹配 `yyyy-MM-dd HH:mm:ss.SSS LEVEL `，且都含 `[otel-local-telemetry-store]`
- `?lines=100000` 返回 200，414 行，没有超过 500 的封顶
- `?lines=abc` 返回 200，回落到缺省 100 行

## 数据确实在走

先记录基线，再打 5 条日志，然后轮询 `/api/summary` 到 `logRows` 增加为止。

| 时刻 | spanRows | logRows | metricRows |
| --- | --- | --- | --- |
| 基线 | 45 | 30 | 352 |
| POST `/demo/logs?count=5&level=WARN` 之后 | 52 | 38 | 352 |
| 差值 | +7 | +8 | 0 |

5 条日志带来 +8 行、+7 个 span，多出来的是 demo 端点自身的包装 span。指标不动，因为这一步没打指标。

三次断言之间行数持续增长（29 → 45 → 734），说明读的是活库，不是缓存。

## 没验的

- **鉴权路径**。本次 `auth=false`，`token_not_echoed` 没跑。要验需配 `otel.localstore.auth=true` 重起。
- **端口退让**。本次 17890 空闲，`actualPort` 与配置值相等。占掉 17890 再起一次可以验退让分支。
- **降级分支**。`storeDegradedReason` 为 null 才是健康态，非 null 的路径没构造。
- **self-log 的 500 行封顶**。日志只有 414 行时，"封顶生效"与"本来就这么长"分不出来。超过 500 行要重看一眼。
