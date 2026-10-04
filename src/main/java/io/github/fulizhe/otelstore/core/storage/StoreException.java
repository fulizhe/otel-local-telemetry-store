package io.github.fulizhe.otelstore.core.storage;

/**
 * 存储层失败。
 *
 * <p><b>unchecked 是有意的</b>：它跑在 {@code RecordQueue} 的 drainer 线程上，
 * 那边只捕获 {@code RuntimeException} 并计进 {@code sinkErrors}（ADR-3 的第 1 种形态）。
 * 写成 checked 异常只会逼调用方在采集路径上写 try/catch，而采集路径<b>不该</b>知道存储会失败。
 */
public class StoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StoreException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public StoreException(final String message) {
        super(message);
    }
}
