package io.github.fulizhe.otelstore.core.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 扩展<b>自己</b>的日志落一个文件，并让读口能读它的尾部（ADR-6 第九节）。
 *
 * <p>为什么单独落一个文件：排障时不用去翻应用的日志 —— 扩展自己那几行
 * （降级、限速告警、周期汇总）混在别人的日志里很难找。而<b>只读扩展自己写的这一个文件</b>：
 * stderr 落到哪个文件由启动方决定，扩展无从得知；为了展示自己的状态去读别人的日志
 * 等于伸手到不该碰的地方，还会把应用日志内容复制到读口页面上、直接扩大暴露面。
 *
 * <p><b>为什么不用 {@code java.util.logging.FileHandler}</b>：实测它的 {@code count}
 * 是文件总数而不是备份份数 —— {@code count=1} 时超限只是把同一个文件截断（没有备份），
 * {@code count=2} 时文件名被 JDK 改成 {@code .0}/{@code .1}（文档写明的
 * {@code <dataDir>/otelstore.log} 不存在）。所以这里自己实现一个
 * {@link Handler} 子类：固定文件名、1 MiB 一份、只留 1 份备份。数字见
 * {@code docs/notes/2026-10-06-self-log-filehandler-probe.md}。
 *
 * <p>三条自我约束：
 * <ul>
 *   <li><b>绝不外抛</b>。扩展自己的日志文件不该把客户的业务线程搞挂；同样的内容仍然在 stderr。</li>
 *   <li><b>零三方依赖</b>。用的全是 JDK 的 {@code java.util.logging} 与 {@code java.io}。</li>
 *   <li><b>有界</b>。单文件 1 MiB、只留 1 份备份，合计 ≤ 2 MiB（ADR-1 第一原则）。</li>
 * </ul>
 */
public final class SelfLog {

    /** 当前日志文件名，落在数据目录下。 */
    public static final String FILE_NAME = "otelstore.log";

    /** 轮转出来的那一份备份。 */
    public static final String BACKUP_FILE_NAME = FILE_NAME + ".1";

    /** 单文件上限：1 MiB。 */
    public static final long LIMIT_BYTES = 1024L * 1024L;

    /** 挂 handler 的 logger 名。扩展的所有 logger 都在它下面。 */
    public static final String LOGGER_NAME = "io.github.fulizhe.otelstore";

    /**
     * 读尾部时最多回读的字节数。
     *
     * <p>文件本身有界（≤ 2 MiB），这个上限只是为了"文件被外部撑大"时不至于把读口拖垮。
     */
    private static final long MAX_TAIL_READ_BYTES = 8L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    /** 当前挂着的 handler 与它所在的 logger；{@link #attach} 会先摘掉上一个，避免叠 handler。 */
    private static Handler attached;
    private static Logger attachedTo;

    private SelfLog() {
    }

    /**
     * 把扩展自己的 logger 挂到 {@code <dataDir>/otelstore.log}。
     *
     * <p>每次调用都会先摘掉上一次挂上的（进程内只应该有一个），所以重复调用不会叠起来。
     *
     * @return 挂上的 handler；数据目录建不出来或挂不上时为 {@code null}（只记日志，不抛）
     */
    public static Handler attach(final File dataDir) {
        return attach(LOGGER_NAME, dataDir, LIMIT_BYTES);
    }

    /** 测试缝：换一个 logger 名与上限，避免污染真实命名空间、也不用写满 1 MiB。 */
    static Handler attach(final String loggerName, final File dataDir, final long limitBytes) {
        synchronized (LOCK) {
            detach();
            if (dataDir == null) {
                return null;
            }
            if (!dataDir.isDirectory() && !dataDir.mkdirs() && !dataDir.isDirectory()) {
                ThrottledLogger.warn("self-log-dir",
                        "建不了数据目录，扩展自己的日志不落文件：" + dataDir);
                return null;
            }
            final RollingFileHandler handler = new RollingFileHandler(
                    new File(dataDir, FILE_NAME), new File(dataDir, BACKUP_FILE_NAME), limitBytes);
            handler.setFormatter(new OneLineFormatter());
            handler.setLevel(Level.ALL);
            final Logger logger = Logger.getLogger(loggerName);
            logger.addHandler(handler);
            // 兜底：agent 可能把 JUL 的根 logger 提到 WARNING。把本命名空间显式放回 INFO，
            // 否则启动那几行 INFO（"已注册三条采集管线"）不会进这个文件。
            if (logger.getLevel() == null) {
                logger.setLevel(Level.INFO);
            }
            attached = handler;
            attachedTo = logger;
            return handler;
        }
    }

