# `java.util.logging.FileHandler` 轮转行为实测（2026-10-06）

为 #9（扩展自有日志 + `/api/self-log`）选实现时，先核了 JDK 自带 `FileHandler` 的
`limit` / `count` 到底怎么算。**结论：它给不了"固定文件名 + 1 份备份"这两样同时成立。**

- 环境：JDK 17.0.8（`D:\apps\java\jdk-17.0.8`），Windows。`FileHandler` 的行为在 8~17 上一致。
- 构造：`new FileHandler(pattern, limit, count, /*append*/ true)`，pattern 无 `%g`。

## `count=1`：没有备份，只是把同一个文件截断

写 200 行、`limit=1000`：

```
count=1 limit=1000 lines=200 -> TOTAL=92   self.log=92
```

200 行的总输入约 14 KB，稳定后目录里只有 `self.log` **一个文件、92 字节**（最新那条），
早期行全丢。也就是说 `count=1` 不是"留一份备份"，而是"到上限就从头覆盖"。

## `count=2`：有 1 份备份，但文件名被 JDK 改掉

同样输入：

```
count=2 limit=1000 lines=200 -> TOTAL=1104   self.log.0=92   self.log.1=1012
```

`self.log.0` 是最新的一段（结尾是最后写入的那条），`self.log.1` 是上一段。
总量被 `limit × count` 管住，这部分是对的。但**文件名是 `.0` / `.1`**，
文档里写的 `<dataDir>/otelstore.log` 不存在；读端还得判断哪个是当前文件
（实测当前是 `.0`，但重启后由 `append=true` 的"找最新代次"逻辑决定，不宜当契约）。

## 附带发现：`limit` 小于单条记录时会病态

用 `limit=200`（小于一条 `SimpleFormatter` 记录的字节数）时，
`count=1` 与 `count=2` 的当前文件都只剩 0 字节 —— 每写一条都触发一次轮转。
生产用的是 1 MiB，不会踩到；但写测试时要避开这种"limit 比一条还小"的取法。

## 因此

`SelfLog` 自己实现一个 `java.util.logging.Handler` 子类：固定写
`<dataDir>/otelstore.log`，到 1 MiB 时先把 `otelstore.log.1` 删掉、再把当前文件改名过去，
然后开新文件。仍然是 JDK 的 logging，零三方依赖，且文件名与轮转行为都是确定的。
