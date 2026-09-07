package io.featureflip.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * eventPayloadVectors — what {@code identify()} and {@code track()} actually put on
 * the wire: {@code {type, flagKey, userId?, variation?, timestamp, metadata?}}.
 *
 * <p>That shape had no executable spec at all, which is the direct cause of #2359 —
 * a three-way payload divergence across six server SDKs (js/node/python forwarded the
 * caller's attributes as {@code metadata}; php/go/ruby discarded them) sat unnoticed
 * indefinitely. Nothing compared an emitted event against an expected shape, and the
 * receiving end reduces every event to a counter tuple, so no downstream assertion
 * caught it either. Java's own share of that gap was larger: it modelled
 * {@code SdkEventType.IDENTIFY} for months with no method able to emit it.
 *
 * <p>Hand-authored rather than engine-generated, because the engine emits no events:
 * it returns an EvaluationResult, and the payload is built a layer above that. See
 * {@code tools/golden-vectors/README.md} for the full runner contract.
 *
 * <p>DO NOT edit vectors.json to make Java pass — if a vector fails, fix the SDK.
 */
class GoldenEventPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The context capabilities this SDK has. {@code anonymousContext} is present
     * since #2665; {@code mapContext} is absent, so the vectors needing it are
     * skipped explicitly rather than quietly passing.
     *
     * <p>{@code mapContext}: EvaluationContext keeps the identity in its own field,
     * so which SPELLING a caller used is not a question this SDK can be asked; a
     * runner mapping {@code user_id ?? userId} into that field would be testing its
     * own mapping. That is a design choice go shares, not a defect.
     *
     * <p>{@code anonymousContext}: {@code EvaluationContext.builder()} builds a
     * context with attributes and no identity. Until #2665 only
     * {@code builder(userId)} existed and it rejected a null id, so this token was
     * absent — java could reach an anonymous event only by passing a null context,
     * which carries no attributes either, making
     * {@code ep-identify-omits-absent-user-id} inexpressible.
     */
    private static final Set<String> CAPABILITIES = Set.of("anonymousContext");

    /**
     * An ISO-8601 instant that designates UTC. Deliberately not an equality check:
     * the precision and the zero-offset spelling differ legitimately per SDK (this
     * one emits Instant.toString, whose fraction is variable-width), so a literal
     * expectation would lock in a divergence rather than a contract.
     */
    private static final Pattern UTC_INSTANT =
        Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|\\+00:00)$");

    private static final String FLAGS_JSON =
        "{\"environment\":\"test\",\"version\":1,\"flags\":[],\"segments\":[]}";

    @TestFactory
    Iterable<DynamicTest> eventPayloadVectors() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/golden/vectors.json")) {
            assertThat(in).as("golden/vectors.json on the test classpath").isNotNull();
            root = mapper.readTree(in);
        }

        JsonNode vectors = root.get("eventPayloadVectors");
        assertThat(vectors).as("eventPayloadVectors in fixture").isNotNull();
        assertThat(vectors.isEmpty()).isFalse();

        List<DynamicTest> tests = new ArrayList<>();
        int executed = 0;

        for (JsonNode v : vectors) {
            if (!supported(v)) {
                continue;
            }
            executed++;
            tests.add(dynamicTest(v.get("id").asText(), () -> runVector(v)));
        }

        // A runner that silently skips everything is worse than no runner at all.
        int ran = executed;
        tests.add(dynamicTest("executed the expected number of vectors", () ->
            assertThat(ran)
                .as("event payload vectors executed — did a skip rule widen?")
                .isGreaterThanOrEqualTo(10)));

        return tests;
    }

    private static boolean supported(JsonNode vector) {
        JsonNode requires = vector.get("requires");
        if (requires == null) {
            return true;
        }
        for (JsonNode c : requires) {
            if (!CAPABILITIES.contains(c.asText())) {
                return false;
            }
        }
        return true;
    }

    private void runVector(JsonNode v) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder()
                .body(FLAGS_JSON)
                .addHeader("Content-Type", "application/json")
                .build());
            server.enqueue(new MockResponse.Builder().code(202).build());
            server.start();

            FeatureflipClient client = FeatureflipClient.builder("events-" + v.get("id").asText())
                .baseUrl(server.url("/").toString())
                .streaming(false)
                .pollInterval(Duration.ofHours(1))
                .build();

            try {
                client.waitForInitialization();

                EvaluationContext context = contextFrom(v.get("context"));
                if ("identify".equals(v.get("kind").asText())) {
                    client.identify(context);
                } else {
                    // A vector with no `metadata` key omits the argument entirely,
                    // which must put the same bytes on the wire as an empty bag.
                    Map<String, Object> metadata = v.has("metadata")
                        ? mapper.convertValue(v.get("metadata"), new TypeReference<Map<String, Object>>() {})
                        : null;
                    client.track(v.get("eventKey").asText(), context, metadata);
                }
                client.flush();
            } finally {
                client.close();
            }

            server.takeRequest(2, TimeUnit.SECONDS); // GET /v1/sdk/flags
            RecordedRequest events = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(events).as("POST to the events endpoint").isNotNull();
            assertThat(events.getUrl().encodedPath()).isEqualTo("/v1/sdk/events");

            // The SERIALIZED body, not the SdkEvent behind it: omission is a
            // serialization-time property (NON_NULL here, `omitempty` in go), and an
            // absent optional is exactly what #2359 was about.
            JsonNode sent = mapper.readTree(events.getBody().utf8()).get("events");
            assertThat(sent).hasSize(1);
            JsonNode event = sent.get(0);

            // The EXACT field set, not a subset: #2359 was a field being present in
            // three SDKs and absent in three, which a subset assertion cannot see.
            Set<String> gotKeys = new TreeSet<>();
            event.fieldNames().forEachRemaining(gotKeys::add);
            Set<String> wantKeys = new TreeSet<>();
            wantKeys.add("timestamp");
            v.get("expect").fieldNames().forEachRemaining(wantKeys::add);
            assertThat(gotKeys).isEqualTo(wantKeys);

            for (Map.Entry<String, JsonNode> field : v.get("expect").properties()) {
                assertThat(event.get(field.getKey()))
                    .as(field.getKey())
                    .isEqualTo(field.getValue());
            }

            assertThat(event.get("timestamp").asText()).matches(UTC_INSTANT);
        }
    }

    /**
     * Splits the vector's flat context into this SDK's typed EvaluationContext: the
     * identity into its own field, everything else into the attribute bag. A vector
     * with no identity maps to an anonymous context ({@code builder()}) rather than
     * to a null one — a null context carries no attributes, so it could not express
     * {@code ep-identify-omits-absent-user-id} at all (#2665). The null-context path
     * is a different contract and is covered by {@code FeatureflipClientTest}.
     */
    private EvaluationContext contextFrom(JsonNode raw) {
        String userId = null;
        Map<String, Object> attributes = new HashMap<>();
        for (Map.Entry<String, JsonNode> field : raw.properties()) {
            if ("user_id".equals(field.getKey()) || "userId".equals(field.getKey())) {
                userId = field.getValue().asText();
            } else {
                attributes.put(field.getKey(), mapper.convertValue(field.getValue(), Object.class));
            }
        }
        EvaluationContext.Builder builder =
            userId == null ? EvaluationContext.builder() : EvaluationContext.builder(userId);
        attributes.forEach(builder::set);
        return builder.build();
    }
}
