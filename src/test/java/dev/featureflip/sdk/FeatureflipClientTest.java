package dev.featureflip.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureflipClientTest {
    private MockWebServer server;
    private FeatureflipClient client;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    private String flagsJson(boolean flagValue) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(Map.of(
            "environment", "test",
            "version", 1,
            "flags", java.util.List.of(Map.of(
                "key", "test-flag",
                "version", 1,
                "type", "Boolean",
                "enabled", true,
                "variations", java.util.List.of(
                    Map.of("key", "on", "value", true),
                    Map.of("key", "off", "value", false)
                ),
                "rules", java.util.List.of(),
                "fallthrough", Map.of("type", "Fixed", "variation", flagValue ? "on" : "off"),
                "offVariation", "off"
            )),
            "segments", java.util.List.of()
        ));
    }

    @Test
    void boolVariationReturnsEvaluatedValue() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse.Builder()
            .body(flagsJson(true))
            .addHeader("Content-Type", "application/json")
            .build());
        server.start();

        client = FeatureflipClient.builder("test-sdk-key")
            .baseUrl(server.url("/").toString())
            .streaming(false)
            .pollInterval(Duration.ofHours(1))
            .build();
        client.waitForInitialization();

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        assertThat(client.boolVariation("test-flag", ctx, false)).isTrue();
    }

    @Test
    void missingFlagReturnsDefault() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse.Builder()
            .body(flagsJson(true))
            .addHeader("Content-Type", "application/json")
            .build());
        server.start();

        client = FeatureflipClient.builder("test-sdk-key")
            .baseUrl(server.url("/").toString())
            .streaming(false)
            .pollInterval(Duration.ofHours(1))
            .build();
        client.waitForInitialization();

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        assertThat(client.boolVariation("nonexistent", ctx, true)).isTrue();
        assertThat(client.stringVariation("nonexistent", ctx, "default")).isEqualTo("default");
        assertThat(client.intVariation("nonexistent", ctx, 42)).isEqualTo(42);
        assertThat(client.doubleVariation("nonexistent", ctx, 3.14)).isEqualTo(3.14);
    }

    @Test
    void variationDetailIncludesReason() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse.Builder()
            .body(flagsJson(true))
            .addHeader("Content-Type", "application/json")
            .build());
        server.start();

        client = FeatureflipClient.builder("test-sdk-key")
            .baseUrl(server.url("/").toString())
            .streaming(false)
            .pollInterval(Duration.ofHours(1))
            .build();
        client.waitForInitialization();

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        EvaluationDetail<Boolean> detail = client.boolVariationDetail("test-flag", ctx, false);
        assertThat(detail.getValue()).isTrue();
        assertThat(detail.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
    }

    @Test
    void variationDetailForMissingFlagShowsFlagNotFound() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse.Builder()
            .body(flagsJson(true))
            .addHeader("Content-Type", "application/json")
            .build());
        server.start();

        client = FeatureflipClient.builder("test-sdk-key")
            .baseUrl(server.url("/").toString())
            .streaming(false)
            .pollInterval(Duration.ofHours(1))
            .build();
        client.waitForInitialization();

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        EvaluationDetail<Boolean> detail = client.boolVariationDetail("nonexistent", ctx, false);
        assertThat(detail.getReason()).isEqualTo(EvaluationReason.FLAG_NOT_FOUND);
    }

    @Test
    void forTestingReturnsFixedValues() {
        client = FeatureflipClient.forTesting(Map.of(
            "bool-flag", true,
            "string-flag", "hello"
        ));

        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        assertThat(client.boolVariation("bool-flag", ctx, false)).isTrue();
        assertThat(client.stringVariation("string-flag", ctx, "default")).isEqualTo("hello");
        assertThat(client.isInitialized()).isTrue();
    }

    @Test
    void closeIsIdempotent() throws Exception {
        client = FeatureflipClient.forTesting(Map.of("flag", true));
        client.close();
        client.close(); // should not throw
    }
}
