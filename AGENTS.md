# AGENTS.md

## 项目与当前阶段

**otel-local-telemetry-store** —— 把 OpenTelemetry 的 traces / logs / metrics 留在应用自己的进程内。
不发给任何远端，不需要第二套 agent，存储有界、降级可控。

当前处于 **Phase 4b 已落地、端到端未验收**：存储层（H2 内存表头 + 环形文件载荷）与 JMX 读口都已接上，
三条采集管线能把数据写进库并读回。**HTTP 读口还没做，端到端验收还没跑过。**
第一个可运行形态见 README 的"目标用法"。

## 演示应用与靶子

`demo-app/` 是独立的 Spring Boot 工程，**不在主工程的 maven reactor 里**（主工程保持单模块）。
它造信号并给出可断言的计数，是端到端验证的靶子。

```powershell
pwsh -NoProfile -File scripts/run-with-agent.ps1   # 构建 + 挂 agent 与扩展 jar 一起起
pwsh -NoProfile -File scripts/run-with-agent.ps1 -Port 18099 -SkipBuild
```

- 页面 `http://localhost:18081/`
- `demo-app` 自己的日志 `demo-app/target/demo.log`；
  **扩展的日志在 `demo-app/target/demo.err.log`**（agent 把 JUL 改到 stderr）——
  验收信号（`已注册三条采集管线` / `退出 … store spans=N`）在那份里，且读它**不要加 `-Encoding UTF8`**
- 单独构建：`mvn -f demo-app/pom.xml -DskipTests package`
- 端点与断言口径见 [`demo-app/README.md`](./demo-app/README.md)

**四条已知的本机环境坑**（写脚本/文档时别忘）：

1. 本机设了 `HTTP_PROXY`，而 **PowerShell 7 的 `Invoke-RestMethod` / `Invoke-WebRequest`
   不认 `NO_PROXY`** —— 访问 localhost 会超时。用 `curl.exe --noproxy "*"` 或加 `-NoProxy`。
2. **`-Dotel.javaagent.extensions=` 指向不存在的路径时，agent 静默忽略、零告警。**
   `scripts/run-with-agent.ps1` 会在起之前校验产物，堵掉这个坑。
3. **外网只放通 Maven Central。** `opentelemetry.io`、`h2database.com` 是连接失败（不是 404），
   `developer.android.com` 与 `raw.githubusercontent.com` 返回 404（路径未试对）。
   查依赖版本/版本对齐就在 Maven Central 上查；查文档别在官网耗时间。
4. **端口 18080 被本机另一个项目占着**（RuoYi-Flowable-Plus）。`demo-app` 因此用 **18081** ——
   别先去试 18080。

## 构建与验证

构建用 **JDK 17**，产出**字节码 8**（`maven.compiler.release=8`）——目标应用可能是 JDK 8。

```powershell
$env:JAVA_HOME="D:\apps\java\jdk-17.0.8"; $env:Path="$env:JAVA_HOME\bin;$env:Path"

mvn -o -q -DskipTests compile     # 编译
mvn -o test                       # 测试
javap -verbose -cp target/classes io.github.fulizhe.otelstore.core.config.LocalStoreConfig |
  Select-String "major version"   # 必须是 52
```

**CI 改完先本地过 actionlint**，不要直接推：

