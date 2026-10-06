package io.github.fulizhe.otelstore.demo.deps;

/**
 * 显式注册 JDBC 驱动，<b>不依赖 {@code DriverManager} 的自动发现</b>。
 *
 * <p>这不是洁癖，是真机验收抓出来的坑（与主工程
 * {@code LocalStore} 里那段注释同源）：
 * {@code DriverManager} 在<b>类初始化</b>时用一次
 * {@code ServiceLoader.load(Driver.class)} 扫 {@code META-INF/services/java.sql.Driver}，
 * 而那次扫描用的是<b>当时的线程上下文 ClassLoader</b>。
 * 挂了 agent 之后，{@code java.sql.DriverManager} 会先被 agent 侧触发，
 * 那时 TCCL 不是应用 ClassLoader —— <b>应用 classpath 上的 H2 / MySQL 驱动扫不到</b>。
 * 而那次扫描一辈子只做一次，之后再怎么调都救不回来。
 *
 * <p>症状是 {@code SQLException: No suitable driver found for jdbc:...}，
 * 而且它<b>只在挂 agent 时出现</b>：进程内的单元测试照常通过。
 * 这正是"单测全绿但真机起不来"的又一个实例。
 *
 * <p>{@code Class.forName} 触发驱动的静态初始化，它内部会
 * {@code DriverManager.registerDriver(...)}，于是不再依赖那次扫描。
 * 驱动与调用方都由应用 ClassLoader 加载，{@code DriverManager} 的
 * {@code isDriverAllowed} 校验天然满足。
 */
final class JdbcDriver {

    private JdbcDriver() {
    }

    /**
     * @param className 驱动类名。<b>由字符串指定</b>而不是直接引用类 ——
     *                  这样驱动可以留在 {@code runtime} scope（H2 就是），
     *                  编译期不需要它，运行时缺了则给出一条能看懂的降级说明。
     */
    static void ensure(final String className) {
        try {
            Class.forName(className);
        } catch (final ClassNotFoundException e) {
            throw new IllegalStateException("JDBC 驱动不在 classpath 上：" + className, e);
        }
    }
}