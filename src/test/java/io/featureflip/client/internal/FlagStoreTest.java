package io.featureflip.client.internal;

import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.FlagType;
import io.featureflip.client.internal.model.Segment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class FlagStoreTest {

    private FlagConfiguration makeFlag(String key) {
        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey(key);
        flag.setVersion(1);
        flag.setType(FlagType.BOOLEAN);
        flag.setEnabled(true);
        return flag;
    }

    @Test
    void getReturnsNullWhenEmpty() {
        FlagStore store = new FlagStore();
        assertThat(store.getFlag("missing")).isNull();
    }

    @Test
    void replaceStoresFlags() {
        FlagStore store = new FlagStore();
        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());
        assertThat(store.getFlag("flag-1")).isNotNull();
        assertThat(store.getFlag("flag-1").getKey()).isEqualTo("flag-1");
    }

    @Test
    void replaceClearsPreviousFlags() {
        FlagStore store = new FlagStore();
        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());
        store.replace(List.of(makeFlag("flag-2")), Collections.emptyList());
        assertThat(store.getFlag("flag-1")).isNull();
        assertThat(store.getFlag("flag-2")).isNotNull();
    }

    @Test
    void replaceWithNullCollectionsTreatsAsEmpty() {
        // A `"flags": null` / `"segments": null` payload deserializes the DTO
        // collections to null (Jackson overrides the default), so replace must
        // treat null as empty rather than NPE — otherwise a null sync snapshot
        // silently fails to apply and the store stays stale.
        FlagStore store = new FlagStore();
        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());

        store.replace(null, null); // must not throw

        assertThat(store.getFlag("flag-1")).isNull();
    }

    @Test
    void upsertAddsNewFlag() {
        FlagStore store = new FlagStore();
        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());
        store.upsertFlag(makeFlag("flag-2"));
        assertThat(store.getFlag("flag-1")).isNotNull();
        assertThat(store.getFlag("flag-2")).isNotNull();
    }

    @Test
    void upsertUpdatesExistingFlag() {
        FlagStore store = new FlagStore();
        FlagConfiguration v1 = makeFlag("flag-1");
        v1.setVersion(1);
        store.replace(List.of(v1), Collections.emptyList());

        FlagConfiguration v2 = makeFlag("flag-1");
        v2.setVersion(2);
        store.upsertFlag(v2);

        assertThat(store.getFlag("flag-1").getVersion()).isEqualTo(2);
    }

    @Test
    void removeFlagDeletesFlag() {
        FlagStore store = new FlagStore();
        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());
        store.removeFlag("flag-1");
        assertThat(store.getFlag("flag-1")).isNull();
    }

    @Test
    void segmentOperationsWork() {
        FlagStore store = new FlagStore();
        Segment seg = new Segment();
        seg.setKey("seg-1");
        seg.setVersion(1);
        store.replace(Collections.emptyList(), List.of(seg));
        assertThat(store.getSegment("seg-1")).isNotNull();

        store.removeSegment("seg-1");
        assertThat(store.getSegment("seg-1")).isNull();
    }

    // --- update notification ---------------------------------------------
    //
    // The store is where listeners are notified because it is the one choke point
    // every data source writes through. These cover that each mutator announces
    // what it did — and, just as importantly, that the ones which changed nothing
    // stay quiet.

    /** Records every notification the store makes. */
    private static final class Recorder implements io.featureflip.client.FlagUpdateListener {
        private final List<List<String>> calls = new CopyOnWriteArrayList<>();

        @Override
        public void onUpdate(List<String> flagKeys) {
            calls.add(new ArrayList<>(flagKeys));
        }
    }

    private FlagConfiguration flagWithPrerequisite(String key, String prerequisiteKey) {
        FlagConfiguration flag = makeFlag(key);
        io.featureflip.client.internal.model.Prerequisite prerequisite =
            new io.featureflip.client.internal.model.Prerequisite();
        prerequisite.setPrerequisiteFlagKey(prerequisiteKey);
        prerequisite.setExpectedVariationKey("on");
        flag.setPrerequisites(new ArrayList<>(List.of(prerequisite)));
        return flag;
    }

    @Test
    void replaceNotifiesWithTheChangedKeys() {
        FlagStore store = new FlagStore();
        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());
        Recorder recorder = new Recorder();
        store.addUpdateListener(recorder);

        FlagConfiguration edited = makeFlag("flag-1");
        edited.setVersion(2);
        store.replace(List.of(edited, makeFlag("flag-2")), Collections.emptyList());

        assertThat(recorder.calls).containsExactly(List.of("flag-1", "flag-2"));
    }

    @Test
    void aReplaceThatChangesNothingNotifiesNobody() {
        // Every poll tick and every SSE reconnect replays the whole snapshot. A
        // listener woken by each of those is worse than no listener at all.
        FlagStore store = new FlagStore();
        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());
        Recorder recorder = new Recorder();
        store.addUpdateListener(recorder);

        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());

        assertThat(recorder.calls).isEmpty();
    }

    @Test
    void upsertNotifiesForTheFlagAndItsDependents() {
        FlagStore store = new FlagStore();
        store.replace(
            List.of(makeFlag("base"), flagWithPrerequisite("dependent", "base")),
            Collections.emptyList());
        Recorder recorder = new Recorder();
        store.addUpdateListener(recorder);

        FlagConfiguration edited = makeFlag("base");
        edited.setVersion(2);
        store.upsertFlag(edited);

        assertThat(recorder.calls).containsExactly(List.of("base", "dependent"));
    }

    @Test
    void upsertingAnIdenticalFlagNotifiesNobody() {
        // The SSE path re-upserts a flag it has already seen on reconnect.
        FlagStore store = new FlagStore();
        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());
        Recorder recorder = new Recorder();
        store.addUpdateListener(recorder);

        store.upsertFlag(makeFlag("flag-1"));

        assertThat(recorder.calls).isEmpty();
    }

    @Test
    void removeNotifiesForTheFlagAndTheDependentsItStrands() {
        FlagStore store = new FlagStore();
        store.replace(
            List.of(makeFlag("base"), flagWithPrerequisite("dependent", "base")),
            Collections.emptyList());
        Recorder recorder = new Recorder();
        store.addUpdateListener(recorder);

        store.removeFlag("base");

        assertThat(recorder.calls).containsExactly(List.of("base", "dependent"));
    }

    @Test
    void removingAFlagThatWasNotHeldNotifiesNobody() {
        FlagStore store = new FlagStore();
        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());
        Recorder recorder = new Recorder();
        store.addUpdateListener(recorder);

        store.removeFlag("never-existed");

        assertThat(recorder.calls).isEmpty();
    }

    @Test
    void aSegmentUpsertNotifiesTheFlagsTargetingIt() {
        FlagStore store = new FlagStore();
        FlagConfiguration gated = makeFlag("gated");
        io.featureflip.client.internal.model.TargetingRule rule =
            new io.featureflip.client.internal.model.TargetingRule();
        rule.setId("rule-1");
        rule.setSegmentKey("beta");
        gated.setRules(new ArrayList<>(List.of(rule)));

        Segment before = new Segment();
        before.setKey("beta");
        before.setVersion(1);
        store.replace(List.of(gated), List.of(before));
        Recorder recorder = new Recorder();
        store.addUpdateListener(recorder);

        Segment after = new Segment();
        after.setKey("beta");
        after.setVersion(2);
        store.upsertSegment(after);

        assertThat(recorder.calls).containsExactly(List.of("gated"));
    }

    @Test
    void unsubscribingStopsNotifications() {
        FlagStore store = new FlagStore();
        Recorder recorder = new Recorder();
        Runnable unsubscribe = store.addUpdateListener(recorder);

        unsubscribe.run();
        unsubscribe.run(); // idempotent
        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());

        assertThat(recorder.calls).isEmpty();
    }

    @Test
    void aListenerThatThrowsDoesNotStopTheOthersOrTheUpdate() {
        // A listener runs on the thread delivering flags. Letting its exception
        // propagate would kill the poll tick or the SSE read loop over caller code.
        FlagStore store = new FlagStore();
        Recorder recorder = new Recorder();
        store.addUpdateListener(keys -> {
            throw new IllegalStateException("listener bug");
        });
        store.addUpdateListener(recorder);

        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());

        assertThat(recorder.calls).containsExactly(List.of("flag-1"));
        assertThat(store.getFlag("flag-1")).isNotNull();
    }

    @Test
    void aListenerSeesTheNewSnapshotWhileItIsBeingNotified() {
        // Notification happens after the snapshot swap and outside the lock, so a
        // listener that re-reads the store observes the configuration it is being
        // told about rather than the one it replaced.
        FlagStore store = new FlagStore();
        store.replace(List.of(makeFlag("flag-1")), Collections.emptyList());
        List<Integer> observed = new ArrayList<>();
        store.addUpdateListener(keys -> observed.add(store.getFlag("flag-1").getVersion()));

        FlagConfiguration edited = makeFlag("flag-1");
        edited.setVersion(9);
        store.replace(List.of(edited), Collections.emptyList());

        assertThat(observed).containsExactly(9);
    }
}
