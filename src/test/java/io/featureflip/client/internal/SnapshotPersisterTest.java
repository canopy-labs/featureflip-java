package io.featureflip.client.internal;

import io.featureflip.client.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotPersisterTest {
    private final AtomicLong wall = new AtomicLong(1_800_000_000_000L);
    private final AtomicLong nanos = new AtomicLong(0);
    private final AtomicReference<String> state = new AtomicReference<>("{\"flags\":[],\"segments\":[]}");
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
    private final List<String> puts = Collections.synchronizedList(new ArrayList<>());

    /** Runs writer tasks inline so every test is deterministic. */
    private SnapshotPersister persister(SnapshotStore store) {
        return new SnapshotPersister(store, "hash", state::get, live::get, wall::get, nanos::get,
            Runnable::run, warnings::add);
    }

    private final SnapshotStore recording = new SnapshotStore() {
        public String get() { return null; }
        public void put(String text) { puts.add(text); }
    };

    private static long minutes(int m) { return TimeUnit.MINUTES.toNanos(m); }

    @Test
    void firstFullApplyWritesTheEnvelopeStampedNow() {
        persister(recording).persistFull();
        assertThat(puts).hasSize(1);
        assertThat(puts.get(0)).isEqualTo(
            "{\"v\":1,\"keyHash\":\"hash\",\"fetchedAt\":1800000000000,\"data\":{\"flags\":[],\"segments\":[]}}");
    }

    @Test
    void unchangedFullApplyInsideTheWindowIsSkippedAndChangedContentWritesAtOnce() {
        SnapshotPersister p = persister(recording);
        p.persistFull();
        nanos.set(minutes(1));
        p.persistFull();
        assertThat(puts).hasSize(1);

        state.set("{\"flags\":[{\"key\":\"a\"}],\"segments\":[]}");
        p.persistFull();
        assertThat(puts).hasSize(2);
    }

    @Test
    void unchangedFullApplyIsRewrittenOnceTheWindowElapses() {
        SnapshotPersister p = persister(recording);
        p.persistFull();
        nanos.set(minutes(5) - 1);
        p.persistFull();
        assertThat(puts).hasSize(1);
        nanos.set(minutes(5));
        wall.addAndGet(300_000);
        p.persistFull();
        assertThat(puts).hasSize(2);
        assertThat(puts.get(1)).contains("\"fetchedAt\":1800000300000");
    }

    @Test
    void windowIsMeasuredFromTheStartOfTheLastWrite() {
        SnapshotPersister p = persister(recording);
        p.onPing(); // first write at t=0
        nanos.set(minutes(4));
        p.onPing();
        assertThat(puts).hasSize(1);
        nanos.set(minutes(5));
        p.onPing();
        assertThat(puts).hasSize(2);
        nanos.set(minutes(9));
        p.onPing(); // 4 minutes after the SECOND write started
        assertThat(puts).hasSize(2);
    }

    @Test
    void theWindowSurvivesTheMonotonicClockWrappingAroundLong() {
        nanos.set(Long.MAX_VALUE - TimeUnit.MINUTES.toNanos(2));
        SnapshotPersister p = persister(recording);
        p.onPing(); // write at the edge of the range
        assertThat(puts).hasSize(1);
        nanos.set(Long.MAX_VALUE - TimeUnit.MINUTES.toNanos(2) + TimeUnit.MINUTES.toNanos(4)); // wrapped negative
        assertThat(nanos.get()).isNegative();
        p.onPing();
        assertThat(puts).as("4 minutes after, across the wrap").hasSize(1);
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(1));
        p.onPing();
        assertThat(puts).hasSize(2);
    }

    @Test
    void pingAndDeltaNeverWriteWhenNotLive() {
        live.set(false);
        SnapshotPersister p = persister(recording);
        p.onPing();
        p.persistDelta();
        assertThat(puts).isEmpty();
        live.set(true);
        p.persistDelta();
        assertThat(puts).hasSize(1);
    }

    @Test
    void deltasAreNotThrottled() {
        SnapshotPersister p = persister(recording);
        p.persistDelta();
        p.persistDelta();
        assertThat(puts).hasSize(2);
    }

    @Test
    void coalescesToOneInFlightPlusOneTrailingWriteThatReadsStateWhenItRuns() throws Exception {
        CountDownLatch firstPutStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        SnapshotStore holding = new SnapshotStore() {
            public String get() { return null; }
            public void put(String text) {
                puts.add(text);
                if (calls.incrementAndGet() == 1) {
                    firstPutStarted.countDown();
                    try {
                        releaseFirst.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        SnapshotPersister p = new SnapshotPersister(holding, "hash", state::get, live::get, wall::get, nanos::get,
            task -> {
                Thread t = new Thread(task);
                t.setDaemon(true);
                t.start();
            }, warnings::add);

        state.set("{\"flags\":[],\"segments\":[],\"n\":1}");
        p.persistDelta();
        assertThat(firstPutStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // Three more changes while the first put is held: all coalesce into ONE trailing write.
        state.set("{\"flags\":[],\"segments\":[],\"n\":2}");
        p.persistDelta();
        state.set("{\"flags\":[],\"segments\":[],\"n\":3}");
        p.persistDelta();
        state.set("{\"flags\":[],\"segments\":[],\"n\":4}");
        p.persistDelta();
        assertThat(puts).hasSize(1);

        releaseFirst.countDown();
        assertThat(io.featureflip.client.SnapshotTestSupport.eventually(5, () -> puts.size() == 2)).isTrue();
        Thread.sleep(100);
        assertThat(puts).hasSize(2);
        assertThat(puts.get(1)).contains("\"n\":4"); // read when it ran, not when it was queued
    }

    @Test
    void noTrailingWriteStartsAfterClose() throws Exception {
        CountDownLatch firstPutStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        SnapshotStore holding = new SnapshotStore() {
            public String get() { return null; }
            public void put(String text) {
                puts.add(text);
                firstPutStarted.countDown();
                try {
                    releaseFirst.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        SnapshotPersister p = new SnapshotPersister(holding, "hash", state::get, live::get, wall::get, nanos::get,
            task -> {
                Thread t = new Thread(task);
                t.setDaemon(true);
                t.start();
            }, warnings::add);
        p.persistDelta();
        assertThat(firstPutStarted.await(5, TimeUnit.SECONDS)).isTrue();
        p.persistDelta(); // queued trailing write
        p.close();
        releaseFirst.countDown();
        Thread.sleep(200);
        assertThat(puts).hasSize(1);
        p.persistDelta(); // and nothing new after close
        assertThat(puts).hasSize(1);
    }

    @Test
    void aThrowingPutIsDroppedWithAWarningAndLaterWritesStillRun() {
        AtomicInteger n = new AtomicInteger();
        SnapshotStore throwing = new SnapshotStore() {
            public String get() { return null; }
            public void put(String text) {
                if (n.incrementAndGet() == 1) throw new IllegalStateException("redis down");
                puts.add(text);
            }
        };
        SnapshotPersister p = persister(throwing);
        p.persistDelta();
        assertThat(warnings).anyMatch(w -> w.contains("redis down"));
        p.persistDelta();
        assertThat(puts).hasSize(1);
    }

    @Test
    void aFailureToStartTheWriterDoesNotWedgePersistence() {
        AtomicBoolean fail = new AtomicBoolean(true);
        SnapshotPersister p = new SnapshotPersister(recording, "hash", state::get, live::get, wall::get, nanos::get,
            task -> {
                if (fail.get()) throw new OutOfMemoryError("unable to create native thread");
                task.run();
            }, warnings::add);
        p.persistDelta(); // must not throw
        assertThat(warnings).anyMatch(w -> w.contains("could not start the writer"));
        fail.set(false);
        p.persistDelta();
        assertThat(puts).hasSize(1);
    }

    @Test
    void aSerializerFailureIsWarnedAndDoesNotWedge() {
        AtomicBoolean fail = new AtomicBoolean(true);
        SnapshotPersister p = new SnapshotPersister(recording, "hash",
            () -> {
                if (fail.get()) throw new IllegalStateException("boom");
                return state.get();
            }, live::get, wall::get, nanos::get, Runnable::run, warnings::add);
        p.persistDelta();
        assertThat(puts).isEmpty();
        assertThat(warnings).anyMatch(w -> w.contains("boom"));
        fail.set(false);
        p.persistDelta();
        assertThat(puts).hasSize(1);
    }
}
