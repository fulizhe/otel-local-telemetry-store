package io.github.fulizhe.otelstore.readout.jmx;

import io.github.fulizhe.otelstore.core.config.LocalStoreConfig;
import io.github.fulizhe.otelstore.core.storage.LocalStore;
import io.github.fulizhe.otelstore.core.util.ThrottledLogger;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.function.Supplier;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * 把 {@link LocalStoreSummary} 注册到平台 MBean 服务器。
 *
 * <p><b>为什么 JMX 是唯一可行的跨 ClassLoader 通道</b>（ADR-1 原则与 R0 笔记第五节的实测）：
 * 我们的代码在 {@code ExtensionClassLoader} 上，它既不挂在应用 {@code AppClassLoader} 上、
 * 也拿不到 {@code AgentClassLoader} 里的类；而 {@code extension-api} 里没有
 * {@code InstrumentationAccess}，所以既不能 {@code appendToSystemClassLoaderSearch}，
 * 也不能靠反射 {@code appendToClassPathForInstrumentation}（JDK 17 要 {@code --add-opens}）。
 * JMX 是平台级的，不受 ClassLoader 边界影响 —— 这是唯一剩下的一条路。
 *
 * <p><b>注册失败只记不抛</b>：同名的 MBean 已存在（同一 JVM 里起了两个实例）属于配置问题，
 * 不该让应用起不来。此时读口降级为"只有启动日志与数据目录里的文件"。
 */
public final class JmxReadout {

    /** ObjectName 的域与键。稳定不变，运维可以把它写进文档与告警规则。 */
    public static final String OBJECT_NAME = "io.github.fulizhe.otelstore:name=LocalStoreSummary";

    private JmxReadout() {
    }

    /**
     * 注册；返回是否成功。
     *
     * @param store 存储层；为 null 时不注册（存储没开起来，挂一个只会报 0 的 MBean 反而误导）
     */
    public static boolean register(final LocalStoreConfig config, final LocalStore store,
            final Supplier<Map<String, Object>> queuesSnapshot) {
        if (store == null) {
            return false;
        }
        try {
            final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            final ObjectName name = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(name)) {
                ThrottledLogger.warn("jmx-already-registered",
                        "JMX 读口已注册过 " + OBJECT_NAME + "，本次不重复注册（同一 JVM 里有两个实例？）");
                return false;
            }
            server.registerMBean(new LocalStoreSummary(config, store, queuesSnapshot), name);
            return true;
        } catch (final Exception e) {
            // JMX 被禁用（JMX remote 没开）或权限不足：读口降级，但扩展其余部分照常
            ThrottledLogger.warn("jmx-register-failed", "JMX 读口注册失败，读口降级（数据照存）：" + e);
            return false;
        }
    }
}
