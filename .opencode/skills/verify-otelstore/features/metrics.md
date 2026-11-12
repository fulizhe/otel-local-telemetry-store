# metrics

`/api/metrics` 给的是**时间序列点**，不是当前值。`detail` 那一列在服务端被解析成结构，客户端不用懂紧凑文本的语法。

## Sub-features

- **列表与过滤**：`?name=` 按指标名过滤，`?limit=` 默认 200，指标点单独封顶 2000（`ReadoutQueries.java:60`）。
- **五种 detail 形态**：`long`、`double`、`explicit`、`exponential`、`quantiles`。
- **显式直方图**：`leBoundaries` 是字符串列表且以 `+Inf` 结尾，`counts` 是非累计计数，`cumulative` 是累计值。
- **认不出时**：`flavor` 为 `unknown`，同时带 `raw` 原文。

## How to get to it (user POV)

页面 `metrics.html` 是这个端点的表格视图。造数据用演示应用的端点，一次产生一个计数器加一个直方图：

```powershell
curl.exe --noproxy "*" -X POST "http://localhost:18081/demo/metrics?count=3"
```

也会自然产生：agent 的 gRPC 仪表化一直在写 `rpc.client.duration` 这类直方图。

## Driving it with assert-readout.ps1

```powershell
pwsh -NoProfile -File .opencode/skills/verify-otelstore/scripts/assert-readout.ps1 histogram
```

| id | 断言 | 失败通常意味着 |
| --- | --- | --- |
| `has_histograms` | 至少一条 `dataType=HISTOGRAM` | 没有指标数据，先打 `/demo/metrics` |
| `no_unknown` | 没有 `flavor=unknown` | 解析器和真实数据形状对不上，历史 bug 的复现 |
| `explicit_shape` | `leBoundaries`、`counts`、`cumulative` 三者等长，最后一个是 `+Inf`，数值边界非降 | 桶与边界错位 |
| `cumulative_monotonic` | 计数非负，累计单调不减 | 累加逻辑坏了 |
| `total_matches` | 末桶累计等于 `metricCount` | 桶丢了数据，或 `+Inf` 桶没补 |

## Gotchas

- **`bounds` 比 `counts` 少一是合法形状**，不是 bug。OTel 的 15 个边界对应 16 个桶，转成 `leBoundaries` 时补一个 `+Inf`，所以三者长度相等。
- **`+Inf` 是字符串**。它不是合法 JSON 数值，别拿 `[double]` 直接转。
- **`unknown` 才是故障信号**。"15 边界 / 16 计数"被判成认不出来，就是本项目付过学费的那个 bug。测试数据要照真实形状写，别用"2 边界 / 2 计数"。
- **`total_matches` 是跨字段校验**。只验形状能过，桶里数据丢了它才会红。
- **2000 条封顶**。数据多的时候 `limit=2000` 只覆盖最近一窗，别把它当成全量。
