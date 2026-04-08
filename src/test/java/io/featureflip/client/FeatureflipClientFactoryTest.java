package io.featureflip.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the static factory that implements singleton-by-construction semantics.
 * Uses {@link FeatureflipClient#resetForTesting()} between tests to keep the static
 * map clean. Uses distinct SDK keys per test to avoid contamination if JUnit runs
 * tests in the class in parallel.
 *
 * Each test passes a config with very short timeouts and an unreachable baseUrl to
 * avoid blocking on the real flag fetch — the shared core's constructor catches
 * initialization failures and continues without flags, so the resulting handle is
 * usable immediately.
 */
class FeatureflipClientFactoryTest {

    @BeforeEach
    void beforeEach() {
        FeatureflipClient.resetForTesting();
    }

    @AfterEach
    void afterEach() {
        FeatureflipClient.resetForTesting();
    }

    private static FeatureFlagConfig fastConfig() {
        return FeatureFlagConfig.builder()
            .baseUrl("http://localhost:1") // unreachable - forces fast init failure
            .connectTimeout(Duration.ofMillis(100))
            .readTimeout(Duration.ofMillis(100))
            .initTimeout(Duration.ofMillis(500))
            .streaming(false)
            .build();
    }

    @Test
    void get_FirstCall_ReturnsHandle() {
        try (FeatureflipClient client = FeatureflipClient.get("sdk-key-get-first", fastConfig())) {
            assertNotNull(client);
        }
    }

    @Test
    void get_SameKeyTwice_ReturnsHandlesSharingOneCore() {
        try (FeatureflipClient h1 = FeatureflipClient.get("sdk-key-same", fastConfig());
             FeatureflipClient h2 = FeatureflipClient.get("sdk-key-same", fastConfig())) {
            assertNotSame(h1, h2);
            assertEquals(1, FeatureflipClient.debugLiveCoreCount());
        }
    }

    @Test
    void get_DifferentKeys_CreatesIndependentCores() {
        try (FeatureflipClient h1 = FeatureflipClient.get("sdk-key-a", fastConfig());
             FeatureflipClient h2 = FeatureflipClient.get("sdk-key-b", fastConfig())) {
            assertEquals(2, FeatureflipClient.debugLiveCoreCount());
        }
    }

    @Test
    void get_AfterOnlyHandleClosed_ConstructsNewCore() {
        FeatureflipClient h1 = FeatureflipClient.get("sdk-key-recycle", fastConfig());
        h1.close();

        assertEquals(0, FeatureflipClient.debugLiveCoreCount());

        try (FeatureflipClient h2 = FeatureflipClient.get("sdk-key-recycle", fastConfig())) {
            assertEquals(1, FeatureflipClient.debugLiveCoreCount());
        }
    }

    @Test
    void close_OneOfTwoHandles_KeepsCoreAlive() {
        FeatureflipClient h1 = FeatureflipClient.get("sdk-key-twohandles", fastConfig());
        try (FeatureflipClient h2 = FeatureflipClient.get("sdk-key-twohandles", fastConfig())) {
            h1.close();
            // h2 still alive; core is still in the map
            assertEquals(1, FeatureflipClient.debugLiveCoreCount());
        }
    }

    @Test
    void get_NullOrEmptyKey_Throws() {
        assertThrows(IllegalArgumentException.class,
            () -> FeatureflipClient.get(null, fastConfig()));
        assertThrows(IllegalArgumentException.class,
            () -> FeatureflipClient.get("", fastConfig()));
        assertThrows(IllegalArgumentException.class,
            () -> FeatureflipClient.get("   ", fastConfig()));
    }

    @Test
    void get_ConcurrentSameKey_AllHandlesShareOneCore() throws Exception {
        final int threadCount = 32;
        final FeatureFlagConfig config = fastConfig();
        final AtomicReferenceArray<FeatureflipClient> handles = new AtomicReferenceArray<>(threadCount);
        final CountDownLatch ready = new CountDownLatch(threadCount);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        handles.set(idx, FeatureflipClient.get("sdk-key-concurrent", config));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "threads did not reach the start gate");
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "factory calls did not complete in 30s");

            try {
                assertEquals(1, FeatureflipClient.debugLiveCoreCount(),
                    "all 32 handles should share exactly one core");
                for (int i = 0; i < threadCount; i++) {
                    assertNotNull(handles.get(i), "handle " + i + " was null");
                }
            } finally {
                for (int i = 0; i < threadCount; i++) {
                    FeatureflipClient handle = handles.get(i);
                    if (handle != null) handle.close();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