    /** 摘掉 {@link #attach} 挂上的 handler。进程内没有挂过时是空操作。 */
    public static void detach() {
        synchronized (LOCK) {
            if (attachedTo != null && attached != null) {
                attachedTo.removeHandler(attached);
            }
            if (attached != null) {
                attached.close();
            }
            attached = null;
            attachedTo = null;
        }
    }

    /**
     * 读 {@code <dataDir>/otelstore.log} 的尾部若干行（旧 → 新）。
     *
     * <p>文件不存在、是空的、或读失败都返回<b>空列表</b>，不抛 ——
     * "被外部删掉/截断"是部署与运维的常态，接口要能说"暂无内容"而不是 500。
     *
     * @param maxLines 最多返回多少行；非正数返回空列表
     */
    public static List<String> tail(final File dataDir, final int maxLines) {
        if (dataDir == null || maxLines <= 0) {
            return Collections.emptyList();
        }
        return tailFile(new File(dataDir, FILE_NAME), maxLines);
    }

    /** 测试缝：直接读某个文件，绕过 {@link #FILE_NAME} 的解析。 */
    static List<String> tailFile(final File file, final int maxLines) {
        if (file == null || maxLines <= 0 || !file.isFile()) {
            return Collections.emptyList();
        }
        try {
            final long len = file.length();
            if (len <= 0L) {
                // 被截断成 0 字节：暂无内容，不是错误
                return Collections.emptyList();
            }
            final long start = Math.max(0L, len - MAX_TAIL_READ_BYTES);
            final byte[] data = new byte[(int) (len - start)];
            final RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                raf.seek(start);
                raf.readFully(data);
            } finally {
                raf.close();
            }
            final List<String> lines = splitLines(data);
            if (lines.size() <= maxLines) {
                return lines;
            }
            return new ArrayList<String>(lines.subList(lines.size() - maxLines, lines.size()));
        } catch (final IOException e) {
            ThrottledLogger.warn("self-log-read", "读扩展日志尾部失败：" + file, e);
            return Collections.emptyList();
        } catch (final OutOfMemoryError e) {
            // 文件被外部撑到超乎想象时，宁可"暂无内容"也不拖垮读口
            return Collections.emptyList();
        }
    }

    /**
     * 按 {@code \n} 切行、去掉行尾 {@code \r}，用平台默认字符集解码
     * （写入端也是平台默认，所以同进程内读写永远一致）。
     *
     * <p>不引 BufferedReader：它是字符流，而我们手里已经是字节，按 {@code \n}（ASCII）
     * 切完再解码既够用又不会把多字节字符切断。
     */
    private static List<String> splitLines(final byte[] data) {
        final Charset cs = Charset.defaultCharset();
        final List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                int end = i;
                if (end > start && data[end - 1] == '\r') {
                    end--;
                }
                out.add(new String(data, start, end - start, cs));
                start = i + 1;
            }
        }
        // 最后一行可能没有换行符（进程被强杀时就是这样）
        if (start < data.length) {
            int end = data.length;
            if (end > start && data[end - 1] == '\r') {
                end--;
            }
            out.add(new String(data, start, end - start, cs));
        }
        return out;
    }

    /**
     * 一行一条记录：{@code 时间 级别 短类名 消息}，异常另起若干行。
     *
     * <p>为什么不用 {@code SimpleFormatter}：它一条记录占<b>两行</b>（头部 + 消息），
     * 而"/api/self-log 读尾部 N 行"里的"N 行"是按物理行算的 —— 两行的形态会让页面上
     * 一半是头部、一半是消息。这里压成一行，异常（少见）才展开。
     */
    private static final class OneLineFormatter extends Formatter {

        private static final DateTimeFormatter TIMESTAMP =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                        .withZone(ZoneId.systemDefault());

        @Override
        public String format(final LogRecord record) {
            final StringBuilder sb = new StringBuilder(96);
            sb.append(TIMESTAMP.format(Instant.ofEpochMilli(record.getMillis())));
            sb.append(' ').append(record.getLevel().getName());
            sb.append(' ').append(shortName(record.getLoggerName()));
            sb.append(' ');
            // 用 getMessage() 而不是 formatMessage()：本项目的消息都是已经拼好的字符串，
            // formatMessage 走 MessageFormat，消息里出现花括号会被当成占位符。
            final String message = record.getMessage();
            sb.append(message == null ? "" : message);
            sb.append(System.lineSeparator());
            if (record.getThrown() != null) {
                final StringWriter sw = new StringWriter();
                final PrintWriter pw = new PrintWriter(sw);
                record.getThrown().printStackTrace(pw);
                pw.flush();
                sb.append(sw.toString());
            }
            return sb.toString();
        }

        private static String shortName(final String name) {
            if (name == null) {
                return "?";
            }
            final int dot = name.lastIndexOf('.');
            return dot >= 0 ? name.substring(dot + 1) : name;
        }
    }

    /**
     * 固定文件名、有界轮转的 JDK handler。
     *
     * <p><b>每次写都开/关文件</b>（而不是长期持有句柄）：扩展自己的日志量很小
     * （限速后每类只打前几条），换来的是"文件不会被本进程锁住"——
     * Windows 上被锁住的日志文件删不掉、也挪不动，而验收里恰好有一条
     * "文件被外部删掉时接口要说暂无内容"。写一次 flush 一次，所以同进程内读到的一定是最新的。
     */
    private static final class RollingFileHandler extends Handler {

        private final File current;
        private final File backup;
        private final long limitBytes;
        private volatile boolean closed;

        RollingFileHandler(final File current, final File backup, final long limitBytes) {
            this.current = current;
            this.backup = backup;
            this.limitBytes = limitBytes;
        }

        @Override
        public synchronized void publish(final LogRecord record) {
            if (closed || record == null || !isLoggable(record)) {
                return;
            }
            final String text;
            try {
                text = getFormatter().format(record);
            } catch (final RuntimeException e) {
                // 格式化失败（不该发生）也不外抛：日志路径绝不影响业务线程
                return;
            }
            if (text == null || text.isEmpty()) {
                return;
            }
            final byte[] bytes = text.getBytes(Charset.defaultCharset());
            try {
                final File parent = current.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    parent.mkdirs();
                }
                if (limitBytes > 0L && current.length() > 0L
                        && current.length() + bytes.length > limitBytes) {
                    try {
                        rotate();
                    } catch (final IOException e) {
                        // 轮转不动就退一步：清空当前文件，至少不让它无限涨
                        current.delete();
                    }
                }
                append(bytes);
            } catch (final IOException e) {
                // 写不进就写不进：同样的内容仍然在 stderr（useParentHandlers 默认 true）
            }
        }

        /** 删掉旧备份、把当前文件改名成备份。调用前必须先确保没有打开的句柄。 */
        private void rotate() throws IOException {
            if (backup.exists() && !backup.delete()) {
                throw new IOException("删不掉旧备份 " + backup);
            }
            if (current.exists() && !current.renameTo(backup)) {
                throw new IOException("轮转改名失败 " + current);
            }
        }

        private void append(final byte[] bytes) throws IOException {
            final FileOutputStream out = new FileOutputStream(current, true);
            try {
                out.write(bytes);
                out.flush();
            } finally {
                out.close();
            }
        }

        @Override
        public synchronized void flush() {
            // 每次 publish 都已经 flush 过，没有需要额外刷的东西
        }

        @Override
        public synchronized void close() {
            closed = true;
        }
    }
}
