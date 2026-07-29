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
import org.junit.jupiter.api.Test;

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
        assertThat(server.getRequestCount()).isEqualTo(1);
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
        assertThat(server.getRequestCount()).isEqualTo(1);
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
        assertThat(server.getRequestCount()).isEqualTo(1);
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
        assertThat(server.getRequestCount()).isEqualTo(1);
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
        assertThat(server.getRequestCount()).as("sync carries the payload — no refetch").isEqualTo(1);
    }
}
