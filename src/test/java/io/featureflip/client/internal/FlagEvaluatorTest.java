package io.featureflip.client.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.EvaluationContext;
import io.featureflip.client.EvaluationReason;
import io.featureflip.client.internal.model.*;
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

    // Issue #2262: an operator that cannot be evaluated means "I cannot evaluate this",
    // NOT "this did not match". Inverting that inability with negate would turn it into a
    // match-everyone, serving the flag to 100% of traffic.
    //
    // Java's enum typing means an *unknown* operator cannot exist as a constant — Jackson
    // raises InvalidFormatException before the evaluator sees it — so the reachable shape
    // here is a null operator from a config that omits the field. Before this fix the
    // switch threw NPE on it; now it fails closed, matching the string-typed SDKs
    // (js/go/ruby/php) where an unrecognised operator IS reachable over the wire.
    @Test
    void nullOperatorIsNotEvaluable() {
        assertThat(evaluator.evaluateOperator(null, "US", List.of("US"))).isNull();
    }

    @Test
    void conditionWithNullOperatorFailsClosedRegardlessOfNegate() {
        for (boolean negate : new boolean[] { false, true }) {
            Condition cond = new Condition();
            cond.setAttribute("country");
            cond.setOperator(null);
            cond.setValues(List.of("US"));
            cond.setNegate(negate);

            EvaluationContext ctx = EvaluationContext.builder("user-1").set("country", "US").build();

            assertThat(evaluator.evaluateCondition(cond, ctx))
                .as("null operator with negate=%s must fail closed", negate)
                .isFalse();
        }
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
    void matchesRegexIsCaseSensitive() {
        // Case-sensitive matching mirrors the engine (RegexOptions.None): a
        // mixed-case pattern matches only the exact case.
        assertOperator(ConditionOperator.MATCHES_REGEX, "US", List.of("^US$"), true);
        assertOperator(ConditionOperator.MATCHES_REGEX, "us", List.of("^US$"), false);
        // Case-insensitivity is opt-in via the (?i) inline flag in the pattern.
        assertOperator(ConditionOperator.MATCHES_REGEX, "us", List.of("(?i)^US$"), true);
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
    void numericNonNumericOperandDoesNotMatch() {
        // #1456: a non-numeric operand must contribute no match (mirroring the engine's
        // Double parse). The SDK previously fell back to a lexical String compare, so
        // "gold" > "abc" matched server-incompatibly.
        assertOperator(ConditionOperator.GREATER_THAN, "gold", List.of("abc"), false);
        assertOperator(ConditionOperator.GREATER_THAN_OR_EQUAL, "abc", List.of("-5"), false);
        // An unparseable condition value is skipped but a numeric one still matches.
        assertOperator(ConditionOperator.GREATER_THAN, "15", List.of("abc", "10"), true);
        // Sanity: a genuinely numeric comparison still matches.
        assertOperator(ConditionOperator.GREATER_THAN, "15", List.of("10"), true);
    }

    @Test
    void beforeOperator() {
        assertOperator(ConditionOperator.BEFORE, "2024-01-01T00:00:00Z", List.of("2025-01-01T00:00:00Z"), true);
        assertOperator(ConditionOperator.BEFORE, "2025-01-01T00:00:00Z", List.of("2024-01-01T00:00:00Z"), false);
        assertOperator(ConditionOperator.BEFORE, "2026-06-01T00:00:00Z", List.of("2026-01-01T00:00:00Z"), false);
    }

    @Test
    void afterOperator() {
        assertOperator(ConditionOperator.AFTER, "2025-01-01T00:00:00Z", List.of("2024-01-01T00:00:00Z"), true);
        assertOperator(ConditionOperator.AFTER, "2024-01-01T00:00:00Z", List.of("2025-01-01T00:00:00Z"), false);
        assertOperator(ConditionOperator.AFTER, "2026-06-01T00:00:00Z", List.of("2026-01-01T00:00:00Z"), true);
    }

    @Test
    void beforeAfterHonorTimezoneOffsets() {
        // #1455: an offset-bearing value must be normalised to UTC before comparison.
        // 12:00+05:00 is 07:00Z, which is before 08:00Z.
        assertOperator(ConditionOperator.BEFORE, "2026-01-01T12:00:00+05:00", List.of("2026-01-01T08:00:00Z"), true);
        assertOperator(ConditionOperator.AFTER, "2026-01-01T12:00:00+05:00", List.of("2026-01-01T08:00:00Z"), false);
    }

    @Test
    void beforeAfterAssumeUtcWhenNoOffsetPresent() {
        // #1455: a value with no timezone offset is assumed UTC (mirrors the engine's
        // AssumeUniversal). The old Instant.parse rejected this and fell back to a lexical
        // String compare; here 08:00 (UTC) is before 09:00Z.
        assertOperator(ConditionOperator.BEFORE, "2026-01-01T08:00:00", List.of("2026-01-01T09:00:00Z"), true);
        assertOperator(ConditionOperator.AFTER, "2026-01-01T08:00:00", List.of("2026-01-01T09:00:00Z"), false);
    }

    @Test
    void beforeAfterSupportUnixSecondsValue() {
        // #1455: an integer value is treated as Unix time in seconds. 1700000000 → 2023-11-14.
        assertOperator(ConditionOperator.AFTER, "1700000000", List.of("2020-01-01T00:00:00Z"), true);
        assertOperator(ConditionOperator.BEFORE, "1700000000", List.of("2020-01-01T00:00:00Z"), false);
    }

    @Test
    void beforeAfterSupportUnixSecondsConditionValue() {
        // #1455: a condition value may itself be Unix seconds. 1700000000 → 2023-11-14,
        // which is before 2023-11-15.
        assertOperator(ConditionOperator.AFTER, "2023-11-15T00:00:00Z", List.of("1700000000"), true);
    }

    @Test
    void beforeAfterRejectNonAsciiUnicodeDigits() {
        // #2467: Long.parseLong resolves digits through Character.digit, which accepts every
        // character in Unicode category Nd -- not just ASCII 0-9. The engine's
        // long.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, ...) rejects
        // them, and so does its DateTimeOffset.TryParse, so a non-ASCII digit string is not a
        // date to the engine at all. Without an explicit ASCII gate java resolved "\u0661\u0662\u0663"
        // to 123 seconds past the epoch and matched where every other implementation did not.
        for (String operand : List.of(
                "\u0661\u0662\u0663",   // Arabic-Indic 123
                "+\u0661\u0662\u0663",  // ... with the leading sign long.TryParse would allow on ASCII
                "\uFF15",       // fullwidth 5
                "\u07C5",       // N'Ko 5
                "\u0660")) {    // Arabic-Indic 0 -- would be the epoch itself
            assertOperator(ConditionOperator.AFTER, operand, List.of("0"), false);
            assertOperator(ConditionOperator.BEFORE, operand, List.of("253402300799"), false);
            // Unparseable on the condition-value side too: it is skipped, leaving no match.
            assertOperator(ConditionOperator.AFTER, "2023-11-15T00:00:00Z", List.of(operand), false);
        }
    }

    @Test
    void beforeAfterStillAcceptAsciiUnixSecondsWithSign() {
        // The #2467 ASCII gate must not narrow the sign class the engine's NumberStyles.Integer
        // accepts: a leading + or - stays a valid unix-seconds operand.
        assertOperator(ConditionOperator.AFTER, "+1700000000", List.of("2020-01-01T00:00:00Z"), true);
        assertOperator(ConditionOperator.BEFORE, "-315619200", List.of("1970-01-01T00:00:00Z"), true);
        assertOperator(ConditionOperator.AFTER, "2023-11-15T00:00:00Z", List.of("+1700000000"), true);
    }

    @Test
    void beforeAfterUnparseableValueDoesNotMatch() {
        // #1455: an unparseable value must contribute NO match — never a lexical
        // compareToIgnoreCase fallback. The old code returned true here ("hello" vs "world").
        assertOperator(ConditionOperator.BEFORE, "hello", List.of("world"), false);
        assertOperator(ConditionOperator.AFTER, "hello", List.of("world"), false);
    }

    @Test
    void beforeAfterMatchAnyConditionValue() {
        // Mirrors the engine's "any-of" semantics: true if the date satisfies the operator
        // against ANY supplied condition value.
        assertOperator(ConditionOperator.AFTER, "2026-03-01T00:00:00Z",
            List.of("2030-01-01T00:00:00Z", "2020-01-01T00:00:00Z"), true);
    }

    @Test
    void beforeAfterSkipUnparseableConditionValues() {
        // An unparseable condition value is skipped, but a parseable one still matches.
        assertOperator(ConditionOperator.BEFORE, "2026-01-01T07:30:00Z",
            List.of("garbage", "2026-01-01T08:00:00Z"), true);
    }

    @Test
    void beforeAfterEmptyValuesDoesNotMatch() {
        // No condition values → no match, and no exception.
        assertOperator(ConditionOperator.BEFORE, "2026-01-01T00:00:00Z", Collections.emptyList(), false);
        assertOperator(ConditionOperator.AFTER, "2026-01-01T00:00:00Z", Collections.emptyList(), false);
    }

    @Test
    void semverEqualsOperator() {
        assertOperator(ConditionOperator.SEMVER_EQUALS, "2.0.0", List.of("2.0"), true); // 2.0 == 2.0.0
        assertOperator(ConditionOperator.SEMVER_EQUALS, "2.0.1", List.of("2.0.0"), false);
    }

    @Test
    void semverGreaterThanOperator() {
        // Multi-segment regression: decimal comparison mis-parsed "2.10.1" and got these wrong.
        assertOperator(ConditionOperator.SEMVER_GREATER_THAN, "2.10.1", List.of("2.0"), true);
        assertOperator(ConditionOperator.SEMVER_GREATER_THAN, "2.10", List.of("2.9"), true);
        assertOperator(ConditionOperator.SEMVER_GREATER_THAN, "2.0", List.of("2.0.0"), false);
    }

    @Test
    void semverGreaterThanOrEqualOperator() {
        // The canonical #1409 regression: 2.10.1 >= 2.0 (decimal path returned false).
        assertOperator(ConditionOperator.SEMVER_GREATER_THAN_OR_EQUAL, "2.10.1", List.of("2.0"), true);
        assertOperator(ConditionOperator.SEMVER_GREATER_THAN_OR_EQUAL, "2.0", List.of("2.0.0"), true);
        assertOperator(ConditionOperator.SEMVER_GREATER_THAN_OR_EQUAL, "1.9.9", List.of("2.0"), false);
    }

    @Test
    void semverLessThanOperator() {
        assertOperator(ConditionOperator.SEMVER_LESS_THAN, "1.9.9", List.of("2.0"), true);
        assertOperator(ConditionOperator.SEMVER_LESS_THAN, "2.10.1", List.of("2.0"), false);
    }

    @Test
    void semverLessThanOrEqualOperator() {
        assertOperator(ConditionOperator.SEMVER_LESS_THAN_OR_EQUAL, "1.9.9", List.of("2.0"), true);
        assertOperator(ConditionOperator.SEMVER_LESS_THAN_OR_EQUAL, "2.0.0", List.of("2.0"), true);
        assertOperator(ConditionOperator.SEMVER_LESS_THAN_OR_EQUAL, "2.10.1", List.of("2.0"), false);
    }

    @Test
    void semverMatchesAnyConditionValue() {
        // Mirrors the engine/JS "any-of" semantics: true if the version satisfies the operator
        // against ANY supplied condition value, not just the first.
        assertOperator(ConditionOperator.SEMVER_EQUALS, "3.1.4", List.of("1.0", "3.1.4", "9.9.9"), true);
        assertOperator(ConditionOperator.SEMVER_GREATER_THAN, "5.0.0", List.of("9.0", "4.9"), true);
    }

    @Test
    void semverUnparseableInputsDoNotMatch() {
        // Unparseable attribute value never matches (consistent with numeric/date operators).
        assertOperator(ConditionOperator.SEMVER_GREATER_THAN, "not-a-version", List.of("2.0"), false);
        // An unparseable condition value contributes no match but doesn't fail the others.
        assertOperator(ConditionOperator.SEMVER_GREATER_THAN, "3.0.0", List.of("not-a-version", "2.0"), true);
    }

    @Test
    void semverEmptyValuesDoesNotMatch() {
        // No condition values → no match, and no exception.
        assertOperator(ConditionOperator.SEMVER_GREATER_THAN_OR_EQUAL, "2.0.0", Collections.emptyList(), false);
    }

    // --- Type-aware numeric Equals/In coercion (#1458) ---

    /**
     * Builds a single-condition context evaluation against a native (non-stringified)
     * attribute value so the type-aware numeric branch in {@link FlagEvaluator#evaluateCondition}
     * is exercised. Mirrors the engine: when the attribute is a Number (booleans excluded),
     * EQUALS/NOT_EQUALS/IN/NOT_IN compare the condition literals numerically.
     */
    private void assertNumericCondition(Object attrValue, ConditionOperator op, List<String> values,
                                        boolean negate, boolean expected) {
        Condition cond = new Condition();
        cond.setAttribute("attr");
        cond.setOperator(op);
        cond.setValues(values);
        cond.setNegate(negate);

        EvaluationContext ctx = EvaluationContext.builder("user-1").set("attr", attrValue).build();
        boolean result = evaluator.evaluateCondition(cond, ctx);
        assertThat(result)
            .as("attr=%s (%s) op=%s values=%s negate=%s", attrValue,
                attrValue != null ? attrValue.getClass().getSimpleName() : "null", op, values, negate)
            .isEqualTo(expected);
    }

    private void assertNumericCondition(Object attrValue, ConditionOperator op, List<String> values, boolean expected) {
        assertNumericCondition(attrValue, op, values, false, expected);
    }

    @Test
    void numericEqualsCoercesNativeNumbers() {
        // Double 1.0 matches "1.0" and "1"
        assertNumericCondition(1.0, ConditionOperator.EQUALS, List.of("1.0"), true);
        assertNumericCondition(1.0, ConditionOperator.EQUALS, List.of("1"), true);
        // Integer 1 matches "1.0" and "1" (1 == 1.0)
        assertNumericCondition(1, ConditionOperator.EQUALS, List.of("1.0"), true);
        assertNumericCondition(1, ConditionOperator.EQUALS, List.of("1"), true);
        // Fractional values
        assertNumericCondition(1.5, ConditionOperator.EQUALS, List.of("1.5"), true);
        assertNumericCondition(1.5, ConditionOperator.EQUALS, List.of("1"), false);
    }

    @Test
    void numericInCoercesNativeNumbers() {
        assertNumericCondition(2, ConditionOperator.IN, List.of("1", "2.0"), true);
        assertNumericCondition(3, ConditionOperator.IN, List.of("1", "2"), false);
    }

    @Test
    void numericNotEqualsAndNotInNegateTheMatch() {
        assertNumericCondition(1.0, ConditionOperator.NOT_EQUALS, List.of("1.0"), false);
        assertNumericCondition(1.0, ConditionOperator.NOT_EQUALS, List.of("2"), true);
        assertNumericCondition(3, ConditionOperator.NOT_IN, List.of("1", "2"), true);
    }

    @Test
    void numericEqualsStrictLiteralParse() {
        // Non-numeric and partially-numeric literals contribute no match (strict parse)
        assertNumericCondition(1, ConditionOperator.EQUALS, List.of("abc"), false);
        assertNumericCondition(1, ConditionOperator.EQUALS, List.of("1abc"), false);
    }

    @Test
    void booleanAttributeNotCoercedToNumeric() {
        // Java's Boolean does NOT implement Number, so it stays on the string path.
        // "true".equals("1") is false; "true".equals("true") is true.
        assertNumericCondition(true, ConditionOperator.EQUALS, List.of("1"), false);
        assertNumericCondition(true, ConditionOperator.EQUALS, List.of("true"), true);
    }

    @Test
    void stringAttributeNotCoercedToNumeric() {
        // A genuine String attribute keeps string semantics — "1.0" != "1", leading zeros preserved.
        assertNumericCondition("1.0", ConditionOperator.EQUALS, List.of("1"), false);
        assertNumericCondition("01234", ConditionOperator.EQUALS, List.of("1234"), false);
    }

    @Test
    void numericEqualsWithNegateInverts() {
        // Numeric mismatch (1 != 2) → result false → negate → true.
        assertNumericCondition(1, ConditionOperator.EQUALS, List.of("2"), true, true);
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
        String result1 = evaluator.resolveServeConfig(rollout, ctx);
        String result2 = evaluator.resolveServeConfig(rollout, ctx);
        assertThat(result1).isEqualTo(result2);
    }

    @Test
    void rolloutWithNoVariationsServesDefault() {
        // Env-level PercentageRollout emits a Rollout serve with its default variation set but
        // no weighted variations (no per-variation weight storage at the env level, #1469). The
        // evaluator degrades to the default variation instead of dereferencing a null/empty
        // variations list. Regression lock — the guard already exists in the SDK evaluator.
        ServeConfig rollout = new ServeConfig();
        rollout.setType(ServeType.ROLLOUT);
        rollout.setBucketBy("userId");
        rollout.setVariation("off");
        // variations intentionally left null

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        String result = evaluator.resolveServeConfig(rollout, ctx);
        assertThat(result).isEqualTo("off");
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
        String result = evaluator.resolveServeConfig(rollout, ctx);
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

        String resultDefault = evaluator.resolveServeConfig(rolloutDefault, ctx);
        String resultExplicit = evaluator.resolveServeConfig(rolloutExplicit, ctx);

        // Default should behave same as explicit "userId" — both use custom attribute
        assertThat(resultDefault).isEqualTo(resultExplicit);
    }

    private static ServeConfig thinControlRollout(String bucketBy) {
        WeightedVariation control = new WeightedVariation();
        control.setKey("on");
        control.setWeight(1);
        WeightedVariation rest = new WeightedVariation();
        rest.setKey("off");
        rest.setWeight(99);
        ServeConfig rollout = new ServeConfig();
        rollout.setType(ServeType.ROLLOUT);
        rollout.setBucketBy(bucketBy);
        rollout.setSalt("test-salt");
        rollout.setVariations(List.of(control, rest));
        return rollout;
    }

    // #4236: a rollout that can't bucket the context is skipped (null), not served
    // its first variation. This replaces the #1457 "serve control" behaviour.
    @Test
    void keylessContextCannotBucketRollout() {
        ServeConfig rollout = thinControlRollout("userId");
        EvaluationContext ctx = EvaluationContext.builder("").build();

        for (int i = 0; i < 20; i++) {
            assertThat(evaluator.resolveServeConfig(rollout, ctx)).as("eval #%d", i).isNull();
        }
    }

    // #2665: the anonymous context reaches the SAME guard as builder("") and must not
    // NPE on the null identity; both spellings skip.
    @Test
    void anonymousContextCannotBucketLikeAnEmptyUserId() {
        ServeConfig rollout = thinControlRollout("userId");
        EvaluationContext anonymous = EvaluationContext.builder().set("plan", "pro").build();

        for (int i = 0; i < 20; i++) {
            assertThat(evaluator.resolveServeConfig(rollout, anonymous)).as("eval #%d", i).isNull();
        }
    }

    @Test
    void whitespaceAndZeroLikeValuesStillBucket() {
        ServeConfig rollout = thinControlRollout("tenant");
        for (Object v : new Object[] {" ", "0", 0, false}) {
            EvaluationContext ctx = EvaluationContext.builder("u").set("tenant", v).build();
            assertThat(evaluator.resolveServeConfig(rollout, ctx)).as("value %s", v).isNotNull();
        }
    }

    @Test
    void customBucketByMissingEmptyOrNullCannotBucket() {
        ServeConfig rollout = thinControlRollout("anonymous_id");
        assertThat(evaluator.resolveServeConfig(rollout, EvaluationContext.builder("u").build())).isNull();
        assertThat(evaluator.resolveServeConfig(rollout,
            EvaluationContext.builder("u").set("anonymous_id", "").build())).isNull();
        assertThat(evaluator.resolveServeConfig(rollout,
            EvaluationContext.builder("u").set("anonymous_id", null).build())).isNull();
        assertThat(evaluator.resolveServeConfig(rollout,
            EvaluationContext.builder("u").set("anonymous_id", "abc").build())).isNotNull();
    }

    @Test
    void rolloutWithoutVariationsKeylessDegradesNotSkips() {
        // The #1469 degrade (no variations -> serve.Variation) runs before the
        // can't-bucket check, so a keyless context still gets the degrade value.
        ServeConfig rollout = new ServeConfig();
        rollout.setType(ServeType.ROLLOUT);
        rollout.setBucketBy("userId");
        rollout.setVariation("on");
        EvaluationContext ctx = EvaluationContext.builder("").build();

        assertThat(evaluator.resolveServeConfig(rollout, ctx)).isEqualTo("on");

        rollout.setVariations(Collections.emptyList());
        assertThat(evaluator.resolveServeConfig(rollout, ctx)).isEqualTo("on");
    }

    private FlagConfiguration keylessFlag(String key, boolean enabled, TargetingRule... rules) {
        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey(key);
        flag.setEnabled(enabled);
        flag.setOffVariation("off");
        flag.setRules(List.of(rules));
        flag.setFallthrough(fixedServe("fall"));
        flag.setVariations(List.of(boolVariation("on", true), boolVariation("off", false),
            boolVariation("fall", false), boolVariation("r2", true)));
        return flag;
    }

    private static TargetingRule rule(String id, int priority, ServeConfig serve) {
        TargetingRule r = new TargetingRule();
        r.setId(id);
        r.setPriority(priority);
        r.setServe(serve);
        return r;
    }

    @Test
    void keylessRolloutRuleFallsThrough() {
        FlagConfiguration flag = keylessFlag("eval-fallthrough", true,
            rule("r1", 0, thinControlRollout("userId")));
        FlagEvaluator.Result result = evaluator.evaluate(flag, EvaluationContext.builder("").build());
        assertThat(result.getVariationKey()).isEqualTo("fall");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
    }

    @Test
    void customBucketByMissingWithUserIdPresentFallsThrough() {
        FlagConfiguration flag = keylessFlag("eval-custom-missing", true,
            rule("r1", 0, thinControlRollout("anonymous_id")));
        FlagEvaluator.Result result = evaluator.evaluate(flag, EvaluationContext.builder("user-1").build());
        assertThat(result.getVariationKey()).isEqualTo("fall");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
    }

    @Test
    void keylessRolloutRuleFollowedByFixedRuleServesNextRule() {
        FlagConfiguration flag = keylessFlag("eval-next-rule", true,
            rule("r1", 0, thinControlRollout("userId")),
            rule("r2", 1, fixedServe("r2")));
        FlagEvaluator.Result result = evaluator.evaluate(flag, EvaluationContext.builder("").build());
        assertThat(result.getVariationKey()).isEqualTo("r2");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.RULE_MATCH);
        assertThat(result.getRuleId()).isEqualTo("r2");
    }

    @Test
    void keylessRolloutFallthroughServesFallthroughVariation() {
        FlagConfiguration flag = keylessFlag("eval-fallthrough-rollout", true);
        ServeConfig ft = thinControlRollout("userId");
        ft.setVariation("fall");
        flag.setFallthrough(ft);
        FlagEvaluator.Result result = evaluator.evaluate(flag, EvaluationContext.builder("").build());
        assertThat(result.getVariationKey()).isEqualTo("fall");
        assertThat(result.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
    }

    // Warning behaviour. Dedup is process-wide, so each test uses its own flag key.
    private List<String> captureWarnings(Runnable body) {
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.Consumer<String> prev = UnbucketableWarnings.shared().setSink(lines::add);
        try {
            body.run();
        } finally {
            UnbucketableWarnings.shared().setSink(prev);
        }
        return lines;
    }

    @Test
    void keylessEvaluationWarnsOncePerFlagAndRuleWithoutLeakingValues() {
        FlagConfiguration flag = keylessFlag("warn-once-flag", true,
            rule("r1", 0, thinControlRollout("userId")));
        EvaluationContext ctx = EvaluationContext.builder("").set("email", "secret@example.com").build();

        List<String> lines = captureWarnings(() -> {
            evaluator.evaluate(flag, ctx);
            evaluator.evaluate(flag, ctx);
        });

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
            .contains("warn-once-flag").contains("rule r1").contains("userId")
            .doesNotContain("secret@example.com");
    }

    @Test
    void disabledFlagKeylessWarnsNothing() {
        FlagConfiguration flag = keylessFlag("warn-disabled-flag", false,
            rule("r1", 0, thinControlRollout("userId")));
        List<String> lines = captureWarnings(() ->
            evaluator.evaluate(flag, EvaluationContext.builder("").build()));
        assertThat(lines).isEmpty();
    }

    @Test
    void rolloutWithoutVariationsDoesNotWarn() {
        ServeConfig rollout = new ServeConfig();
        rollout.setType(ServeType.ROLLOUT);
        rollout.setBucketBy("userId");
        rollout.setVariation("on");
        FlagConfiguration flag = keylessFlag("warn-degrade-flag", true, rule("r1", 0, rollout));
        List<String> lines = captureWarnings(() -> {
            FlagEvaluator.Result r = evaluator.evaluate(flag, EvaluationContext.builder("").build());
            assertThat(r.getVariationKey()).isEqualTo("on");
        });
        assertThat(lines).isEmpty();
    }

    @Test
    void fallthroughWarningNamesFallthrough() {
        FlagConfiguration flag = keylessFlag("warn-fallthrough-flag", true);
        flag.setFallthrough(thinControlRollout("userId"));
        List<String> lines = captureWarnings(() ->
            evaluator.evaluate(flag, EvaluationContext.builder("").build()));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("warn-fallthrough-flag").contains("(fallthrough)");
    }

    @Test
    void concurrentKeylessEvaluationsWarnExactlyOnce() throws Exception {
        FlagConfiguration flag = keylessFlag("warn-concurrent-flag", true,
            rule("r1", 0, thinControlRollout("userId")));
        EvaluationContext ctx = EvaluationContext.builder("").build();
        int threads = 32;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        List<String> lines = captureWarnings(() -> {
            try {
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        evaluator.evaluate(flag, ctx);
                        return null;
                    }));
                }
                start.countDown();
                for (java.util.concurrent.Future<?> f : futures) f.get();
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                pool.shutdownNow();
            }
        });

        assertThat(lines).hasSize(1);
    }

    private static ServeConfig rolloutServe(String salt) {
        WeightedVariation on = new WeightedVariation();
        on.setKey("on");
        on.setWeight(50);
        WeightedVariation off = new WeightedVariation();
        off.setKey("off");
        off.setWeight(50);
        ServeConfig s = new ServeConfig();
        s.setType(ServeType.ROLLOUT);
        s.setBucketBy("userId");
        s.setSalt(salt);
        s.setVariations(List.of(on, off));
        return s;
    }

    @Test
    void rolloutNullSaltBucketsLikeEmptySalt() {
        // A null salt must hash as "" (engine behavior), not the flag key.
        // Pre-fix: null -> flagKey "rollout-flag" -> diverges for some users -> FAIL.
        ServeConfig nullSalt = rolloutServe(null);
        ServeConfig emptySalt = rolloutServe("");
        for (int i = 0; i < 50; i++) {
            EvaluationContext ctx = EvaluationContext.builder("user-" + i).build();
            assertThat(evaluator.resolveServeConfig(nullSalt, ctx))
                .isEqualTo(evaluator.resolveServeConfig(emptySalt, ctx));
        }
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

    @Test
    void ruleWithSegmentKeyAndNoStoreReturnsFalse() {
        // A non-empty segmentKey with no segment source (null store) must fail
        // closed (no match), mirroring the engine + C# SDK, rather than falling
        // through to the rule's empty condition groups (which match
        // unconditionally). See #1459.
        FlagEvaluator noStoreEvaluator = new FlagEvaluator(null);

        TargetingRule rule = new TargetingRule();
        rule.setId("rule-1");
        rule.setPriority(0);
        rule.setSegmentKey("beta-users");
        rule.setServe(fixedServe("on"));

        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey("test");
        flag.setEnabled(true);
        flag.setRules(List.of(rule));
        flag.setFallthrough(fixedServe("off"));
        flag.setVariations(List.of(boolVariation("on", true), boolVariation("off", false)));

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        FlagEvaluator.Result result = noStoreEvaluator.evaluate(flag, ctx);
        assertThat(result.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
        assertThat(result.getVariationKey()).isEqualTo("off");
    }
}
