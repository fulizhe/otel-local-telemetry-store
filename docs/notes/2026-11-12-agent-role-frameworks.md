# 用 agent 角色分工跑开发流程：框架盘点（2026-11-12）

调研笔记，**不是决策**。方向定下来之后再单开 ADR。

证据分两档，写清楚免得以后把记忆当事实：

- **[已读]** 本轮抓过仓库 README 或官方文档
- **[记忆]** 未在本轮核对，引用前先复核

## 一句话结论

这类框架是三条线，不是一条：

1. **角色人格型**：BMAD、MetaGPT、ChatDev、CrewAI。每个角色是一份 persona，按阶段交接。
2. **流程产物型**：OpenSpec、Spec Kit、Kiro、Agent OS。没有角色，靠规格产物推进。
3. **技能触发型**：Superpowers、pstack、Roo Code Boomerang、claude-flow 的 SPARC。角色在运行时派生。

两个常见的归类错误：

- **OpenSpec 不是角色分工**。它按 `change` 目录产出四件套，没有 persona，也没有硬门禁。
- **Superpowers 不只是"流程"**。它的 `subagent-driven-development` 每个任务派新 subagent，回来过两道评审，这就是分工，只不过角色是运行时生成的。

## 对照表

| 框架 | 角色怎么出现 | 主要产物 | 强制程度 | 适合 |
| --- | --- | --- | --- | --- |
| BMAD-METHOD | v6 是六个 persona；v7 改为技能 + 专业视角 | brief、spec、架构、故事 | 高，阶段门 | 从 0 到 1，需求不清 |
| MetaGPT | PM / 架构师 / 项目经理 / 工程师，SOP 写死 | 需求、数据结构、API、代码 | 高，固定 SOP | 一句话生成一个仓库 |
| ChatDev | CEO / CTO / 程序员 / 评审 / 测试，瀑布接力 | 对话链 + 代码 | 高，瀑布 | 教学、演示 |
| CrewAI | role + goal + backstory，顺序或层级流程 | 由使用者定义 | 低，通用编排 | 自己搭流程 |
| Superpowers | 运行时派生：实现者 + spec 审查 + 质量审查 | 设计文档、逐任务计划 | 高，技能强制触发 | 单人高 rigor 交付 |
| pstack | `poteto-mode` 路由 → 剧本 → 委派工人 + 审查技能 | 剧本、决策记录、验证证据 | 中，入口强制 | 已有代码库的日常改动 |
| OpenSpec | 无角色 | `proposal / specs / design / tasks` | 中，产物契约 | 存量代码，多人多仓 |
| Spec Kit | 无角色 | constitution / spec / plan / tasks | 高，阶段门 | 新项目起步 |
| Agent OS | 无角色，先抽代码库规范 | standards + spec | 中 | 规范不统一的存量仓库 |
| Roo Code | 模式即角色：Architect / Code / Debug / Orchestrator | 子任务摘要 | 中，harness 内置 | 希望上下文强隔离 |

## 一、角色人格型

### BMAD-METHOD [已读]

`Breakthrough Method for Agile Ai Driven Development`，55.3k stars，MIT。
<https://github.com/bmad-code-org/BMAD-METHOD>

两条线并存：

- **稳定线 v6.12**：npm 安装器，除修 bug 外冻结。经典六角色（Analyst / PM / Architect / Scrum Master / Dev / QA）是这一线的形态 **[记忆，未逐条核对 v6 文档]**。
- **v7 预览**：README 写 ETA 为 2026-11 中旬（**今天就是 11-12，可能随时变 beta**）。改成 skills 形态，安装是
  `npx skills add bmad-code-org/BMAD-METHOD --skill '*'`，入口 `bmad-build`，问路用 `bmad-help`。

v7 的主循环是 Clarify → Plan → Build and verify → Learn and adjust，README 的卖点写的是
"Specialized perspectives — product, architecture, UX, development, testing"，即角色收敛成了专业视角，
不再是一人一个 agent 文件。生态模块有 BMad Builder、Test Architect、Loop、Game Dev Studio。

关键设计：角色之间**交接产物**，不共享上下文。v7 的 `bmad-loop` 能无人值守跑完一整个 epic。

### MetaGPT [已读]

