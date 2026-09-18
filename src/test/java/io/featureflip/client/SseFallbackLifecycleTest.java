package io.featureflip.client;

import io.featureflip.client.internal.FlagHttpClient;
import io.featureflip.client.internal.FlagStore;
import io.featureflip.client.internal.SseDataSource;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.sse.EventSourceListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Falling back to polling must never be the END of the stream (#3071).
 *
 * <p>The failure this pins is silent and permanent: after five consecutive SSE
 * failures — about 31 seconds of Evaluation API unreachability — the source used to
 * hand over to the polling fallback and simply {@code return}, scheduling no further
 * reconnect. Nothing else re-opens the stream, so every affected instance lost
 * real-time updates (a kill switch then lands up to a poll interval late) and polled
 * forever, until the host process restarted. Python, Go and C# keep retrying; this
 * brings java in line.
 */
class SseFallbackLifecycleTest {

    /**
     * Counts reconnect scheduling without performing it. The reconnect delay is
     * jittered up to 30s (#2508), so the observable is the SCHEDULE call, not a
     * socket — substituting a no-op command keeps the assertion off the clock.
     */
    private static final class CountingScheduler extends ScheduledThreadPoolExecutor {
        private final AtomicInteger scheduled = new AtomicInteger();

        CountingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            scheduled.incrementAndGet();
            return super.schedule(() -> { }, delay, unit);
        }

        int scheduleCount() {
            return scheduled.get();
        }
    }

    private MockWebServer server;
    private CountingScheduler executor;
    private FlagStore store;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        executor = new CountingScheduler();
        store = new FlagStore();
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        server.close();
    }

    private FeatureFlagConfig config() {
        return FeatureFlagConfig.builder()
            .baseUrl(server.url("/").toString())
            .pollInterval(Duration.ofHours(1))
            .build();
    }

    private SseDataSource createDataSource(Runnable onFallbackToPolling, Runnable onStreamRecovered) {
        FeatureFlagConfig config = config();
        FlagHttpClient httpClient = new FlagHttpClient("test-sdk-key", config);
        return new SseDataSource(httpClient, store, executor, () -> { },
            onFallbackToPolling, onStreamRecovered);
    }

    private static String syncPayload() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "environment", "test",
            "version", 1,
            "flags", List.of(Map.of(
                "key", "flag-a",
                "version", 1,
                "type", "Boolean",
                "enabled", true,
                "variations", List.of(
                    Map.of("key", "on", "value", true),
                    Map.of("key", "off", "value", false)
                ),
                "rules", List.of(),
                "fallthrough", Map.of("type", "Fixed", "variation", "on"),
                "offVariation", "off"
            )),
            "segments", List.of()
        ));
    }

    /** Drives {@code count} consecutive connect failures through the listener. */
    private static void fail(EventSourceListener listener, int count) {
        for (int i = 0; i < count; i++) {
            listener.onFailure(null, new IOException("connection refused"), null);
        }
    }

    @Test
    @DisplayName("the stream keeps reconnecting after it falls back to polling")
    void keepsReconnectingAfterFallback() {
        AtomicInteger fallbacks = new AtomicInteger();
        SseDataSource sse = createDataSource(fallbacks::incrementAndGet, () -> { });
        EventSourceListener listener = sse.buildSseListener();

        // Five consecutive failures — the cap. The fifth is the one that used to
        // hand over to polling and return without scheduling anything.
        fail(listener, 5);

        assertThat(fallbacks.get())
            .as("the polling fallback should have been started")
            .isGreaterThanOrEqualTo(1);
        assertThat(executor.scheduleCount())
            .as("every failure — including the one that triggers the fallback — must "
                + "still schedule a reconnect, or nothing ever re-opens the stream")
            .isEqualTo(5);

        // And it keeps going: the outage outlasting the cap must not silence it either.
        fail(listener, 3);
        assertThat(executor.scheduleCount())
            .as("reconnects continue past the fallback, with capped backoff")
            .isEqualTo(8);

        sse.close();
    }

    @Test
    @DisplayName("a closed source stops reconnecting even past the fallback")
    void closedSourceStopsReconnecting() {
        SseDataSource sse = createDataSource(() -> { }, () -> { });
        EventSourceListener listener = sse.buildSseListener();

        fail(listener, 5);
        int beforeClose = executor.scheduleCount();

        sse.close();
        fail(listener, 3);

        assertThat(executor.scheduleCount())
            .as("close() is the one thing that ends the retry loop")
            .isEqualTo(beforeClose);
    }

    @Test
    @DisplayName("a sync delivered after the fallback signals recovery exactly once")
    void syncAfterFallbackSignalsRecovery() throws Exception {
        AtomicInteger recoveries = new AtomicInteger();
        SseDataSource sse = createDataSource(() -> { }, recoveries::incrementAndGet);
        EventSourceListener listener = sse.buildSseListener();

        fail(listener, 5);
        assertThat(recoveries.get()).as("no sync has arrived yet").isZero();

        listener.onOpen(null, null);
        listener.onEvent(null, null, "sync", syncPayload());

        assertThat(recoveries.get())
            .as("a reopened stream that has replayed its snapshot makes the poller redundant")
            .isEqualTo(1);

        // A healthy stream re-syncing later is not another recovery.
        listener.onEvent(null, null, "sync", syncPayload());
        assertThat(recoveries.get()).isEqualTo(1);

        sse.close();
    }

    @Test
    @DisplayName("a sync on a stream that never fell back signals nothing")
    void syncWithoutFallbackSignalsNothing() throws Exception {
        AtomicInteger recoveries = new AtomicInteger();
        SseDataSource sse = createDataSource(() -> { }, recoveries::incrementAndGet);
        EventSourceListener listener = sse.buildSseListener();

        listener.onOpen(null, null);
        listener.onEvent(null, null, "sync", syncPayload());

        assertThat(recoveries.get())
            .as("recovery is the fallback's counterpart — without one there is nothing to stop")
            .isZero();

        sse.close();
    }

    @Test
    @DisplayName("the core starts, stops and can restart the fallback poller")
    void corePollerLifecycleIsReversible() throws Exception {
        // The production constructor does one initial fetch; the SSE connect then
        // parks on MockWebServer's empty queue, which is all this test needs.
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body("{\"environment\":\"test\",\"version\":1,\"flags\":[],\"segments\":[]}")
            .build());

        SharedFeatureflipCore core = new SharedFeatureflipCore("test-sdk-key", config());
        try {
            assertThat(core.hasFallbackPoller()).isFalse();

            core.startPolling();
            assertThat(core.hasFallbackPoller())
                .as("the fallback poller covers the outage")
                .isTrue();

            core.stopFallbackPolling();
            assertThat(core.hasFallbackPoller())
                .as("a recovered stream must retire the poller — otherwise a 30s poll "
                    + "per instance runs until the process restarts")
                .isFalse();

            core.startPolling();
            assertThat(core.hasFallbackPoller())
                .as("a second outage falls back again — stopping must not be terminal")
                .isTrue();
        } finally {
            core.release();
        }
    }
}
