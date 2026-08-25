package io.featureflip.client.internal;

import io.featureflip.client.internal.model.SdkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Bounded FIFO buffer holding analytics events between flushes.
 *
 * <p>Backed by an {@link ArrayDeque} under an explicit monitor rather than the
 * {@code ConcurrentLinkedQueue} this used to be: {@link #requeue} has to push a failed
 * batch back at the FRONT, and the bound has to be enforced from the front too — neither is
 * expressible on a concurrent queue. The monitor also retires the separate size counter the
 * old implementation kept in step by hand, since {@code queue.size()} read under the same
 * lock cannot drift from the contents it is describing.
 */
public final class EventProcessor {
    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    /**
     * Upper bound on buffered events.
     *
     * <p>Only reachable once {@link #requeue} starts returning batches faster than they
     * drain — i.e. a sustained outage of the events endpoint. Past the bound the OLDEST
     * events are shed, which caps memory and keeps the freshest analytics. It also means a
     * long outage sheds the re-queued (stale) batches first rather than starving new
     * events, so the SDK degrades to its previous behaviour instead of hoarding data it
     * cannot send.
     */
    public static final int DEFAULT_MAX_QUEUE_SIZE = 10_000;

    /**
     * How many further events may be shed before another warning is emitted.
     *
     * <p>Once the queue is at its bound, every single enqueue sheds one event. A line per
     * shed event would bury the operator in log noise during exactly the outage they are
     * trying to read, so the first drop is logged and then one line per this many.
     */
    private static final long DROP_LOG_INTERVAL = 1_000L;

    private final Object gate = new Object();
    private final Deque<SdkEvent> queue = new ArrayDeque<>();
    private final int batchSize;
    private final int maxQueueSize;

    private boolean closed;            // guarded by gate
    private long totalDropped;         // guarded by gate
    private long nextDropLogAt = 1;    // guarded by gate

    public EventProcessor(int batchSize) {
        this(batchSize, DEFAULT_MAX_QUEUE_SIZE);
    }

    /**
     * Overload taking the queue bound, so tests can reach it without buffering 10,000 events.
     *
     * @param batchSize    queue length at which {@link #shouldFlush()} starts returning true
     * @param maxQueueSize upper bound on buffered events; non-positive means the default
     */
    public EventProcessor(int batchSize, int maxQueueSize) {
        this.batchSize = batchSize;
        this.maxQueueSize = maxQueueSize > 0 ? maxQueueSize : DEFAULT_MAX_QUEUE_SIZE;
    }

    public void enqueue(SdkEvent event) {
        synchronized (gate) {
            if (closed) return;
            queue.addLast(event);
            if (trimToBoundLocked() > 0) {
                logDropsLocked();
            }
        }
    }

    /**
     * Removes and returns everything queued.
     *
     * <p>Still permitted after {@link #close()} — shutdown's one final flush attempt has to
     * be able to read the buffer it is trying to deliver.
     *
     * @return the buffered events in the order they were recorded, oldest first
     */
    public List<SdkEvent> drain() {
        return drainBatch(Integer.MAX_VALUE);
    }

    /**
     * Removes and returns at most {@code maxCount} of the oldest events.
     *
     * <p>The flush path sends one request per batch rather than one for the whole queue.
     * Re-queuing failures (#2456) is what lets the queue grow towards its bound during an
     * outage, and posting 10,000 events in a single body risks a server rejecting it
     * outright — a 413 is not retryable, so the whole backlog would be dropped by the very
     * path that exists to preserve it.
     *
     * @param maxCount most events to remove; values below 1 are treated as 1
     * @return up to {@code maxCount} events, oldest first
     */
    public List<SdkEvent> drainBatch(int maxCount) {
        int limit = Math.max(maxCount, 1);
        synchronized (gate) {
            if (queue.isEmpty()) return Collections.emptyList();
            int take = Math.min(limit, queue.size());
            List<SdkEvent> events = new ArrayList<>(take);
            for (int i = 0; i < take; i++) {
                events.add(queue.removeFirst());
            }
            return events;
        }
    }

    /**
     * Returns a batch that failed to send to the FRONT of the queue, so the next flush
     * retries it ahead of newer events and rough chronological order is preserved.
     *
     * <p>{@link #drainBatch(int)} removes the batch before it is sent, so without this a
     * single transient failure discarded it outright (#2456). Callers must only re-queue
     * failures a retry could plausibly fix — a rejected SDK key would otherwise be retried
     * forever and pin the queue at its bound.
     *
     * @param events the batch that failed to send
     * @return how many events were dropped rather than kept, so the caller can say so
     */
    public int requeue(List<SdkEvent> events) {
        if (events == null || events.isEmpty()) return 0;

        synchronized (gate) {
            // Closed means shutdown is under way and nothing will drain this again; report
            // the whole batch as dropped rather than growing a queue no one will read.
            if (closed) return events.size();

            for (int i = events.size() - 1; i >= 0; i--) {
                queue.addFirst(events.get(i));
            }
            return trimToBoundLocked();
        }
    }

    public boolean shouldFlush() {
        synchronized (gate) {
            return queue.size() >= batchSize;
        }
    }

    /**
     * Stops accepting new events. {@link #drain()} still works; {@link #requeue} does not,
     * because after this nothing will flush again.
     */
    public void close() {
        synchronized (gate) {
            closed = true;
        }
    }

    /** Sheds oldest-first until the queue fits the bound. Caller holds {@code gate}. */
    private int trimToBoundLocked() {
        int dropped = 0;
        while (queue.size() > maxQueueSize) {
            queue.removeFirst();
            dropped++;
        }
        totalDropped += dropped;
        return dropped;
    }

    /** Caller holds {@code gate}. */
    private void logDropsLocked() {
        if (totalDropped < nextDropLogAt) return;
        log.warn("Event queue is full at {} events; {} of the oldest analytics events have been "
            + "shed. The events endpoint is likely failing.", maxQueueSize, totalDropped);
        nextDropLogAt = totalDropped + DROP_LOG_INTERVAL;
    }
}
