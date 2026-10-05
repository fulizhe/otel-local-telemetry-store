# demo-app —— 造信号的靶子

## 怎么起

一条命令（会先构建，再挂 agent 与扩展 jar 一起起）：

```powershell
pwsh -NoProfile -File scripts/run-with-agent.ps1
```

输出里会给出 PID、页面地址和日志路径。端口被占用时换端口：`-Port 18099`。
已有构建产物、只想快点起：`-SkipBuild`。

只要应用不要 agent（此时 OTel 是 no-op，页面照样能用）：

```powershell
cd demo-app
mvn -q -DskipTests package
java -jar target/otel-local-telemetry-store-demo-0.1.0-SNAPSHOT.jar
```

## 预期看到什么

浏览器打开 **http://localhost:18081/**：

- 三张卡片分别造 traces / logs / metrics，各有一个按钮
- 一张卡片发真实 HTTP 请求（server span 由 agent 的 Web 仪表化产生，不走我们的代码）
- 下方「本应用已产生的信号」每 2 秒自动刷新，六个计数

手动验证一条（**用 `curl.exe --noproxy "*"`**，本机设了代理，`Invoke-RestMethod` 会超时）：

```powershell
curl.exe --noproxy "*" -X POST "http://localhost:18081/demo/spans?count=3&childPerSpan=2&errors=1&slow=1&slowMs=30"
# {"spans":3,"childSpans":6,"errorSpans":1,"slowSpans":1,"totalSpans":9}
```

计数自洽：`spans` 加上 `/demo/logs` 与 `/demo/metrics` 触发的包装 span，等于 `totalSpans` 之和。

**扩展的日志不在 `demo.log` 里** —— agent 把 `java.util.logging` 改到了 stderr，
所以在 `demo-app/target/demo.err.log`。读它**不要加 `-Encoding UTF8`**（agent 用平台编码）。

## 端点

| 端点 | 作用 | 可断言的产出 |
| --- | --- | --- |
| `POST /demo/spans?count=&childPerSpan=&errors=&slow=&slowMs=` | 造父子 span，按比例标 error / slow | 返回四个分项计数 |
| `POST /demo/logs?count=&level=INFO\|WARN\|ERROR` | 在 current span 内部打日志，因此**带 trace 上下文** | 行数 |
| `POST /demo/metrics?count=` | 一个计数器 + 一个直方图 | 指标点数 = count × 2 |
| `GET /demo/work?ms=` | 一次真实请求，sleep 指定毫秒 | 由 agent 产生 server span |
| `GET /demo/stats` | 本应用造了多少（六个计数 + uptime + 五跳各多少/失败多少） | 断言的期望值来源 |
| `POST /demo/reset` | 计数清零 | — |
| `POST /demo/deps/h2` | 打一次 H2 那一跳（`demo_h2_order` 表） | 返回**当前 traceId** + `rows` |
| `GET /demo/deps/status` | 五项依赖的 `ready` / `detail` / `embedded` | 少的那一跳要能提前看到 |

## 五类外部依赖的调用样例

demo-app 会**真的去调**五类外部依赖，让 agent 的仪表化在真实客户端库上产生 span
（H2 / Redis / Kafka / gRPC / MySQL）。

**这里没有一行自己开 span 的代码，而且这是核心断言**：调用发生在 handler 线程里，
Tomcat 仪表化已经把 server span 设成 current，所以 CLIENT span 会自动挂上去。
自己开 span 就是在测自己的代码。

当前进度（`GET /demo/deps/status` 是唯一准绳）：

| 跳 | 服务端 | `embedded` | 现状 |
| --- | --- | --- | --- |
| `h2` | 进程内内存库 | true | **已通** |
| `redis` | 进程内（`embedded-redis`） | true | 未接入 |
| `kafka` | 进程内 KRaft 单节点 | true | 未接入 |
| `grpc` | 进程内 Netty server | true | 未接入 |
| `mysql` | **外部实例**（ADR-7 里唯一的例外） | false | 未接入 |

```powershell
curl.exe --noproxy "*" "http://localhost:18081/demo/deps/status"
curl.exe --noproxy "*" -X POST "http://localhost:18081/demo/deps/h2"
```

**降级时端点仍然返回 HTTP 200**，而且 `reason` 分三种说法（`not-ready` / `call-failed` /
`not-registered`）——「这个依赖没起来」是可预期的正常状态（MySQL 尤其如此），
500 会让人分不清"依赖没起来"与"服务坏了"。进程照常启动，不重试。

**四个坑**：

1. **H2 与 MySQL 在图上长得一模一样**：都只产生一条 `scopeName=jdbc` 的 CLIENT span。
   H2 是内存库、没有网络连接，所以**没有 SERVER span**；MySQL 的线协议层 agent 也没仪表化。
   → 区分靠 **SQL 里的表名**（`demo_h2_order` / `demo_mysql_order`），span name 就是 SQL。
2. **别用 starter**。Spring Boot 管理的 Lettuce 6.x / spring-kafka 落在仪表化支持范围外，
   **静默不生效** —— 所以 H2 也只引驱动不引 `spring-boot-starter-jdbc`。
3. **`scopeName` 全一样** → 调用的不是客户端类（例如错用了 `MockProducer`）。
4. **`/demo/stats` 里 `depCalls` 与 `depFailures` 分开** ——
   「少了一条 CLIENT span」可能是没埋点，也可能是库没起来，混成一个数就分不出来了。

