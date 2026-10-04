package io.github.fulizhe.otelstore.agentext;

import io.github.fulizhe.otelstore.core.collection.RecordQueue;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import io.opentelemetry.sdk.logs.data.LogRecordData;

/**
 * 日志采集口 —— <b>三条路径里唯一会打在业务线程上的一条</b>。
 *
 * <p>{@code onEmit} 是同步回调，因此这里<b>绝不能</b>压缩、写文件或加锁。
 * 正确形态是"包住 autoconfigure 那个 {@code BatchLogRecordProcessor}，另接一条
 * 有界队列 + 独立 drainer"（见 {@code docs/adr/adr-01-scope-and-principles.md}）。
 *
 * <p>注意入参是 SDK 内部的 {@link ReadWriteLogRecord}，它<b>没有</b> {@code getResource()}；
 * 要拿 resource 必须先转成 {@link LogRecordData}。
 */
final class LogTap implements LogRecordProcessor {

    private final RecordQueue<Object> queue;

    LogTap(final RecordQueue<Object> queue) {
        this.queue = queue;
    }

    @Override
    public void onEmit(final Context context, final ReadWriteLogRecord record) {
        if (record == null) {
            return;
        }
        final LogRecordData data = record.toLogRecordData();
        if (data != null) {
            queue.offer(data);
        }
    }
}