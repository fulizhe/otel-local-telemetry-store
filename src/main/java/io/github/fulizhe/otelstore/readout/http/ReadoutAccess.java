package io.github.fulizhe.otelstore.readout.http;

import io.github.fulizhe.otelstore.core.util.ThrottledLogger;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.security.SecureRandom;

/**
 * 读口的两个"落盘物"：实际端口文件、以及启用鉴权时的访问 token 文件。
 *
 * <p>放在一起是因为它们是同一类东西：**读口开起来了，需要让外部知道它开在哪、
 * 怎么访问**。两个都是"每次启动覆盖写" —— 残留的旧文件比没有文件更坏，
 * 它会报出一个错的端口或一个已经失效的 token。
 *
 * <p><b>token 绝不出现在任何日志、快照或异常消息里</b>（ADR-1）。
 * 这个类里唯一的日志调用只打文件名，不打内容。
 */
public final class ReadoutAccess {

    /** 端口文件名。写在数据目录下，运维与脚本都能直接找。 */
    public static final String PORT_FILE = "otelstore.port";

    /** token 文件名。仅在启用鉴权时写。 */
    public static final String TOKEN_FILE = "otelstore.token";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ReadoutAccess() {
    }

    /**
     * 写实际端口。{@code File} 的父目录必须已存在（数据目录由存储层建好）。
     *
     * @return 写成功返回 true；失败只记日志不抛 —— 读口已经起来了，
     *         不能因为"写不了一个提示文件"把服务撤掉
     */
    public static boolean writePort(final File dataDir, final int port) {
        try {
            return writeFile(new File(dataDir, PORT_FILE), String.valueOf(port));
        } catch (final IOException e) {
            ThrottledLogger.warn("port-file-failed",
                    "实际端口写不进文件（读口仍在 " + port + " 端口上），见 " + PORT_FILE, e);
            return false;
        }
    }

    /**
     * 生成访问 token 并写入文件。
     *
     * <p>用 {@link SecureRandom} 而不是 {@code Random}：这个值唯一的用途就是
     * "决定谁能读你的 SQL 语句与日志原文"，可预测的 token 等于没有 token。
     *
     * @return token 明文；调用方负责只在请求头里比对，**不要打日志**
     */
    public static String generateToken(final File dataDir) {
        final byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            final int v = bytes[i] & 0xFF;
            sb.append(hex(v >>> 4)).append(hex(v & 0x0F));
        }
        final String token = sb.toString();
        try {
            writeFile(new File(dataDir, TOKEN_FILE), token);
        } catch (final IOException e) {
            // 写不进去是部署问题，但要打出来 —— 否则用户会以为 token 在文件里而找不到
            ThrottledLogger.warn("token-file-failed",
                    "token 写不进文件（读口仍然要求 token），见 " + TOKEN_FILE, e);
        }
        return token;
    }

    /**
     * 把**显式配置**的 token 落盘。
     *
     * <p>与 {@link #generateToken} 分开是因为语义不同：那个是"我们造的"，
     * 这个是"用户给的" —— 落盘失败时的排查方向也不一样。
     */
    public static void writeToken(final File dataDir, final String token) throws IOException {
        writeFile(new File(dataDir, TOKEN_FILE), token);
    }

    /**
     * 常量时间比对两个字符串。
     *
     * <p>逐字符比较且**不提前返回**：提前返回会让"前 N 位对不对"这件事可被计时区分，
     * 而 token 就是要挡"能反复试"的人。长度不同直接返回 false —— 长度本身不是秘密。
     */
    public static boolean tokenMatches(final String expected, final String presented) {
        if (expected == null || presented == null || expected.length() != presented.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ presented.charAt(i);
        }
        return diff == 0;
    }

    private static boolean writeFile(final File file, final String content) throws IOException {
        final RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.setLength(0L);
            final byte[] bytes = content.getBytes(UTF8);
            raf.write(bytes);
            raf.getChannel().force(true);
        } finally {
            raf.close();
        }
        return true;
    }

    private static char hex(final int v) {
        return (char) (v < 10 ? ('0' + v) : ('a' + v - 10));
    }
}