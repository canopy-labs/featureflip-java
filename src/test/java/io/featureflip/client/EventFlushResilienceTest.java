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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Regression guard for #2456: analytics events must survive a transient failure of the
 * events endpoint.
 *
 * <p>The flush path drains the queue <em>before</em> the POST, so until this was fixed a
 * single non-2xx discarded that batch permanently — there was no re-queue and no retry.
 * Worse, {@code sendEvents} only logged the status, so the core could not even tell a
 * rejected batch from a delivered one. In production the public edge answers this endpoint
 * with a 503 at a low but constant rate, so a steady trickle of evaluation analytics was
 * being lost outright.
 *
 * <p>The distinction under test is between a <em>retryable</em> failure (5xx, 429,
 * transport fault, timeout), which must preserve the batch for the next flush, and a
 * <em>permanent</em> one (401/403/400), which must drop it — retrying a rejected SDK key
 * forever would pin the queue at its bound and starve every later event.
 */
class EventFlushResilienceTest {
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

    /**
     * Answers {@code GET /v1/sdk/flags} with a one-flag config and hands every
     * {@code POST /v1/sdk/events} to a caller-supplied script keyed by the 1-based attempt
     * number, recording the attempt count and the body of each attempt.
     *
     * <p>A dispatcher rather than {@code server.enqueue(...)}: the point of most of these
     * tests is how many event POSTs happen, and an exhausted response queue blocks the
     * caller instead of failing, which would read as a hang rather than an assertion.
     */
    private static final class EventsDispatcher extends Dispatcher {
        private final IntFunction<MockResponse> eventsScript;
        private final AtomicInteger eventPosts = new AtomicInteger();
        private final List<String> postedBodies = Collections.synchronizedList(new ArrayList<>());

        EventsDispatcher(IntFunction<MockResponse> eventsScript) {
            this.eventsScript = eventsScript;
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            if ("/v1/sdk/events".equals(request.getUrl().encodedPath())) {
                postedBodies.add(request.getBody() == null ? "" : request.getBody().utf8());
                return eventsScript.apply(eventPosts.incrementAndGet());
            }
            return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(flagsJson())
                .build();
        }
    }

