package io.github.fulizhe.otelstore.demo.deps;

/**
 * 一个依赖的可用性。<b>{@code ready} 与 {@code detail} 两者缺一不可</b> ——
 * 只给 ready 的话，调用端点只能说"连不上"，人无法判断是库没起来、地址写错了、还是驱动缺了。
 */
public final class DepStatus {

    private final String key;
    private final String title;
    private final boolean embedded;
    private final boolean ready;
    private final String detail;

    /** {@code ready} 与 {@code detail} 不许被分开设 —— 省得拼出"说了 ready 但不解释"。 */
    public DepStatus(final String key, final String title, final boolean embedded,
                     final boolean ready, final String detail) {
        this.key = key;
        this.title = title;
        this.embedded = embedded;
        this.ready = ready;
        this.detail = detail;
    }

    public static DepStatus ready(final String key, final String title, final boolean embedded,
                                  final String detail) {
        return new DepStatus(key, title, embedded, true, detail);
    }

    public static DepStatus notReady(final String key, final String title, final boolean embedded,
                                     final String detail) {
        return new DepStatus(key, title, embedded, false, detail);
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    /** true 表示服务端在进程内起；false 表示它要连外部（本项目里只有 MySQL，ADR-7）。 */
    public boolean embedded() {
        return embedded;
    }

    public boolean ready() {
        return ready;
    }

    public String detail() {
        return detail;
    }

    @Override
    public String toString() {
        return key + "=" + (ready ? "ready" : "not-ready") + " (" + detail + ")";
    }
}