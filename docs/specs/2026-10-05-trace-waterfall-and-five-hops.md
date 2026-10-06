## Problem Statement

读口已经能落库、能读回、能画指标趋势图，HTTP 页面已真机验收。但**"这个进程现在在跟谁打交道"这件事仍然看不出来**。

具体地说，是三件事看不见：

1. **看不到跨进程边界**。demo-app 只造自己的 span，加上一次真实 HTTP 调用。库里绝大多数 span 是
   `INTERNAL` 的自造 span，看不出这个应用到底连了数据库、缓存还是消息队列。
   于是"埋点挂上了吗"这件事无法验证 —— 只能靠读代码相信它挂上了。
2. **看不到一条 trace 的形状**。span 列表一行一条，`trace` 链接跳到带 `traceId` 的列表页，
   依然是**一列一行**。父子关系要靠人眼比对 `span_id` 与 `parent_span_id` 两个 16 位十六进制串。
   有五六个 span 时就已经要来回横跳了。
3. **看不出调用链用了哪些技术**。SkyWalking 那种"点开一个 trace 就看到一棵调用树"的体验没有 ——
   而这是"把可观测数据留在本机"这件事最有说服力的证明方式。

而 **demo-app 是靶子**：它存在的唯一理由是造出可断言的信号。现在它造不出跨依赖的信号，
所以主体那条"agent 自带仪表化真的挂上了"一直只能靠推断，不能靠对账。

## Solution

两件事，一个 spec 里分两段：

**段 A —— demo-app 加五类外部依赖的调用样例**（H2、Redis、Kafka、gRPC、MySQL），
端点返回**当前 traceId**，让人能直接从响应里拿到 traceId 去读口对账，不用从页面里抠。
这一段把主体从"自己造 span"升级为"调用真实客户端库、由 agent 产生 CLIENT span"。

**段 B —— 读口加 trace 瀑布图**：点 trace 链接开出瀑布弹框，内联 SVG，
按 `scopeName` 分组、按 `kind` 上色、父 span 缩进。**后端不动** ——
`/api/traces?traceId=` 已经返回画图需要的全部字段。

关键取舍见 Implementation Decisions。最重要的三条：

- **零自研埋点**。demo-app 里绝不自己开 span —— 测的必须是 agent 的仪表化，不是自己的代码。
- **后端零改动**。画图要用的 `spanId` / `parentSpanId` / `startTime` / `endTime` / `kind` /
  `scopeName` 已经在 span 列表响应里。
- **依赖起不来就降级，不崩**。任一依赖不可用时进程照常起，`/demo/deps/status` 报出原因。

## User Stories

**排障者 / 验证者（主要读者）**

1. 作为验证者，我想让 demo-app **真的去连一个 H2 数据库**，
   以便确认 JDBC 仪表化在真客户端库上挂得上，而不只是我信它挂上了。
2. 作为验证者，我想让 demo-app **真的去连一个 MySQL**，
   以便把"两种数据库仪表化都挂上了"这件事变成可对账的事实。
3. 作为验证者，我想在链路图上**分得出哪一跳打的是 H2、哪一跳打的是 MySQL**，
   以便确认两个库都真的被调用过，而不是其中一路静默失败。
4. 作为验证者，我想让 demo-app **真的去连一个 Redis**，
   以便确认 Jedis 仪表化挂上了（它与数据库是完全不同的仪表化）。
5. 作为验证者，我想让 demo-app **真的收发一条 Kafka 消息**，
   以便确认 producer 与 consumer 两种 `kind` 都能产生 span —— 这是唯一能同时
   看到 `PRODUCER` 与 `CONSUMER` 两种上色的地方。
6. 作为验证者，我想让 demo-app **真的调一次 gRPC**，
   以便确认 Netty server 与 client 两侧都产生了 span。
7. 作为验证者，我想在**一次请求里同时打出全部五跳**，
   以便一张链路图就能看到完整的一跳链，而不是分五次看。
8. 作为验证者，我想让每次调用返回**当前 traceId**，
   以便我能直接从响应里拿到 traceId 去读口对账，不用从页面里抠字符串。
9. 作为验证者，我想看到**每个依赖各自的 ready 与失败原因**，
   以便区分"埋点没挂上"与"库没起来"—— 这两件事在链路图上长得一模一样（都是少一跳）。
10. 作为验证者，我想让**某个依赖起不来时进程照常启动**，
    以便排查时不会因为 Redis 没起来就整个 demo 跑不了。
