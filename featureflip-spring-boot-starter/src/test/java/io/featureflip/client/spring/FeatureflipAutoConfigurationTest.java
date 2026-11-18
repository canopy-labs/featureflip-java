package io.featureflip.client.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.featureflip.client.FeatureflipClient;
import org.springframework.boot.SpringBootVersion;
import java.util.Map;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Offline: every context points at an unreachable port or a local MockWebServer. */
class FeatureflipAutoConfigurationTest {

    private static final String UNREACHABLE = "featureflip.base-url=http://localhost:1";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FeatureflipAutoConfiguration.class))
        .withPropertyValues(UNREACHABLE, "featureflip.streaming=false",
            "featureflip.init-timeout=500ms", "featureflip.connect-timeout=100ms");

    @Test
    void registersTheClientAsASingleBean() {
        runner.withPropertyValues("featureflip.sdk-key=sdk-spring-singleton").run(context -> {
            assertThat(context).hasSingleBean(FeatureflipClient.class);
            assertThat(context.getBean(FeatureflipClient.class))
                .isSameAs(context.getBean(FeatureflipClient.class));
        });
    }

    @Test
    void aUserDefinedClientWins() {
        FeatureflipClient mine = FeatureflipClient.forTesting(Map.of("flag", true));
        runner.withPropertyValues("featureflip.sdk-key=sdk-spring-user-bean")
            .withBean(FeatureflipClient.class, () -> mine)
            .run(context -> {
                assertThat(context).hasSingleBean(FeatureflipClient.class);
                assertThat(context.getBean(FeatureflipClient.class)).isSameAs(mine);
            });
    }

    @Test
    void enabledFalseRegistersNothing() {
        runner.withPropertyValues("featureflip.sdk-key=sdk-spring-disabled", "featureflip.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(FeatureflipClient.class));
    }

    @Test
    void propertiesReachTheClientAndItIdentifiesTheStarter() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"version\":1,\"flags\":[],\"segments\":[]}").build());
            runner.withPropertyValues("featureflip.sdk-key=sdk-spring-props",
                    "featureflip.base-url=" + server.url("/").toString().replaceAll("/$", ""),
                    "featureflip.init-timeout=5s")
                .run(context -> {
                    FeatureflipClient client = context.getBean(FeatureflipClient.class);
                    client.waitForInitialization();
                    assertThat(client.isInitialized()).isTrue();
                });
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getUrl().encodedPath()).isEqualTo("/v1/sdk/flags");
            assertThat(request.getHeaders().get("User-Agent"))
                .contains("featureflip-java/")
                .endsWith("featureflip-spring-boot/" + SpringBootVersion.getVersion());
        }
    }

    @Test
    void closingTheContextClosesTheClient() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"version\":1,\"flags\":[],\"segments\":[]}").build());
            FeatureflipClient[] captured = new FeatureflipClient[1];
            runner.withPropertyValues("featureflip.sdk-key=sdk-spring-close",
                    "featureflip.base-url=" + server.url("/").toString().replaceAll("/$", ""),
                    "featureflip.init-timeout=5s")
                .run(context -> {
                    captured[0] = context.getBean(FeatureflipClient.class);
                    assertThat(captured[0].isInitialized()).isTrue();
                });
            // The runner has closed the context; a closed handle reports not initialized.
            assertThat(captured[0].isInitialized()).isFalse();
        }
    }

    @Test
    void anInvalidOptionIsNotReportedAsAMissingKey() {
        runner.withPropertyValues("featureflip.sdk-key=sdk-spring-bad-option",
                "featureflip.max-snapshot-age-seconds=-1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasStackTraceContaining("maxSnapshotAgeSeconds")
                .satisfies(t -> assertThat(stackTrace(t)).doesNotContain("No Featureflip SDK key"));
        });
    }

    private static String stackTrace(Throwable t) {
        java.io.StringWriter out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }

    @Test
    void aMissingKeyFailsWithAClearMessageThatNamesNoKey() {
        assumeTrue(System.getenv("FEATUREFLIP_SDK_KEY") == null, "env key set on this machine");
        runner.run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null && !(root instanceof IllegalStateException)) {
                root = root.getCause();
            }
            assertThat(root.getMessage())
                .contains("featureflip.sdk-key")
                .contains("FEATUREFLIP_SDK_KEY");
        });
    }
}
