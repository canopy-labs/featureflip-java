package io.featureflip.client.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.featureflip.client.FeatureflipClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.version.VersionUtils;
import io.micronaut.context.exceptions.BeanInstantiationException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Offline: every context points at an unreachable port or a local MockWebServer. */
class FeatureflipFactoryTest {

    private static Map<String, Object> props(Object... extra) {
        Map<String, Object> map = new HashMap<>();
        map.put("featureflip.base-url", "http://localhost:1");
        map.put("featureflip.streaming", false);
        map.put("featureflip.init-timeout", "500ms");
        map.put("featureflip.connect-timeout", "100ms");
        for (int i = 0; i < extra.length; i += 2) map.put((String) extra[i], extra[i + 1]);
        return map;
    }

    @Test
    void providesTheClientAsASingleton() {
        try (ApplicationContext ctx = ApplicationContext.run(props("featureflip.sdk-key", "sdk-mn-singleton"))) {
            FeatureflipClient a = ctx.getBean(FeatureflipClient.class);
            assertThat(a).isSameAs(ctx.getBean(FeatureflipClient.class));
        }
    }

    @Test
    void aUserDefinedClientWins() {
        FeatureflipClient mine = FeatureflipClient.forTesting(Map.of("flag", true));
        try (ApplicationContext ctx = ApplicationContext.builder()
                .properties(props("featureflip.sdk-key", "sdk-mn-user")).build()) {
            ctx.registerSingleton(FeatureflipClient.class, mine);
            ctx.start();
            assertThat(ctx.getBeansOfType(FeatureflipClient.class)).hasSize(1);
            assertThat(ctx.getBean(FeatureflipClient.class)).isSameAs(mine);
        }
    }

    @Test
    void enabledFalseRegistersNothing() {
        try (ApplicationContext ctx = ApplicationContext.run(
                props("featureflip.sdk-key", "sdk-mn-disabled", "featureflip.enabled", false))) {
            assertThat(ctx.containsBean(FeatureflipClient.class)).isFalse();
        }
    }

    @Test
    void propertiesReachTheClientAndItIdentifiesTheIntegration() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"version\":1,\"flags\":[],\"segments\":[]}").build());
            try (ApplicationContext ctx = ApplicationContext.run(props(
                    "featureflip.sdk-key", "sdk-mn-props",
                    "featureflip.base-url", server.url("/").toString().replaceAll("/$", ""),
                    "featureflip.init-timeout", "5s"))) {
                FeatureflipClient client = ctx.getBean(FeatureflipClient.class);
                client.waitForInitialization();
                assertThat(client.isInitialized()).isTrue();
            }
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getUrl().encodedPath()).isEqualTo("/v1/sdk/flags");
            assertThat(request.getHeaders().get("User-Agent"))
                .contains("featureflip-java/")
                .endsWith("featureflip-micronaut/" + VersionUtils.getMicronautVersion());
        }
    }

    @Test
    void stoppingTheContextClosesTheClient() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"version\":1,\"flags\":[],\"segments\":[]}").build());
            FeatureflipClient client;
            try (ApplicationContext ctx = ApplicationContext.run(props(
                    "featureflip.sdk-key", "sdk-mn-close",
                    "featureflip.base-url", server.url("/").toString().replaceAll("/$", ""),
                    "featureflip.init-timeout", "5s"))) {
                client = ctx.getBean(FeatureflipClient.class);
                assertThat(client.isInitialized()).isTrue();
            }
            assertThat(client.isInitialized()).isFalse();
        }
    }

    @Test
    void anInvalidOptionFailsStartupAndIsNotReportedAsAMissingKey() {
        assertThatThrownBy(() -> ApplicationContext.run(props(
                "featureflip.sdk-key", "sdk-mn-bad-option", "featureflip.max-snapshot-age-seconds", -1)))
            .hasStackTraceContaining("maxSnapshotAgeSeconds")
            .satisfies(t -> {
                java.io.StringWriter out = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(out));
                assertThat(out.toString()).doesNotContain("No Featureflip SDK key");
            });
    }

    @Test
    void aMissingKeyFailsStartupWithAClearMessageThatNamesNoKey() {
        assumeTrue(System.getenv("FEATUREFLIP_SDK_KEY") == null, "env key set on this machine");
        assertThatThrownBy(() -> ApplicationContext.run(props()))
            .isInstanceOf(BeanInstantiationException.class)
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasStackTraceContaining("featureflip.sdk-key")
            .hasStackTraceContaining("FEATUREFLIP_SDK_KEY");
    }

    @Test
    void theClientIsCreatedAtStartupNotOnFirstInjection() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"version\":1,\"flags\":[],\"segments\":[]}").build());
            try (ApplicationContext ctx = ApplicationContext.run(props(
                    "featureflip.sdk-key", "sdk-mn-eager",
                    "featureflip.base-url", server.url("/").toString().replaceAll("/$", ""),
                    "featureflip.init-timeout", "5s"))) {
                // No getBean yet: the request must already have been made.
                assertThat(ctx.isRunning()).isTrue();
                assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FALSE", "False", "false"})
    void enabledFalseInAnyCaseRegistersNothingAndDoesNotFail(String value) {
        assumeTrue(System.getenv("FEATUREFLIP_SDK_KEY") == null, "env key set on this machine");
        try (ApplicationContext ctx = ApplicationContext.run(props("featureflip.enabled", value))) {
            assertThat(ctx.containsBean(FeatureflipClient.class)).isFalse();
        }
    }
}
