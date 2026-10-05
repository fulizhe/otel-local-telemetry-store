package io.github.fulizhe.otelstore.demo.deps;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.fulizhe.otelstore.demo.stats.GeneratedSignals;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 五类依赖的调用入口。
 *
 * <p><b>这里没有一行"自己开 span"的代码，而且这是本项目的核心断言</b>：
 * 所有 CLIENT span 都必须由 agent 的仪表化产生。调用发生在 Spring MVC handler 线程里，
 * 而 Tomcat 仪表化已经把那个 server span 设成了 current，所以 CLIENT span 会自动挂上去。
 * 自己开 span 的话，测的就是自己的代码而不是仪表化 —— 那这条链路样例就白做了。
 */
public final class DepsDemoService {

    private static final Logger LOG = LoggerFactory.getLogger("otelstore.demo.deps");

    private final DepsRegistry registry;
    private final H2Dependency h2;
    private final RedisDependency redis;
    private final GeneratedSignals stats;

    public DepsDemoService(final DepsRegistry registry, final H2Dependency h2,
                           final RedisDependency redis, final GeneratedSignals stats) {
        this.registry = registry;
        this.h2 = h2;
        this.redis = redis;
        this.stats = stats;
    }

    /** 五项状态，页面与 {@code /demo/deps/status} 都从这里取，不自己再拼一份。 */
    public List<DepStatus> status() {
        return registry.all();
    }

    /**
     * 打一次 H2 那一跳。
     *
     * <p><b>降级时返回的仍然是 HTTP 200</b>，理由与 ADR-7 一致：
     * 「这个依赖没起来」是一个正常的、可预期的状态（MySQL 那一跳尤其如此），
     * 而 500 会让调用方分不清"依赖没起来"与"服务坏了"。
     */
    public Map<String, Object> callH2() {
        final DepStatus status = registry.get(H2Dependency.KEY);
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dependency", H2Dependency.KEY);
        m.put("traceId", currentTraceId());

        if (status == null || !status.ready()) {
            return degrade(m, status);
        }
        try {
            // 真正发 SQL 的就是这一句。**不要为了"让图更好看"在这里加自己的 span。**
            final int rows = h2.queryOrders();
            stats.addDepCall(H2Dependency.KEY, true);
            m.put("ok", Boolean.TRUE);
            m.put("rows", Integer.valueOf(rows));
            m.put("note", "这一跳的 span 全部来自 agent 的 JDBC 仪表化，代码里没有自己开 span");
            return m;
        } catch (final SQLException e) {
            // 探测时是好的、调用时失败 —— 那是真的坏了，所以这里要说得比"探测没过"更重。
            stats.addDepCall(H2Dependency.KEY, false);
            LOG.warn("H2 调用失败（探测时是通的）", e);
            m.put("ok", Boolean.FALSE);
            m.put("error", String.valueOf(e.getMessage()));
            m.put("note", "启动探测时这一项是 ready 的，现在失败了 —— 说明是运行期问题，不是没接入");
            return m;
        }
    }

    /**
     * 打一次 Redis 那一跳（SET / GET / DEL）。
     *
     * <p>它与 H2 那一跳是<b>完全不同的一种仪表化</b>，所以值得单独一跳：
     * 图上会出现 {@code scopeName=jedis}，而那是五个来源里第一个非 JDBC 的。
     */
    public Map<String, Object> callRedis() {
        final DepStatus status = registry.get(RedisDependency.KEY);
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dependency", RedisDependency.KEY);
        m.put("traceId", currentTraceId());

        if (status == null || !status.ready()) {
            return degrade(m, status);
        }
        try {
            // SET / GET / DEL 各是一次真实的客户端调用 —— span 来自 Jedis 的方法，
            // 不是我们自己开的。**不要为了"让图更好看"加自己的 span。**
            m.put("ops", redis.call());
            stats.addDepCall(RedisDependency.KEY, true);
            m.put("ok", Boolean.TRUE);
            m.put("note", "这一跳的 span 来自 agent 的 Jedis 仪表化；"
                    + "用 starter 的 Lettuce 6.x 会静默不生效（见 README）");
            return m;
        } catch (final RuntimeException e) {
            // Jedis 连不上抛的是 JedisConnectionException（RuntimeException），不走 SQLException
            stats.addDepCall(RedisDependency.KEY, false);
            LOG.warn("Redis 调用失败（探测时是通的）", e);
            m.put("ok", Boolean.FALSE);
            m.put("error", String.valueOf(e.getMessage()));
            m.put("note", "启动探测时这一项是 ready 的，现在失败了 —— 说明是运行期问题");
            return m;
        }
    }

    /**
     * 统一的降级响应。
     *
     * <p>{@code reason} 分三种说法，因为它们要处理的事完全不同：
     * 没接入 / 探不通 / 调用时失败。混成一句"不可用"的话，
     * 人就会在"靶子还没做完"和"真有东西坏了"之间猜。
     */
    private Map<String, Object> degrade(final Map<String, Object> m, final DepStatus status) {
        stats.addDepCall(String.valueOf(m.get("dependency")), false);
        m.put("ok", Boolean.FALSE);
        if (status == null) {
            m.put("reason", "not-registered");
            m.put("detail", "这一项还没有注册探测");
        } else {
            m.put("reason", status.ready() ? "call-failed" : "not-ready");
            m.put("detail", status.detail());
        }
        m.put("note", "这是预期行为，不是故障：进程照常起，其余几跳照常打（ADR-7）");
        return m;
    }

    /**
     * 当前 traceId，让调用方能直接拿去 {@code /api/traces?traceId=} 对账，
     * 不用从页面上抠那串 32 位十六进制。
     *
     * <p>读 current span <b>不等于自己开 span</b> —— 这里只取 id，不创建。
     * 没有 current span 时给空串而不是全 0，因为全 0 是一个合法的 traceId 形状，
     * 拿去查库会让人以为"库里有一条全 0 的 trace"。
     */
    private static String currentTraceId() {
        final Span cur = Span.current();
        final String id = cur.getSpanContext().getTraceId();
        return Span.current().getSpanContext().isValid() ? id : "";
    }
}