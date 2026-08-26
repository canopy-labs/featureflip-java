package io.featureflip.client.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSE drops this backoff absorbs are fleet-wide: one edge event severs every
 * stream at once (#2457 — measured at a 2.5-3.0ms spread across both eval-api pods),
 * so every client re-enters the backoff at the same failure count together. A
 * deterministic delay there republishes the drop's own synchronisation as a
 * reconnect spike one delay later (#2508).
 */
class SseBackoffTest {

    @Test
    @DisplayName("first reconnect is scattered, not a constant every client shares")
    void firstReconnectIsJittered() {
        Set<Long> samples = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            samples.add(SseDataSource.backoffMillis(0));
        }

        assertThat(samples)
            .as("first-reconnect delay is deterministic — a fleet-wide drop reconnects in lockstep")
            .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("every level stays inside [d/2, d] and strictly positive")
    void everyLevelIsBoundedAndPositive() {
        for (int failures = 0; failures <= 10; failures++) {
            long ceiling = Math.min(1L << failures, SseDataSource.MAX_BACKOFF_SECONDS) * 1000L;
            for (int i = 0; i < 50; i++) {
                long delay = SseDataSource.backoffMillis(failures);
                assertThat(delay)
                    .as("failures=%d", failures)
                    .isGreaterThan(0)          // anti-busy-loop on a clean sever
                    .isBetween(ceiling / 2, ceiling);
            }
        }
    }

    @Test
    @DisplayName("still escalates and caps")
    void escalatesAndCaps() {
        assertThat(SseDataSource.backoffMillis(0)).isLessThanOrEqualTo(1_000L);
        assertThat(SseDataSource.backoffMillis(4)).isGreaterThan(1_000L);
        assertThat(SseDataSource.backoffMillis(30))
            .isBetween(SseDataSource.MAX_BACKOFF_SECONDS * 500L, SseDataSource.MAX_BACKOFF_SECONDS * 1000L);
    }
}
