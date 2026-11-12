# verify-otelstore 真机验收：summary（2026-11-12）

对象是 `demo-app` 加 agent 2.32.0 加扩展 jar，由 `scripts/run-with-agent.ps1` 启动，PID 33040。

- 读口 `http://127.0.0.1:17890/`（配置端口，本次没有退让）
- 数据目录 `demo-app/otel-local-telemetry-store`
- 页面 `http://localhost:18081/`

## 结果

`assert-readout.ps1 summary` 通过 **12/12**，退出码 0。机读报告见 [`summary.json`](summary.json)。

跑的时候的关键字段：

| 断言 | 值 |
| --- | --- |
| `rows_nonneg` | spans=67 logs=38 metrics=530 |
| `store_errors` | 0 |
| `token_masked` | `<generated-at-startup>` |
| `agent_version` | `opentelemetry-javaagent-2.32.0.jar` |
| `actual_port` | expected=17890 actual=17890 |
| `uptime` | 与当前时间相差 85 ms |

## 数据确实在走

先记录基线，再打 5 条日志，然后轮询 `/api/summary` 到 `logRows` 增加为止。

| 时刻 | spanRows | logRows | metricRows |
| --- | --- | --- | --- |
| 基线 | 45 | 30 | 352 |
| POST `/demo/logs?count=5&level=WARN` 之后 | 52 | 38 | 352 |
| 差值 | +7 | +8 | 0 |

5 条日志带来 +8 行、+7 个 span，多出来的是 demo 端点自身的包装 span。指标不动，因为这一步没打指标。

行数在两次断言之间继续增长（29 → 45 → 67），说明读的是活库，不是缓存。

## 没验的

- **鉴权路径**。本次 `auth=false`，13 条检查里的 `token_not_echoed` 没跑。要验需配 `otel.localstore.auth=true` 重起。
- **端口退让**。本次 17890 空闲，`actualPort` 与配置值相等。占掉 17890 再起一次可以验退让分支。
- **降级分支**。`storeDegradedReason` 为 null 才是健康态，非 null 的路径没构造。
