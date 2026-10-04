# 可移植性调研 —— Android / Go / Python

- **日期**：2026-10-04（侧会话）
- **性质**：**尚未决定要不要做的事**。多语言与 Android 支持都还没进 ADR，
  本文只记"如果要支持，代价是什么"以及哪些结论有一手证据、哪些是先验知识。
- **证据等级**：
  - **§一 / §二 = 已实测**（命令可复现，只依赖 Maven Central）。
  - **§三 = 已实测的冲突判定**（依据见该节，每条给出机理）。
  - **§四 = 高置信待确认**（本机网络放不通，**勿当已实测引用**）。
  - **§五 = 环境限制**（避免下次白跑）。

## 一、OTel Android 是 AAR 库，不是 javaagent（已实测）

```powershell
curl.exe -s https://repo1.maven.org/maven2/io/opentelemetry/android/core/maven-metadata.xml
# latest = 1.7.0-alpha，lastUpdated 2026-09-04
curl.exe -s https://repo1.maven.org/maven2/io/opentelemetry/android/core/1.7.0-alpha/core-1.7.0-alpha.pom
```

- `<packaging>aar</packaging>`
- 依赖：`opentelemetry-sdk`、`kotlinx-coroutines-core:1.11.0`、
  `opentelemetry-disk-buffering:1.60.0-alpha`、`opentelemetry-exporter-logging`
- **无 H2、无内嵌可查询存储**

**后果**：[adr-01](../adr/adr-01-scope-and-principles.md) 的整个接入面论证（javaagent SPI
`AutoConfigurationCustomizerProvider`）在 Android 上**不适用**。Android 在接入难度这一档上
与 Go / Python 同档，不是与 Java 同档。

**且官方只带 logging exporter** —— "本地可查询存储"在 Android 侧是真空位。

## 二、意外收获：ADR-2 拿到上游一手佐证（已实测）

```powershell
curl.exe -s https://repo1.maven.org/maven2/io/opentelemetry/contrib/opentelemetry-disk-buffering/1.60.0-alpha/opentelemetry-disk-buffering-1.60.0-alpha.pom
```

依赖 `com.squareup.wire:wire-runtime:6.4.5` + `opentelemetry-exporter-otlp-common:1.65.0`。

即**上游 Android 的磁盘缓冲落的就是 OTLP protobuf bytes**，
与 [adr-02](../adr/adr-02-data-model.md)（payload 存编码后的 OTLP bytes）一致。
证据记在本文；adr-02 的 Consequences 已加一行指回这里 —— **决策本身没变，本文只做支撑**。

## 三、Android 的三条致命冲突

三条都不是调参能解决的。

1. **H2 不可用** —— H2 是 JDBC 驱动（`org.h2.Driver`），Android 无 `java.sql`，
   连类加载都过不去。**要换存储引擎。**
2. **ADR-4 的前提被推翻** —— "内存模式 = 存储随进程存活 = 重启即清空"在服务器上是明确的产品性质
   （见 [adr-04](../adr/adr-04-h2-in-memory-and-reset-on-startup.md)），在手机上等于**没有存储**
   （进程随时被杀）。Android 版几乎必须落盘 —— **前提级改动**。
3. **无 JMX** —— adr-01 钉死的"JMX 是唯一跨 ClassLoader 通道"作废。读口只剩 HTTP + `adb forward`，
   且默认绑 `0.0.0.0` 必须改成 `127.0.0.1`（公开 WiFi 下等于开门）。

## 四、高置信待确认（本机网络放不通，**勿当已实测**）

以下均来自先验知识，未取一手文档：

- `java.sql` / `javax.management` / `java.util.logging` 在 Android 上的有无
  （§三第 1 条的机理判断依赖它）。
- OTel Android 是否真的接了 logs 与 metrics —— core 依赖了全 SDK，但**接线情况未知**。
- Go 的"必须改 main"、Python 的"`.pth` 注入"两条接入面性质 —— 未取一手文档。

## 五、环境限制

本机网络**只放通 Maven Central**：

- `opentelemetry.io` / `h2database.com` —— 连接失败（非 404）
- `developer.android.com` / `raw.githubusercontent.com` —— 返回 404（正确路径未试）

⇒ **凡非 Maven Central 的一手源，本会话查不到。** 下次查 Android 侧先别在官网文档上耗时间。

## 六、定性

- **Android 不是移植，是重做存储层 + 读口**；采集层可复用。
  需 3~4 条新 ADR：**存储落盘 / 读口走 adb / 无 JMX / 容量预算**
  （256MiB × 2 手机吃不消）。
- **Go / Python**：设计可移植、接入面不可移植。
- **Java 是三者里接入最难的一个** —— ClassLoader 边界 + shade + 自写 mapper 三笔税；
  SQLite 在另两者上是免费的。