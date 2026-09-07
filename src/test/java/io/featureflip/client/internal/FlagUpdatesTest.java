package io.featureflip.client.internal;

import com.fasterxml.jackson.databind.node.BooleanNode;
import io.featureflip.client.internal.model.Condition;
import io.featureflip.client.internal.model.ConditionOperator;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.FlagType;
import io.featureflip.client.internal.model.Prerequisite;
import io.featureflip.client.internal.model.Segment;
import io.featureflip.client.internal.model.TargetingRule;
import io.featureflip.client.internal.model.Variation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reduction from "a full snapshot arrived" to "these keys moved".
 *
 * <p>Every poll tick and every SSE reconnect hands the store a complete
 * snapshot, so the interesting assertions here are the NEGATIVE ones: an
 * identical snapshot must report nothing, or a listener fires once per poll
 * interval forever. The positive ones cover the three ways a flag's evaluated
 * value moves without its own configuration changing.
 */
class FlagUpdatesTest {

    // --- fixtures ---------------------------------------------------------

    private static FlagConfiguration flag(String key, int version, boolean enabled) {
        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey(key);
        flag.setVersion(version);
        flag.setType(FlagType.BOOLEAN);
        flag.setEnabled(enabled);
        Variation on = new Variation();
        on.setKey("on");
        on.setValue(BooleanNode.TRUE);
        flag.setVariations(new ArrayList<>(Collections.singletonList(on)));
        return flag;
    }

    private static FlagConfiguration flagWithSegmentRule(String key, String segmentKey) {
        FlagConfiguration flag = flag(key, 1, true);
        TargetingRule rule = new TargetingRule();
        rule.setId("rule-1");
        rule.setSegmentKey(segmentKey);
        flag.setRules(new ArrayList<>(Collections.singletonList(rule)));
        return flag;
    }

    private static FlagConfiguration flagWithPrerequisite(String key, String prerequisiteKey) {
        FlagConfiguration flag = flag(key, 1, true);
        Prerequisite prerequisite = new Prerequisite();
        prerequisite.setPrerequisiteFlagKey(prerequisiteKey);
        prerequisite.setExpectedVariationKey("on");
        flag.setPrerequisites(new ArrayList<>(Collections.singletonList(prerequisite)));
        return flag;
    }

    private static Segment segment(String key, String attribute) {
        Segment segment = new Segment();
        segment.setKey(key);
        segment.setVersion(1);
        Condition condition = new Condition();
        condition.setAttribute(attribute);
        condition.setOperator(ConditionOperator.EQUALS);
        segment.setConditions(new ArrayList<>(Collections.singletonList(condition)));
        return segment;
    }

