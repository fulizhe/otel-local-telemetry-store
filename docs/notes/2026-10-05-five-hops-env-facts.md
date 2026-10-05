# 五类依赖样例的环境实测（2026-10-05）

配 ADR-7。这里只放**跑出来的数字与事实**，决策在
[`../adr/adr-07-demo-app-embedded-deps-except-mysql.md`](../adr/adr-07-demo-app-embedded-deps-except-mysql.md)。

## handoff 里悬着的最大风险：已消除

handoff 段 A 风险 1 要求「下完 `kafka_2.13` 第一件事是确认字节码 52，不是就退回三跳」。实测：

```powershell
mvn -q dependency:get "-Dartifact=org.apache.kafka:kafka_2.13:2.7.1"
javap -verbose -cp "$env:USERPROFILE\.m2\repository\org\apache\kafka\kafka_2.13\2.7.1\kafka_2.13-2.7.1.jar" kafka.server.KafkaServer | Select-String "major version"
```

结果：**`major version: 52`**。Kafka 那一跳保留，五跳成立。
（`kafka_2.13` 是本项目**唯一**需要从 Maven Central 新下载的包 —— 这是 Docker Hub 不通的直接后果。）

## Kafka 那一跳：KRaft 在 Windows 上起不来，改用 ZK 模式（2026-10-06 实测）

原计划是 KRaft 单节点。实测**不成立**：

- `kafka_2.13:2.7.1` 里**根本没有 KRaft broker** —— `kafka.server.KafkaRaftServer`
  与 `kafka.tools.StorageTool` 都是 **2.8.0** 才有的类。2.7 只有 Raft 元数据 quorum。
- 换 `2.8.2`（字节码实测仍是 **52**）后类都在了，但 **broker 在这台 Windows 上起不来**：

```
java.nio.file.FileSystemException: ...\logs\@metadata-0\quorum-state.tmp -> ...\quorum-state:
  另一个程序正在使用此文件，进程无法访问。
	at kafka.raft.KafkaRaftManager.buildRaftClient(RaftManager.scala:229)
	at kafka.server.KafkaRaftServer.<init>(KafkaRaftServer.scala:70)
```

`FileBasedStateStore.rewriteStateFile` 把 `quorum-state.tmp` 改名成 `quorum-state` 时，
源文件仍被本进程持有句柄，Windows 拒绝该 rename（atomic move 的 fallback 也一样失败）。
Kafka 官方本就不支持 broker 跑在 Windows 上。**这是平台限制，不是配置问题。**

**改用 ZK 模式**（`kafka.server.KafkaServer` + 进程内 `org.apache.zookeeper.server.ZooKeeperServer`
+ `NIOServerCnxnFactory`）。实测**能起能收发**，`KafkaDependencyTest` 里真的起了一个 broker
并往返一条消息（约 3~7 秒，起完即关）。ZooKeeper 随 `kafka_2.13` 传递进来（3.5.9），
但因为直接起它，所以显式声明了。

**别的 KRaft 细节**（将来 Linux 上想换回去时用得上）：
`StorageTool.formatCommand(PrintStream, Seq<String>, MetaProperties, boolean)` 的第二个参数
**不是** `-t/-c` 参数表而是**日志目录表** —— 把 `-t <id> -c <file>` 喂进去会被当成三个目录名，
报 `Unable to create storage directory <file>`。格式化要手写 `meta.properties`
（`MetaProperties.apply(clusterId, nodeId).toProperties()`，键 `node.id / version / cluster.id`）。

## 本地 m2 已有的包（`~/.m2/repository`）

| 用途 | 坐标 | 已确认的版本 |
| --- | --- | --- |
| Redis 客户端 | `redis.clients:jedis` | 2.9.3 / 3.3.0 / 3.6.3 / 3.7.0 / **3.8.0** / 6.0.0 |
| Redis 服务端 | `it.ozimov:embedded-redis` | **0.7.3** |
| Kafka 客户端 | `org.apache.kafka:kafka-clients` | 2.0.1 / 2.5.0 / 2.5.1 / **2.7.1** / 2.8.2 |
| Kafka 服务端 | `org.apache.kafka:kafka_2.13` | **2.7.1（本次新下）** |
| gRPC | `io.grpc:grpc-netty-shaded` + `grpc-stub` | netty-shaded 1.30.2 / **1.58.0**；stub 1.12.0 / 1.28.1 / 1.59.1 |
| MySQL 客户端 | `mysql:mysql-connector-java` | 5.1.46 / 5.1.47 / 8.0.16 / 8.0.17 / 8.0.20 / **8.0.26** |

- `embedded-redis-0.7.3.jar` 内含 `redis-server-2.8.19` / `-32` / `.exe` / `.app` 四个平台二进制。
  注意版本很老（2.8.19），但 **Jedis 的 span 来自客户端方法，服务端版本不影响埋点**。
- `mysql-connector-java-8.0.26` 的 `com.mysql.cj.jdbc.Driver` 实测 **`major version: 52`**。

## JDBC 仪表化确实在 agent 里

`inst/io/opentelemetry/javaagent/instrumentation/jdbc/` 下有
`ConnectionInstrumentation` / `DriverInstrumentation` / `PreparedStatementInstrumentation` /
`StatementInstrumentation`（`.classdata` 形式）。

对 H2 与 MySQL 都只产生**一条** `scopeName=jdbc` 的 CLIENT span：
H2 是内存库、没有网络连接，所以**没有 SERVER span**；MySQL 的线协议层 agent 也没仪表化。
→ 两种库在图上长得一样，只能靠 **SQL 里的表名**区分（`demo_h2_order` / `demo_mysql_order`）。

## 本机 MySQL 容器（ADR-7 的外部依赖）

```
demo-app-stability-mysql-1   mysql:8.0   0.0.0.0:13306->3306/tcp   Up (healthy)
```

容器环境变量：`MYSQL_MAJOR=8.0`、`MYSQL_VERSION=8.0.46-1.el9`、`MYSQL_DATABASE=demo`、
`MYSQL_ALLOW_EMPTY_PASSWORD=yes` → **库名 `demo`、空密码**。
实测 `Test-NetConnection 127.0.0.1 -Port 13306` 返回 `True`。

## 端口占用实测（`Get-NetTCPConnection -State Listen`）

| 端口 | 状态 | 说明 |
| --- | --- | --- |
| 6379 / 9092 / 19099 / 18099 | **空闲** | 内嵌 Redis / Kafka broker / Kafka controller / demo-app 可选端口 |
| 18081 / 17890 | LISTEN（pid 36632） | 上一次验收留下的 demo-app 与读口，**起新的要先停** |
| 13306 / 16379 / 19092 | docker 映射 | 本机的 mysql / redis / kafka（`demo-app-stability-*` 一套） |

## 本机在跑的其它追踪栈（不参与本项目，仅避免撞端口 / 误判）

`jaeger-all-in-one`（16686 UI、4317/4318 OTLP）、`sw-h2`（SkyWalking OAP 9.4.0，8082/1521）、
`sw-ui`（8080）、`jaeger-demo-{java,go,python}`（28080/28081/28082）。

**SkyWalking 与 Jaeger 都在跑**，正好可作读口页面的**人工对照** ——
但它们的端口（8080 / 16686 / 1521）不要拿来做 demo-app 的端口。