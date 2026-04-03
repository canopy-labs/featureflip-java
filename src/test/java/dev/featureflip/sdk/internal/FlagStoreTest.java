package dev.featureflip.sdk.internal;

import dev.featureflip.sdk.internal.model.FlagConfiguration;
import dev.featureflip.sdk.internal.model.FlagType;
import dev.featureflip.sdk.internal.model.Segment;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

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
}
