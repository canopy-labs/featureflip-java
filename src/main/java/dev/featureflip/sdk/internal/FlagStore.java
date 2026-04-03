package dev.featureflip.sdk.internal;

import dev.featureflip.sdk.internal.model.FlagConfiguration;
import dev.featureflip.sdk.internal.model.Segment;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe in-memory store for flag configurations and segments.
 * Uses volatile snapshot + lock for atomic updates with lock-free reads.
 */
public final class FlagStore {
    private volatile Snapshot snapshot = new Snapshot(Collections.emptyMap(), Collections.emptyMap());
    private final ReentrantLock updateLock = new ReentrantLock();

    public FlagConfiguration getFlag(String key) {
        return snapshot.flags.get(key);
    }

    public Segment getSegment(String key) {
        return snapshot.segments.get(key);
    }

    public Map<String, FlagConfiguration> getAllFlags() {
        return snapshot.flags;
    }

    public void replace(List<FlagConfiguration> flags, List<Segment> segments) {
        updateLock.lock();
        try {
            Map<String, FlagConfiguration> flagMap = new HashMap<>();
            for (FlagConfiguration f : flags) flagMap.put(f.getKey(), f);
            Map<String, Segment> segMap = new HashMap<>();
            for (Segment s : segments) segMap.put(s.getKey(), s);
            snapshot = new Snapshot(Collections.unmodifiableMap(flagMap), Collections.unmodifiableMap(segMap));
        } finally {
            updateLock.unlock();
        }
    }

    public void upsertFlag(FlagConfiguration flag) {
        updateLock.lock();
        try {
            Map<String, FlagConfiguration> newFlags = new HashMap<>(snapshot.flags);
            newFlags.put(flag.getKey(), flag);
            snapshot = new Snapshot(Collections.unmodifiableMap(newFlags), snapshot.segments);
        } finally {
            updateLock.unlock();
        }
    }

    public void upsertSegment(Segment segment) {
        updateLock.lock();
        try {
            Map<String, Segment> newSegments = new HashMap<>(snapshot.segments);
            newSegments.put(segment.getKey(), segment);
            snapshot = new Snapshot(snapshot.flags, Collections.unmodifiableMap(newSegments));
        } finally {
            updateLock.unlock();
        }
    }

    public void removeFlag(String key) {
        updateLock.lock();
        try {
            Map<String, FlagConfiguration> newFlags = new HashMap<>(snapshot.flags);
            newFlags.remove(key);
            snapshot = new Snapshot(Collections.unmodifiableMap(newFlags), snapshot.segments);
        } finally {
            updateLock.unlock();
        }
    }

    public void removeSegment(String key) {
        updateLock.lock();
        try {
            Map<String, Segment> newSegments = new HashMap<>(snapshot.segments);
            newSegments.remove(key);
            snapshot = new Snapshot(snapshot.flags, Collections.unmodifiableMap(newSegments));
        } finally {
            updateLock.unlock();
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
