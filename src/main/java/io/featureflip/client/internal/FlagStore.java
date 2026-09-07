package io.featureflip.client.internal;

import io.featureflip.client.FlagUpdateListener;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe in-memory store for flag configurations and segments.
 * Uses volatile snapshot + lock for atomic updates with lock-free reads.
 *
 * <p>It is also where flag-update listeners are notified, and that placement is
 * deliberate: every data source — SSE sync, SSE per-flag upsert/delete, the
 * poller, and the initial fetch — writes through these mutators and nothing
 * else. Notifying here means a future update path cannot deliver flags without
 * announcing them; notifying from the call sites would make that a thing to
 * remember, and a missed notification is invisible (a listener simply never
 * hears about an edit).
 */
public final class FlagStore {
    private static final Logger log = LoggerFactory.getLogger(FlagStore.class);

    private volatile Snapshot snapshot = new Snapshot(Collections.emptyMap(), Collections.emptyMap());
    private final ReentrantLock updateLock = new ReentrantLock();

    /**
     * Registered listeners. Copy-on-write because it is read on every store
     * mutation and written only when somebody subscribes.
     */
    private final CopyOnWriteArrayList<FlagUpdateListener> listeners = new CopyOnWriteArrayList<>();

    public FlagConfiguration getFlag(String key) {
        return snapshot.flags.get(key);
    }

    public Segment getSegment(String key) {
        return snapshot.segments.get(key);
    }

    public Map<String, FlagConfiguration> getAllFlags() {
        return snapshot.flags;
    }

    /**
     * Subscribes to flag-configuration changes.
     *
     * @return an idempotent unsubscribe action
     */
    public Runnable addUpdateListener(FlagUpdateListener listener) {
        listeners.add(listener);
        AtomicBoolean removed = new AtomicBoolean(false);
        return () -> {
            if (removed.compareAndSet(false, true)) {
                listeners.remove(listener);
            }
        };
    }

    public void replace(List<FlagConfiguration> flags, List<Segment> segments) {
        // Treat null as empty: a `"flags": null` / `"segments": null` payload
        // deserializes the response collections to null (Jackson overrides the
        // default), and a null sync snapshot must clear the store rather than NPE.
        List<FlagConfiguration> safeFlags = flags != null ? flags : Collections.emptyList();
        List<Segment> safeSegments = segments != null ? segments : Collections.emptyList();
        List<String> changed;
        updateLock.lock();
        try {
            Map<String, FlagConfiguration> flagMap = new HashMap<>();
            for (FlagConfiguration f : safeFlags) flagMap.put(f.getKey(), f);
            Map<String, Segment> segMap = new HashMap<>();
            for (Segment s : safeSegments) segMap.put(s.getKey(), s);

            // Diffed INSIDE the lock, against the snapshot still in place. Reading
            // the previous state from outside and diffing after the write would lose
            // any change a concurrent writer landed in between, which under-reports.
            changed = watched()
                ? FlagUpdates.diffSnapshot(snapshot.flags, snapshot.segments, flagMap, segMap)
                : Collections.emptyList();

            snapshot = new Snapshot(Collections.unmodifiableMap(flagMap), Collections.unmodifiableMap(segMap));
        } finally {
            updateLock.unlock();
        }
        notifyListeners(changed);
    }

    public void upsertFlag(FlagConfiguration flag) {
        List<String> changed;
        updateLock.lock();
        try {
            Map<String, FlagConfiguration> newFlags = new HashMap<>(snapshot.flags);
            FlagConfiguration previous = newFlags.put(flag.getKey(), flag);
            // A redundant delta wakes nobody: the SSE path re-upserts a flag it has
            // already seen on every reconnect. Dependents are resolved against the
            // POST-write flag set, since they are other flags entirely.
            boolean moved = previous == null || !FlagUpdates.structurallyEqual(previous, flag);
            changed = watched() && moved
                ? withPrerequisiteDependents(Collections.singletonList(flag.getKey()), newFlags)
                : Collections.emptyList();
            snapshot = new Snapshot(Collections.unmodifiableMap(newFlags), snapshot.segments);
        } finally {
            updateLock.unlock();
        }
        notifyListeners(changed);
    }