`Code = SOP(Team)` 是它的核心主张：把标准作业程序写进代码，让一组 LLM 角色去执行。
MIT，71.3k stars。一句需求进去，用户故事、竞品分析、需求、数据结构、API、文档、代码出来。
角色是产品经理、架构师、项目经理、工程师。
<https://github.com/FoundationAgents/MetaGPT>

和 BMAD 的差别：BMAD 是可裁剪的协作流程，MetaGPT 是不可裁的自动化产线。

### ChatDev [记忆]

"虚拟软件公司"，角色有 CEO、CTO、程序员、评审、测试，按瀑布阶段用对话链接力。
学术味浓，常用作多 agent 软件工程的对照基线。本轮未抓 README。

### CrewAI [记忆]

通用编排框架，不是 SDLC 框架。给每个 agent `role`、`goal`、`backstory`，
用 sequential 或 hierarchical process 串起来。很多"角色分工开发"的实现是拿它自己搭的。

### 子代理集合 [记忆]

`wshobson/agents`、`contains-studio/agents` 这类仓库，一个 Markdown 一个角色
（backend-architect、code-reviewer、security-auditor 等）。只有 persona，没有流程，靠人串。

## 二、流程产物型

### OpenSpec [已读]

SDD（spec-driven development），74.7k stars，MIT。
<https://github.com/Fission-AI/OpenSpec>

一个 change 走四个动作：

| 动作 | 作用 |
| --- | --- |
| `/openspec-explore` | 还没想清时的思考伙伴，只读代码、摆选项 |
| `/openspec-propose` | 生成 change 目录：`proposal.md`、`specs/`、`design.md`、`tasks.md` |
| `/openspec-apply-change` | 按 tasks 实施 |
| `/openspec-archive-change` | 归档，同时更新 `openspec/specs/` |

立场写得很明确：fluid not rigid、iterative not waterfall、brownfield 优先。
2.0 起以 skills 形式分发，支持 30+ 工具；Codex 里写成 `$openspec-propose`。
Stores（beta）允许把规格放独立仓库，实现跨仓规划。

**没有角色分工**，这是它和 BMAD 最本质的区别。

### Spec Kit [记忆]

GitHub 官方，五段命令：`constitution → specify → plan → tasks → implement`。
阶段门比 OpenSpec 硬，Markdown 更多，安装要 Python。OpenSpec 的 README 里专门有一节对比它。

### Kiro [记忆]

AWS 的 IDE 方案：`requirements.md / design.md / tasks.md` + steering + hooks。
代价是锁 IDE，模型选择也受限。

### Agent OS [已读]

<https://github.com/buildermethods/agent-os>

核心能力四项：Discover Standards（从现有代码抽规范）、Deploy Standards（按任务注入）、
Shape Spec（写规格）、Index Standards（可检索）。命令在 `commands/agent-os` 下，
支持 Claude Code、Cursor、Antigravity。

它的差异点是**把"这个仓库已有的规范"当一等公民**。BMAD 和 OpenSpec 都需要你另外告诉 agent
项目约定，Agent OS 是先把约定挖出来。

## 三、技能触发型

### Superpowers [已读]

316k stars，MIT，作者 Jesse Vincent。定位是"给 coding agent 的一整套开发方法论"。
<https://github.com/obra/superpowers>

基本流程，每一步都由技能触发：

| 阶段 | 技能 | 做什么 |
| --- | --- | --- |
| 1 | `brainstorming` | 问出真实意图，写成设计文档 |
| 2 | `using-git-worktrees` | 隔离工作区，跑一遍干净基线 |
| 3 | `writing-plans` | 拆成 2-5 分钟的任务，带确切文件路径和验证步骤 |
| 4 | `subagent-driven-development` | 每个任务派新 subagent，做完立即评审 |
| 5 | `test-driven-development` | 强制 RED-GREEN-REFACTOR |
| 6 | `requesting-code-review` | 按严重度报告，关键问题卡住流程 |
| 7 | `finishing-a-development-branch` | 验证、给合并/PR/丢弃选项、清理 worktree |

README 原话是 "Mandatory workflows, not suggestions"，技能检查发生在每个任务之前。
`subagent-driven-development` 是两道评审：先 spec 合规，再代码质量。

OpenCode 有独立安装入口（`.opencode/INSTALL.md`）。

