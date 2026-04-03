package dev.featureflip.sdk.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.featureflip.sdk.EvaluationContext;
import dev.featureflip.sdk.EvaluationReason;
import dev.featureflip.sdk.internal.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlagEvaluatorTest {
    private FlagStore store;
    private FlagEvaluator evaluator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        store = new FlagStore();
        evaluator = new FlagEvaluator(store);
        mapper = new ObjectMapper();
    }

    private Variation boolVariation(String key, boolean value) {
        Variation v = new Variation();
        v.setKey(key);
        v.setValue(mapper.valueToTree(value));
        return v;
    }

    private ServeConfig fixedServe(String variationKey) {
        ServeConfig s = new ServeConfig();
        s.setType(ServeType.FIXED);
        s.setVariation(variationKey);
        return s;
    }

    private ConditionGroup conditionGroup(ConditionLogic operator, Condition... conditions) {
        ConditionGroup group = new ConditionGroup();
        group.setOperator(operator);
        group.setConditions(List.of(conditions));
        return group;
    }

    @Test
    void disabledFlagReturnsOffVariation() {
        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey("test");
        flag.setEnabled(false);
        flag.setOffVariation("off");
        flag.setVariations(List.of(boolVariation("off", false)));

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        FlagEvaluator.Result result = evaluator.evaluate(flag, ctx);

        assertThat(result.getVariationKey()).isEqualTo("off");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.FLAG_DISABLED);
    }

    @Test
    void enabledFlagWithNoRulesReturnsFallthrough() {
        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey("test");
        flag.setEnabled(true);
        flag.setRules(Collections.emptyList());
        flag.setFallthrough(fixedServe("on"));
        flag.setVariations(List.of(boolVariation("on", true)));

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        FlagEvaluator.Result result = evaluator.evaluate(flag, ctx);

        assertThat(result.getVariationKey()).isEqualTo("on");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
    }

    @Test
    void matchingRuleReturnsRuleMatch() {
        Condition cond = new Condition();
        cond.setAttribute("country");
        cond.setOperator(ConditionOperator.EQUALS);
        cond.setValues(List.of("US"));

        TargetingRule rule = new TargetingRule();
        rule.setId("rule-1");
        rule.setPriority(0);
        rule.setConditionGroups(List.of(conditionGroup(ConditionLogic.AND, cond)));
        rule.setServe(fixedServe("on"));

        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey("test");
        flag.setEnabled(true);
        flag.setRules(List.of(rule));
        flag.setFallthrough(fixedServe("off"));
        flag.setVariations(List.of(boolVariation("on", true), boolVariation("off", false)));

        EvaluationContext ctx = EvaluationContext.builder("user-1").set("country", "US").build();
        FlagEvaluator.Result result = evaluator.evaluate(flag, ctx);

        assertThat(result.getVariationKey()).isEqualTo("on");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.RULE_MATCH);
        assertThat(result.getRuleId()).isEqualTo("rule-1");
    }

    @Test
    void rulesEvaluatedInPriorityOrder() {
        Condition cond = new Condition();
        cond.setAttribute("country");
        cond.setOperator(ConditionOperator.EQUALS);
        cond.setValues(List.of("US"));

        TargetingRule lowPriority = new TargetingRule();
        lowPriority.setId("rule-low");
        lowPriority.setPriority(10);
        lowPriority.setConditionGroups(List.of(conditionGroup(ConditionLogic.AND, cond)));
        lowPriority.setServe(fixedServe("off"));

        TargetingRule highPriority = new TargetingRule();
        highPriority.setId("rule-high");
        highPriority.setPriority(0);
        highPriority.setConditionGroups(List.of(conditionGroup(ConditionLogic.AND, cond)));
        highPriority.setServe(fixedServe("on"));

        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey("test");
        flag.setEnabled(true);
        flag.setRules(List.of(lowPriority, highPriority)); // out of order
        flag.setFallthrough(fixedServe("off"));
        flag.setVariations(List.of(boolVariation("on", true), boolVariation("off", false)));

        EvaluationContext ctx = EvaluationContext.builder("user-1").set("country", "US").build();
        FlagEvaluator.Result result = evaluator.evaluate(flag, ctx);

        assertThat(result.getRuleId()).isEqualTo("rule-high");
    }

    @Test
    void emptyConditionGroupsAlwaysMatch() {
        TargetingRule rule = new TargetingRule();
        rule.setId("rule-1");
        rule.setPriority(0);
        rule.setConditionGroups(Collections.emptyList());
        rule.setServe(fixedServe("on"));

        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey("test");
        flag.setEnabled(true);
        flag.setRules(List.of(rule));
        flag.setFallthrough(fixedServe("off"));
        flag.setVariations(List.of(boolVariation("on", true)));

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        FlagEvaluator.Result result = evaluator.evaluate(flag, ctx);

        assertThat(result.getVariationKey()).isEqualTo("on");
    }

    @Test
    void andLogicRequiresAllConditionsInGroup() {
        Condition c1 = new Condition();
        c1.setAttribute("country");
        c1.setOperator(ConditionOperator.EQUALS);
        c1.setValues(List.of("US"));

        Condition c2 = new Condition();
        c2.setAttribute("plan");
        c2.setOperator(ConditionOperator.EQUALS);
        c2.setValues(List.of("pro"));

        TargetingRule rule = new TargetingRule();
        rule.setId("rule-1");
        rule.setPriority(0);
        rule.setConditionGroups(List.of(conditionGroup(ConditionLogic.AND, c1, c2)));
        rule.setServe(fixedServe("on"));

        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey("test");
        flag.setEnabled(true);
        flag.setRules(List.of(rule));
        flag.setFallthrough(fixedServe("off"));
        flag.setVariations(List.of(boolVariation("on", true), boolVariation("off", false)));

        // Only one condition met
        EvaluationContext ctx = EvaluationContext.builder("user-1").set("country", "US").build();
        FlagEvaluator.Result result = evaluator.evaluate(flag, ctx);
        assertThat(result.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
    }

    @Test
    void orLogicRequiresOneConditionInGroup() {
        Condition c1 = new Condition();
        c1.setAttribute("country");
        c1.setOperator(ConditionOperator.EQUALS);
        c1.setValues(List.of("US"));

        Condition c2 = new Condition();
        c2.setAttribute("plan");
        c2.setOperator(ConditionOperator.EQUALS);
        c2.setValues(List.of("pro"));

        TargetingRule rule = new TargetingRule();
        rule.setId("rule-1");
        rule.setPriority(0);
        rule.setConditionGroups(List.of(conditionGroup(ConditionLogic.OR, c1, c2)));
        rule.setServe(fixedServe("on"));

        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey("test");
        flag.setEnabled(true);
        flag.setRules(List.of(rule));
        flag.setFallthrough(fixedServe("off"));
        flag.setVariations(List.of(boolVariation("on", true), boolVariation("off", false)));

        // Only one condition met
        EvaluationContext ctx = EvaluationContext.builder("user-1").set("country", "US").build();
        FlagEvaluator.Result result = evaluator.evaluate(flag, ctx);
        assertThat(result.getReason()).isEqualTo(EvaluationReason.RULE_MATCH);
    }

    @Test
    void multipleConditionGroupsAreAndedTogether() {
        // Group 1: country == US (OR logic, single condition)
        Condition countryCond = new Condition();
        countryCond.setAttribute("country");
        countryCond.setOperator(ConditionOperator.EQUALS);
        countryCond.setValues(List.of("US"));

        // Group 2: plan == pro (AND logic, single condition)
        Condition planCond = new Condition();
        planCond.setAttribute("plan");
        planCond.setOperator(ConditionOperator.EQUALS);
        planCond.setValues(List.of("pro"));

        TargetingRule rule = new TargetingRule();
        rule.setId("rule-1");
        rule.setPriority(0);
        rule.setConditionGroups(List.of(
            conditionGroup(ConditionLogic.OR, countryCond),
            conditionGroup(ConditionLogic.AND, planCond)
        ));
        rule.setServe(fixedServe("on"));

        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey("test");
        flag.setEnabled(true);
        flag.setRules(List.of(rule));
        flag.setFallthrough(fixedServe("off"));
        flag.setVariations(List.of(boolVariation("on", true), boolVariation("off", false)));

        // Both groups match
        EvaluationContext bothCtx = EvaluationContext.builder("user-1")
            .set("country", "US").set("plan", "pro").build();
        assertThat(evaluator.evaluate(flag, bothCtx).getReason()).isEqualTo(EvaluationReason.RULE_MATCH);

        // Only first group matches — should fall through (groups are ANDed)
        EvaluationContext onlyCountryCtx = EvaluationContext.builder("user-2")
            .set("country", "US").build();
        assertThat(evaluator.evaluate(flag, onlyCountryCtx).getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);

        // Only second group matches — should fall through
        EvaluationContext onlyPlanCtx = EvaluationContext.builder("user-3")
            .set("plan", "pro").build();
        assertThat(evaluator.evaluate(flag, onlyPlanCtx).getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
    }

    // --- Condition Operator Tests ---

    private void assertOperator(ConditionOperator op, String value, List<String> targets, boolean expected) {
        boolean result = evaluator.evaluateOperator(op, value, targets);
        assertThat(result).as("Operator %s: %s vs %s", op, value, targets).isEqualTo(expected);
    }

    @Test
    void equalsOperatorCaseInsensitive() {
        assertOperator(ConditionOperator.EQUALS, "hello", List.of("Hello"), true);
        assertOperator(ConditionOperator.EQUALS, "hello", List.of("world"), false);
    }

    @Test
    void notEqualsOperator() {
        assertOperator(ConditionOperator.NOT_EQUALS, "hello", List.of("world"), true);
        assertOperator(ConditionOperator.NOT_EQUALS, "hello", List.of("Hello"), false);
    }

    @Test
    void containsOperator() {
        assertOperator(ConditionOperator.CONTAINS, "hello world", List.of("World"), true);
        assertOperator(ConditionOperator.CONTAINS, "hello", List.of("world"), false);
    }

    @Test
    void notContainsOperator() {
        assertOperator(ConditionOperator.NOT_CONTAINS, "hello", List.of("world"), true);
        assertOperator(ConditionOperator.NOT_CONTAINS, "hello world", List.of("World"), false);
    }

    @Test
    void startsWithOperator() {
        assertOperator(ConditionOperator.STARTS_WITH, "hello world", List.of("Hello"), true);
        assertOperator(ConditionOperator.STARTS_WITH, "hello", List.of("world"), false);
    }

    @Test
    void endsWithOperator() {
        assertOperator(ConditionOperator.ENDS_WITH, "hello world", List.of("World"), true);
        assertOperator(ConditionOperator.ENDS_WITH, "hello", List.of("world"), false);
    }

    @Test
    void inOperator() {
        assertOperator(ConditionOperator.IN, "US", List.of("US", "UK", "CA"), true);
        assertOperator(ConditionOperator.IN, "DE", List.of("US", "UK", "CA"), false);
    }

    @Test
    void notInOperator() {
        assertOperator(ConditionOperator.NOT_IN, "DE", List.of("US", "UK"), true);
        assertOperator(ConditionOperator.NOT_IN, "US", List.of("US", "UK"), false);
    }

    @Test
    void matchesRegexOperator() {
        assertOperator(ConditionOperator.MATCHES_REGEX, "user@example.com", List.of(".*@example\\.com"), true);
        assertOperator(ConditionOperator.MATCHES_REGEX, "user@other.com", List.of(".*@example\\.com"), false);
    }

    @Test
    void matchesRegexInvalidPatternReturnsFalse() {
        assertOperator(ConditionOperator.MATCHES_REGEX, "hello", List.of("[invalid"), false);
    }

    @Test
    void greaterThanOperator() {
        assertOperator(ConditionOperator.GREATER_THAN, "10", List.of("5"), true);
        assertOperator(ConditionOperator.GREATER_THAN, "5", List.of("10"), false);
    }

    @Test
    void lessThanOperator() {
        assertOperator(ConditionOperator.LESS_THAN, "5", List.of("10"), true);
        assertOperator(ConditionOperator.LESS_THAN, "10", List.of("5"), false);
    }

    @Test
    void greaterThanOrEqualOperator() {
        assertOperator(ConditionOperator.GREATER_THAN_OR_EQUAL, "10", List.of("10"), true);
        assertOperator(ConditionOperator.GREATER_THAN_OR_EQUAL, "5", List.of("10"), false);
    }

    @Test
    void lessThanOrEqualOperator() {
        assertOperator(ConditionOperator.LESS_THAN_OR_EQUAL, "10", List.of("10"), true);
        assertOperator(ConditionOperator.LESS_THAN_OR_EQUAL, "15", List.of("10"), false);
    }

    @Test
    void beforeOperator() {
        assertOperator(ConditionOperator.BEFORE, "2024-01-01T00:00:00Z", List.of("2025-01-01T00:00:00Z"), true);
        assertOperator(ConditionOperator.BEFORE, "2025-01-01T00:00:00Z", List.of("2024-01-01T00:00:00Z"), false);
    }

    @Test
    void afterOperator() {
        assertOperator(ConditionOperator.AFTER, "2025-01-01T00:00:00Z", List.of("2024-01-01T00:00:00Z"), true);
        assertOperator(ConditionOperator.AFTER, "2024-01-01T00:00:00Z", List.of("2025-01-01T00:00:00Z"), false);
    }

    @Test
    void negateInvertsResult() {
        Condition cond = new Condition();
        cond.setAttribute("country");
        cond.setOperator(ConditionOperator.EQUALS);
        cond.setValues(List.of("US"));
        cond.setNegate(true);

        EvaluationContext ctx = EvaluationContext.builder("user-1").set("country", "US").build();
        boolean result = evaluator.evaluateCondition(cond, ctx);
        assertThat(result).isFalse(); // negated: US equals US is true, negated = false
    }

    @Test
    void emptyStringDoesNotMatchEquals() {
        assertOperator(ConditionOperator.EQUALS, "", List.of("US"), false);
    }

    @Test
    void missingAttributeReturnsNonMatch() {
        // Missing attribute should return false (non-match) for positive conditions
        Condition cond = new Condition();
        cond.setAttribute("missing");
        cond.setOperator(ConditionOperator.NOT_EQUALS);
        cond.setValues(List.of("US"));
        cond.setNegate(false);

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        boolean result = evaluator.evaluateCondition(cond, ctx);
        assertThat(result).isFalse(); // missing attr = non-match, not true
    }

    @Test
    void missingAttributeWithNegateReturnsTrue() {
        // Missing attribute + negate should return true
        Condition cond = new Condition();
        cond.setAttribute("missing");
        cond.setOperator(ConditionOperator.EQUALS);
        cond.setValues(List.of("US"));
        cond.setNegate(false);

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        assertThat(evaluator.evaluateCondition(cond, ctx)).isFalse();

        // With negate, missing returns true
        cond.setNegate(true);
        assertThat(evaluator.evaluateCondition(cond, ctx)).isTrue();
    }

    // --- Rollout Bucketing Tests ---

    @Test
    void bucketingIsDeterministic() {
        int bucket1 = evaluator.calculateBucket("salt", "user-1");
        int bucket2 = evaluator.calculateBucket("salt", "user-1");
        assertThat(bucket1).isEqualTo(bucket2);
    }

    @Test
    void bucketingReturnsValueInRange() {
        for (int i = 0; i < 100; i++) {
            int bucket = evaluator.calculateBucket("salt", "user-" + i);
            assertThat(bucket).isBetween(0, 99);
        }
    }

    @Test
    void bucketingDistributesRoughlyEvenly() {
        int[] buckets = new int[2]; // 50/50
        for (int i = 0; i < 10000; i++) {
            int bucket = evaluator.calculateBucket("salt", "user-" + i);
            buckets[bucket < 50 ? 0 : 1]++;
        }
        // Each half should be roughly 5000 (within 10%)
        assertThat(buckets[0]).isBetween(4000, 6000);
        assertThat(buckets[1]).isBetween(4000, 6000);
    }

    @Test
    void rolloutServesCorrectVariation() {
        WeightedVariation v1 = new WeightedVariation();
        v1.setKey("control");
        v1.setWeight(50);
        WeightedVariation v2 = new WeightedVariation();
        v2.setKey("treatment");
        v2.setWeight(50);

        ServeConfig rollout = new ServeConfig();
        rollout.setType(ServeType.ROLLOUT);
        rollout.setBucketBy("userId");
        rollout.setSalt("test-salt");
        rollout.setVariations(List.of(v1, v2));

        // Same user always gets same variation
        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        String result1 = evaluator.resolveServeConfig(rollout, ctx, "flag");
        String result2 = evaluator.resolveServeConfig(rollout, ctx, "flag");
        assertThat(result1).isEqualTo(result2);
    }

    @Test
    void rolloutUsesCustomBucketBy() {
        WeightedVariation v1 = new WeightedVariation();
        v1.setKey("control");
        v1.setWeight(50);
        WeightedVariation v2 = new WeightedVariation();
        v2.setKey("treatment");
        v2.setWeight(50);

        ServeConfig rollout = new ServeConfig();
        rollout.setType(ServeType.ROLLOUT);
        rollout.setBucketBy("orgId");
        rollout.setSalt("salt");
        rollout.setVariations(List.of(v1, v2));

        EvaluationContext ctx = EvaluationContext.builder("user-1").set("orgId", "org-1").build();
        String result = evaluator.resolveServeConfig(rollout, ctx, "flag");
        assertThat(result).isIn("control", "treatment");
    }

    @Test
    void defaultBucketByUsesCustomUserIdAttribute() {
        // When user sets "userId" as a custom attribute (different from builder userId),
        // the default bucketBy should use the custom attribute value, not the builder userId.
        // This matches JS/Python/Go/PHP/Ruby SDK behavior.

        // Verify the two values produce different buckets (so the test is meaningful)
        int bucketCustom = evaluator.calculateBucket("test-salt", "custom-user");
        int bucketBuilder = evaluator.calculateBucket("test-salt", "builder-user");
        assertThat(bucketCustom).isNotEqualTo(bucketBuilder);

        // Use a weight split that places them in different variations
        int boundary = Math.max(bucketCustom, bucketBuilder);
        WeightedVariation v1 = new WeightedVariation();
        v1.setKey("control");
        v1.setWeight(boundary);
        WeightedVariation v2 = new WeightedVariation();
        v2.setKey("treatment");
        v2.setWeight(100 - boundary);

        ServeConfig rolloutDefault = new ServeConfig();
        rolloutDefault.setType(ServeType.ROLLOUT);
        // No bucketBy set — should default to "userId"
        rolloutDefault.setSalt("test-salt");
        rolloutDefault.setVariations(List.of(v1, v2));

        ServeConfig rolloutExplicit = new ServeConfig();
        rolloutExplicit.setType(ServeType.ROLLOUT);
        rolloutExplicit.setBucketBy("userId");
        rolloutExplicit.setSalt("test-salt");
        rolloutExplicit.setVariations(List.of(v1, v2));

        // Custom attribute "userId" differs from builder userId
        EvaluationContext ctx = EvaluationContext.builder("builder-user")
                .set("userId", "custom-user")
                .build();

        String resultDefault = evaluator.resolveServeConfig(rolloutDefault, ctx, "flag");
        String resultExplicit = evaluator.resolveServeConfig(rolloutExplicit, ctx, "flag");

        // Default should behave same as explicit "userId" — both use custom attribute
        assertThat(resultDefault).isEqualTo(resultExplicit);
    }

    // --- Segment Evaluation Tests ---

    @Test
    void ruleWithSegmentKeyUsesSegmentConditions() {
        // Create a segment with a condition
        Condition segCond = new Condition();
        segCond.setAttribute("country");
        segCond.setOperator(ConditionOperator.EQUALS);
        segCond.setValues(List.of("US"));

        Segment segment = new Segment();
        segment.setKey("us-users");
        segment.setConditions(List.of(segCond));
        segment.setConditionLogic(ConditionLogic.AND);

        // Add segment to store
        store.upsertSegment(segment);

        // Create rule that references the segment
        TargetingRule rule = new TargetingRule();
        rule.setId("rule-1");
        rule.setPriority(0);
        rule.setSegmentKey("us-users");
        rule.setServe(fixedServe("on"));

        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey("test");
        flag.setEnabled(true);
        flag.setRules(List.of(rule));
        flag.setFallthrough(fixedServe("off"));
        flag.setVariations(List.of(boolVariation("on", true), boolVariation("off", false)));

        // User matches segment
        EvaluationContext matchCtx = EvaluationContext.builder("user-1").set("country", "US").build();
        FlagEvaluator.Result matchResult = evaluator.evaluate(flag, matchCtx);
        assertThat(matchResult.getReason()).isEqualTo(EvaluationReason.RULE_MATCH);
        assertThat(matchResult.getVariationKey()).isEqualTo("on");

        // User does NOT match segment
        EvaluationContext noMatchCtx = EvaluationContext.builder("user-2").set("country", "UK").build();
        FlagEvaluator.Result noMatchResult = evaluator.evaluate(flag, noMatchCtx);
        assertThat(noMatchResult.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
        assertThat(noMatchResult.getVariationKey()).isEqualTo("off");
    }

    @Test
    void ruleWithMissingSegmentReturnsFalse() {
        TargetingRule rule = new TargetingRule();
        rule.setId("rule-1");
        rule.setPriority(0);
        rule.setSegmentKey("nonexistent-segment");
        rule.setServe(fixedServe("on"));

        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey("test");
        flag.setEnabled(true);
        flag.setRules(List.of(rule));
        flag.setFallthrough(fixedServe("off"));
        flag.setVariations(List.of(boolVariation("on", true), boolVariation("off", false)));

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        FlagEvaluator.Result result = evaluator.evaluate(flag, ctx);
        assertThat(result.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
        assertThat(result.getVariationKey()).isEqualTo("off");
    }
}