## 端到端对账的口径

`/demo/stats` 的存在理由：**端到端验证需要一个不依赖读口的期望值来源。**
"我造了 3 个 span"这件事应用自己就知道 —— 拿它和库里实际的条数对账，
这就是 SW 侧"影子对账"的轻量版，也是 ADR-3 里那几种"数据少了"**唯一**的发现手段
（采样与 SDK 截断在原理上不可计数）。

对账的两边分别是：

| 一边 | 在哪看 |
| --- | --- |
| 本应用造了多少 | `GET /demo/stats`（六个计数） |
| 库里存了多少 | 停进程时日志的 `退出 … \| store spans=N logs=N metricPoints=N`，或 JMX 的 `spanRows()` / `logRows()` / `metricRows()` |

两边差额应当只来自采样与丢弃；出现别的差额就是 bug。

## 两个坑

**① 本机设了代理时，PowerShell 访问 localhost 会超时。**
`Invoke-RestMethod` / `Invoke-WebRequest` **不认 `NO_PROXY` 环境变量**，
即使 `NO_PROXY=localhost,127.0.0.1` 已经设好。三种解法，任选：

```powershell
curl.exe --noproxy "*" http://localhost:18081/demo/stats     # 最省事
Invoke-RestMethod -NoProxy http://localhost:18081/demo/stats   # PS7 支持 -NoProxy
```

**② `-Dotel.javaagent.extensions=` 指向不存在的路径时，agent 静默忽略、零告警。**
你会以为扩展挂上了，其实没有。`scripts/run-with-agent.ps1` 会在起之前替 agent 把这个坑堵掉 ——
扩展 jar 不存在就直接失败。

## 现在还看不到什么

**存储已经能用了，但只有 JMX 那条读口**（Phase 4b 落地，Phase 5 未做）。所以现在：

- 造出来的信号**真的进了本地库** —— H2 内存表头 + 两个堆外环形文件，落在 `dataDir` 里
- **页面上的「存储读口」卡片仍只是一段说明**：HTTP 读口还没做，浏览器读不到
- 要在浏览器之外看数据，用 `jconsole` 连本进程 → MBeans →
  `io.github.fulizhe.otelstore` → `LocalStoreSummary`
  （`summary` / `recentSpans(10)` / `spansOfTrace(<trace_id>)` / `spanPayloadHex(<id>)`）

验收信号与失败判据见
[`../docs/notes/2026-10-04-verification-and-pitfalls.md`](../docs/notes/2026-10-04-verification-and-pitfalls.md)。

Phase 5 之后，读口会是 agent 扩展里的一个 HTTP 服务（默认端口 `17890`，撞端口自动退随机，
实际端口写在 `otel-local-telemetry-store/otelstore.port` 与启动日志里）。

**跨源问题已经定了**（[ADR-6](../docs/adr/adr-06-readout-http-surface.md) 第五节）：
**浏览器直接打开读口那侧**（`http://<host>:17890/`），因此**读口不开 CORS、本页不加代理**。
代价是那个页面不在本页导航里，要另开一个标签页；换来的是少一个开放面 ——
开 CORS 等于允许任意站点来读你的 trace。

## 结构

```
demo-app/
├── pom.xml                     Spring Boot 2.7.18（2.x 末代，支持 Java 8）
└── src/
    ├── main/
    │   ├── java/io/github/fulizhe/otelstore/demo/
    │   │   ├── DemoApplication.java          入口 + 各个 @Bean
    │   │   ├── stats/GeneratedSignals.java   本应用造了多少（脱离 Spring 可单测）
    │   │   ├── deps/                         五类外部依赖的探测与调用
    │   │   │   ├── DepStatus.java            ready + detail 不可分开
    │   │   │   ├── DependencyProbe.java      探测接口（key/title/embedded/probe）
    │   │   │   ├── DepsRegistry.java         启动时探测一次，异常压成降级
    │   │   │   ├── H2Dependency.java         内存库，裸 JDBC，不引 starter
    │   │   │   └── DepsDemoService.java      调用 + 降级响应（**不开自己的 span**）
    │   │   └── web/
    │   │       ├── DemoSignalController.java 造信号的全部端点
    │   │       └── DepsDemoController.java   /demo/deps/*
    │   └── resources/
    │       ├── application.yml               端口 18081、日志格式
    │       └── static/index.html             控制台页面（无构建步骤，纯静态）
    └── test/java/...                          只有纯内存的可单测部分
```

`demo-app` **不在主工程的 maven reactor 里**（主工程保持单模块），
用自己的 `pom.xml` 独立构建，父 pom 是 `spring-boot-starter-parent`。

**它刻意不托管读口。** SW 侧 demo-app 自己实现 `/inner/sw/*` 读口，靠 toolkit 桩跨 ClassLoader 调用插件。
我们这边读口在 agent 扩展内部，与本应用隔着三个 ClassLoader
（`ExtensionClassLoader` / `AgentClassLoader` / `AppClassLoader`），
应用侧根本调不到我们的类 —— 所以读口只能是 HTTP/JMX，见
[`../docs/notes/2026-10-04-r0-extension-points.md`](../docs/notes/2026-10-04-r0-extension-points.md) 第五节。