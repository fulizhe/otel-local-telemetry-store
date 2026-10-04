package io.github.fulizhe.otelstore.agentext;

import io.github.fulizhe.otelstore.core.collection.RecordQueue;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * span 采集口。
 *
 * <p>{@link #onEnd(ReadableSpan)} 跑在<b>业务线程</b>上（若上游用的是 SimpleProcessor）或
 * SDK 的处理线程上（BatchSpanProcessor）。无论哪种，这里只做一次 {@code toSpanData()} + 一次
 * {@code offer}，**不压缩、不写文件、不加锁**。
 */
final class SpanTap implements SpanProcessor {

    private final RecordQueue<Object> queue;

    SpanTap(final RecordQueue<Object> queue) {
        this.queue = queue;
    }

    @Override
    public void onStart(final Context parentContext, final ReadWriteSpan span) {
        // 不需要开始事件
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(final ReadableSpan span) {
        if (span == null) {
            return;
        }
        final SpanData data = span.toSpanData();
        if (data != null) {
            queue.offer(data);
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }
}