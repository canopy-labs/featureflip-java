package io.featureflip.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Resolving the SDK key from the environment (#2273).
 *
 * <p>The README has advertised {@code FEATUREFLIP_SDK_KEY} since the SDK shipped and
 * nothing ever read it. It is not a stray claim either: python ({@code client.py}), go
 * ({@code featureflip.go}), csharp ({@code FeatureflipClient.cs}), ruby ({@code client.rb})
 * and php ({@code FeatureflipClient.php}, #2272) all implement exactly this fallback, so
 * java was the SDK documenting a convention it did not honour.
 *
 * <p>These tests reach the environment through a swappable function rather than the real
 * process environment: Java has no supported way to mutate its own environment in-process
 * — the reflection route into {@code ProcessEnvironment} is sealed by module encapsulation
 * from 17 onward, and CI runs 11, 17 and 21. {@link #theDefaultReaderIsTheRealEnvironment()}
 * covers the un-swapped path.
 */
@SuppressWarnings("try") // handles are closed by try-with-resources without being referenced
class SdkKeyResolutionTest {

    private static final String ENV_VAR = "FEATUREFLIP_SDK_KEY";
    private static final String ENV_KEY = "sdk-server-from-the-environment";
    private static final String ARG_KEY = "sdk-server-passed-by-the-caller";

    @BeforeEach
    void beforeEach() {
        FeatureflipClient.resetForTesting();
    }

    @AfterEach
    void afterEach() {
        // Restored before resetForTesting(): a throw from that call must not leave the
        // swapped reader in place for sibling tests.
        FeatureflipClient.setEnvReaderForTesting(null);
        FeatureflipClient.resetForTesting();
    }

    /** Unreachable baseUrl and short timeouts: the core catches its init failure, so the handle is usable at once. */
    private static FeatureFlagConfig fastConfig() {
        return FeatureFlagConfig.builder()
            .baseUrl("http://localhost:1")
            .connectTimeout(Duration.ofMillis(100))
            .readTimeout(Duration.ofMillis(100))
            .initTimeout(Duration.ofMillis(500))
            .streaming(false)
            .build();
    }

    private static void environment(Map<String, String> values) {
        FeatureflipClient.setEnvReaderForTesting(values::get);
    }

    private static void emptyEnvironment() {
        environment(Collections.emptyMap());
    }

    // --- Resolution ---

    @Test
    void aBlankKeyFallsBackToTheEnvironment() {
        environment(Collections.singletonMap(ENV_VAR, ENV_KEY));

        assertEquals(ENV_KEY, FeatureflipClient.resolveSdkKey(""));
        assertEquals(ENV_KEY, FeatureflipClient.resolveSdkKey("   "));
        assertEquals(ENV_KEY, FeatureflipClient.resolveSdkKey(null));
    }

    @Test
    void anExplicitKeyWinsOverTheEnvironment() {
        environment(Collections.singletonMap(ENV_VAR, ENV_KEY));

        assertEquals(ARG_KEY, FeatureflipClient.resolveSdkKey(ARG_KEY));
    }

    @Test
    void neitherSourceIsAnErrorNamingBoth() {
        emptyEnvironment();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> FeatureflipClient.get("", fastConfig()));

        // Naming both sources is the point: the old message told a developer who had set
        // the environment variable to pass the key they thought they had already supplied.
        assertTrue(thrown.getMessage().contains(ENV_VAR),
            "message should name the environment variable, was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("get("),
            "message should name the argument route, was: " + thrown.getMessage());
    }

    @Test
    void aBlankEnvironmentValueCountsAsAbsent() {
        environment(Collections.singletonMap(ENV_VAR, "   "));

        assertThrows(IllegalArgumentException.class, () -> FeatureflipClient.resolveSdkKey(""));
    }

    @Test
    void theDefaultReaderIsTheRealEnvironment() {
        // No swap: proves get() consults System.getenv rather than only the test seam.
        assumeTrue(System.getenv(ENV_VAR) == null, "FEATUREFLIP_SDK_KEY is set in this environment");

        assertThrows(IllegalArgumentException.class, () -> FeatureflipClient.get("", fastConfig()));
    }

    // --- Cache keying ---

    /**
     * The resolved key — not the blank string the caller passed — has to be what the core
     * is cached under, or a caller who names the key explicitly would build a second core
     * against the same environment.
     */
    @Test
    void theCoreIsCachedUnderTheResolvedKey() {
        environment(Collections.singletonMap(ENV_VAR, ENV_KEY));

        try (FeatureflipClient fromEnvironment = FeatureflipClient.get("", fastConfig());
             FeatureflipClient fromArgument = FeatureflipClient.get(ENV_KEY, fastConfig())) {
            assertNotNull(fromEnvironment);
            assertNotNull(fromArgument);
            assertEquals(1, FeatureflipClient.debugLiveCoreCount());
        }
    }

    @Test
    void anExplicitKeyDoesNotShareTheEnvironmentsCore() {
        environment(Collections.singletonMap(ENV_VAR, ENV_KEY));

        try (FeatureflipClient fromEnvironment = FeatureflipClient.get("", fastConfig());
             FeatureflipClient fromArgument = FeatureflipClient.get(ARG_KEY, fastConfig())) {
            assertEquals(2, FeatureflipClient.debugLiveCoreCount());
        }
    }

    // --- Builder parity ---

    @Test
    void theBuilderResolvesTheSameWay() {
        environment(Collections.singletonMap(ENV_VAR, ENV_KEY));

        try (FeatureflipClient fromBuilder = FeatureflipClient.builder("").baseUrl("http://localhost:1")
                 .connectTimeout(Duration.ofMillis(100)).readTimeout(Duration.ofMillis(100))
                 .initTimeout(Duration.ofMillis(500)).streaming(false).build();
             FeatureflipClient fromGet = FeatureflipClient.get(ENV_KEY, fastConfig())) {
            assertNotNull(fromBuilder);
            assertEquals(1, FeatureflipClient.debugLiveCoreCount());
        }
    }

    @Test
    void theBuilderReportsTheMissingKeyAtBuild() {
        emptyEnvironment();

        FeatureflipClient.Builder builder = FeatureflipClient.builder(null);

        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
