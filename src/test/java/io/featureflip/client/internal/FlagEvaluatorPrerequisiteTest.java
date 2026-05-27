package io.featureflip.client.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.EvaluationContext;
import io.featureflip.client.EvaluationReason;
import io.featureflip.client.internal.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlagEvaluatorPrerequisiteTest {
    private FlagEvaluator evaluator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        evaluator = new FlagEvaluator(new FlagStore());
        mapper = new ObjectMapper();
    }

    @Test
    void evaluate_NoPrerequisites_BehavesUnchanged() {
        FlagConfiguration flag = boolFlag("child", true, null);
        Map<String, FlagConfiguration> allFlags = toMap(flag);

        FlagEvaluator.Result result = evaluator.evaluate(flag, ctx("u1"), allFlags);

        assertThat(result.getVariationKey()).isEqualTo("on");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
        assertThat(result.getPrerequisiteKey()).isNull();
    }

    @Test
    void evaluate_PrerequisiteSatisfied_EvaluatesChildNormally() {
        FlagConfiguration parent = boolFlag("parent", true, null);
        FlagConfiguration child = boolFlag("child", true, List.of(prereq("parent", "on")));

        FlagEvaluator.Result result = evaluator.evaluate(child, ctx("u1"), toMap(parent, child));

        assertThat(result.getVariationKey()).isEqualTo("on");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
        assertThat(result.getPrerequisiteKey()).isNull();
    }

    @Test
    void evaluate_PrerequisiteUnsatisfied_ReturnsOffVariationWithPrerequisiteKey() {
        FlagConfiguration parent = boolFlag("parent", true, null); // serves "on"
        FlagConfiguration child = boolFlag("child", true, List.of(prereq("parent", "off")));

        FlagEvaluator.Result result = evaluator.evaluate(child, ctx("u1"), toMap(parent, child));

        assertThat(result.getVariationKey()).isEqualTo("off");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.PREREQUISITE_FAILED);
        assertThat(result.getPrerequisiteKey()).isEqualTo("parent");
    }

    @Test
    void evaluate_DisabledPrerequisite_ServesOffSoMismatchFails() {
        FlagConfiguration parent = boolFlag("parent", false, null); // disabled => "off"
        FlagConfiguration child = boolFlag("child", true, List.of(prereq("parent", "on")));

        FlagEvaluator.Result result = evaluator.evaluate(child, ctx("u1"), toMap(parent, child));

        assertThat(result.getVariationKey()).isEqualTo("off");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.PREREQUISITE_FAILED);
        assertThat(result.getPrerequisiteKey()).isEqualTo("parent");
    }

    @Test
    void evaluate_MultiplePrerequisites_FirstFailingKeyIsReported() {
        FlagConfiguration p1 = boolFlag("p1", true, null);
        FlagConfiguration p2 = boolFlag("p2", true, null);
        FlagConfiguration child = boolFlag("child", true, List.of(
            prereq("p1", "off"), // first fails
            prereq("p2", "off")  // also fails — not reported
        ));

        FlagEvaluator.Result result = evaluator.evaluate(child, ctx("u1"), toMap(p1, p2, child));

        assertThat(result.getReason()).isEqualTo(EvaluationReason.PREREQUISITE_FAILED);
        assertThat(result.getPrerequisiteKey()).isEqualTo("p1");
    }

    @Test
    void evaluate_ChainedPrerequisites_PropagatesFailureUpward() {
        FlagConfiguration grandparent = boolFlag("grandparent", true, null);
        FlagConfiguration parent = boolFlag("parent", true,
            List.of(prereq("grandparent", "off"))); // fails — grandparent serves "on"
        FlagConfiguration child = boolFlag("child", true, List.of(prereq("parent", "on")));

        FlagEvaluator.Result result = evaluator.evaluate(child, ctx("u1"),
            toMap(grandparent, parent, child));

        assertThat(result.getReason()).isEqualTo(EvaluationReason.PREREQUISITE_FAILED);
        // Child's prereq is "parent", and parent fails -> reported key is "parent"
        assertThat(result.getPrerequisiteKey()).isEqualTo("parent");
    }

    @Test
    void evaluate_MissingPrerequisiteFlag_FailsSafely() {
        FlagConfiguration child = boolFlag("child", true, List.of(prereq("missing", "on")));

        FlagEvaluator.Result result = evaluator.evaluate(child, ctx("u1"), toMap(child));

        assertThat(result.getVariationKey()).isEqualTo("off");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.PREREQUISITE_FAILED);
        assertThat(result.getPrerequisiteKey()).isEqualTo("missing");
    }

    @Test
    void evaluate_DepthExceeded_ReturnsErrorReason() {
        // Build a chain of 12 flags: f0 -> f1 -> ... -> f11. MAX_PREREQUISITE_DEPTH is 10,
        // so f0 should hit the error path.
        Map<String, FlagConfiguration> flags = new HashMap<>();
        for (int i = 0; i < 12; i++) {
            List<Prerequisite> prereqs = i < 11
                ? List.of(prereq("f" + (i + 1), "on"))
                : null;
            FlagConfiguration f = boolFlag("f" + i, true, prereqs);
            flags.put(f.getKey(), f);
        }

        FlagEvaluator.Result result = evaluator.evaluate(flags.get("f0"), ctx("u1"), flags);

        assertThat(result.getReason()).isEqualTo(EvaluationReason.ERROR);
    }

    @Test
    void evaluate_NullAllFlagsWithPrerequisite_TreatedAsMissing() {
        FlagConfiguration child = boolFlag("child", true, List.of(prereq("parent", "on")));

        FlagEvaluator.Result result = evaluator.evaluate(child, ctx("u1"), null);

        assertThat(result.getReason()).isEqualTo(EvaluationReason.PREREQUISITE_FAILED);
        assertThat(result.getPrerequisiteKey()).isEqualTo("parent");
    }

    @Test
    void evaluateWithSharedMemo_ReusesPrerequisiteResultAcrossFlags() {
        // Two child flags share a prerequisite. With a shared memo, the prereq should
        // only evaluate once across both top-level calls.
        FlagConfiguration parent = boolFlag("parent", true, null);
        FlagConfiguration childA = boolFlag("a", true, List.of(prereq("parent", "on")));
        FlagConfiguration childB = boolFlag("b", true, List.of(prereq("parent", "on")));
        Map<String, FlagConfiguration> allFlags = toMap(parent, childA, childB);
        Map<String, FlagEvaluator.Result> memo = new HashMap<>();
        EvaluationContext ctx = ctx("u1");

        FlagEvaluator.Result a = evaluator.evaluateWithSharedMemo(childA, ctx, allFlags, memo);
        assertThat(memo).containsKey("parent");
        FlagEvaluator.Result parentResultAfterFirst = memo.get("parent");

        FlagEvaluator.Result b = evaluator.evaluateWithSharedMemo(childB, ctx, allFlags, memo);

        // Same reference: memo wasn't overwritten by a fresh parent evaluation.
        assertThat(memo.get("parent")).isSameAs(parentResultAfterFirst);
        assertThat(a.getVariationKey()).isEqualTo("on");
        assertThat(b.getVariationKey()).isEqualTo("on");
    }

    @Test
    void evaluate_NoArgOverload_TreatsPrerequisitesAsMissing() {
        // The legacy two-arg overload doesn't have access to allFlags, so any
        // declared prerequisite must fail safely rather than NPE.
        FlagConfiguration child = boolFlag("child", true, List.of(prereq("parent", "on")));

        FlagEvaluator.Result result = evaluator.evaluate(child, ctx("u1"));

        assertThat(result.getReason()).isEqualTo(EvaluationReason.PREREQUISITE_FAILED);
        assertThat(result.getPrerequisiteKey()).isEqualTo("parent");
    }

    // --- Helpers ---

    private EvaluationContext ctx(String userId) {
        return EvaluationContext.builder(userId).build();
    }

    private Prerequisite prereq(String flagKey, String expected) {
        Prerequisite p = new Prerequisite();
        p.setPrerequisiteFlagKey(flagKey);
        p.setExpectedVariationKey(expected);
        return p;
    }

    private Map<String, FlagConfiguration> toMap(FlagConfiguration... flags) {
        Map<String, FlagConfiguration> map = new HashMap<>();
        for (FlagConfiguration f : flags) map.put(f.getKey(), f);
        return map;
    }

    private FlagConfiguration boolFlag(String key, boolean enabled, List<Prerequisite> prerequisites) {
        FlagConfiguration f = new FlagConfiguration();
        f.setKey(key);
        f.setEnabled(enabled);
        f.setOffVariation("off");
        f.setRules(Collections.emptyList());
        f.setVariations(Arrays.asList(boolVariation("on", true), boolVariation("off", false)));

        ServeConfig fallthrough = new ServeConfig();
        fallthrough.setType(ServeType.FIXED);
        fallthrough.setVariation("on");
        f.setFallthrough(fallthrough);

        if (prerequisites != null) f.setPrerequisites(new ArrayList<>(prerequisites));
        return f;
    }

    private Variation boolVariation(String key, boolean value) {
        Variation v = new Variation();
        v.setKey(key);
        v.setValue(mapper.valueToTree(value));
        return v;
    }
}
