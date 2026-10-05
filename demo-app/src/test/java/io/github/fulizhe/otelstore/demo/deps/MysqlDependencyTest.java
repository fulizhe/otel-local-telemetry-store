package io.github.fulizhe.otelstore.demo.deps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MySQL 那一跳 —— <b>只测不需要真库的一半</b>，因为它是唯一外部依赖。
 *
 * <p>"能连上"那半由用户在真机上验（README 的验收清单）。这里钉的是两件
 * <b>不需要库、但很容易写错</b>的事：
 *
 * <ol>
 *   <li><b>降级不崩</b>：连不上时返回 not-ready + detail，而不是抛异常。
 *       这一跳是五跳里唯一<b>注定可能</b>失败的，所以这条是它的主路径。</li>
 *   <li><b>配置永不失败</b>：非法端口 / 空主机要回落默认值，不抛异常。</li>
 * </ol>
 *
 * <p>用<b>保留端口</b>（1）来制造"连不上"：13306 上本机真有库，
 * 拿它测降级会随环境变；端口 1 上不会有 MySQL，断言才是确定的。
 */
class MysqlDependencyTest {

    @Test
    @DisplayName("连不上时降级 + detail 说清这是预期状态，且不抛异常")
    void unreachableMysqlDegradesWithoutThrowing() {
        final MysqlDependency dep = new MysqlDependency("127.0.0.1", 1, "demo", 500);
        final DepStatus s = dep.probe();
        assertNotNull(s);
        assertFalse(s.ready(), "连不上就该 ready=false");
        assertTrue(s.detail().contains("预期状态"),
                "detail 要写清这一跳没起来是正常的（换机器没有这个实例）：" + s.detail());
        assertFalse(s.embedded(), "MySQL 是唯一外部依赖，embedded 必须是 false");
    }

    @Test
    @DisplayName("表名带 mysql —— 图上靠它把这一跳与 H2 那一跳分开")
    void jdbcUrlPointsAtConfiguredDatabase() {
        final MysqlDependency dep = new MysqlDependency("db.example", 13306, "demo", 1000);
        assertTrue(dep.jdbcUrl().startsWith("jdbc:mysql://db.example:13306/demo"), dep.jdbcUrl());
        // 超时必须显式给：不给的话连不上会挂很久，端点上表现为"卡住"
        assertTrue(dep.jdbcUrl().contains("connectTimeout=1000"), dep.jdbcUrl());
        assertEquals("demo", dep.database());
    }

    @Test
    @DisplayName("非法配置一律回落默认值，绝不抛异常 —— 靶子不该因为一个笔误起不来")
    void illegalConfigFallsBackToDefaults() {
        final String port = System.getProperty("demo.deps.mysql.port");
        final String host = System.getProperty("demo.deps.mysql.host");
        try {
            System.setProperty("demo.deps.mysql.port", "abc");
            System.setProperty("demo.deps.mysql.host", "   ");
            final MysqlDependency dep = new MysqlDependency();
            assertTrue(dep.jdbcUrl().contains(":" + MysqlDependency.DEFAULT_PORT + "/"),
                    "非法端口与空白主机都要回落默认值：" + dep.jdbcUrl());
            assertTrue(dep.jdbcUrl().contains(MysqlDependency.DEFAULT_HOST), dep.jdbcUrl());
        } finally {
            restore("demo.deps.mysql.port", port);
            restore("demo.deps.mysql.host", host);
        }
    }

    private static void restore(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    @DisplayName("key / title / embedded 要能对上名字")
    void identifiesItself() {
        final MysqlDependency dep = new MysqlDependency("127.0.0.1", 13306, "demo", 1000);
        assertEquals(DepsRegistry.MYSQL, dep.key());
        assertTrue(dep.title().contains("MySQL"), dep.title());
        assertFalse(dep.embedded());
    }
}