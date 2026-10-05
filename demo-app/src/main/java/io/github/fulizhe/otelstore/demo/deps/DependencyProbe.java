package io.github.fulizhe.otelstore.demo.deps;

/**
 * 一个外部依赖的<b>探测</b>。
 *
 * <p>存在的理由：五类依赖的"能不能用"必须在<b>调用之前</b>就知道 ——
 * 链路图上少一跳时，调用端点返回"我降级了"和读口图上"就是没埋点"长得一模一样，
 * 只有提前探过一次才能把这两件事分开（ADR-7）。
 */
public interface DependencyProbe {

    /**
     * 依赖的 key。<b>它必须在探测之前就能给出</b> ——
     * 否则探测抛异常时，兜底造出来的状态就没有名字，
     * 而 {@code /demo/deps/status} 里少一项对得上名字的降级记录，
     * 等于让人在"这一项没接入"和"这一项掉了"之间猜。
     */
    String key();

    /** 给人看的一行标题（技术名 + 进程内还是外部）。 */
    String title();

    /** true 表示服务端在进程内起；false 表示它要连外部（本项目里只有 MySQL，ADR-7）。 */
    boolean embedded();

    /**
     * 探测一次。连得上就返回 ready 的状态，连不上返回 not-ready 并<b>说清为什么</b>。
     *
     * <p><b>实现绝不允许抛异常</b>：探测发生在启动路径上，
     * 而 demo-app 的姿态是"起不来就降级、进程照常启动"。
     * {@link DepsRegistry} 会再兜一层，但兜底不该是常规路径。
     */
    DepStatus probe();
}