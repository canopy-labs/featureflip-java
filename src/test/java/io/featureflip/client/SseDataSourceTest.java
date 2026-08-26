package io.featureflip.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.internal.FlagHttpClient;
import io.featureflip.client.internal.FlagStore;
import io.featureflip.client.internal.SseDataSource;
import io.featureflip.client.internal.model.FlagConfiguration;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import okhttp3.sse.EventSourceListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SseDataSourceTest {
    private MockWebServer server;
    private FlagStore store;
    private ScheduledExecutorService executor;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new FlagStore();
        executor = Executors.newSingleThreadScheduledExecutor();
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        server.close();
    }

    private String singleFlagJson(String key) throws Exception {
        return mapper.writeValueAsString(Map.of(
            "key", key,
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
        ));
    }

    private String allFlagsJson(String... keys) throws Exception {
        var flags = new java.util.ArrayList<Map<String, Object>>();
        for (String key : keys) {
            flags.add(Map.of(
                "key", key,
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
            ));
        }
        return mapper.writeValueAsString(Map.of(
            "environment", "test",
            "version", 1,
            "flags", flags,
            "segments", List.of()
        ));
    }

    private String sseMessage(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
    }


    /**
     * Asserts the source connected and issued no REFETCH — no request to anything but
     * the SSE stream endpoint.
     *
     * <p>Counted by path rather than by total request count: a reconnect re-hits the
     * stream endpoint, and since #2508 its delay is jittered to [d/2, d], so it can
     * land inside a test's sleep window. A raw {@code getRequestCount() == 1} read a
     * routine reconnect as a refetch — and only passed before because the un-jittered
     * 1s backoff happened to tie the 1s sleep.
     */
    private void assertOnlyStreamRequests() throws InterruptedException {
        assertThat(server.getRequestCount()).as("the stream should have been connected").isGreaterThanOrEqualTo(1);

        RecordedRequest request;
        while ((request = server.takeRequest(50, TimeUnit.MILLISECONDS)) != null) {
            assertThat(request.getUrl().encodedPath())
                .as("only the SSE stream endpoint should be hit — a refetch would use another path")
                .isEqualTo("/v1/sdk/stream");
        }
    }

    private SseDataSource createDataSource() {
        return createDataSource(() -> {});
    }

    private SseDataSource createDataSource(Runnable onInitialized) {
        FeatureFlagConfig config = FeatureFlagConfig.builder()
            .baseUrl(server.url("/").toString())
            .build();
        FlagHttpClient httpClient = new FlagHttpClient("test-sdk-key", config);
        return new SseDataSource(httpClient, store, executor, onInitialized, () -> {});
    }

    /**
     * Captures whatever slf4j-simple writes while {@code body} runs. slf4j-simple
     * resolves System.err per write (cacheOutputStream defaults to false), so
     * swapping it here is enough to observe the level a line was logged at.
     */
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

    @Test
    void severAfterHealthyStreamIsNotLoggedAtWarn() {
        // A stream severed after it opened is routine behind a CDN or proxy (#2457):
        // the source reconnects and the server replays a full `sync`, so nothing is
        // degraded. Warning on it turned ordinary operation into recurring alarm.
        SseDataSource sseDataSource = createDataSource();
        EventSourceListener listener = sseDataSource.buildSseListener();

        String err = captureStderr(() -> {
            listener.onOpen(null, null);
            listener.onFailure(null, new IOException("The response ended prematurely"), null);
        });

        assertThat(err).doesNotContain("WARN");

        sseDataSource.close();
    }

    @Test
    void streamThatNeverOpenedIsStillLoggedAtWarn() {
        // The counterpart: a stream that never opened carried no configuration, so
        // quieting the routine sever must not quieten this one too.
        SseDataSource sseDataSource = createDataSource();
        EventSourceListener listener = sseDataSource.buildSseListener();

        String err = captureStderr(() ->
            listener.onFailure(null, new IOException("connection refused"), null));

        assertThat(err).contains("WARN");

        sseDataSource.close();
    }

    @Test
    void sseClientHasFiniteReadTimeoutWatchdog() {
        // The SSE client must use a finite read timeout (well above the ~30s
        // server ping) as a liveness watchdog — a half-open socket then surfaces
        // as a read timeout and reconnects, instead of blocking forever on
        // readTimeout(0).
        SseDataSource sse = createDataSource();
        okhttp3.OkHttpClient client = sse.buildSseClient();
        assertThat(client.readTimeoutMillis()).isNotZero();
        assertThat(client.readTimeoutMillis()).isGreaterThan(30_000);
    }

    @Test
    void flagCreatedFetchesSingleFlag() throws Exception {
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("flag.created", "{\"key\":\"new-flag\",\"version\":1}"))
            .code(200)
            .build());
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body(singleFlagJson("new-flag"))
            .build());

        SseDataSource sseDataSource = createDataSource();
        sseDataSource.start();

        Thread.sleep(1000);
        sseDataSource.close();

        assertThat(store.getFlag("new-flag")).isNotNull();
        assertThat(store.getFlag("new-flag").getKey()).isEqualTo("new-flag");

        server.takeRequest(1, TimeUnit.SECONDS); // SSE connection
        RecordedRequest fetchRequest = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(fetchRequest).isNotNull();
        assertThat(fetchRequest.getUrl().encodedPath()).isEqualTo("/v1/sdk/flags/new-flag");
    }

    @Test
    void flagUpdatedFetchesSingleFlag() throws Exception {
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("flag.updated", "{\"key\":\"my-flag\",\"version\":2}"))
            .code(200)
            .build());
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body(singleFlagJson("my-flag"))
            .build());

        SseDataSource sseDataSource = createDataSource();
        sseDataSource.start();

        Thread.sleep(1000);
        sseDataSource.close();

        assertThat(store.getFlag("my-flag")).isNotNull();

        server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest fetchRequest = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(fetchRequest).isNotNull();
        assertThat(fetchRequest.getUrl().encodedPath()).isEqualTo("/v1/sdk/flags/my-flag");
    }

    @Test
    void flagDeletedRemovesFromStore() throws Exception {
        FlagConfiguration existingFlag = new FlagConfiguration();
        existingFlag.setKey("doomed-flag");
        existingFlag.setEnabled(true);
        store.upsertFlag(existingFlag);
        assertThat(store.getFlag("doomed-flag")).isNotNull();

        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("flag.deleted", "{\"key\":\"doomed-flag\"}"))
            .code(200)
            .build());

        SseDataSource sseDataSource = createDataSource();
        sseDataSource.start();

        Thread.sleep(1000);
        sseDataSource.close();

        assertThat(store.getFlag("doomed-flag")).isNull();
        assertOnlyStreamRequests();
    }

    @Test
    void flagDeletedForMissingKeyIsNoOp() throws Exception {
        assertThat(store.getFlag("nonexistent")).isNull();

        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("flag.deleted", "{\"key\":\"nonexistent\"}"))
            .code(200)
            .build());

        SseDataSource sseDataSource = createDataSource();
        sseDataSource.start();

        Thread.sleep(1000);
        sseDataSource.close();

        assertThat(store.getFlag("nonexistent")).isNull();
    }

    @Test
    void segmentUpdatedTriggersFullRefetch() throws Exception {
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("segment.updated", "{\"key\":\"seg-1\",\"version\":1}"))
            .code(200)
            .build());
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body(allFlagsJson("flag-a", "flag-b"))
            .build());

        SseDataSource sseDataSource = createDataSource();
        sseDataSource.start();

        Thread.sleep(1000);
        sseDataSource.close();

        assertThat(store.getFlag("flag-a")).isNotNull();
        assertThat(store.getFlag("flag-b")).isNotNull();

        server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest fetchRequest = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(fetchRequest).isNotNull();
        assertThat(fetchRequest.getUrl().encodedPath()).isEqualTo("/v1/sdk/flags");
    }

    @Test
    void flagDeletedCallsOnInitialized() throws Exception {
        FlagConfiguration existingFlag = new FlagConfiguration();
        existingFlag.setKey("doomed-flag");
        existingFlag.setEnabled(true);
        store.upsertFlag(existingFlag);

        CountDownLatch latch = new CountDownLatch(1);

        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("flag.deleted", "{\"key\":\"doomed-flag\"}"))
            .code(200)
            .build());

        SseDataSource sseDataSource = createDataSource(latch::countDown);
        sseDataSource.start();

        boolean initialized = latch.await(3, TimeUnit.SECONDS);
        sseDataSource.close();

        assertThat(initialized).as("onInitialized should be called after flag.deleted").isTrue();
    }

    @Test
    void unknownEventTypeIsIgnored() throws Exception {
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("some.unknown.event", "{\"key\":\"x\"}"))
            .code(200)
            .build());

        SseDataSource sseDataSource = createDataSource();
        sseDataSource.start();

        Thread.sleep(500);
        sseDataSource.close();

        assertThat(store.getAllFlags()).isEmpty();
        assertOnlyStreamRequests();
    }

    @Test
    void emptyKeyInPayloadIsSkipped() throws Exception {
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("flag.updated", "{\"version\":1}"))
            .code(200)
            .build());

        SseDataSource sseDataSource = createDataSource();
        sseDataSource.start();

        Thread.sleep(500);
        sseDataSource.close();

        assertThat(store.getAllFlags()).isEmpty();
        assertOnlyStreamRequests();
    }

    @Test
    void syncReplacesStoreWithoutRefetch() throws Exception {
        // Pre-seed a stale flag the snapshot does NOT contain.
        FlagConfiguration stale = new FlagConfiguration();
        stale.setKey("flag-stale");
        stale.setEnabled(true);
        store.upsertFlag(stale);

        // A single connect-time `sync` carrying only flag-new (payload is the
        // full-snapshot shape — same as GET /v1/sdk/flags).
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("sync", allFlagsJson("flag-new")))
            .code(200)
            .build());

        SseDataSource sseDataSource = createDataSource();
        sseDataSource.start();

        Thread.sleep(1000);
        sseDataSource.close();

        assertThat(store.getFlag("flag-new")).isNotNull();
        // Full REPLACE, not merge: a flag absent from the snapshot is dropped.
        assertThat(store.getFlag("flag-stale")).isNull();
        // sync carries the payload — no second (fetch) request, unlike segment.updated.
        assertOnlyStreamRequests();
    }

    @Test
    void reconnectsAndResyncsAfterStreamDrop() throws Exception {
        // A stale flag present before the outage — must be dropped on the reconnect
        // full-replace.
        FlagConfiguration stale = new FlagConfiguration();
        stale.setKey("flag-stale");
        stale.setEnabled(true);
        store.upsertFlag(stale);

        // Stream #1: connect-time sync carrying flag-a, then the server closes (drop).
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("sync", allFlagsJson("flag-a")))
            .code(200)
            .build());
        // Stream #2 (after the automatic reconnect): sync carrying only flag-b —
        // flag-a AND flag-stale must be gone (full replace), proving re-sync with
        // no manual restart.
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("sync", allFlagsJson("flag-b")))
            .code(200)
            .build());

        SseDataSource sseDataSource = createDataSource();
        sseDataSource.start();

        // onClosed after stream #1 schedules reconnect at min(2^0,30)=1s; wait past it.
        Thread.sleep(3000);
        sseDataSource.close();

        assertThat(store.getFlag("flag-b")).as("re-synced flag after reconnect").isNotNull();
        assertThat(store.getFlag("flag-a")).as("stream #1 flag replaced on reconnect").isNull();
        assertThat(store.getFlag("flag-stale")).as("stale pre-outage flag dropped").isNull();
        assertThat(server.getRequestCount()).as("reconnected with no intervention").isGreaterThanOrEqualTo(2);
    }

    private String loadSnapshotFixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/golden/snapshot.json")) {
            assertThat(in).as("golden/snapshot.json must be on the test classpath").isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
    }

    @Test
    void syncAppliesLargeMultiFlagSnapshotFixture() throws Exception {
        // Realism bump (#1935): feed the shared >64 KiB multi-flag `sync` fixture
        // through handleSync and assert a full store REPLACE. OkHttp owns SSE
        // framing; this exercises Jackson deserialization of a realistic snapshot
        // + FlagStore.replace at size, not the wire parser.
        String snapshot = loadSnapshotFixture();
        assertThat(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            .as("fixture exceeds 64 KiB").isGreaterThan(64 * 1024);

        // Pre-seed a stale flag the snapshot does NOT contain — must be dropped.
        FlagConfiguration stale = new FlagConfiguration();
        stale.setKey("flag-stale");
        stale.setEnabled(true);
        store.upsertFlag(stale);

        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(sseMessage("sync", snapshot))
            .code(200)
            .build());

        SseDataSource sseDataSource = createDataSource();
        sseDataSource.start();

        Thread.sleep(1000);
        sseDataSource.close();

        assertThat(store.getFlag("sync-canary")).as("declared canary flag applied").isNotNull();
        assertThat(store.getAllFlags().size()).as("all padding flags applied").isGreaterThan(800);
        // Structural payload (not just flag keys) round-trips — a dropped segment
        // would otherwise be swallowed by @JsonIgnoreProperties(ignoreUnknown).
        assertThat(store.getSegment("snapshot-segment")).as("segment from snapshot applied").isNotNull();
        assertThat(store.getFlag("flag-stale")).as("full replace drops absent flag").isNull();
        assertOnlyStreamRequests(); // sync carries the payload — no refetch
    }
}
