package io.featureflip.client.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.FeatureFlagConfig;
import io.featureflip.client.internal.model.GetFlagsResponse;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Runner for the shared {@code malformedConfigVectors} class (#2315).
 *
 * <p>The rule: a config payload violating the wire contract is discarded WHOLESALE,
 * never partially applied. One server bug (#2279 — enums as integers over SSE but
 * strings over REST) produced five different user-visible outcomes because every SDK
 * improvised; this class makes a sixth improvisation fail a build instead of shipping.
 *
 * <p>Java already rejects these by construction ({@code FAIL_ON_NUMBERS_FOR_ENUMS},
 * #2283). {@code IntegerEnumRejectionTest} pins that per-SDK; this runner pins the same
 * ground from the SHARED fixture, so a divergence between SDKs is what fails.
 *
 * <p>Deliberately drives the <b>shipped</b> mapper via {@link FlagHttpClient#getObjectMapper()}
 * rather than a locally-built one — a test-local replica keeps passing when the real
 * configuration regresses, which is exactly how this class of bug survives.
 */
class GoldenMalformedTest {

    private static ObjectMapper shippedMapper() {
        FeatureFlagConfig config = FeatureFlagConfig.builder().baseUrl("http://localhost:1").build();
        return new FlagHttpClient("test-sdk-key", config).getObjectMapper();
    }

    private static JsonNode block() throws Exception {
        try (InputStream in = GoldenMalformedTest.class.getResourceAsStream("/golden/vectors.json")) {
            assertThat(in).as("golden/vectors.json must be on the test classpath").isNotNull();
            return new ObjectMapper().readTree(in).get("malformedConfigVectors");
        }
    }

    /**
     * Applies the shared seed. A runner whose seed silently failed would "pass" every
     * reject vector for entirely the wrong reason.
     */
    private static FlagStore seededStore(ObjectMapper mapper, JsonNode seed) throws Exception {
        GetFlagsResponse parsed = mapper.treeToValue(seed, GetFlagsResponse.class);
        FlagStore store = new FlagStore();
        store.replace(parsed.getFlags(), parsed.getSegments());
        assertThat(store.getFlag("mc-seed")).as("seed snapshot did not apply").isNotNull();
        return store;
    }

    @TestFactory
    List<DynamicTest> malformedConfigVectors() throws Exception {
        JsonNode block = block();
        JsonNode vectors = block.get("vectors");
        assertThat(vectors.size())
            .as("runner must not silently execute nothing")
            .isGreaterThanOrEqualTo(8);

        ObjectMapper mapper = shippedMapper();
        List<DynamicTest> tests = new ArrayList<>();

        for (JsonNode v : vectors) {
            String id = v.get("id").asText();
            String description = v.get("description").asText();
            String expect = v.get("expect").asText();
            JsonNode payload = v.get("payload");

            tests.add(dynamicTest(id, () -> {
                FlagStore store = seededStore(mapper, block.get("seed"));

                boolean applied;
                try {
                    GetFlagsResponse parsed = mapper.treeToValue(payload, GetFlagsResponse.class);
                    store.replace(parsed.getFlags(), parsed.getSegments());
                    applied = true;
                } catch (Exception e) {
                    applied = false;
                }

                switch (expect) {
                    case "reject":
                        assertThat(applied).as("%s: payload was accepted", description).isFalse();
                        // Wholesale: the previous config still serves, nothing leaked in.
                        assertThat(store.getFlag("mc-seed"))
                            .as("%s: seeded config was replaced", description).isNotNull();
                        assertThat(store.getFlag("mc-bad-type"))
                            .as("%s: rejected payload partially applied", description).isNull();
                        break;
                    case "accept":
                        assertThat(applied)
                            .as("%s: forward-compatible payload was rejected", description).isTrue();
                        assertThat(store.getFlag("mc-accepted-flag"))
                            .as("%s: accepted payload did not apply", description).isNotNull();
                        break;
                    default:
                        throw new AssertionError("unmapped expect " + expect);
                }
            }));
        }

        return tests;
    }
}
