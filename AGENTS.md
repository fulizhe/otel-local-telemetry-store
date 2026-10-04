# AGENTS.md

## 项目与当前阶段

**otel-local-telemetry-store** —— 把 OpenTelemetry 的 traces / logs / metrics 留在应用自己的进程内。
不发给任何远端，不需要第二套 agent，存储有界、降级可控。

当前处于 **Phase 0（骨架）**：能编译、能跑测试，功能尚未落地。第一个可运行形态见 README 的"目标用法"。

## 演示应用与靶子

`demo-app/` 是独立的 Spring Boot 工程，**不在主工程的 maven reactor 里**（主工程保持单模块）。
它造信号并给出可断言的计数，是端到端验证的靶子。

```powershell
pwsh -NoProfile -File scripts/run-with-agent.ps1   # 构建 + 挂 agent 与扩展 jar 一起起
pwsh -NoProfile -File scripts/run-with-agent.ps1 -Port 18099 -SkipBuild
```

- 页面 `http://localhost:18081/`，日志 `demo-app/target/demo.log`
- 单独构建：`mvn -f demo-app/pom.xml -DskipTests package`
- 端点与断言口径见 [`demo-app/README.md`](./demo-app/README.md)

**两条已知的本机环境坑**（写脚本/文档时别忘）：

1. 本机设了 `HTTP_PROXY`，而 **PowerShell 7 的 `Invoke-RestMethod` / `Invoke-WebRequest`
   不认 `NO_PROXY`** —— 访问 localhost 会超时。用 `curl.exe --noproxy "*"` 或加 `-NoProxy`。
2. **`-Dotel.javaagent.extensions=` 指向不存在的路径时，agent 静默忽略、零告警。**
   `scripts/run-with-agent.ps1` 会在起之前校验产物，堵掉这个坑。

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
D:\apps\actionlint\actionlint.exe .github/workflows/*.yml
```

CI 的字节码断言用的就是上面那条 `major version: 52`。

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

- **配置命名空间 `otel.localstore.*`**，避开 OTel 自身的 `otel.traces.*` / `otel.metrics.*` / `otel.logs.*`。
- **配置永不失败**：非法取值回落默认值，不抛异常。本项目跑在客户进程里，一个笔误不该让应用起不来。
- **端口冲突不阻塞启动**：退到随机端口并报出实际值。
- **异常不就地外抛**：存储与读口内部捕获、计数、限速日志。
- **区分"丢了"与"过期"**：环形文件写满是预期行为不是故障，两种计数必须分开。

## 读口的安全姿态

- 默认开启、默认绑定 `0.0.0.0`（可远程访问）、**结构性只读**（只注册 GET/HEAD，不提供 raw SQL）。
- 默认要求访问 token：进程启动时随机生成，写入 `*.token` 文件与启动日志。
  **token 绝不能出现在任何日志、快照或异常消息里** —— 配 `describe()` 时注意。
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