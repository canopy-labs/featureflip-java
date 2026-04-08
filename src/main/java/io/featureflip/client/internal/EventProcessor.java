package io.featureflip.client.internal;

import io.featureflip.client.internal.model.SdkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class EventProcessor {
    private static final int MAX_QUEUE_SIZE = 10_000;

    private final ConcurrentLinkedQueue<SdkEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger(0);
    private final int batchSize;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public EventProcessor(int batchSize) {
        this.batchSize = batchSize;
    }

    public void enqueue(SdkEvent event) {
        if (closed.get()) return;
        if (size.get() >= MAX_QUEUE_SIZE) return; // drop events if queue is full
        queue.add(event);
        size.incrementAndGet();
    }

    public List<SdkEvent> drain() {
        List<SdkEvent> events = new ArrayList<>();
        SdkEvent event;
        while ((event = queue.poll()) != null) {
            events.add(event);
        }
        size.addAndGet(-events.size());
        return events;
    }

    public boolean shouldFlush() {
        return size.get() >= batchSize;
    }

    public void close() {
        closed.set(true);
    }
}
