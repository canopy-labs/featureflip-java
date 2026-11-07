package io.featureflip.client;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

import static io.featureflip.client.SnapshotTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Public-surface behavior: config validation, the factory warning and the cross-process parity scenario. */
class SnapshotStoreClientTest {
    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        FeatureflipClient.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        FeatureflipClient.resetForTesting();
    }

    private FeatureFlagConfig.Builder fast() {
        return FeatureFlagConfig.builder()
            .baseUrl("http://localhost:1")
            .connectTimeout(Duration.ofMillis(100))
            .readTimeout(Duration.ofMillis(100))
            .initTimeout(Duration.ofMillis(500))
            .streaming(false);
    }

    private static String captureStderr(Runnable body) {
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

    // ------------------------------------------------------------------ max age validation

    @Test
    void maxSnapshotAgeDefaultsToOneDay() {
        assertThat(fast().build().getMaxSnapshotAgeSeconds()).isEqualTo(86400d);
        assertThat(fast().maxSnapshotAgeSeconds(30.5).build().getMaxSnapshotAgeSeconds()).isEqualTo(30.5);
    }

    @Test
    void maxSnapshotAgeMustBePositiveAndFinite() {
        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> fast().maxSnapshotAgeSeconds(bad).build())
                .as(String.valueOf(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSnapshotAgeSeconds");
        }
    }

    // ------------------------------------------------------------------ factory warning

    private String warnOnSecondGet(String key, FeatureFlagConfig first, Supplier<FeatureFlagConfig> second) {
        try (FeatureflipClient a = FeatureflipClient.get(key, first)) {
            assertThat(a).isNotNull();
            return captureStderr(() -> FeatureflipClient.get(key, second.get()).close());
        }
    }

    @Test
    void aDifferentStoreInstanceWarnsOptionIgnored() {
        SnapshotStore one = new MemoryStore();
        SnapshotStore two = new MemoryStore();
        String err = warnOnSecondGet("k-store-diff", fast().snapshotStore(one).build(),
            () -> fast().snapshotStore(two).build());
        assertThat(err).contains("different config");
    }

    @Test
    void theSameStoreInstanceIsSilent() {
        SnapshotStore one = new MemoryStore();
        String err = warnOnSecondGet("k-store-same", fast().snapshotStore(one).build(),
            () -> fast().snapshotStore(one).build());
        assertThat(err).doesNotContain("different config");
    }

    @Test
    void aStoreOnALaterGetWhenTheFirstHadNoneWarns() {
        String err = warnOnSecondGet("k-store-late", fast().build(), () -> fast().snapshotStore(new MemoryStore()).build());
        assertThat(err).contains("different config");
    }

    @Test
    void aDifferentExplicitMaxAgeWarnsButAnOmittedOneDoesNot() {
        SnapshotStore one = new MemoryStore();
        FeatureFlagConfig first = fast().snapshotStore(one).maxSnapshotAgeSeconds(3600).build();
        assertThat(warnOnSecondGet("k-age-diff", first,
            () -> fast().snapshotStore(one).maxSnapshotAgeSeconds(60).build())).contains("different config");
        // A wrapper that omits the option must not trip the warning against a customized first caller.
        assertThat(warnOnSecondGet("k-age-omitted", first, () -> fast().snapshotStore(one).build()))
            .doesNotContain("different config");
        assertThat(warnOnSecondGet("k-age-same", first,
            () -> fast().snapshotStore(one).maxSnapshotAgeSeconds(3600).build())).doesNotContain("different config");
    }

    // ------------------------------------------------------------------ shared parity scenario

    @Test
    void aFreshClientStartsFromTheSnapshotAnotherClientWroteAfterTheApiIsGone() throws Exception {
        Path file = dir.resolve("featureflip-snapshot.json");
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse.Builder()
            .body(flagsResponse("parity-flag", true)).addHeader("Content-Type", "application/json").build());
        server.start();
        String base = server.url("/").toString();

        try (FeatureflipClient a = FeatureflipClient.builder(SDK_KEY)
                .baseUrl(base).streaming(false).pollInterval(Duration.ofHours(1))
                .snapshotStore(new FileSnapshotStore(file.toString())).build()) {
            a.waitForInitialization();
            assertThat(a.startupInfo()).isEqualTo(StartupInfo.live());
            assertThat(eventually(5, () -> Files.exists(file))).isTrue();
        }
        // The API disappears.
        server.close();
        FeatureflipClient.resetForTesting();

        long start = System.nanoTime();
        try (FeatureflipClient b = FeatureflipClient.builder(SDK_KEY)
                .baseUrl(base).streaming(false).pollInterval(Duration.ofHours(1))
                .initTimeout(Duration.ofMillis(500)).connectTimeout(Duration.ofMillis(300))
                .snapshotStore(new FileSnapshotStore(file.toString())).build()) {
            assertThat(b.isInitialized()).isTrue();
            assertThat(b.startupInfo().getSource()).isEqualTo(StartupInfo.Source.SNAPSHOT);
            assertThat(b.startupInfo().getReason()).isEqualTo("snapshot");
            assertThat(b.boolVariation("parity-flag", EvaluationContext.builder("u1").build(), false)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }
}
