# profiling 能不能补进来 —— 待研究

- **状态**：明确 out of scope，但研究已立项。
- **为何现在不写**：profiling 不是第四个信号，是另一种东西。它需要 native agent
  （async-profiler / async-profiler 的 JFR 引擎），OpenTelemetry 的信号管线给不了 ——
  没有 exporter 能把采样栈变成 span。

## 要回答的问题

1. **共用与冲突** —— async-profiler 作为独立 agent（或 attach）与
   `opentelemetry-javaagent` 能否在同一个 JVM 里共存？各自会装哪些东西、
   有没有类加载或信号处理上的冲突。
2. **数据模型差距** —— Glowroot / async-profiler 产出的是采样栈 + 火焰图聚合；
   本项目的存储形态是"表头行 + 环形载荷"。采样栈的体量与压缩率能否套进同一个环形文件，
   还是需要第四种存储形态。
3. **能不能借 OTel 部分桥接** —— async-profiler 支持把采样事件作为自定义事件往外发。
   若能借 OTel 的 `LogRecord` 或 event 管线落地，就能复用本项目的存储与读口，
   不必自己造一条。**这条最省事，优先查。**
4. **成本** —— 采样本身的开销（JVM 百分之几）、额外依赖的部署方式、
   以及"三信号 + profiling"这个组合是否还能维持"有界预算"的承诺。

## 参考

- Glowroot 官方文档与仓库（它是本项目最贴近的对标对象，也是这个问题的最佳参照）
- async-profiler 官方文档：`Attach API` / `JFR output` / `event output` 三种导出方式

## 结论写回哪里

研究结论落成本目录下一份新笔记，并把 README 的 Out of scope 段落改成指向它。
若判定"能借 OTel 桥接"，另写一份 ADR 说明 profiling 走哪条存储形态。