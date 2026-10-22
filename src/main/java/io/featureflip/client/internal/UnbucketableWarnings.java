package io.featureflip.client.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Logs, once per (flag key, rule id or "fallthrough", bucketBy) per process, that a
 * percentage rollout was skipped because the context had no value to bucket on.
 * Never logs context values. Capped so a hostile flag set can't grow it without bound;
 * once full, unseen keys are dropped silently.
 */
final class UnbucketableWarnings {
    static final int MAX_KEYS = 1000;

    private static final Logger LOG = LoggerFactory.getLogger(FlagEvaluator.class);
    private static final UnbucketableWarnings SHARED = new UnbucketableWarnings(LOG::warn);

    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final AtomicInteger count = new AtomicInteger();
    private volatile Consumer<String> sink;

    UnbucketableWarnings(Consumer<String> sink) {
        this.sink = sink;
    }

    /** Process-wide dedup, the one the evaluator uses. */
    static UnbucketableWarnings shared() {
        return SHARED;
    }

    /** Process-wide entry point. A null {@code ruleId} means the flag's fallthrough. */
    static void warn(String flagKey, String ruleId, String bucketBy) {
        SHARED.record(flagKey, ruleId, bucketBy);
    }

    /** Swaps the message sink (tests only) and returns the previous one. */
    Consumer<String> setSink(Consumer<String> newSink) {
        Consumer<String> previous = sink;
        sink = newSink;
        return previous;
    }

    // Instance form so tests can exercise the cap without exhausting the shared set.
    void record(String flagKey, String ruleId, String bucketBy) {
        String where = ruleId == null ? "fallthrough" : "rule " + ruleId;
        String key = flagKey + "\u0000" + where + "\u0000" + bucketBy;

        if (seen.contains(key)) return;

        // Reserve a slot first so concurrent first-callers can't exceed the cap.
        if (count.incrementAndGet() > MAX_KEYS) {
            count.decrementAndGet();
            return;
        }
        if (!seen.add(key)) {
            // Lost a race with another thread adding the same key; it logs.
            count.decrementAndGet();
            return;
        }

        sink.accept("[featureflip] Skipped the percentage rollout in flag \"" + flagKey + "\" ("
            + where + "): the context has no value for \"" + bucketBy
            + "\". Pass a stable id in that attribute to include this context in the rollout.");
    }
}
