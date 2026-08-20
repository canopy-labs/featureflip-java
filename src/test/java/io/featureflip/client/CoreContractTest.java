package io.featureflip.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.internal.FlagStore;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.Segment;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * coreContractVectors — the shared CORE's contract, one layer above the evaluator.
 *
 * <p>{@code GoldenVectorTest} asserts what the evaluator computes, with the .NET
 * engine as its oracle. This class asserts the shared core's client-facing
 * contract (typed-accessor strictness, malformed-variation handling), where the
 * engine has no opinion and in fact disagrees: it returns null where an SDK must
 * return the CALLER'S default. That is why #1989 and #2281 could not be locked
 * with the existing classes, and why both shipped as 6-of-7-SDK divergences that
 * no CI could see. These vectors are hand-authored, not engine-generated.
 *
 * <p>{@code expect.reason} is a CANONICAL token mapped to this SDK's vocabulary.
 *
 * <p>DO NOT edit vectors.json to make Java pass — if a vector fails, fix the SDK.
 */
class CoreContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static EvaluationReason canonicalReason(String token) {
        switch (token) {
            case "Error":
                return EvaluationReason.ERROR;
            case "Fallthrough":
                return EvaluationReason.FALLTHROUGH;
            case "FlagNotFound":
                return EvaluationReason.FLAG_NOT_FOUND;
            default:
                throw new AssertionError("unmapped canonical reason '" + token + "'");
        }
    }

    @TestFactory
    List<DynamicTest> coreContractVectors() throws Exception {
        InputStream in = getClass().getResourceAsStream("/golden/vectors.json");
        assertThat(in).as("golden/vectors.json must be on the test classpath").isNotNull();
        JsonNode root = mapper.readTree(in);

        JsonNode vectors = root.get("coreContractVectors");
        assertThat(vectors).as("fixture must carry coreContractVectors").isNotNull();
        assertThat(vectors.size()).as("runner must not silently execute nothing").isGreaterThanOrEqualTo(14);

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : vectors) {
            final String id = v.get("id").asText();
            final String description = v.get("description").asText();

            tests.add(dynamicTest(id, () -> {
                List<FlagConfiguration> flags = new ArrayList<>();
                for (JsonNode fn : v.get("flags")) {
                    flags.add(mapper.treeToValue(fn, FlagConfiguration.class));
                }

                List<EvaluationEvent> events = new ArrayList<>();
                FlagStore store = new FlagStore();
                store.replace(flags, new ArrayList<Segment>());

                FeatureFlagConfig config = FeatureFlagConfig.builder()
                    .inspectors(Collections.<EvaluationInspector>singletonList(events::add))
                    .build();
                SharedFeatureflipCore core = SharedFeatureflipCore.createForTesting(store, config);

                String flagKey = v.get("flagKey").asText();
                EvaluationContext ctx =
                    EvaluationContext.builder(v.get("context").get("userId").asText()).build();
                JsonNode read = v.get("read");
                JsonNode expect = v.get("expect");
                JsonNode dflt = read.get("default");
                String as = read.get("as").asText();

                Object got;
                switch (as) {
                    case "bool":
                        got = core.evaluate(flagKey, ctx, dflt.asBoolean(), Boolean.class).getValue();
                        break;
                    case "string":
                        got = core.evaluate(flagKey, ctx, dflt.asText(), String.class).getValue();
                        break;
                    case "int":
                        got = core.evaluate(flagKey, ctx, dflt.asInt(), Integer.class).getValue();
                        break;
                    case "number":
                    case "double":
                        got = core.evaluate(flagKey, ctx, dflt.asDouble(), Double.class).getValue();
                        break;
                    default:
                        throw new AssertionError("unmapped read.as '" + as + "'");
                }

                JsonNode want = expect.get("value");
                if (want.isBoolean()) {
                    assertThat(got).as(description).isEqualTo(want.asBoolean());
                } else if (want.isTextual()) {
                    assertThat(got).as(description).isEqualTo(want.asText());
                } else if (want.isNumber()) {
                    // Compare numerically so an int read and a double read of the
                    // same JSON number agree.
                    assertThat(((Number) got).doubleValue())
                        .as(description)
                        .isEqualTo(want.asDouble());
                } else {
                    throw new AssertionError("[" + id + "] unsupported expected value kind");
                }

                // core.evaluate is the choke point every typed accessor funnels
                // through, and it notifies inspectors exactly once — the same
                // surface a real caller observes the reason on.
                assertThat(events).as("[%s] inspector must fire exactly once", id).hasSize(1);
                assertThat(events.get(0).getReason())
                    .as(description)
                    .isEqualTo(canonicalReason(expect.get("reason").asText()));
            }));
        }
        return tests;
    }
}
