# pstack 入门（本项目版）

pstack 是 poteto（[@poteto](https://x.com/poteto)，React 编译器团队成员）开源的一套 Agent Skills，
MIT 协议，目标是把「能跑」和「能交付」之间的差距补上：写得更少，验证得更狠。
项目已经装进 `.opencode/skills/`，**只对本仓库生效**。

这份文档只回答三件事：装在哪、怎么用、在这个 JVM 仓库里哪些默认口径要改。

## 1. 装在哪，改了什么

| 路径 | 内容 |
| --- | --- |
| `.opencode/skills/` | pstack 全部 58 条技能和 24 条原则 |
| `.opencode/agents/poteto-agent.md` | `poteto-mode` 的委派子代理 |
| `.opencode/agents/comment-sicko.md` | 只读的注释审查子代理（禁编辑） |

有两条和全局 matt 技能（`~/.claude/skills/`）撞名，已改名：

- `tdd` → `pstack-tdd`
- `teach` → `pstack-teach`

技能正文里的交叉引用同步改过（`poteto-help`、`bug-fix` playbook）。**本项目不使用 matt 那套技能**，
两边都想要同名能力时用 pstack 这边。

## 2. 先分清三样东西

**技能（skill）**：`SKILL.md`，一条可复用的做法。OpenCode 只把「ID + 描述」放进模型的选择列表，
真正加载要显式点名。pstack 里大多数技能带 `disable-model-invocation: true`，
意味着它们**不会**出现在自动列表里，必须 `@id` 叫出来。

**剧本（playbook）**：`poteto-mode/playbooks/*.md`，共 23 个。不是独立技能，
由 `poteto-mode` 按任务类型自己挑，你不需要点名。

**原则（principle-\*）**：24 条很短的经验，`poteto-mode` 在正文里内联了索引，
用到哪条才去读它的叶子文件。

## 3. 入口只用记一个：poteto-mode

`poteto-mode` 做四件事：

1. 把任务匹配到一份剧本，开一个 todo 列表，第一步就把剧本的步骤原样抄进去。
2. 按步骤路由到别的技能（`how`、`architect`、`swarm`、`unslop` 等）。
3. 派子代理干活，自己保留剧本、todo 和上下文窗口。
4. 用 `unslop` 的规则写回复：短句、结论带证据、不写套话。

在 OpenCode 里没有 `/poteto-mode` 这种斜杠命令，把它写在提示词里：

```text
@poteto-mode 修掉读口 summary 里丢掉 wrapCount 的问题，先复现再改
```

## 4. 技能地图

按用途查，不用全背。

| 想做的事 | 技能 | 备注 |
| --- | --- | --- |
| 弄清一段代码怎么跑 | `how` | 子系统大时会并行起探索代理 |
| 弄清当初为什么这么写 | `why` | 并行查 git、issue、文档、监控等证据 |
| 把一件事讲到人能懂 | `pstack-teach` | 内部调用 `how` + `why` |
| 接着上次的活干 | `recall` | 从会话历史和当前状态重建简报 |
| 出方案、定接口 | `architect` | 依赖 `arena`、`how`，先画类型签名再实现 |
| 多个方案对打 | `arena` | 并行候选，选一个当底座嫁接其他方案的长处 |
| 多模型对抗评审 | `interrogate` | 只认跑出来的证据 |
| 评估影响面 | `blast-radius` | 依赖 `arena`、`how`、`why`、`unslop` |
| 并行铺开验证 | `swarm` | 扇出 N 个工人，收一份报告 |
| 把 bug 写成测试 | `pstack-tdd` | 只在测试路径便宜时用 |
| 查性能数字是否可信 | `benchmark-checklist` | 报快慢之前先过一遍 |
| 模型检查并发/状态机 | `statechart` | TLA+，重，按需 |
| 写文档、README、PR 描述 | `technical-writing` | 先分教程/操作/参考/解释四类，再洗 AI 味 |
| 洗掉 AI 味 | `unslop` | 也管你的回复 |
| 删掉注释里的废话 | `no-comments` | 派 `comment-sicko` |
| 把纠正变成永久的墙 | `correct` | 优先改结构，其次类型，最后才写文档 |
| 长任务留下决策痕迹 | `show-me-your-work` | 一行一个决策的 TSV |
| 没有现成剧本可用 | `figure-it-out` | 现场设计一套可审计的流程 |
| 不知道该用哪个 | `poteto-help` | 也可以直接问 agent |

组合关系（装的时候要一起装）：`pstack-teach` → `how` + `why`；`architect` → `arena` + `how`；
`blast-radius` → `arena` + `how` + `why` + `unslop`；`technical-writing` → `unslop`；
`create-verification-skill` → `maintain-verification-skill`。

## 5. 在本项目里，哪些默认口径要改

pstack 的默认假设是「有一个 agent 能驱动的应用」：Electron、Web、CLI。
它讲验证，讲的是让 agent 自己点按钮、截图、录视频。这个仓库没有 UI，是 JVM 扩展库，
所以照抄会空转。

### 5.1 验证技能的对应物是「读口断言 + demo-app 计数」

本项目的验证资产已经存在，只是不叫 pstack 的名字：

- `demo-app/` 造三种信号并给出可断言的计数
- `scripts/run-with-agent.ps1` 把 agent 和扩展一起挂起来
- `curl.exe --noproxy "*"` 打 `/api/summary`、`/api/traces`、`/api/logs`、`/api/metrics`
- 扩展自己的日志在 `demo-app/target/demo.err.log`

pstack 说的「控制 CLI」在这个仓库的落点是：把这些断言包成一个脚本，
子命令化（`summary`、`traces`、`assert-counts`），输出 JSON。
**这条建议还没做**，做了它才能真正跑 `/create-verification-skill`：
agent 一条命令拿结果，而不是每次手搓 curl。

### 5.2 长验证仍然归你，不归 agent

AGENTS.md 的验证节奏写死了：长驻进程由用户跑，agent 只交付「命令 + 预期 + 失败判据」。
pstack 的验证剧本默认 agent 自己能起服务、能录屏，这里要主动收住。
写提示词时把这条说进去，例如：

```text
@poteto-mode 改完后给出验证命令、预期输出和失败判据；长驻进程我自己起
```

### 5.3 模型路由对不上，别跑 setup-pstack

pstack 的默认分工是 grok 写代码、opus 做判断和评审。本项目的 provider 是 `opencode-go`，
那些 slug 不存在。子代理不写 `model` 就继承父会话模型，这是当下正确行为。

`setup-pstack` 会把映射写到用户级 `~/.agents/pstack-models.md`，跨项目生效，
和「只属于本项目」冲突。要用它，得先改成项目内路径。

### 5.4 其余几条

- `poteto-mode`、`pstack-tdd`、`pstack-teach` 因为 `disable-model-invocation`，
  不会出现在自动列表里，必须显式点名。
- 原文按 Cursor 写的。遇到 `AskQuestion` 读成 `question`，`Task` 读成 `subagent`，
  Bugbot、cloud agents、automations 在本项目没有对应物，跳过。
- `statechart/` 带 2.8 MB 的 TLA+ 测试语料，除正式建模外不用翻。
- pstack 作者说的「每月 2000 个 PR」靠的是验证技能加云端并发，不是让你照着加吞吐。
  这个仓库该抄的是验证闭环，不是数量。

## 6. 可以照抄的提示词

```text
@poteto-mode 读 AGENTS.md 和 docs/adr/index.md，用你自己的话复述这次要改什么，再动手
```

```text
@poteto-mode 复现 <症状>。先给假设和证据，别急着改代码
```

```text
@poteto-mode 对 <模块> 跑 architect，先给候选类型签名和调用点草图，我选完再实现
```

```text
@poteto-mode 用 demo-app 的计数验证这条改动，给出命令、预期、失败判据
```

```text
@pstack-teach 讲讲这条链路为什么这么分层，以及另一种做法差在哪
```

```text
@poteto-mode 收尾：按 no-comments 过一遍 diff，再按 unslop 检查回复
```

## 7. 延伸阅读

- 安装源（独立镜像，MIT）：<https://github.com/backnotprop/pstack>
- 上游（Cursor 插件仓）：<https://github.com/cursor/plugins/tree/main/pstack>
- 作者原文：<https://x.com/poteto/article/2094457600259842065>
- 中文完全指南 第 1 部分，讲验证技能：<https://x.com/Stephan_Talk/status/2115888996022993036>
- 中文完全指南 第 2 部分，讲调研、规划、原型与架构：<https://x.com/Stephan_Talk/status/2116109437064892679>