### pstack [已读，已装在本仓库]

58 条技能、23 个剧本、24 条原则，入口 `poteto-mode`。用法见 [`../pstack-guide.md`](../pstack-guide.md)。

和 Superpowers 的分歧点：pstack 明确**反对 planning skill**，主张用原型和 `architect` 的类型草图
代替抽象计划；Superpowers 反过来，先把计划写到"一个有热情但没判断力的初级工程师也能照做"的程度。

角色层面，pstack 的"分工"是委派工人（`poteto-agent`）加独立审查技能（`interrogate`、`no-comments`、
`blast-radius`），不是六个 persona。

### Roo Code Boomerang [已读]

<https://docs.roocode.com/features/boomerang-tasks>

`Orchestrator` 模式被刻意砍掉能力：不读文件、不写文件、不跑命令、不调 MCP，只拆任务。
子任务通过 `new_task` 交给 `Architect / Code / Debug` 等模式，结束时用 `attempt_completion`
只回一段 summary。上下文完全隔离，父任务拿不到子任务的原始输出。

这是"orchestrator-worker + 角色交接"最干净的实现，代价是摘要会丢信息。

### claude-flow / SPARC [记忆]

SPARC 五阶段：Specification / Pseudocode / Architecture / Refinement / Completion。
claude-flow 把它做成 hive-mind 式编排，带 queen/worker 角色。本轮未核对版本细节。

## 通用编排模式（不属于 SDLC 框架）

这些是上面各家实现背后的模式，看清楚它们比记框架名有用：

- **orchestrator-worker**：一个只做调度的父，若干隔离上下文的子（Roo、Superpowers、pstack）
- **planner-executor-critic**：计划、执行、批评三段分离
- **maker-checker**：同一件事由两个角色分别做和查
- **handoff**：AutoGen 的 GroupChat、LangGraph 的 supervisor、OpenAI Agents SDK 的 handoffs

## 怎么选

- **需求不清楚、从 0 到 1**：角色型。BMAD 的六视角是为了逼出"你没说但必须要"的东西。
- **存量代码、多人、多仓**：产物型。角色会漂，`proposal.md` 不会。
- **规范不统一的老仓库**：Agent OS 先抽 standards，再谈流程。
- **日常单点改动**：技能型，也就是本仓库现在的形态。
- **单人仓库照搬六角色**：等于六次上下文切换加六份重复读代码，通常不划算，裁成三角色更现实。

## 对本仓库的落点（只记录，不实施）

现状：

- 已有 pstack：技能、委派、验证技能、`show-me-your-work` 的决策记录、`correct` 的错误固化。
- 没有角色层：`poteto-agent` 是通用工人，不是架构师或评审。
- 没有 change 级产物：`docs/specs/` 下两篇是按主题的文档，不是"一次改动一个目录"。
- `docs/adr/` 记的是决策，不是某次改动的提案。

如果以后要落，三条可裁剪路线：

1. **只补角色层**：`.opencode/agents/` 加 architect / implementer / reviewer，交接走文件，流程仍由 `poteto-mode` 串。
2. **只补规格层**：`docs/specs/<change>/` 用 OpenSpec 四件套，先在一两个真实改动上试用。
3. **两者都补**：先角色后规格，避免一开始就加两套仪式。

风险：OpenSpec 的四件套与本仓库的 ADR 和 specs 习惯重叠。落地前先定清楚"谁写哪份、
ADR 与 change 目录的边界在哪"，否则会出现同一件事写三遍。

## 链接

- BMAD-METHOD：<https://github.com/bmad-code-org/BMAD-METHOD>
- MetaGPT：<https://github.com/FoundationAgents/MetaGPT>
- OpenSpec：<https://github.com/Fission-AI/OpenSpec>
- Spec Kit：<https://github.com/github/spec-kit>
- Agent OS：<https://github.com/buildermethods/agent-os>
- Superpowers：<https://github.com/obra/superpowers>
- pstack：<https://github.com/backnotprop/pstack>
- Roo Code Boomerang Tasks：<https://docs.roocode.com/features/boomerang-tasks>
- Kiro：<https://kiro.dev>
- 本仓库的 pstack 用法：[`../pstack-guide.md`](../pstack-guide.md)