11. 作为验证者，我想让**调用端点在依赖不可用时返回明确原因**，
    以便我不会把一个降级响应误读成埋点问题。

**排障者 —— 链路图本身**

12. 作为排障者，我想**点一条 trace 就看到它全部 span 的时间轴瀑布**，
    以便一眼看清一次请求的调用链形状。
13. 作为排障者，我想在瀑布里看到**父子缩进**，
    以便看清谁调用了谁，而不用手比十六进制串。
14. 作为排障者，我想**按仪表化来源分组**，
    以便认出这条链里出现了哪几种技术（tomcat / jdbc / jedis / kafka-clients / grpc）。
15. 作为排障者，我想**按 span 的 kind 上色**（SERVER / CLIENT / PRODUCER / CONSUMER / INTERNAL），
    以便一眼看出进程边界在哪 —— 这正是 SkyWalking 拓扑图的视觉语言。
16. 作为排障者，我想**点瀑布里任何一条就打开它的详情**，
    以便从"这条很短"直接跳到"它带了哪些 attributes"。
17. 作为排障者，我想**从详情弹框里也能进瀑布**，
    以便不用退回列表页。
18. 作为排障者，我想看到**这条 trace 一共多少跳、各自耗时多少**，
    以便定位慢在哪一跳。

**链路图必须诚实的地方**

19. 作为排障者，我想在**父 span 被行数水位淘汰、不在结果集里**时看到明确说明，
    以便我知道那条 span 是当根画的，而不是它真的没有父。
20. 作为排障者，我想在**只有一条 span 画不出树**时被明说，
    以便我不把一条横线读成"调用链就这么长"。
21. 作为排障者，我想知道**图上没画的东西是什么**（例如超过上限的序列条数），
    以便不把局部当成全部 —— 这是 ADR-6 第十节那条规矩在图上的延续。

**运维 / 长期维护者**

22. 作为维护者，我想让**依赖状态不进自监控页**，
    以便读口继续不认识 demo-app 的东西（封闭清单原则）。
23. 作为维护者，我想让 demo-app 的依赖配置**非法取值回落默认值而不抛异常**，
    以便一个笔误不会让靶子起不来 —— 与主体同一条纪律。
24. 作为维护者，我想让 demo-app **保持不在主工程 reactor 里**，
    以便主工程仍是单模块。
25. 作为维护者，我想在**动前端后有测试钉住"真的画出来了"**，
    以便不再出现"HTML 正常、Java 全绿、只在浏览器控制台抛一行"这一类 bug（已连出三次）。
26. 作为维护者，我想让**测试数据照真实 SDK 的形状写**，
    以便不再出现"每个直方图都认不出来"那种自己造的假失败。

**脚本 / 自动化**

27. 作为脚本使用者，我想用 `curl` 依次打五跳并拿到 traceId，
    以便把"五跳都在库里"这件事做成可重复的命令。
28. 作为脚本使用者，我想让**任一端点都不会返回 500**（依赖不可用时也要 200 + 明确说明），
    以便脚本不用区分"依赖没起来"和"服务坏了"。

**用户已拍板、不要再问的**

29. 作为用户，我想**先做同一个 trace 的时间轴瀑布**，以便马上能用；
    服务拓扑图以后再说。
30. 作为用户，我想**按 `scopeName` 分组而不是伪造 `service.name`**，
    以便图上看到的来源就是仪表化真的来源。

## Implementation Decisions

**两段，段 A 必须在段 B 之前**（没有五跳，瀑布图上没有东西可画）。
每段都能独立验收。

### 段 A —— demo-app 的五类依赖

**A.1 零自研埋点**

demo-app 里**绝不自己开 span**。所有 span 都必须来自 agent 自带仪表化。
推论：调用只要发生在 Spring MVC handler 线程里（Tomcat 仪表化已把 server span 设为 current），
CLIENT span 就会自动挂上去。若 demo-app 自己开 span，测的就是自己的代码。

**A.2 依赖选型与端口**

- **一律用裸客户端，不用 starter**。Spring Boot 管理的 Lettuce 6.x / spring-kafka 落在
  仪表化支持范围外，会**静默不生效** —— 这是参考项目明确记过的坑，要照抄注释。