```powershell
D:\apps\actionlint\actionlint.exe (Get-ChildItem .github\workflows\*.yml).FullName   # 预期：无输出
（本机是 Windows：actionlint 不吃 glob 也不吃目录，必须这样把文件逐个喂给它。直接给 `.github/workflows/*.yml` 会报 `could not read`。）
```

CI 的字节码断言用的就是上面那条 `major version: 52`。

### 仓库外的环境坐标（不在 git 里，换机器要重找）

| 东西 | 位置 |
| --- | --- |
| agent jar | `D:\apps\opentelemetry-javaagent-2.32.0.jar`（26 MB，2.32.0） |
| actionlint | `D:\apps\actionlint\actionlint.exe`（1.7.12） |
| 一次性探针目录 | `C:\Users\lqzkc\AppData\Local\Temp\opencode\otel-probe` |
| `gh` | **未安装** —— GitHub 远程是手工建的 |

`JAVA_HOME`（JDK 17）的用法已列在上面，不重复。**探针目录可复用可销毁**：里面有一个
SPI provider 骨架，再要验 agent 扩展点的任何事实之前先去翻它，别从头搭一遍。

**shade 进去的三方依赖也必须是 52**（H2 / protobuf / opentelemetry-proto，见
[ADR-5](docs/adr/adr-05-shade-third-party-deps-into-extension-jar.md)）。shade 不重编译依赖 ——
升级它们时要自己核 class 版本，H2 2.3.x 已经是 55（Java 11）。

## 代码分层与依赖方向

```
io.github.fulizhe.otelstore
├── core      与 OTel 无关：配置、存储、统计
├── agentext  OTel 接入：SPI provider 与三条信号管线
└── readout   读口：JMX、HTTP、Prometheus 渲染
```

三条硬规则，评审时按它们查：

1. **`core` 不许 import 任何 `io.opentelemetry.*`。** 存储层与采集端解耦，是它将来能独立复用的唯一保证。
2. **`agentext` 不许直接操作存储实现**，只负责把 OTel 的数据模型翻译成 `core` 的入参。
3. **`readout` 只读。** 任何"顺手加个修改接口"的想法要先写 ADR。

## 运行期约定

- **单实例前提：同一个 `dataDir` 只会有一个本扩展的实例**（[ADR-4](docs/adr/adr-04-h2-in-memory-and-reset-on-startup.md)）。
  环形文件**没有文件锁**、文件头没有实例标识，因此多实例共用会互相覆盖 → 读到别的进程的数据。
  **不要"顺手"给环形文件加锁，也不要给默认 `dataDir` 加进程后缀** —— 前提已声明单实例，
  加锁是独立决策，加后缀只会让运维找不到文件。见到有人要支持多实例，先要求写 ADR。
- **存储随进程存活**：H2 内存模式 + 启动时显式重置环形文件，两个存储层一起清空。
  重置还保证 `getWrapCount()` / `getOldestLiveIndex()` 从 0 起算，不把上个进程的成绩算成自己的。
- **配置命名空间 `otel.localstore.*`**，避开 OTel 自身的 `otel.traces.*` / `otel.metrics.*` / `otel.logs.*`。
- **配置永不失败**：非法取值回落默认值，不抛异常。本项目跑在客户进程里，一个笔误不该让应用起不来。
- **端口冲突不阻塞启动**：退到随机端口并报出实际值。
- **异常不就地外抛**：存储与读口内部捕获、计数、限速日志。
- **区分"丢了"与"过期"**：环形文件写满是预期行为不是故障，两种计数必须分开。

## 读口的安全姿态

- 默认开启、默认绑定 `0.0.0.0`（可远程访问）、**结构性只读**（只注册 GET/HEAD，不提供 raw SQL）。
- **默认不要求 token**（要 token 需显式 `otel.localstore.auth=true`）。这是一条**安全姿态**决定：
  默认 `0.0.0.0` + 默认无鉴权 = 同一网络内任何机器都能读走全部载荷（SQL、HTTP header、请求体、日志原文）。
  改动它必须同时改 [ADR-1](docs/adr/adr-01-scope-and-principles.md) 与
  [ADR-6](docs/adr/adr-06-readout-http-surface.md)，不能只改默认值。
- 配了 `auth=true` 时：token 进程启动时随机生成，写入 `*.token` 文件与启动日志；
  **token 绝不能出现在任何日志、快照或异常消息里** —— 配 `describe()` 时注意。
  页面用「粘贴 token + sessionStorage」拿它，**不进 URL、不进 cookie**。
- 在客户应用里开端口是安全决策，不是顺手的事。改绑定地址或鉴权策略要写进 ADR。

## 验证节奏

### 绝不自己跑的东西

**长验证一律交给用户。** 具体点名，下面这些命令**不许自己执行**：

- `pwsh -NoProfile -File scripts/run-with-agent.ps1` —— 启的是长驻进程
- 任何 `Start-Process` 起 java / compose / 压测 / 容器的命令
- 任何会阻塞到人为结束的进程

理由（2026-10-04 实证）：自己跑这类命令会退化成「起进程 → `Start-Sleep` 轮询 → 再轮询」的循环，
工具调用一次次挂在超时上，用户全程干等且看不到进展。**验证的价值在用户手里，不在 agent 手里。**

### 该怎么交付

给三样东西，然后**停下等用户回话**：

1. **命令** —— 可直接粘贴，含环境准备（`$env:JAVA_HOME` 等）
2. **预期看到什么** —— 具体到输出形态（`Tests run: 21, Failures: 0`、`major version: 52`）
3. **怎么判断失败** —— 出现什么就说明哪一层出了问题

### 可以自己跑的

- 预计 **< 60s**、无交互、非长驻的命令：`mvn compile` / `mvn test` / `javap` 验证 /
  `actionlint` / 单文件 javap 检查 / `git` 操作
- 只读排查：`jar tf`、`javap`、`Select-String`、看日志尾部

### 其它

- 后台起了进程：**先回报 PID 与日志路径，再去检查**。不要闷头轮询。
- 失败即给结论与下一步，不用"我继续"过渡。
- 改了 workflow 记得本地 actionlint —— GitHub 对 workflow 另有一套契约，YAML 能解析不等于它会认。

### demo-app 是靶子，不是交付物

`demo-app/` 的用途只有两个：**验证主体**与**人工可视化验收**。
为主体写 demo-app 时的任何调试（端口占用、代理、启动脚本）都不算主体进展，
不要在那一层连续投入 —— 主体没动，就直说"主体下一步是什么"，别拿 demo-app 的进展顶替。

## 决策与事实的分工

- **决策**（为什么这么定、否掉了什么、后果是什么）→ `docs/adr/`。标题是一句决策，
  正文只有「面对什么 / 怎么定 / Considered Options / Consequences」，**不放实测数据**。
- **事实与实测数据**（跑出来的数字、版本号、类名、字段清单）→ `docs/notes/`。
- 动代码前先翻 [`docs/adr/index.md`](docs/adr/index.md)。有相关决策就照它做；
  决策与现实冲突时**先改决策**，不偷偷改代码。
