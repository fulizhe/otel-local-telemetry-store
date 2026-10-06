package io.github.fulizhe.otelstore.demo.deps;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MySQL 那一跳：<b>五跳里唯一的外部依赖</b>（ADR-7）。
 *
 * <p><b>为什么不能内嵌</b>：H2 本来就是库、Redis 有 embedded-redis、Kafka 能内嵌 brokerage、
 * gRPC 起个 Netty server 就行 —— <b>MySQL 没有可嵌入的服务端实现</b>。
 * 所以这一跳连本机已有实例，连不上就降级。它不是唯一会失败的一跳，
 * 但它是唯一<b>注定可能</b>失败的一跳，因此是 ADR-7 那套降级口径的第一次真实检验。
 *
 * <p><b>表名带 {@code mysql} 是刻意的</b>：JDBC 仪表化对 H2 与 MySQL 产生的东西完全一样
 * （都只一条 {@code scopeName=jdbc} 的 CLIENT span），图上区分两个库靠的是
 * <b>SQL 里的表名</b> —— span name 就是 SQL，零后端改动。
 *
 * <p>配置从系统属性读，<b>非法取值一律回落默认值、绝不抛异常</b>：
 * 这是靶子，一个笔误不该让它起不来（与主体"配置永不失败"同一条纪律）。
 */
public final class MysqlDependency implements DependencyProbe {

    public static final String KEY = DepsRegistry.MYSQL;
    private static final String TITLE = "MySQL（外部实例）";

    /** 驱动类名。用字符串而不是直接引用 —— 见 {@link JdbcDriver} 里那个 agent 的坑。 */
    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";

    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 13306;
    static final String DEFAULT_DB = "demo";

    private final String host;
    private final int port;
    private final String database;
    private final int timeoutMs;

    public MysqlDependency() {
        this(prop("demo.deps.mysql.host", DEFAULT_HOST),
                intProp("demo.deps.mysql.port", DEFAULT_PORT),
                prop("demo.deps.mysql.db", DEFAULT_DB),
                3000);
    }

    public MysqlDependency(final String host, final int port, final String database,
                           final int timeoutMs) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public boolean embedded() {
        return false;
    }

    public String jdbcUrl() {
        // connectTimeout / socketTimeout 都要显式给：不给的话连不上时会挂很久，
        // 而"端点挂住不返回"是靶子最难查的一种表现
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?connectTimeout=" + timeoutMs + "&socketTimeout=" + timeoutMs
                + "&useSSL=false&allowPublicKeyRetrieval=true";
    }

    public String database() {
        return database;
    }

    /**
     * 探测 = <b>真的建表并查一次</b>。
     *
     * <p>只探"端口通不通"是不够的：端口通、库不存在或没权限时，端点第一次就会失败，
     * 而那时它报的是"调用失败"，看起来像运行期故障而不是"没准备好"。
     */
    @Override
    public DepStatus probe() {
        try {
            initSchema();
            return DepStatus.ready(KEY, TITLE, false,
                    "外部实例就绪（" + host + ":" + port + "/" + database
                            + "，建表与查询都已跑通一次）");
        } catch (final SQLException e) {
            return DepStatus.notReady(KEY, TITLE, false,
                    "连不上（" + e.getClass().getSimpleName() + ": " + e.getMessage()
                            + "）。这是<b>预期状态</b>：换机器若没有这个实例，"
                            + "其余四跳照常工作，链路图少一跳不是故障（ADR-7）");
        }
    }

    private void initSchema() throws SQLException {
        try (final Connection conn = connect();
             final Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS demo_mysql_order ("
                    + "id INT PRIMARY KEY, item VARCHAR(64), amount INT)");
            // MySQL 的 upsert 与 H2 不同名：H2 是 MERGE ... KEY，MySQL 是 ON DUPLICATE KEY
            st.execute("INSERT INTO demo_mysql_order (id, item, amount) VALUES (1, 'demo-mysql-item', 200)"
                    + " ON DUPLICATE KEY UPDATE item = VALUES(item)");
        }
    }

    /** 一次真实的查询 —— 这一句就是链路图上那条 {@code jdbc} CLIENT span 的来源。 */
    public Map<String, Object> queryOrders() throws SQLException {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        try (final Connection conn = connect();
             final Statement st = conn.createStatement();
             final ResultSet rs = st.executeQuery(
                     "SELECT item, amount FROM demo_mysql_order ORDER BY id")) {
            int rows = 0;
            while (rs.next()) {
                rows++;
            }
            m.put("rows", Integer.valueOf(rows));
        }
        return m;
    }

    private Connection connect() throws SQLException {
        // 显式注册驱动：挂 agent 时 DriverManager 的自动发现会用错 TCCL，见 JdbcDriver
        JdbcDriver.ensure(DRIVER);
        return DriverManager.getConnection(jdbcUrl(), "root", "");
    }

    /** 系统属性取不到或非法一律回落默认值 —— 靶子的配置永不让它起不来。 */
    private static String prop(final String key, final String fallback) {
        final String v = System.getProperty(key);
        return v == null || v.trim().isEmpty() ? fallback : v.trim();
    }

    private static int intProp(final String key, final int fallback) {
        final String v = System.getProperty(key);
        if (v == null) {
            return fallback;
        }
        try {
            final int parsed = Integer.parseInt(v.trim());
            return parsed > 0 && parsed < 65536 ? parsed : fallback;
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }
}