- H2 走 Spring Boot 自带的 JDBC starter（`h2` runtime 依赖）。
- MySQL 用 `mysql-connector-java` 裸驱动，**不引 `spring-boot-starter-jdbc` 的 MySQL 方言**。
- Redis 用 Jedis 裸客户端，`new Jedis(host, port, timeout)`，超时显式收紧。
- Kafka 用 `kafka-clients` 裸客户端；consumer 用 `assign()` 而不是 `subscribe()`。
- gRPC 起**真端口**的 Netty server，**不用 protoc**：手搓 `MethodDescriptor<byte[],byte[]>`
  加 `byte[]` identity marshaller。这样 CLIENT 与 SERVER 两侧都产生 span。
- 端口：Redis 6379、Kafka broker 9092 / controller 19099、gRPC 18900，
  **避开本机已被 docker 占用的 16379 / 19092 / 13306**。

**A.3 Kafka 怎么起**

`org.apache.kafka:kafka_2.13` 的**内嵌 broker + 内嵌 ZooKeeper**（ZK 模式，2.8.2，字节码实测 52）。
它是本项目**唯一**需要从 Maven Central 新下载的包（Docker Hub 不通的直接后果）。

依赖冲突要处理：broker 包会把 `log4j-slf4j-impl` 与 logback 一起拖进来，必须排除。

> **实现期更正（2026-10-06）**：本文原写"KRaft 单节点（2.7.1）"，实测不成立 ——
> 2.7.1 里根本没有 in-process KRaft broker（`KafkaRaftServer` / `StorageTool` 是 2.8 才有的类），
> 而 2.8.2 的 KRaft broker 在 **Windows 上起不来**（`quorum-state.tmp` 的 rename 撞上
> Windows 的文件占用语义）。改用 ZK 模式，仍然零外部进程，ADR-7 的"MySQL 是唯一例外"未被推翻。
> 实测数据见 [`../notes/2026-10-05-five-hops-env-facts.md`](../notes/2026-10-05-five-hops-env-facts.md)。

**A.4 MySQL 是唯一外部依赖**

其余四个内嵌（见 ADR-7）。MySQL 连用户环境里已有的实例，
默认 `127.0.0.1:13306`、库名 `demo`、空密码。

**A.5 降级口径（写死，否则会退化成"库没起来 → 端点 500 → 忘了它是外部依赖"）**

- 启动时**探测一次**每个依赖，连不连得上只进 `/demo/deps/status`。
- **探不通不抛异常、不重试、不阻塞启动** —— 进程照常起来。
- 调用端点在依赖不可用时返回**明确的降级说明**（哪一项、为什么），**状态码仍是 200**。
- 链路图上不会出现那一跳，且这件事**在 `/demo/deps/status` 里能提前看到** ——
  不会让人对着空图猜"是埋点没挂上还是库没起来"。

**A.6 表名区分两种数据库**

`demo_h2_order` / `demo_mysql_order`。JDBC 仪表化对 H2 与 MySQL **都只产生一条**
`scopeName=jdbc` 的 CLIENT span（H2 是内存库没有网络连接，所以没有 SERVER span；
MySQL 的线协议层 agent 也没仪表化）→ 图上两条长得一模一样。
靠**表名让 span name（SQL）自己区分**，零后端改动。

**A.7 对账口径沿用现状**

新增的计数器并入现有的"不依赖读口的期望值"那套（`/demo/stats`），
保持"我造了多少"与"库里存了多少"两边能对账这条纪律。

**A.8 不改主体的三层**

段 A 只碰 `demo-app/`。`core` 不许 import OTel、`agentext` 不许直接操作存储、
`readout` 只读 —— 三条硬规则都不受影响。

### 段 B —— trace 瀑布图

**B.1 先补 ADR-6 第十五节**（现有到第十四节"指标页给趋势图"）。
决策与实测数据的分工照旧：为什么这么定 → ADR；版本号与实测数字 → notes。

**B.2 后端零改动**

`/api/traces?traceId=` 按 `start_time ASC` 返回该 trace 的全部 span，且响应里已含
`spanId` / `parentSpanId` / `startTime` / `endTime` / `kind` / `scopeName` / `name`。
`kind` 是 **OTLP proto** 的枚举数值：`0 UNSPECIFIED / 1 INTERNAL / 2 SERVER / 3 CLIENT / 4 PRODUCER / 5 CONSUMER`，
可直接拿来上色。（表头 `kind` 列的契约见 `SpanMapper.kindNumber`。）