    private static MockResponse status(int code) {
        return new MockResponse.Builder().code(code).build();
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

    private MockWebServer serverWith(EventsDispatcher dispatcher) throws IOException {
        MockWebServer mockWebServer = new MockWebServer();
        mockWebServer.setDispatcher(dispatcher);
        mockWebServer.start();
        return mockWebServer;
    }

    /**
     * A client whose background loops stay out of the way: a 10-minute flush interval so the
     * scheduled flush never fires on its own, and (unless a test says otherwise) a batch size
     * far above what the test records so nothing auto-flushes. Every flush is explicit.
     */
    private FeatureflipClient clientFor(MockWebServer mockWebServer, int flushBatchSize, Duration readTimeout) {
        FeatureflipClient built = FeatureflipClient.builder("test-sdk-key")
            .baseUrl(mockWebServer.url("/").toString())
            .streaming(false)
            .pollInterval(Duration.ofHours(1))
            .flushInterval(Duration.ofMinutes(10))
            .flushBatchSize(flushBatchSize)
            .readTimeout(readTimeout)
            .build();
        built.waitForInitialization();
        return built;
    }

    private FeatureflipClient clientFor(MockWebServer mockWebServer) {
        return clientFor(mockWebServer, 1000, Duration.ofSeconds(5));
    }

    private void recordOneEvaluation(String userId) {
        client.boolVariation("test-flag", EvaluationContext.builder(userId).build(), false);
    }

    @Test
    void a503KeepsTheBatchSoTheNextFlushReSendsIt() throws Exception {
        // The production failure exactly: the edge answers the flush with a 503.
        EventsDispatcher dispatcher = new EventsDispatcher(attempt -> status(attempt == 1 ? 503 : 202));
        server = serverWith(dispatcher);
        client = clientFor(server);

        recordOneEvaluation("user-1");

        client.flush(); // rejected with 503 — the batch must NOT be lost
        client.flush(); // must carry the same events

        assertThat(dispatcher.eventPosts.get())
            .as("the rejected batch must be re-sent by the next flush")
            .isEqualTo(2);
        assertThat(dispatcher.postedBodies.get(1))
            .as("the second POST must carry the event the 503 rejected")
            .contains("\"flagKey\":\"test-flag\"")
            .contains("\"userId\":\"user-1\"");
    }

    @Test
    void aTransportFailureIsRetryableAndKeepsTheBatch() throws Exception {
        // A read timeout is the transport fault this endpoint actually suffers: the request
        // is fully sent, then no response headers arrive. OkHttp raises SocketTimeoutException
        // (an IOException) and does not retry once the send has started, so the SDK's own
        // classification is what decides whether the batch survives.
        EventsDispatcher dispatcher = new EventsDispatcher(attempt -> attempt == 1
            ? new MockResponse.Builder().code(202).headersDelay(30, TimeUnit.SECONDS).build()
            : status(202));
        server = serverWith(dispatcher);
        client = clientFor(server, 1000, Duration.ofMillis(500));

        recordOneEvaluation("user-1");

        client.flush(); // times out
        client.flush(); // must re-send

        assertThat(dispatcher.eventPosts.get())
            .as("a transport failure must be treated as retryable")
            .isEqualTo(2);
        assertThat(dispatcher.postedBodies.get(1)).contains("\"userId\":\"user-1\"");
    }

    @Test
    void a401DropsTheBatchWithoutRetrying() throws Exception {
        // A rejected SDK key fails identically next time. Retrying it forever would pin the
        // queue at its bound and starve every later event.
        EventsDispatcher dispatcher = new EventsDispatcher(attempt -> status(401));
        server = serverWith(dispatcher);
        client = clientFor(server);

        recordOneEvaluation("user-1");

        client.flush();
        client.flush();

        assertThat(dispatcher.eventPosts.get())
            .as("a permanently rejected batch must be dropped, not retried")
            .isEqualTo(1);
    }

    @Test
    void aFailingEndpointDoesNotGetOneRequestPerRecordedEvent() throws Exception {
        // A re-queued batch leaves the queue at or above the batch size, so without a backoff
        // gate AND an in-flight latch every subsequent event would trigger another flush —
        // one request per evaluation against an endpoint that is already failing, which is
        // worse for the server than the dropping this fix replaces.
        EventsDispatcher dispatcher = new EventsDispatcher(attempt -> status(503));
        server = serverWith(dispatcher);
        client = clientFor(server, 1, Duration.ofSeconds(5));

        for (int i = 0; i < 10; i++) {
            recordOneEvaluation("user-" + i);
        }

        assertThat(dispatcher.eventPosts.get())
            .as("the size trigger must back off until the scheduled flush retries")
            .isEqualTo(1);
    }

    @Test
    void aBacklogIsSentInBatchesRatherThanOneOversizedRequest() throws Exception {
        // Re-queuing failures lets the queue grow towards its 10,000 bound during an outage.
        // Posting all of it in one body invites a 413, which is NOT retryable — so the very
        // path that exists to preserve the backlog would be the one that dropped it.
        EventsDispatcher dispatcher = new EventsDispatcher(attempt -> status(attempt == 1 ? 503 : 202));
        server = serverWith(dispatcher);
        client = clientFor(server, 2, Duration.ofSeconds(5));

        // The second event trips the size trigger and meets the 503; the remaining three
        // pile up behind the backoff gate, leaving a five-event backlog.
        for (int i = 0; i < 5; i++) {
            recordOneEvaluation("user-" + i);
        }
        assertThat(dispatcher.eventPosts.get()).isEqualTo(1);

        client.flush();

        List<String> bodies = new ArrayList<>(dispatcher.postedBodies);
        for (String body : bodies) {
            assertThat(MAPPER.readTree(body).get("events").size())
                .as("no request may carry more than the batch size")
                .isLessThanOrEqualTo(2);
        }
        // Every event still arrives — batching splits the backlog, it does not shed any of it.
        StringBuilder delivered = new StringBuilder();
        for (int i = 1; i < bodies.size(); i++) {
            delivered.append(bodies.get(i));
        }
        for (int i = 0; i < 5; i++) {
            assertThat(delivered.toString())
                .as("event for user-%d must reach the endpoint", i)
                .contains("\"userId\":\"user-" + i + "\"");
        }
    }

    @Test
    void closeTerminatesWhileTheEndpointIsFailing() throws Exception {
        // Shutdown makes ONE final attempt and discards the remainder. Looping until the
        // queue emptied would hang for as long as the endpoint stayed down.
        EventsDispatcher dispatcher = new EventsDispatcher(attempt -> status(503));
        server = serverWith(dispatcher);
        client = clientFor(server);

        recordOneEvaluation("user-1");
        recordOneEvaluation("user-2");

        FeatureflipClient closing = client;
        assertTimeoutPreemptively(Duration.ofSeconds(10), closing::close);
        client = null;

        assertThat(dispatcher.eventPosts.get())
            .as("close must make exactly one final flush attempt")
            .isEqualTo(1);
    }
}
