package io.featureflip.client.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnbucketableWarningsTest {
    @Test
    void capsDistinctKeysAndDropsTheOverflowSilently() {
        List<String> lines = new ArrayList<>();
        UnbucketableWarnings w = new UnbucketableWarnings(lines::add);

        for (int i = 0; i < UnbucketableWarnings.MAX_KEYS; i++) {
            w.record("cap-flag-" + i, "r1", "userId");
        }
        assertThat(lines).hasSize(UnbucketableWarnings.MAX_KEYS);

        w.record("cap-flag-overflow", "r1", "userId");
        assertThat(lines).hasSize(UnbucketableWarnings.MAX_KEYS);

        // A key already recorded stays deduplicated.
        w.record("cap-flag-0", "r1", "userId");
        assertThat(lines).hasSize(UnbucketableWarnings.MAX_KEYS);
    }

    @Test
    void messageMatchesTheContract() {
        List<String> lines = new ArrayList<>();
        new UnbucketableWarnings(lines::add).record("msg-flag", null, "anonymous_id");
        assertThat(lines).containsExactly(
            "[featureflip] Skipped the percentage rollout in flag \"msg-flag\" (fallthrough): "
            + "the context has no value for \"anonymous_id\". Pass a stable id in that attribute "
            + "to include this context in the rollout.");
    }
}
