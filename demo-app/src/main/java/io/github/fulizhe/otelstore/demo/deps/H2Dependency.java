package io.github.fulizhe.otelstore.demo.deps;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * H2 那一跳：进程内的内存库。
 *
 * <p><b>刻意用裸 JDBC（{@link DriverManager}）而不是 Spring 的 JDBC starter</b>，
 * 与 Redis / Kafka 那几跳同一条理由：starter 会把连接池一起拖进来，
 * 而连接池的获取本身会被仪表化成 span，链路图上多出来的不是我们想展示的东西。
 * 用裸驱动时，{@code scopeName=jdbc} 的 CLIENT span 数量恰好等于我们真的发了几条 SQL。
 *
 * <p>H2 是<b>内存库</b>，所以它没有网络连接，也就没有 SERVER span ——
 * 图上只有一条 {@code jdbc} 的 CLIENT span。这一条在验收时要先知道，
 * 否则会以为"SERVER span 缺失"是 bug（ADR-7）。
 */
public final class H2Dependency implements DependencyProbe {

    public static final String KEY = DepsRegistry.H2;
    private static final String TITLE = "H2（进程内内存库）";

    /** 驱动类名。用字符串而不是直接引用 —— H2 是 runtime scope，见 {@link JdbcDriver}。 */
    private static final String DRIVER = "org.h2.Driver";

    /**
     * 库名与 {@code DB_CLOSE_DELAY=-1}：后者保证内存库在第一条连接关掉之后还在 ——
     * 否则每次探测建出来的库都会消失，端点会看到一张空表。
     */
    private final String jdbcUrl;

    public H2Dependency() {
        this("jdbc:h2:mem:otelstore_demo;DB_CLOSE_DELAY=-1");
    }

    public H2Dependency(final String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    /**
     * 探测 = <b>真的建一次表并查一次</b>，而不是"类在不在"。
     *
     * <p>理由：H2 是随 jar 一起进来的，"驱动类能不能加载"几乎必然为真，
     * 那样探出来的 ready=true 说明不了任何事。探到<b>能建表能查</b>，
     * 后面那个端点才不会第一次就失败。
     */
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
        return true;
    }

    @Override
    public DepStatus probe() {
        try {
            initSchema();
            return DepStatus.ready(KEY, TITLE, true,
                    "内存库就绪（建表与查询都已跑通一次），库名 otelstore_demo");
        } catch (final SQLException e) {
            // 不重试：探测只做一次。要的是"能不能指望它"，不是"现在通不通"。
            return DepStatus.notReady(KEY, TITLE, true,
                    "连不上内存库：" + e.getMessage()
                            + "（配置永不失败：这一项只会降级，进程照常启动）");
        }
    }

    /**
     * 建表并塞一行。
     *
     * <p><b>表名带 {@code h2} 是刻意的</b>：JDBC 仪表化对 H2 与 MySQL 产生的东西完全一样
     * （都只一条 {@code scopeName=jdbc} 的 CLIENT span），所以图上区分两个库靠的是
     * <b>SQL 里的表名</b> —— span name 就是 SQL，图上直接读得出来，零后端改动（ADR-7）。
     */
    void initSchema() throws SQLException {
        try (final Connection conn = connect();
             final Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS demo_h2_order ("
                    + "id INT PRIMARY KEY, item VARCHAR(64), amount INT)");
            st.execute("MERGE INTO demo_h2_order (id, item, amount) KEY(id) "
                    + "VALUES (1, 'demo-h2-item', 100)");
        }
    }

    /**
     * 一次真实的查询 —— 这一句就是链路图上那条 {@code jdbc} CLIENT span 的来源。
     *
     * @return 表里的行数，交给调用方做"我造了多少 / 库里存了多少"的对账
     */
    public int queryOrders() throws SQLException {
        try (final Connection conn = connect();
             final Statement st = conn.createStatement();
             final ResultSet rs = st.executeQuery(
                     "SELECT item, amount FROM demo_h2_order ORDER BY id")) {
            int rows = 0;
            while (rs.next()) {
                rows++;
            }
            return rows;
        }
    }

    /**
     * 取连接。<b>每次都先显式注册驱动</b>（幂等，成本可忽略）。
     *
     * <p>放在这里而不是只放探测里：调用端点也可能在探测之后、agent 把
     * {@code DriverManager} 初始化过之后才第一次连 —— 那时才发现驱动没了就晚了。
     */
    private Connection connect() throws SQLException {
        JdbcDriver.ensure(DRIVER);
        return DriverManager.getConnection(jdbcUrl);
    }
}