package io.featureflip.client.internal;

import io.featureflip.client.SnapshotStore;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Writes the in-memory state to a {@link SnapshotStore} without ever blocking init or
 * evaluation.
 *
 * <p>Writes are coalesced: at most one {@code put} in flight plus one trailing write, which
 * reads the state when it runs. A write runs on a short-lived daemon thread. Closing neither
 * waits for nor cancels a pending write, and no trailing write starts afterwards. A hung
 * {@code put} blocks later writes for that client (a known limit).
 *
 * <p>While the client is live, an unchanged state is rewritten (refreshing {@code fetchedAt})
 * at most once per {@link #REFRESH_INTERVAL_MS}, measured from the START of the last write.
 * Changed content is never throttled.
 */
public final class SnapshotPersister {
    private static final RedactingLogger log = RedactingLogger.get(SnapshotPersister.class);

    /** While live, an unchanged state is rewritten at most this often. */
    public static final long REFRESH_INTERVAL_MS = 300_000L;

    private final SnapshotStore store;
    private final String keyHash;
    private final StateSerializer serializer;
    private final BooleanSupplier live;
    private final LongSupplier wallMillis;
    private final LongSupplier monotonicNanos;
    private final Consumer<Runnable> spawner;
    private final Consumer<String> warn;

    private final Object lock = new Object();
    private boolean inFlight;
    private boolean queued;
    private boolean closed;
    private boolean hasWritten;
    private long lastWriteStartNanos;
    private String lastData;

    /** Produces the {@code data} text for the current state. */
    @FunctionalInterface
    public interface StateSerializer {
        String serialize() throws Exception;
    }

    public SnapshotPersister(SnapshotStore store, String keyHash, StateSerializer serializer,
                             BooleanSupplier live, LongSupplier wallMillis, LongSupplier monotonicNanos,
                             Consumer<Runnable> spawner, Consumer<String> warn) {
        this.store = store;
        this.keyHash = keyHash;
        this.serializer = serializer;
        this.live = live;
        this.wallMillis = wallMillis;
        this.monotonicNanos = monotonicNanos;
        this.spawner = spawner != null ? spawner : SnapshotPersister::spawnDaemon;
        this.warn = warn != null ? warn : message -> log.warn("{}", message);
    }

    private static void spawnDaemon(Runnable task) {
        Thread t = new Thread(task, "featureflip-snapshot-write");
        t.setDaemon(true);
        t.start();
    }

    /**
     * After a full live apply. Changed content is written at once. An unchanged state is
     * only rewritten once the last write is a refresh interval old.
     */
    public void persistFull() {
        if (isClosed()) return;
        if (withinRefreshWindow()) {
            String data;
            try {
                data = serializer.serialize();
            } catch (Exception e) {
                warn.accept("Snapshot persist failed: " + e);
                return;
            }
            synchronized (lock) {
                if (data.equals(lastData)) return;
            }
        }
        persist();
    }

    /** After a single-flag or segment delta: only once live, never right after a snapshot or none start. */
    public void persistDelta() {
        if (live.getAsBoolean()) {
            persist();
        }
    }

    /** The stream heartbeat: while live, refresh an unchanged state at most once per interval. */
    public void onPing() {
        if (!live.getAsBoolean() || withinRefreshWindow()) return;
        persist();
    }

    /** No trailing write starts after this. A write already in flight is left to finish. */
    public void close() {
        synchronized (lock) {
            closed = true;
        }
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private boolean withinRefreshWindow() {
        synchronized (lock) {
            return hasWritten
                && (monotonicNanos.getAsLong() - lastWriteStartNanos) < REFRESH_INTERVAL_MS * 1_000_000L;
        }
    }

    private void persist() {
        synchronized (lock) {
            if (closed) return;
            if (inFlight) {
                queued = true;
                return;
            }
            inFlight = true;
        }
        String text = buildText();
        if (text == null) {
            text = finish();
            if (text == null) return;
        }
        final String first = text;
        try {
            spawner.accept(() -> worker(first));
        } catch (Throwable t) {
            // Could not start the writer (resource limit). Don't leave the in-flight flag
            // set, or every later write is wedged, and never let this escape into an apply.
            synchronized (lock) {
                inFlight = false;
                queued = false;
            }
            warn.accept("Snapshot persist failed: could not start the writer: " + t);
        }
    }

    /** The envelope for the current state, stamped now. Never throws. */
    private String buildText() {
        try {
            long fetchedAt = wallMillis.getAsLong();
            String data = serializer.serialize();
            synchronized (lock) {
                hasWritten = true;
                lastWriteStartNanos = monotonicNanos.getAsLong();
                lastData = data;
            }
            return SnapshotCodec.encodeEnvelope(keyHash, fetchedAt, data);
        } catch (Exception e) {
            warn.accept("Snapshot persist failed: " + e);
            return null;
        }
    }

    /** Ends one write. Returns the next envelope if a trailing write is due. */
    private String finish() {
        while (true) {
            synchronized (lock) {
                if (!queued || closed) {
                    queued = false;
                    inFlight = false;
                    return null;
                }
                queued = false;
            }
            String text = buildText();
            if (text != null) return text;
        }
    }

    private void worker(String first) {
        String text = first;
        while (text != null) {
            try {
                store.put(text);
            } catch (Throwable t) {
                warn.accept("Snapshot store write failed: " + t);
            }
            text = finish();
        }
    }
}
