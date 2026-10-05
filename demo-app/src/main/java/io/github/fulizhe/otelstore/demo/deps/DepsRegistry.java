package io.github.fulizhe.otelstore.demo.deps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 五类依赖的探测结果，<b>启动时探测一次</b>。
 *
 * <p>三条口径是 ADR-7 定的，写在这里是免得后面四跳各自发明一套：
 *
 * <ul>
 *   <li><b>探测只做一次</b>，不重试。调用端点每次都重新试，
 *       但"能不能用"这个判断留在启动时 —— 它答的是"我该不该指望它"，不是"现在通不通"。
 *   <li><b>探不通不抛异常、不阻塞启动</b>。demo-app 的进程照常起来。
 *   <li><b>ready=false 必须带 detail</b>，且 detail 要能区分"没接入"与"接入了但起不来"：
 *       前者是靶子还没做完，后者是真有东西坏了。
 * </ul>
 *
 * <p>为什么值得单独一个类：链路图上少一跳时，人分不清"埋点没挂上"与"库没起来" ——
 * 两种情况在图上长得一模一样。只有提前探过一次，才能在调用端点的响应里说清是哪一种。
 */
public final class DepsRegistry {

    /** 依赖 key。放在这里而不是各处的字面量，是为了让五跳的拼写只有一个来源。 */
    public static final String H2 = "h2";
    public static final String REDIS = "redis";
    public static final String KAFKA = "kafka";
    public static final String GRPC = "grpc";
    public static final String MYSQL = "mysql";

    private final Map<String, DepStatus> byKey = new LinkedHashMap<String, DepStatus>();
    private final List<DepStatus> ordered = new ArrayList<DepStatus>();

    /**
     * @param probes 五类依赖的探测，<b>按要展示的顺序传</b>（页面上就按这个顺序列）。
     */
    public DepsRegistry(final List<DependencyProbe> probes) {
        for (final DependencyProbe probe : probes) {
            final DepStatus status = probeOnce(probe);
            byKey.put(status.key(), status);
            ordered.add(status);
        }
    }

    /**
     * 探测一次，并把任何意外都压成 not-ready。
     *
     * <p>这一层兜底看着多余 —— 接口注释已经要求 probe() 不抛异常。但它在<b>启动路径</b>上：
     * 一个本该降级的依赖因为探测代码里的一个 NPE 把应用搞崩，是最难查的一类故障。
     *
     * <p>兜底出来的状态**仍然带 key / title / embedded**（从 probe 上取，
     * 而不是从它的返回值上取）—— 降级的那一项在 API 里必须对得上名字。
     */
    private static DepStatus probeOnce(final DependencyProbe probe) {
        final String key = safeKey(probe);
        final String title = probe.title();
        final boolean embedded = probe.embedded();
        try {
            final DepStatus status = probe.probe();
            if (status == null) {
                return DepStatus.notReady(key, title, embedded, "探测返回了 null —— 探测实现有 bug");
            }
            // 以 registry 自己取到的 key 为准：若探测返回的状态带了别的 key，
            // 会出现"status 里查得到、列表里查不到"的两份口径。
            return new DepStatus(key, title, embedded, status.ready(), status.detail());
        } catch (final Throwable t) {
            // 故意 catch Throwable 而不是 Exception：探测里的 NPE / NoClassDefFoundError
            // 同样该被压成"这个依赖不可用"，而不是让靶子起不来。
            return DepStatus.notReady(key, title, embedded,
                    "探测时抛异常（已经降级，应用照常启动）：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : " — " + t.getMessage()));
        }
    }

    private static String safeKey(final DependencyProbe probe) {
        try {
            final String k = probe.key();
            return k == null ? "?" : k;
        } catch (final Throwable t) {
            return "?";
        }
    }

    /** 按注册顺序的全量状态，页面与 {@code /demo/deps/status} 就按这个顺序返回。 */
    public List<DepStatus> all() {
        return Collections.unmodifiableList(ordered);
    }

    /** 单项状态；没有这一项时返回 null（调用方自己决定怎么表述"没有"，不静默当成不可用）。 */
    public DepStatus get(final String key) {
        return byKey.get(key);
    }
}