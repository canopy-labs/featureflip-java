package io.featureflip.client.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.featureflip.client.EvaluationContext;
import io.featureflip.client.EvaluationReason;
import io.featureflip.client.internal.model.*;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Cross-SDK golden-vector parity harness for the Java SDK.
 *
 * Loads the canonical fixture from the classpath ({@code /golden/vectors.json})
 * and drives four assertion families — bucket, rollout, condition, and flag —
 * as one {@link DynamicTest} per vector so each vector appears individually
 * in the test report.
 *
 * Segment resolution: the fixture's {@code flagVectors} carry a {@code segments}
 * array alongside {@code flags}. We load both into a {@link FlagStore} and pass
 * that store to {@link FlagEvaluator}'s constructor, which is how the production
 * path wires segment lookup. Vectors with a non-null {@code segmentKey} on a rule
 * are therefore fully exercised — including {@code f-segment-match} and
 * {@code f-segment-no-match}.
 *
 * Reason normalization: the Java evaluator returns {@link EvaluationReason}
 * UPPER_SNAKE enum values; the fixture uses canonical PascalCase kind strings.
 * A small static map converts between the two for assertion.
 */
class GoldenVectorTest {

    // Canonical reason-kind map: UPPER_SNAKE enum → PascalCase fixture string.
    private static final Map<EvaluationReason, String> KIND_MAP;

    static {
        Map<EvaluationReason, String> m = new HashMap<>();
        m.put(EvaluationReason.FLAG_DISABLED, "FlagDisabled");
        m.put(EvaluationReason.RULE_MATCH, "RuleMatch");
        m.put(EvaluationReason.FALLTHROUGH, "Fallthrough");
        m.put(EvaluationReason.PREREQUISITE_FAILED, "PrerequisiteFailed");
        m.put(EvaluationReason.FLAG_NOT_FOUND, "FlagNotFound");
        m.put(EvaluationReason.ERROR, "Error");
        KIND_MAP = Collections.unmodifiableMap(m);
    }

    private final ObjectMapper mapper = new ObjectMapper();

    // -----------------------------------------------------------------------
    // Bucket vectors
    // -----------------------------------------------------------------------

