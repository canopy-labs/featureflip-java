package io.featureflip.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for #2477: at most one drain loop may run at a time.
 *
 * <p>#2456 added a backoff gate plus an in-flight latch, but the latch guarded only the
 * batch-size trigger. Nothing stopped the scheduled flush loop, an explicit
 * {@code flush()} and a size-triggered flush from entering the drain together — two
 * request streams against the endpoint the gate exists to protect, and a success in one
 * clearing the gate a failure in the other had just armed.
 *
 * <p>The only externally visible evidence of a second drain is a second request arriving
 * while the first is still unanswered, so the dispatcher below parks its first event POST
 * and records the greatest number ever in flight at once.
 */
class EventFlushCoalescingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer server;
    private FeatureflipClient client;

    @BeforeEach
    void setUp() {
        FeatureflipClient.resetForTesting();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
        if (server != null) server.close();
        client = null;
        server = null;
        FeatureflipClient.resetForTesting();
    }

    private static final class ParkingEventsDispatcher extends Dispatcher {
        private final CountDownLatch firstArrived = new CountDownLatch(1);
        private final CountDownLatch gate = new CountDownLatch(1);
        private final AtomicBoolean parked = new AtomicBoolean();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger delivered = new AtomicInteger();

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            if (!"/v1/sdk/events".equals(request.getUrl().encodedPath())) {
                return new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(flagsJson())
                    .build();
            }

            int depth = inFlight.incrementAndGet();
            peak.accumulateAndGet(depth, Math::max);

            // Only the very FIRST request is parked; re-parking a later one would wait on
            // a gate nothing opens again and hang the test.
            if (parked.compareAndSet(false, true)) {
                firstArrived.countDown();
                try {
                    gate.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            inFlight.decrementAndGet();
            delivered.incrementAndGet();
            return new MockResponse.Builder().code(202).build();
        }
    }

    private static String flagsJson() {
        try {
            return MAPPER.writeValueAsString(Map.of(
                "environment", "test",
                "version", 1,
                "flags", List.of(Map.of(
                    "key", "test-flag",
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
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Batch size well above what the test records, so the size trigger never fires and
     * every drain under test is one the test started deliberately. MockWebServer dispatches
     * each request on its own thread, so a parked one does not block the next from arriving.
     */
    private FeatureflipClient clientFor(MockWebServer mockWebServer) {
        FeatureflipClient built = FeatureflipClient.builder("test-sdk-key")
            .baseUrl(mockWebServer.url("/").toString())
            .streaming(false)
            .pollInterval(Duration.ofHours(1))
            .flushInterval(Duration.ofMinutes(10))
            .flushBatchSize(1)
            .readTimeout(Duration.ofSeconds(30))
            .build();
        built.waitForInitialization();
        return built;
    }

    @Test
    void aConcurrentFlushWaitsForTheInFlightDrainInsteadOfStartingASecond() throws Exception {
        ParkingEventsDispatcher dispatcher = new ParkingEventsDispatcher();
        server = new MockWebServer();
        server.setDispatcher(dispatcher);
        server.start();
        client = clientFor(server);

        // Recorded on their own thread. Batch size is 1, so the size trigger fires on the
        // first evaluation — and maybeAutoFlush runs the drain SYNCHRONOUSLY on the
        // recording thread, so doing this on the test thread would park the test itself
        // inside its own setup.
        Thread recorder = new Thread(() -> {
            for (int i = 0; i < 6; i++) {
                client.boolVariation("test-flag", EvaluationContext.builder("u" + i).build(), false);
            }
        });
        recorder.start();

        assertThat(dispatcher.firstArrived.await(20, TimeUnit.SECONDS))
            .as("the first event POST never reached the server")
            .isTrue();

        AtomicBoolean released = new AtomicBoolean();
        AtomicBoolean secondSawRelease = new AtomicBoolean();
        CountDownLatch secondDone = new CountDownLatch(1);
        Thread second = new Thread(() -> {
            client.flush();
            secondSawRelease.set(released.get());
            secondDone.countDown();
        });
        second.start();

        // Room for the second caller to misbehave: uncoalesced it drains a batch and posts
        // it, which the peak counter catches.
        Thread.sleep(300);

        released.set(true);
        dispatcher.gate.countDown();

        assertThat(secondDone.await(20, TimeUnit.SECONDS))
            .as("the second flush never returned")
            .isTrue();
        second.join(TimeUnit.SECONDS.toMillis(20));
        recorder.join(TimeUnit.SECONDS.toMillis(20));

        assertThat(dispatcher.peak.get())
            .as("peak concurrent event requests — a second drain loop ran")
            .isEqualTo(1);
        // A caller that asked for a flush is asking for its events to be sent, so it waits
        // for the drain rather than returning early. Matches the js/node SDKs.
        assertThat(secondSawRelease.get())
            .as("the second flush returned before the in-flight drain finished")
            .isTrue();
    }
}
