package io.featureflip.client.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.Prerequisite;
import io.featureflip.client.internal.model.Segment;
import io.featureflip.client.internal.model.TargetingRule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reduces a configuration update to the flag keys whose evaluated value may have
 * moved.
 *
 * <p>Mirrors {@code packages/go-sdk/updates.go} ({@code diffSnapshot}),
 * {@code packages/js-sdk/src/core/store.ts} and the Python SDK's
 * {@code _updates.diff_snapshot}. The semantics are cross-SDK contract, not an
 * implementation detail — a listener in any language is told the same set.
 */
public final class FlagUpdates {

    /**
     * Renders a DTO as a tree so two of them can be compared structurally.
     *
     * <p>Its own mapper, with no configuration: this only ever walks objects
     * this package deserialized, and comparing against a differently-configured
     * mapper's output would make equality depend on serialization settings.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private FlagUpdates() {
    }

    /**
     * Reports the flag keys whose evaluated value may differ between two snapshots.
     *
     * <p>The store is handed a FULL snapshot on every poll tick and on every SSE
     * {@code sync} reconnect, so notifying on each one would report "everything
     * changed" once per poll interval. This reduces a snapshot to the keys that
     * could actually have moved.
     *
     * <p>Version numbers are deliberately NOT used as the comparison: a segment
     * edit moves a flag's evaluated value without bumping that flag's version.
     *
     * @return the changed keys in ascending order, or an empty list
     */
    public static List<String> diffSnapshot(
            Map<String, FlagConfiguration> previousFlags,
            Map<String, Segment> previousSegments,
            Map<String, FlagConfiguration> nextFlags,
            Map<String, Segment> nextSegments) {

        Set<String> changed = new HashSet<>();
        collectChangedKeys(previousFlags, nextFlags, changed);

        Set<String> changedSegments = new HashSet<>();
        collectChangedKeys(previousSegments, nextSegments, changedSegments);

        if (!changedSegments.isEmpty()) {
            // A segment edit alters evaluation outcomes without bumping any flag's
            // version, so the flags referencing it have to be pulled in by hand.
            //
            // Only the incoming flags need scanning: a flag that dropped its
            // reference to a changed segment must have had its own rules edited,
            // which changes its own config and is already reported above.
            for (FlagConfiguration flag : nextFlags.values()) {
                if (referencesAnySegment(flag, changedSegments)) {
                    changed.add(flag.getKey());
                }
            }
        }

        // Must run last: it walks out from the fully-resolved changed set, so a
        // flag that only the segment scan above pulled in still reaches its own
        // dependents.
        addPrerequisiteDependents(nextFlags.values(), changed);

        return sortedKeys(changed);
    }

    /**
     * Adds to {@code changed} every key that is new, structurally different, or
     * gone.
     *
     * <p>A key removed server-side is still a change for anyone holding the old
     * value.
     */
    private static <T> void collectChangedKeys(
            Map<String, T> previous, Map<String, T> next, Set<String> changed) {
        for (Map.Entry<String, T> entry : next.entrySet()) {
            T before = previous.get(entry.getKey());
            if (before == null || !structurallyEqual(before, entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (String key : previous.keySet()) {
            if (!next.containsKey(key)) {
                changed.add(key);
            }
        }
    }

    /**
     * Compares two configuration DTOs by their serialized shape.
     *
     * <p>Deliberately not {@code equals}: none of the model classes defines one,
     * and hand-writing sixteen of them would make this comparison depend on
     * somebody remembering to extend {@code equals} every time a field is added
     * to a DTO. A field left out silently under-reports a change — a listener
     * never hears about an edit — which is the one direction that matters here,
     * and it would fail invisibly. A tree comparison covers every serialized
     * field by construction and cannot go stale.
     *
     * <p>A DTO that cannot be rendered as a tree is reported as CHANGED rather
     * than equal. Over-reporting costs a listener one redundant re-read;
     * under-reporting loses the update entirely.
     */
    static boolean structurallyEqual(Object before, Object after) {
        try {
            return OBJECT_MAPPER.valueToTree(before).equals(OBJECT_MAPPER.valueToTree(after));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether any of {@code flag}'s rules targets one of {@code segmentKeys}. */
    private static boolean referencesAnySegment(FlagConfiguration flag, Set<String> segmentKeys) {
        List<TargetingRule> rules = flag.getRules();
        if (rules == null) {
            return false;
        }
        for (TargetingRule rule : rules) {
            String segmentKey = rule.getSegmentKey();
            if (segmentKey != null && !segmentKey.isEmpty() && segmentKeys.contains(segmentKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Expands {@code changed} in place with every transitive prerequisite dependent.
     *
     * <p>A flag's version covers its own prerequisite rows but not the flags those
     * rows point at, so toggling a prerequisite bumps only the prerequisite's
     * version. The evaluator resolves prerequisites recursively, so the dependent's
     * value still flips — to its off variation, with
     * {@link io.featureflip.client.EvaluationReason#PREREQUISITE_FAILED} — leaving
     * the dependent silently absent from the change notification.
     *
     * <p>Walks reverse edges out from the changed set rather than scanning every
     * flag's chain, so cost is proportional to the affected subgraph rather than to
     * the whole snapshot.
     *
     * <p>{@code flags} is normally the INCOMING snapshot, so a removed flag still
     * resolves its dependents: they are still present and still carry the
     * now-dangling prerequisite row.
     */
    static void addPrerequisiteDependents(Collection<FlagConfiguration> flags, Set<String> changed) {
        if (changed.isEmpty()) {
            return;
        }

        Map<String, List<String>> dependents = new HashMap<>();
        for (FlagConfiguration flag : flags) {
            List<Prerequisite> prerequisites = flag.getPrerequisites();
            if (prerequisites == null) {
                continue;
            }
            for (Prerequisite prerequisite : prerequisites) {
                String key = prerequisite.getPrerequisiteFlagKey();
                if (key == null || key.isEmpty()) {
                    continue;
                }
                dependents.computeIfAbsent(key, k -> new ArrayList<>()).add(flag.getKey());
            }
        }
        if (dependents.isEmpty()) {
            return;
        }

        // changed doubles as the visited set, so a prerequisite cycle (rejected by
        // the server, but reachable via a malformed payload) terminates instead of
        // looping forever. No depth cap is needed for the same reason — unlike the
        // evaluator, this walk visits each flag at most once.
        Deque<String> queue = new ArrayDeque<>(changed);
        while (!queue.isEmpty()) {
            String key = queue.pop();
            for (String dependent : dependents.getOrDefault(key, Collections.emptyList())) {
                if (changed.add(dependent)) {
                    queue.push(dependent);
                }
            }
        }
    }

    /**
     * Renders a key set as a sorted, unmodifiable list, so a listener sees a
     * stable order rather than hash iteration order.
     */
    private static List<String> sortedKeys(Set<String> keys) {
        if (keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }
}