    private static <T> Map<String, T> byKey(List<T> values, java.util.function.Function<T, String> key) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T value : values) {
            map.put(key.apply(value), value);
        }
        return map;
    }

    private static Map<String, FlagConfiguration> flags(FlagConfiguration... values) {
        return byKey(Arrays.asList(values), FlagConfiguration::getKey);
    }

    private static Map<String, Segment> segments(Segment... values) {
        return byKey(Arrays.asList(values), Segment::getKey);
    }

    private static List<String> diff(
            Map<String, FlagConfiguration> previousFlags,
            Map<String, Segment> previousSegments,
            Map<String, FlagConfiguration> nextFlags,
            Map<String, Segment> nextSegments) {
        return FlagUpdates.diffSnapshot(previousFlags, previousSegments, nextFlags, nextSegments);
    }

    // --- the negative case, which is the whole point ----------------------

    @Test
    void anIdenticalSnapshotReportsNothing() {
        // The store is handed a full snapshot on every poll tick. If equality were
        // by identity, or by a field the server does not vary, this would report
        // every flag once per poll interval — a listener that fires constantly is
        // indistinguishable from one that is broken.
        Map<String, FlagConfiguration> previous = flags(flag("a", 1, true), flag("b", 3, false));
        Map<String, FlagConfiguration> next = flags(flag("a", 1, true), flag("b", 3, false));

        assertThat(diff(previous, Collections.emptyMap(), next, Collections.emptyMap())).isEmpty();
    }

    @Test
    void anIdenticalSnapshotOfSegmentsReportsNothing() {
        Map<String, Segment> previous = segments(segment("beta", "plan"));
        Map<String, Segment> next = segments(segment("beta", "plan"));
        Map<String, FlagConfiguration> unchanged = flags(flagWithSegmentRule("gated", "beta"));

        assertThat(diff(unchanged, previous, unchanged, next)).isEmpty();
    }

    // --- added / removed / modified ---------------------------------------

    @Test
    void reportsAddedRemovedAndModifiedFlags() {
        Map<String, FlagConfiguration> previous =
                flags(flag("kept", 1, true), flag("edited", 1, true), flag("removed", 1, true));
        Map<String, FlagConfiguration> next =
                flags(flag("kept", 1, true), flag("edited", 2, false), flag("added", 1, true));

        assertThat(diff(previous, Collections.emptyMap(), next, Collections.emptyMap()))
                .containsExactly("added", "edited", "removed");
    }

    @Test
    void detectsAnEditThatLeavesTheVersionAlone() {
        // The comparison is structural, not a version check: the server is not the
        // only thing that can hand us a changed body, and an SSE `sync` replaying a
        // rolled-back config need not move the number.
        FlagConfiguration before = flag("a", 7, true);
        FlagConfiguration after = flag("a", 7, false);

        assertThat(diff(flags(before), Collections.emptyMap(), flags(after), Collections.emptyMap()))
                .containsExactly("a");
    }

    @Test
    void detectsAnEditNestedInsideAVariation() {
        // Nothing at the top level of the DTO moved — only the JSON value of one
        // variation. A comparison over a hand-maintained field list is exactly what
        // misses this.
        FlagConfiguration before = flag("a", 1, true);
        FlagConfiguration after = flag("a", 1, true);
        after.getVariations().get(0).setValue(BooleanNode.FALSE);

        assertThat(diff(flags(before), Collections.emptyMap(), flags(after), Collections.emptyMap()))
                .containsExactly("a");
    }

    // --- the three indirect movements -------------------------------------

    @Test
    void aSegmentEditPullsInTheFlagsTargetingIt() {
        // A segment edit changes what a rule matches without touching — or
        // versioning — the flag that references it.
        Map<String, FlagConfiguration> unchangedFlags =
                flags(flagWithSegmentRule("gated", "beta"), flag("unrelated", 1, true));

        List<String> changed = diff(
                unchangedFlags, segments(segment("beta", "plan")),
                unchangedFlags, segments(segment("beta", "tier")));

        assertThat(changed).containsExactly("gated");
    }

    @Test
    void aRemovedSegmentPullsInTheFlagsTargetingIt() {
        Map<String, FlagConfiguration> unchangedFlags = flags(flagWithSegmentRule("gated", "beta"));

        List<String> changed = diff(
                unchangedFlags, segments(segment("beta", "plan")),
                unchangedFlags, Collections.emptyMap());

        assertThat(changed).containsExactly("gated");
    }

    @Test
    void aChangedFlagPullsInItsPrerequisiteDependents() {
        // Toggling `base` bumps only `base`'s version. `dependent` evaluates to its
        // off variation with PREREQUISITE_FAILED, so its value moved and nothing in
        // its own configuration says so.
        Map<String, FlagConfiguration> previous =
                flags(flag("base", 1, true), flagWithPrerequisite("dependent", "base"));
        Map<String, FlagConfiguration> next =
                flags(flag("base", 2, false), flagWithPrerequisite("dependent", "base"));

        assertThat(diff(previous, Collections.emptyMap(), next, Collections.emptyMap()))
                .containsExactly("base", "dependent");
    }

    @Test
    void prerequisiteDependentsAreFollowedTransitively() {
        Map<String, FlagConfiguration> previous = flags(
                flag("base", 1, true),
                flagWithPrerequisite("middle", "base"),
                flagWithPrerequisite("leaf", "middle"));
        Map<String, FlagConfiguration> next = flags(
                flag("base", 2, false),
                flagWithPrerequisite("middle", "base"),
                flagWithPrerequisite("leaf", "middle"));

        assertThat(diff(previous, Collections.emptyMap(), next, Collections.emptyMap()))
                .containsExactly("base", "leaf", "middle");
    }

    @Test
    void aSegmentEditReachesTheDependentsOfTheFlagItPulledIn() {
        // The prerequisite walk must run AFTER the segment scan, or a flag that only
        // the segment scan reached never reaches its own dependents.
        Map<String, FlagConfiguration> unchangedFlags = flags(
                flagWithSegmentRule("gated", "beta"),
                flagWithPrerequisite("dependent", "gated"));

        List<String> changed = diff(
                unchangedFlags, segments(segment("beta", "plan")),
                unchangedFlags, segments(segment("beta", "tier")));

        assertThat(changed).containsExactly("dependent", "gated");
    }

    @Test
    void aRemovedFlagStillReachesItsDependents() {
        // Dependents resolve from what REMAINS: they still carry the now-dangling
        // prerequisite row, so their value moves to their off variation.
        Map<String, FlagConfiguration> previous =
                flags(flag("base", 1, true), flagWithPrerequisite("dependent", "base"));
        Map<String, FlagConfiguration> next = flags(flagWithPrerequisite("dependent", "base"));

        assertThat(diff(previous, Collections.emptyMap(), next, Collections.emptyMap()))
                .containsExactly("base", "dependent");
    }

    // --- termination and ordering -----------------------------------------

    @Test
    void aPrerequisiteCycleTerminates() {
        // The server rejects cycles, but a malformed payload can carry one and this
        // walk must not be the thing that hangs the polling thread.
        Map<String, FlagConfiguration> previous = flags(
                flagWithPrerequisite("a", "b"), flagWithPrerequisite("b", "a"), flag("trigger", 1, true));
        FlagConfiguration cyclicA = flagWithPrerequisite("a", "b");
        cyclicA.setVersion(2);
        Map<String, FlagConfiguration> next = flags(
                cyclicA, flagWithPrerequisite("b", "a"), flag("trigger", 1, true));

        assertThat(diff(previous, Collections.emptyMap(), next, Collections.emptyMap()))
                .containsExactly("a", "b");
    }

    @Test
    void keysAreReportedInAscendingOrder() {
        Map<String, FlagConfiguration> next = flags(flag("zulu", 1, true), flag("alpha", 1, true));

        assertThat(diff(Collections.emptyMap(), Collections.emptyMap(), next, Collections.emptyMap()))
                .containsExactly("alpha", "zulu");
    }
}