    @TestFactory
    List<DynamicTest> bucketVectors() throws Exception {
        JsonNode root = loadFixture();
        FlagStore store = new FlagStore();
        FlagEvaluator evaluator = new FlagEvaluator(store);

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : root.get("bucketVectors")) {
            String id = v.get("id").asText();
            String salt = v.get("salt").asText();
            String value = v.get("value").asText();
            int expected = v.get("expectedBucket").asInt();
            tests.add(dynamicTest(id, () -> {
                int got = evaluator.calculateBucket(salt, value);
                assertThat(got)
                    .as("bucket for salt=%s value=%s", salt, value)
                    .isEqualTo(expected);
            }));
        }
        return tests;
    }

    // -----------------------------------------------------------------------
    // Rollout vectors
    // -----------------------------------------------------------------------

    @TestFactory
    List<DynamicTest> rolloutVectors() throws Exception {
        JsonNode root = loadFixture();
        FlagStore store = new FlagStore();
        FlagEvaluator evaluator = new FlagEvaluator(store);

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : root.get("rolloutVectors")) {
            String id = v.get("id").asText();
            String salt = v.get("salt").asText();
            String value = v.get("value").asText();
            String expectedVariation = v.get("expectedVariation").asText();
            JsonNode variationsNode = v.get("variations");

            tests.add(dynamicTest(id, () -> {
                // Build a minimal flag with a Rollout fallthrough using the vector's
                // salt + weighted variations. No rules — bucket falls through directly.
                ObjectNode flagJson = mapper.createObjectNode();
                flagJson.put("key", "rollout-flag");
                flagJson.put("version", 1);
                flagJson.put("type", "String");
                flagJson.put("enabled", true);
                flagJson.put("offVariation", "");

                // Variations list (key + value)
                ArrayNode variationsList = mapper.createArrayNode();
                for (JsonNode wv : variationsNode) {
                    ObjectNode var = mapper.createObjectNode();
                    var.put("key", wv.get("key").asText());
                    var.put("value", wv.get("key").asText());
                    variationsList.add(var);
                }
                flagJson.set("variations", variationsList);

                // Fallthrough = Rollout with the vector's salt + weighted variations
                ObjectNode fallthrough = mapper.createObjectNode();
                fallthrough.put("type", "Rollout");
                fallthrough.put("salt", salt);
                ArrayNode wvList = mapper.createArrayNode();
                for (JsonNode wv : variationsNode) {
                    ObjectNode wvNode = mapper.createObjectNode();
                    wvNode.put("key", wv.get("key").asText());
                    wvNode.put("weight", wv.get("weight").asInt());
                    wvList.add(wvNode);
                }
                fallthrough.set("variations", wvList);
                flagJson.set("fallthrough", fallthrough);

                flagJson.set("rules", mapper.createArrayNode());
                flagJson.set("prerequisites", mapper.createArrayNode());

                FlagConfiguration flag = mapper.treeToValue(flagJson, FlagConfiguration.class);
                EvaluationContext ctx = EvaluationContext.builder(value).build();
                FlagEvaluator.Result result = evaluator.evaluate(flag, ctx, Collections.emptyMap());

                assertThat(result.getVariationKey())
                    .as("rollout variation for salt=%s value=%s", salt, value)
                    .isEqualTo(expectedVariation);
            }));
        }
        return tests;
    }

    // -----------------------------------------------------------------------
    // Condition vectors
    // -----------------------------------------------------------------------

    @TestFactory
    List<DynamicTest> conditionVectors() throws Exception {
        return conditionVectorTests("conditionVectors");
    }

    /**
     * A date operand outside the ISO grammar must match nothing (#2480).
     *
     * <p>Hand-authored because the ENGINE DISSENTS: {@code DateTimeOffset.TryParse} under the
     * invariant culture is lenient about the date FORMAT rather than merely about ISO-8601 — it
     * resolves {@code 05/15/2023}, {@code Jan 1 2024} and {@code 2024.01.01} — so generating these
     * would take the engine's match as the expectation and fail every SDK runner. The engine keeps
     * that leniency deliberately: narrowing it would stop an operand that evaluates today from
     * evaluating at all, for rules customers may already have saved, so Management rejects them on
     * the WRITE path instead.
     *
     * <p>The six SDKs that always rejected these did so as a SIDE EFFECT of their grammar and not
     * one of them asserted it — which is how js came to resolve a non-ISO operand in the host's
     * timezone unnoticed, the same invisible-divergence shape as #1989 and #2281.
     */
    @TestFactory
    List<DynamicTest> dateGrammarVectors() throws Exception {
        return conditionVectorTests("dateGrammarVectors");
    }

    /**
     * Builds the single-condition flag each vector describes and asserts whether the "match"
     * variation is served. Shared by the engine-generated condition vectors and the hand-authored
     * date-grammar vectors, which have an identical input shape.
     */
    private List<DynamicTest> conditionVectorTests(String vectorClass) throws Exception {
        JsonNode root = loadFixture();
        FlagStore store = new FlagStore();
        FlagEvaluator evaluator = new FlagEvaluator(store);

        JsonNode vectors = root.get(vectorClass);
        assertThat(vectors).as("%s must be present in the fixture", vectorClass).isNotNull();
        assertThat(vectors.size()).as("%s must not be empty", vectorClass).isPositive();

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : vectors) {
            String id = v.get("id").asText();
            JsonNode attrNode = v.get("attribute");
            String attrType = attrNode.get("type").asText();
            JsonNode attrValueNode = attrNode.get("value");
            String operator = v.get("operator").asText();
            JsonNode valuesNode = v.get("values");
            boolean negate = v.get("negate").asBoolean();
            boolean expectedMatch = v.get("expectedMatch").asBoolean();

            tests.add(dynamicTest(id, () -> {
                // Build a minimal flag: one rule with one condition group. The rule
                // serves "match"; fallthrough serves "nomatch". Evaluating against the
                // context tells us whether the condition matched.
                ObjectNode conditionJson = mapper.createObjectNode();
                conditionJson.put("attribute", "attr");
                conditionJson.put("operator", operator);
                conditionJson.set("values", valuesNode);
                conditionJson.put("negate", negate);

                ObjectNode groupJson = mapper.createObjectNode();
                groupJson.put("operator", "And");
                ArrayNode conditionsArray = mapper.createArrayNode();
                conditionsArray.add(conditionJson);
                groupJson.set("conditions", conditionsArray);

                ArrayNode groupsArray = mapper.createArrayNode();
                groupsArray.add(groupJson);

                ObjectNode ruleJson = mapper.createObjectNode();
                ruleJson.put("id", "r1");
                ruleJson.put("priority", 0);
                ruleJson.set("conditionGroups", groupsArray);
                ObjectNode serveJson = mapper.createObjectNode();
                serveJson.put("type", "Fixed");
                serveJson.put("variation", "match");
                ruleJson.set("serve", serveJson);
                ruleJson.putNull("segmentKey");

                ArrayNode matchVariation = mapper.createArrayNode();
                ObjectNode vm = mapper.createObjectNode();
                vm.put("key", "match");
                vm.set("value", mapper.getNodeFactory().textNode("match"));
                matchVariation.add(vm);
                ObjectNode vn = mapper.createObjectNode();
                vn.put("key", "nomatch");
                vn.set("value", mapper.getNodeFactory().textNode("nomatch"));
                matchVariation.add(vn);

                ObjectNode flagJson = mapper.createObjectNode();
                flagJson.put("key", "cond-flag");
                flagJson.put("version", 1);
                flagJson.put("type", "String");
                flagJson.put("enabled", true);
                flagJson.put("offVariation", "nomatch");
                flagJson.set("variations", matchVariation);
                ArrayNode rulesArray = mapper.createArrayNode();
                rulesArray.add(ruleJson);
                flagJson.set("rules", rulesArray);
                ObjectNode ftJson = mapper.createObjectNode();
                ftJson.put("type", "Fixed");
                ftJson.put("variation", "nomatch");
                flagJson.set("fallthrough", ftJson);
                flagJson.set("prerequisites", mapper.createArrayNode());

                FlagConfiguration flag = mapper.treeToValue(flagJson, FlagConfiguration.class);

                // Build typed EvaluationContext
                Object typedValue = buildTypedValue(attrType, attrValueNode);
                EvaluationContext ctx = EvaluationContext.builder("test-user")
                    .set("attr", typedValue)
                    .build();

                FlagEvaluator.Result result = evaluator.evaluate(flag, ctx, Collections.emptyMap());
                boolean gotMatch = "match".equals(result.getVariationKey());

                assertThat(gotMatch)
                    .as("condition match for operator=%s attr=%s(%s) values=%s negate=%s",
                        operator, attrType, attrValueNode, valuesNode, negate)
                    .isEqualTo(expectedMatch);
            }));
        }
        return tests;
    }

    // -----------------------------------------------------------------------
    // Flag vectors
    // -----------------------------------------------------------------------

    @TestFactory
    List<DynamicTest> flagVectors() throws Exception {
        JsonNode root = loadFixture();

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : root.get("flagVectors")) {
            String id = v.get("id").asText();
            String flagKey = v.get("flagKey").asText();
            JsonNode flagsNode = v.get("flags");
            JsonNode segmentsNode = v.get("segments");
            JsonNode contextNode = v.get("context");
            JsonNode expectedNode = v.get("expected");

            tests.add(dynamicTest(id, () -> {
                // Load flags into store — also used for allFlags map
                List<FlagConfiguration> flagList = new ArrayList<>();
                Map<String, FlagConfiguration> allFlags = new HashMap<>();
                for (JsonNode fn : flagsNode) {
                    FlagConfiguration fc = mapper.treeToValue(fn, FlagConfiguration.class);
                    flagList.add(fc);
                    allFlags.put(fc.getKey(), fc);
                }

                // Load segments into store so segment-keyed rules resolve
                List<Segment> segmentList = new ArrayList<>();
                for (JsonNode sn : segmentsNode) {
                    Segment seg = mapper.treeToValue(sn, Segment.class);
                    segmentList.add(seg);
                }

                FlagStore store = new FlagStore();
                store.replace(flagList, segmentList);
                FlagEvaluator evaluator = new FlagEvaluator(store);

                // Build EvaluationContext from the vector's context node
                String userId = contextNode.has("userId") ? contextNode.get("userId").asText() : "anonymous";
                EvaluationContext.Builder ctxBuilder = EvaluationContext.builder(userId);
                JsonNode attrsNode = contextNode.get("attributes");
                if (attrsNode != null) {
                    for (Map.Entry<String, JsonNode> entry : attrsNode.properties()) {
                        ctxBuilder.set(entry.getKey(), jsonNodeToObject(entry.getValue()));
                    }
                }
                EvaluationContext ctx = ctxBuilder.build();

                FlagConfiguration targetFlag = allFlags.get(flagKey);
                assertThat(targetFlag).as("flag '%s' must exist in flags array", flagKey).isNotNull();

                FlagEvaluator.Result result = evaluator.evaluate(targetFlag, ctx, allFlags);

                // Assert variation key
                String expectedVariation = expectedNode.get("variation").asText();
                assertThat(result.getVariationKey())
                    .as("[%s] variation", id)
                    .isEqualTo(expectedVariation);

                // Assert value: find variation by key, compare JSON serialization
                Variation variation = targetFlag.getVariationByKey(result.getVariationKey());
                assertThat(variation)
                    .as("[%s] variation '%s' not found in flag", id, result.getVariationKey())
                    .isNotNull();
                String gotValueJson = mapper.writeValueAsString(variation.getValue());
                String expectedValueJson = mapper.writeValueAsString(expectedNode.get("value"));
                assertThat(gotValueJson)
                    .as("[%s] value JSON", id)
                    .isEqualTo(expectedValueJson);

                // Assert reason kind (normalize UPPER_SNAKE → PascalCase)
                JsonNode reasonNode = expectedNode.get("reason");
                String expectedKind = reasonNode.get("kind").asText();
                String gotKind = KIND_MAP.get(result.getReason());
                assertThat(gotKind)
                    .as("[%s] reason kind (got enum=%s)", id, result.getReason())
                    .isEqualTo(expectedKind);

                // Assert ruleId when present in fixture
                if (reasonNode.has("ruleId")) {
                    String expectedRuleId = reasonNode.get("ruleId").asText();
                    assertThat(result.getRuleId())
                        .as("[%s] ruleId", id)
                        .isEqualTo(expectedRuleId);
                }

                // Assert prerequisiteKey when present in fixture
                if (reasonNode.has("prerequisiteKey")) {
                    String expectedPrereqKey = reasonNode.get("prerequisiteKey").asText();
                    assertThat(result.getPrerequisiteKey())
                        .as("[%s] prerequisiteKey", id)
                        .isEqualTo(expectedPrereqKey);
                }
            }));
        }
        return tests;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private JsonNode loadFixture() throws Exception {
        InputStream in = getClass().getResourceAsStream("/golden/vectors.json");
        assertThat(in).as("golden/vectors.json must be on the test classpath").isNotNull();
        return mapper.readTree(in);
    }

    /**
     * Converts a JSON attribute value to a typed Java object so the type-aware
     * numeric coercion path in {@link FlagEvaluator#evaluateCondition} is exercised
     * (the #1458 fix). Boolean attributes are passed as {@link Boolean}, number
     * attributes as {@link Double}, and string attributes as {@link String}.
     */
    private Object buildTypedValue(String type, JsonNode valueNode) {
        switch (type) {
            case "boolean":
                return valueNode.asBoolean();
            case "number":
                return valueNode.asDouble();
            default:
                return valueNode.asText();
        }
    }

    /**
     * Converts a {@link JsonNode} from the context attributes map to a plain Java
     * object. Booleans → {@link Boolean}, numbers → {@link Double},
     * everything else → {@link String}.
     */
    private Object jsonNodeToObject(JsonNode node) {
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.asDouble();
        return node.asText();
    }
}
