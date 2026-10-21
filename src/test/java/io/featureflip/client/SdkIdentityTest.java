package io.featureflip.client;

import io.featureflip.client.internal.FlagHttpClient;
import io.featureflip.client.internal.FlagStore;
import io.featureflip.client.internal.SdkIdentity;
import io.featureflip.client.internal.SseDataSource;
import io.featureflip.client.internal.model.SdkEvent;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every request names the SDK and its version so the Evaluation API can count usage
 * (#4140, contract in #4138). Before this the SDK sent OkHttp's default
 * {@code okhttp/<x>}, which the Android SDK sends too, so the two were indistinguishable.
 *
 * <p>Asserted on the wire through the shipped {@link FlagHttpClient} for all three paths.
 * The stream builds its own client from the shared one, so it is exercised separately
 * rather than assumed to inherit the header.
 */
class SdkIdentityTest {
    private MockWebServer server;
    private ScheduledExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        server.close();
    }

    @Test
    void versionIsTheOneThePublishWorkflowWritesToTheBuildFile() throws Exception {
        // publish-java-sdk.yml seds `version = "..."` in build.gradle.kts from the tag, and
        // processResources stamps that into the jar. Pinning the two together is what stops
        // the UA advertising a stale version across a release line (#2141).
        String build = new String(Files.readAllBytes(Path.of("build.gradle.kts")), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?m)^version = \"([^\"]+)\"").matcher(build);
        assertThat(m.find()).isTrue();

        assertThat(SdkIdentity.VERSION).isEqualTo(m.group(1));
    }

    @Test
    void everyRequestPathSendsTheSdkToken() throws Exception {
        String expected = "featureflip-java/" + SdkIdentity.VERSION;

        assertThat(userAgentsFor(FeatureFlagConfig.builder())).containsExactly(expected, expected, expected);
    }

    @Test
    void wrapperIsAppendedAsASecondProductToken() throws Exception {
        String expected = "featureflip-java/" + SdkIdentity.VERSION + " featureflip-openfeature-java/1.2.3";

        assertThat(userAgentsFor(FeatureFlagConfig.builder().wrapper("featureflip-openfeature-java", "1.2.3")))
            .containsExactly(expected, expected, expected);
    }

    @Test
    void wrapperWithoutAVersionIsSentAsABareName() throws Exception {
        assertThat(userAgentsFor(FeatureFlagConfig.builder().wrapper("my-wrapper", null)).get(0))
            .isEqualTo("featureflip-java/" + SdkIdentity.VERSION + " my-wrapper");
    }

    @Test
    void invalidWrapperIsDroppedWithAWarning() throws Exception {
        // A space is not a legal product-token character, and OkHttp would happily send it
        // inside the header value. Identity metadata must never stop the client starting,
        // so it is dropped and said so.
        String[] agents = new String[1];
        String err = captureStderr(() -> {
            try {
                agents[0] = userAgentsFor(FeatureFlagConfig.builder().wrapper("bad name", "1.0")).get(0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(agents[0]).isEqualTo("featureflip-java/" + SdkIdentity.VERSION);
        assertThat(err).contains("WARN").contains("bad name");
    }

    /** One request down each path (flags, events, stream), returning each one's User-Agent. */
    private List<String> userAgentsFor(FeatureFlagConfig.Builder builder) throws Exception {
        server.enqueue(new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body("{\"environment\":\"e\",\"version\":1,\"flags\":[],\"segments\":[]}")
            .build());
        server.enqueue(new MockResponse.Builder().code(202).build());
        server.enqueue(new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "text/event-stream")
            .body("event: ping\ndata: {}\n\n")
            .build());

        FeatureFlagConfig config = builder.baseUrl(server.url("/").toString()).build();
        FlagHttpClient httpClient = new FlagHttpClient("test-sdk-key", config);

        httpClient.fetchFlags();
        httpClient.sendEvents(List.of(new SdkEvent()));
        SseDataSource sse = new SseDataSource(httpClient, new FlagStore(), executor, () -> {}, () -> {});
        sse.start();

        RecordedRequest flags = server.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest events = server.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest stream = server.takeRequest(5, TimeUnit.SECONDS);
        sse.close();

        assertThat(flags.getUrl().encodedPath()).isEqualTo("/v1/sdk/flags");
        assertThat(events.getUrl().encodedPath()).isEqualTo("/v1/sdk/events");
        assertThat(stream.getUrl().encodedPath()).isEqualTo("/v1/sdk/stream");
        return List.of(
            flags.getHeaders().get("User-Agent"),
            events.getHeaders().get("User-Agent"),
            stream.getHeaders().get("User-Agent"));
    }

    /** slf4j-simple resolves System.err per write, so swapping it observes the level. */
    private String captureStderr(Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