> **实现期更正（2026-10-06）**：本文原写"OTel SpanKind 的 int（0 INTERNAL / 1 SERVER …）"——
> 那是 **SDK** 的 `ordinal()`，与落库的 **proto** 枚举**差一位**。按 0-based 上色时整条链
> 颜色全体错位一级（根 SERVER 画成 CLIENT 色、Kafka 的 publish 画成 CONSUMER 色），
> 真机验收才看出来。已按 proto 基准修 `spanKindName`，并在冒烟测试里钉住四种 kind 的颜色都出现。

**这是本段最重要的约束**：段 B 是**纯前端**改动。凡是需要动存储层才能画出来的东西，
一律先判断能不能用现有字段凑出来；不能就把这条记成后置增强，不要在段 B 里顺手改 ADR-2。

**B.3 入口**

- 列表行里的 **trace 链接** → 瀑布弹框（现状是跳到带 `traceId` 的列表页，改成弹框）。
- 详情弹框里点 trace → 同一个瀑布弹框。
- 瀑布里点任何一条 → **复用现有详情弹框**。
- Esc 关闭**两个**弹框（共用现有的 keydown 处理）。

**B.4 画的形态**

横轴 = 时间，缩进 = 父子深度，条按 `startTime..endTime` 定位。
内联 SVG，与指标趋势图同一套零依赖口径（`app.css` 的 `.chart` / `.legend` 可复用）。

- **按 `scopeName` 分组**（用户已拍板）：每条 span 带来源徽标，颜色一致贯穿。
- **按 `kind` 上色**：SERVER / CLIENT / PRODUCER / CONSUMER / INTERNAL 各一色。
- **共享逻辑必须留在 `app.js`**。token 粘贴、详情弹框、Esc 关闭都靠它注入，
  漏一份只表现为"这个页面的详情打不开"。

**B.5 三条"不编"的纪律**（沿用项目既有口径）

- `parentSpanId` 全 0 → 根。
- **父 span 不在结果集里**（被行数水位淘汰）→ 仍当根，标题写明「父 span 不在库里」，
  **不静默改挂**。这是最容易被写成"聪明逻辑"的地方。
- 只有一个 span / 没数据 → **明说画不出**，不画一条横线。
  （指标趋势图已经为"单个时间点"立过同一条规矩，这里照抄。）

**B.6 后置增强（明确不在本段）**

- 在时间轴上打日志标记（`/api/logs?traceId=` 已有）。
- 把 `db.system` 提成 `span` 表一列并在图上画成徽标 —— 那要动 ADR-2，值得单独一条决策。

### 明确否决

- **服务拓扑图**：用户说以后再说。那需要单独定义 endpoint×component 的聚合口径，值得一张 ADR。
- **自己开 span 来让图更好看**：测的是自己的代码。
- **画图时发现字段不够就顺手给表加列**：段 B 是纯前端改动这条约束本身要守住。
- **把依赖健康状态搬进 `self.html`**：读口不该认识 demo-app 的东西。
- **用 canvas 或引图表库**：与指标趋势图同源的理由 —— 读口跑在客户应用里，不能假设有外网。

## Testing Decisions

**什么算好测试**：只测外部可观察行为。不测"画图函数里有几个分支"，
测的是"喂进去这批 span，SVG 里出现了什么、缩进对不对、那三条自我约束有没有生效"。

**缝 1（主缝）—— 扩展已存在的 `PageScriptSmokeTest`**

这是能拿到的最高缝：它用最小 DOM 桩**真的把 `app.js` 执行一遍**。
这一类 bug 已经连出三次（缺 id、loaders 键与 tbody id 对不上、push 了立即调用的结果），
每次都是"HTML 正常、Java 全绿、只在浏览器控制台抛一行"，人眼看不出来。
**因此加任何前端代码都必须同步扩展它**，并像上一段那样**把 bug 注回去验证测试真的会红**。

扩展方式：桩的 `fetch` 对 `/api/traces?traceId=` 返回**一批有多级父子关系的 span**
（不是一行），然后断言：

- SVG 真的画出来了，且**条数等于 span 数**（画少了就是漏画）。
- **缩进层级正确**：子 span 比父 span 多一级缩进。
- **`scopeName` 分组正确**：五种来源各自归位。
- **`kind` 上色正确**：五种 kind 拿到五种颜色。
- **孤儿 parent**（父不在结果集里）→ 仍然画出来，且页面上有"父 span 不在库里"的说明。
- **单 span** → **没有** `<svg>`，且有"画不出"的说明（照抄指标趋势图那条既有断言的形状）。
- 深度超过上限时，写明还剩几层没画（ADR-6 第十节那条规矩在图上的延续）。

