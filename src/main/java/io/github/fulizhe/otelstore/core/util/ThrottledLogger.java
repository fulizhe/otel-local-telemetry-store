package io.github.fulizhe.otelstore.core.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 限速日志：同一个 key 的前 {@value #HEAD_COUNT} 条照打，之后每 {@value #TAIL_EVERY} 条打一条。
 *
 * <p>存在的理由不是"少打点日志"。存储路径上的异常如果每条都打，坏数据（比如某个应用
 * 每秒几千条超长 payload）会把业务日志彻底淹掉 —— 而 AGENTS.md 的头号约束是
 * "监控只能是助力"。不打同样不行：静默的失败比吵闹的失败更难排查。
 *
 * <p><b>计数永远照做</b>，限速只作用于"往 stderr 写几个字"。被限速挡掉的那些仍能在
 * 读口快照里看到总数，所以没有信息真的丢失。
 */
public final class ThrottledLogger {

    private static final Logger LOGGER = Logger.getLogger(ThrottledLogger.class.getName());

    private static final int HEAD_COUNT = 3;
    private static final int TAIL_EVERY = 1000;

    private static final ConcurrentHashMap<String, AtomicInteger> COUNTS =
            new ConcurrentHashMap<String, AtomicInteger>();

    private ThrottledLogger() {
    }

    /**
     * 记一次并按限速决定要不要真打。
     *
     * @param key 口径的名字（如 {@code payload-too-large}）；不同 key 各自计数
     */
    public static void warn(final String key, final String message) {
        warn(key, message, null);
    }

    /**
     * 带异常一起记。
     *
     * <p><b>异常必须走这个重载</b>，不要用 {@code + e} 拼进消息里：
     * {@code Throwable.toString()} 只有一行"类名: 消息"，
     * <b>cause 链整个丢掉</b> —— 而 cause 才是答案所在。
     * 2026-10-04 首次挂 agent 时就是被这一点拖了很久：
     * 日志里只有 {@code ExceptionInInitializerError}，
     * 是靠另外去 agent jar 里查 protobuf 版本才定位到根因的；
     * 有栈的话一眼就能看到 `validateProtobufGencodeVersion` 抛的那一行。
     */
    public static void warn(final String key, final String message, final Throwable cause) {
        final AtomicInteger counter = COUNTS.get(key);
        final int nth = counter == null ? firstCount(key) : counter.incrementAndGet();
        if (shouldLog(nth)) {
            final String text = "[otel-local-telemetry-store] " + message
                    + "（" + key + " 第 " + nth + " 次）";
            if (cause == null) {
                LOGGER.log(Level.WARNING, text);
            } else {
                LOGGER.log(Level.WARNING, text, cause);
            }
        }
    }

    private static int firstCount(final String key) {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger prev = COUNTS.putIfAbsent(key, created);
        if (prev != null) {
            return prev.incrementAndGet();
        }
        created.incrementAndGet();
        return 1;
    }

    /** 限速判定：前 {@value #HEAD_COUNT} 次为真，之后每 {@value #TAIL_EVERY} 次一次。 */
    public static boolean shouldLog(final int nth) {
        return nth <= HEAD_COUNT || nth % TAIL_EVERY == 0;
    }
}