    public void upsertSegment(Segment segment) {
        List<String> changed;
        updateLock.lock();
        try {
            Map<String, Segment> newSegments = new HashMap<>(snapshot.segments);
            newSegments.put(segment.getKey(), segment);
            changed = watched()
                ? FlagUpdates.diffSnapshot(snapshot.flags, snapshot.segments, snapshot.flags, newSegments)
                : Collections.emptyList();
            snapshot = new Snapshot(snapshot.flags, Collections.unmodifiableMap(newSegments));
        } finally {
            updateLock.unlock();
        }
        notifyListeners(changed);
    }

    public void removeFlag(String key) {
        List<String> changed;
        updateLock.lock();
        try {
            Map<String, FlagConfiguration> newFlags = new HashMap<>(snapshot.flags);
            boolean held = newFlags.remove(key) != null;
            // Dependents are resolved from what REMAINS after the delete: they still
            // carry the now-dangling prerequisite row, so their evaluated value moves
            // to their off variation with PREREQUISITE_FAILED.
            changed = watched() && held
                ? withPrerequisiteDependents(Collections.singletonList(key), newFlags)
                : Collections.emptyList();
            snapshot = new Snapshot(Collections.unmodifiableMap(newFlags), snapshot.segments);
        } finally {
            updateLock.unlock();
        }
        notifyListeners(changed);
    }

    public void removeSegment(String key) {
        List<String> changed;
        updateLock.lock();
        try {
            Map<String, Segment> newSegments = new HashMap<>(snapshot.segments);
            newSegments.remove(key);
            changed = watched()
                ? FlagUpdates.diffSnapshot(snapshot.flags, snapshot.segments, snapshot.flags, newSegments)
                : Collections.emptyList();
            snapshot = new Snapshot(snapshot.flags, Collections.unmodifiableMap(newSegments));
        } finally {
            updateLock.unlock();
        }
        notifyListeners(changed);
    }

    /**
     * Whether anything is listening. The diff serializes every flag it compares,
     * so the whole computation is skipped for the common case of a client nobody
     * has subscribed to — which includes the initial fetch, since a listener can
     * only be registered once the client exists.
     */
    private boolean watched() {
        return !listeners.isEmpty();
    }

    /** {@code keys} plus every flag transitively depending on one through a prerequisite. */
    private static List<String> withPrerequisiteDependents(
            List<String> keys, Map<String, FlagConfiguration> flags) {
        Set<String> changed = new HashSet<>(keys);
        FlagUpdates.addPrerequisiteDependents(flags.values(), changed);
        List<String> sorted = new ArrayList<>(changed);
        Collections.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }

    /**
     * Runs the listeners, outside the update lock and after the new snapshot is
     * visible, so a listener that re-reads the store sees the configuration it is
     * being told about and a slow one does not block the next update.
     */
    private void notifyListeners(List<String> changed) {
        if (changed.isEmpty()) {
            return;
        }
        for (FlagUpdateListener listener : listeners) {
            try {
                listener.onUpdate(changed);
            } catch (RuntimeException e) {
                // Swallowed: a listener is caller code running on the thread that
                // delivers flags. Letting it propagate would kill the poll tick or
                // the SSE read loop over somebody else's bug.
                log.warn("Flag update listener threw: {}", e.toString());
            }
        }
    }

    private static final class Snapshot {
        final Map<String, FlagConfiguration> flags;
        final Map<String, Segment> segments;

        Snapshot(Map<String, FlagConfiguration> flags, Map<String, Segment> segments) {
            this.flags = flags;
            this.segments = segments;
        }
    }
}