**缝 2 —— demo-app 侧只有纯计数可单测**

真实依赖（真 Redis、真 broker、真 MySQL）**不做进程内测试** ——
demo-app 不在主工程 reactor 里，为它往主工程塞缝是本末倒置。
可单测的只有 `GeneratedSignals` 那类纯计数器（新增的五个计数器并进去，沿用现有形状）。

**真机验收交给用户**（AGENTS.md：长验证一律交给用户，不自己跑常驻进程）。
要交付的是：命令、预期输出形态、以及**每种失败对应哪一层**。

**失败判据必须写清**，因为这五跳的失败长得都一样（span 变少）：

- `deps/status` 某项 `ready=false` → 该依赖没起来，看它的 `detail`。
- `api/traces` 只有 1~2 条 → 埋点没挂上，**多半是客户端版本落在仪表化支持范围外**
  （lettuce 6.x / kafka 3.x 静默不生效，参考项目 README 记过这个坑）。
- 有 5~8 条但 `scopeName` 全一样 → 调用的不是客户端类（例如错用了 `MockProducer`）。
- 瀑布里 span 错挂 → parent 判定有问题，这条**要在测试里钉住**，不能靠人眼看。

**进程内测试的已知局限必须写进测试注释**：进程内全绿**不等于**挂 agent 时能跑。
Phase 4b 已经踩过两次这种坑（一个靠上下文 ClassLoader、一个靠 ClassLoader 委托顺序）。
因此两段做完之后**必须真挂一次 agent**，验收信号是那条周期汇总行
（关停钩子靠不住：`Stop-Process` 不执行 shutdown hook）。

## Out of Scope

- **服务拓扑图**：以后单独一张 ADR（要定义 endpoint×component 的聚合口径）。
- **在时间轴上打日志标记**：后置增强，`/api/logs?traceId=` 已经有了。
- **给 `span` 表加 `db.system` 列**：会碰 ADR-2，值得单独一条决策。
  第一版靠 SQL 表名区分。
- **MySQL 内嵌**（MariaDB4j 之类）：ADR-7 已明确否决。
- **把依赖健康状态搬进读口**（`self.html` 或任何新端点）：读口封闭清单不认识 demo-app 的东西。
- **读口新增端点**：段 B 明确后端零改动。
- **自研埋点**：任何为了"让图好看"而自己开的 span。
- **demo-app 依赖 starter**（spring-boot-starter-data-redis / spring-kafka）：
  落在仪表化支持范围外，会静默不生效。
- **把 demo-app 加进主工程 maven reactor**：主工程保持单模块。
- **分页游标 / 布局参数 / 折叠交互**：v1 是"看一眼"。
- **`generated-signals` 之外的自动化断言脚本**：真机验收是手工的，命令交给用户。

## Further Notes

- **ADR-6 是段 B 的授权依据，ADR-7 是段 A 的授权依据**。
  若实现过程中发现哪一条不成立，**先改决策记录，再改代码** —— 不要偷偷绕过。
- **Java 8 是一道真的约束**，不是形式：demo-app 锁 Java 8，Kafka broker 钉 2.7.x。
  `kafka_2.13:2.7.1` 与 `mysql-connector-java:8.0.26` 的字节码实测都是 52，
  版本选错会让整个 demo 起不来。
- **本机四条环境坑**（代理 / Docker Hub 不通 / 端口被占 / `extensions=` 指向不存在路径时
  agent 零告警）已在 `docs/notes/2026-10-05-five-hops-env-facts.md` 里记着，
  实测数据以那份 notes 为准，不要在决策记录里复述。
- **不要在两段做完前把"链路图"写进 README**。本项目已经吃过一次亏：
  文档写着"骨架阶段"而代码已落地。
- **段 A 的产物是靶子，不是交付物**。为 demo-app 做的任何调试（端口、代理、启动脚本）
  都不算主体进展 —— 主体没动就直说主体下一步是什么。
- **agent 自带仪表化的清单以 agent jar 为准**，查法是
  `jar tf <agent.jar>` 看 `inst/META-INF/io/opentelemetry/instrumentation/` 下的文件名，
  别凭记忆断言某个库"应该"被支持。