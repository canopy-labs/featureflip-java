package io.featureflip.client;

import io.featureflip.client.internal.FlagHttpClient;
import io.featureflip.client.internal.SnapshotCodec;
import io.featureflip.client.internal.model.GetFlagsResponse;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.featureflip.client.SnapshotTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core-level snapshot behavior. Data sources are stubbed out (Testing.startDataSources) and
 * the clock is injected, so recovery, listener and refresh behavior is deterministic: no
 * test races an immediate first poll against its own listener registration.
 */
class SharedFeatureflipCoreSnapshotTest {
    private static final long NOW = 1_800_000_000_000L;

    @TempDir
    Path dir;

    private final AtomicLong wall = new AtomicLong(NOW);
    private final AtomicLong nanos = new AtomicLong(0);
    private final List<SharedFeatureflipCore> cores = new ArrayList<>();
    private final List<AutoCloseable> closeables = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> snapshotWarnings = new java.util.concurrent.CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (SharedFeatureflipCore core : cores) core.release();
        for (AutoCloseable c : closeables) c.close();
    }

    private SharedFeatureflipCore.Testing testing() {
        SharedFeatureflipCore.Testing t = new SharedFeatureflipCore.Testing();
        t.startDataSources = false;
        t.wallMillis = wall::get;
        t.monotonicNanos = nanos::get;
        t.snapshotWarn = snapshotWarnings::add;
        return t;
    }

    private FeatureFlagConfig.Builder config(String baseUrl, SnapshotStore store) {
        return FeatureFlagConfig.builder()
            .baseUrl(baseUrl)
            .connectTimeout(Duration.ofMillis(300))
            .readTimeout(Duration.ofSeconds(5))
            .initTimeout(Duration.ofSeconds(2))
            .streaming(false)
            .snapshotStore(store);
    }

    private SharedFeatureflipCore core(FeatureFlagConfig config, SharedFeatureflipCore.Testing testing) {
        return core(SDK_KEY, config, testing);
    }

    private SharedFeatureflipCore core(String sdkKey, FeatureFlagConfig config, SharedFeatureflipCore.Testing testing) {
        SharedFeatureflipCore core = new SharedFeatureflipCore(sdkKey, config, testing);
        cores.add(core);
        return core;
    }

    private MockWebServer serving(int count, String body) throws Exception {
        MockWebServer server = new MockWebServer();
        for (int i = 0; i < count; i++) {
            server.enqueue(new MockResponse.Builder().body(body).addHeader("Content-Type", "application/json").build());
        }
        server.start();
        closeables.add(server::close);
        return server;
    }

    private MockWebServer failing(int status) throws Exception {
        MockWebServer server = new MockWebServer();
        for (int i = 0; i < 5; i++) server.enqueue(new MockResponse.Builder().code(status).build());
        server.start();
        closeables.add(server::close);
        return server;
    }

    /** slf4j-simple resolves System.err per write, so swapping it observes the log. */
    private static String captureStderr(Runnable body) {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean bool(SharedFeatureflipCore core, String key, boolean dflt) {
        return core.evaluate(key, EvaluationContext.builder("u1").build(), dflt, Boolean.class).getValue();
    }

    private String snapshotText(String baseUrl, String sdkKey, long fetchedAt, String flagKey, boolean value) {
        return envelope(SnapshotCodec.computeKeyHash(baseUrl, sdkKey), fetchedAt,
            wireData(flagJson(flagKey, value), ""));
    }

    // ---------------------------------------------------------------- healthy start

    @Test
    void healthyStartWritesAfterTheFetchAndNeverReadsTheStore() throws Exception {
        MemoryStore memory = new MemoryStore();
        MockWebServer server = serving(1, flagsResponse("f", true));
        String base = server.url("/").toString();
        SharedFeatureflipCore core = core(config(base, memory).build(), testing());

        assertThat(core.isInitialized()).isTrue();
        assertThat(core.startupInfo()).isEqualTo(StartupInfo.live());
        assertThat(eventually(5, () -> memory.puts.size() == 1)).isTrue();
        assertThat(memory.gets).isZero();
        // Real wire casing and an envelope that decodes under this client's own key hash.
        String put = memory.puts.get(0);
        assertThat(put).contains("\"type\":\"Boolean\"", "\"Fixed\"").doesNotContain(SDK_KEY);
        SnapshotCodec.Decoded decoded = SnapshotCodec.decode(put,
            SnapshotCodec.computeKeyHash(base, SDK_KEY), NOW, FlagHttpClient.newObjectMapper());
        assertThat(decoded.getFlags()).hasSize(1);
        assertThat(decoded.getFetchedAtMs()).isEqualTo(NOW);
    }

    // ---------------------------------------------------------------- start from snapshot

    @Test
    void fetchErrorFallsBackToTheSnapshotWithoutWriting() throws Exception {
        MockWebServer server = failing(503);
        String base = server.url("/").toString();
        MemoryStore memory = new MemoryStore();
        memory.text = snapshotText(base, SDK_KEY, NOW - 90_500, "f", true);

        SharedFeatureflipCore core = core(config(base, memory).build(), testing());

        assertThat(core.isInitialized()).isTrue();
        assertThat(core.startupInfo().getSource()).isEqualTo(StartupInfo.Source.SNAPSHOT);
        assertThat(core.startupInfo().getReason()).isEqualTo("snapshot");
        assertThat(core.startupInfo().getSnapshotAgeSeconds()).isEqualTo(90L); // whole seconds
        assertThat(bool(core, "f", false)).isTrue();
        Thread.sleep(150);
        assertThat(memory.puts).as("loading a snapshot never writes").isEmpty();
    }

    @Test
    void initTimeoutFallsBackToTheSnapshotToo() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        Socket s = silent.accept(); // accepts, never answers
                        closeables.add(s);
                    }
                } catch (Exception ignored) {
                    // closed
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            String base = "http://localhost:" + silent.getLocalPort();
            MemoryStore memory = new MemoryStore();
            memory.text = snapshotText(base, SDK_KEY, NOW - 1_000, "f", true);

            long start = System.nanoTime();
            SharedFeatureflipCore core = core(config(base, memory).initTimeout(Duration.ofMillis(300)).build(), testing());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(core.startupInfo().getSource()).isEqualTo(StartupInfo.Source.SNAPSHOT);
            assertThat(bool(core, "f", false)).isTrue();
            assertThat(elapsedMs).isLessThan(3_000);
        }
    }

    @Test
    void aStaleFetchResultArrivingAfterTheTimeoutIsNeverApplied() throws Exception {
        try (ServerSocket slow = new ServerSocket(0)) {
            CountDownLatch release = new CountDownLatch(1);
            Thread acceptor = new Thread(() -> {
                try {
                    Socket s = slow.accept();
                    release.await(10, TimeUnit.SECONDS);
                    String body = flagsResponse("late", true);
                    s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body).getBytes());
                    s.getOutputStream().flush();
                    s.close();
                } catch (Exception ignored) {
                    // test over
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            String base = "http://localhost:" + slow.getLocalPort();
            SharedFeatureflipCore core = core(
                config(base, new MemoryStore()).initTimeout(Duration.ofMillis(300)).build(), testing());
            release.countDown();
            Thread.sleep(300);

            assertThat(core.startupInfo().getSource()).isEqualTo(StartupInfo.Source.NONE);
            assertThat(bool(core, "late", false)).isFalse();
        }
    }

    @Test
    void expiredSnapshotServesDefaultsWithItsOwnReason() throws Exception {
        MockWebServer server = failing(500);
        String base = server.url("/").toString();
        MemoryStore memory = new MemoryStore();
        memory.text = snapshotText(base, SDK_KEY, NOW - 86_401_000L, "f", true);

        SharedFeatureflipCore core = core(config(base, memory).build(), testing());

        assertThat(core.startupInfo()).isEqualTo(
            new StartupInfo(StartupInfo.Source.NONE, "snapshot-expired", 86_401L));
        assertThat(bool(core, "f", false)).isFalse();
        assertThat(core.isInitialized()).isTrue();
    }

    @Test
    void ageAtTheLimitIsStillUsableAndTheLimitIsConfigurable() throws Exception {
        MockWebServer server = failing(500);
        String base = server.url("/").toString();
        MemoryStore memory = new MemoryStore();
        memory.text = snapshotText(base, SDK_KEY, NOW - 10_000, "f", true);
        assertThat(core(config(base, memory).maxSnapshotAgeSeconds(10).build(), testing())
            .startupInfo().getSource()).isEqualTo(StartupInfo.Source.SNAPSHOT);

        memory.text = snapshotText(base, SDK_KEY, NOW - 10_001, "f", true);
        assertThat(core(config(base, memory).maxSnapshotAgeSeconds(10).build(), testing())
            .startupInfo().getReason()).isEqualTo("snapshot-expired");
    }

    @Test
    void invalidReasonsThroughTheRealFileStore() throws Exception {
        MockWebServer server = failing(500);
        String base = server.url("/").toString();
        String good = snapshotText(base, SDK_KEY, NOW - 1_000, "f", true);
        String otherKey = snapshotText(base, "another-key", NOW - 1_000, "f", true);
        String otherBase = snapshotText("http://elsewhere.test", SDK_KEY, NOW - 1_000, "f", true);
        String[][] cases = {
            {"garbage", "this is not json"},
            {"truncated", good.substring(0, good.length() / 2)},
            {"other key", otherKey},
            {"other base url", otherBase},
            {"v2", good.replace("\"v\":1", "\"v\":2")},
            {"future", snapshotText(base, SDK_KEY, NOW + 61_000, "f", true)},
            {"empty", ""},
        };
        for (String[] c : cases) {
            Path file = dir.resolve("snap-" + c[0].replace(' ', '-') + ".json");
            Files.writeString(file, c[1]);
            SharedFeatureflipCore core = core(
                config(base, new FileSnapshotStore(file.toString())).build(), testing());
            assertThat(core.startupInfo().getSource()).as(c[0]).isEqualTo(StartupInfo.Source.NONE);
            assertThat(core.startupInfo().getReason()).as(c[0]).isEqualTo("snapshot-invalid");
            assertThat(core.startupInfo().getSnapshotAgeSeconds()).as(c[0]).isNull();
            assertThat(bool(core, "f", false)).as(c[0]).isFalse();
            assertThat(core.isInitialized()).as(c[0]).isTrue();
        }
    }

    @Test
    void futureWithinSixtySecondsIsAcceptedWithAgeZero() throws Exception {
        MockWebServer server = failing(500);
        String base = server.url("/").toString();
        MemoryStore memory = new MemoryStore();
        memory.text = snapshotText(base, SDK_KEY, NOW + 60_000, "f", true);
        SharedFeatureflipCore core = core(config(base, memory).build(), testing());
        assertThat(core.startupInfo()).isEqualTo(new StartupInfo(StartupInfo.Source.SNAPSHOT, "snapshot", 0L));
    }

    @Test
    void missingFileIsAPlainMissWithNoReason() throws Exception {
        MockWebServer server = failing(500);
        String base = server.url("/").toString();
        SharedFeatureflipCore core = core(
            config(base, new FileSnapshotStore(dir.resolve("absent.json").toString())).build(), testing());
        assertThat(core.startupInfo()).isEqualTo(StartupInfo.none());
        assertThat(core.isInitialized()).isTrue();
        assertThat(bool(core, "f", true)).isTrue(); // caller default
    }

    @Test
    void oneFileStoreSharedByTwoSdkKeysNeverLeaks() throws Exception {
        MockWebServer server = serving(1, flagsResponse("only-a", true));
        String base = server.url("/").toString();
        FileSnapshotStore shared = new FileSnapshotStore(dir.resolve("shared.json").toString());
        SharedFeatureflipCore a = core("key-a", config(base, shared).build(), testing());
        assertThat(eventually(5, () -> shared.get() != null)).isTrue();
        assertThat(a.startupInfo()).isEqualTo(StartupInfo.live());

        server.enqueue(new MockResponse.Builder().code(500).build()); // B's fetch fails
        SharedFeatureflipCore b = core("key-b", config(base, shared).build(), testing());
        assertThat(b.startupInfo().getReason()).isEqualTo("snapshot-invalid");
        assertThat(bool(b, "only-a", false)).isFalse();
    }

    // ---------------------------------------------------------------- store failures

    @Test
    void aHungGetIsBoundedByTheInitTimeoutAndItsLateResultIgnored() throws Exception {
        MockWebServer server = failing(500);
        String base = server.url("/").toString();
        CountDownLatch release = new CountDownLatch(1);
        String valid = snapshotText(base, SDK_KEY, NOW - 1_000, "f", true);
        SnapshotStore hung = new SnapshotStore() {
            public String get() {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return valid;
            }
            public void put(String text) { }
        };
        long start = System.nanoTime();
        SharedFeatureflipCore core = core(config(base, hung).initTimeout(Duration.ofMillis(300)).build(), testing());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        release.countDown();
        Thread.sleep(200);

        assertThat(elapsedMs).isLessThan(2_500);
        assertThat(core.isInitialized()).isTrue();
        assertThat(core.startupInfo()).isEqualTo(StartupInfo.none());
        assertThat(bool(core, "f", false)).isFalse();
    }

    @Test
    void aThrowingGetIsAMissAndNeverEscapesInit() throws Exception {
        MockWebServer server = failing(500);
        SnapshotStore throwing = new SnapshotStore() {
            public String get() { throw new IllegalStateException("redis down"); }
            public void put(String text) { }
        };
        SharedFeatureflipCore core = core(config(server.url("/").toString(), throwing).build(), testing());
        assertThat(core.isInitialized()).isTrue();
        assertThat(core.startupInfo()).isEqualTo(StartupInfo.none());
    }

    @Test
    void aThrowingPutNeverBreaksInitOrEvaluation() throws Exception {
        MockWebServer server = serving(1, flagsResponse("f", true));
        SnapshotStore throwing = new SnapshotStore() {
            public String get() { return null; }
            public void put(String text) { throw new IllegalStateException("disk full"); }
        };
        SharedFeatureflipCore core = core(config(server.url("/").toString(), throwing).build(), testing());
        assertThat(core.startupInfo()).isEqualTo(StartupInfo.live());
        assertThat(bool(core, "f", false)).isTrue();
    }

    @Test
    void aWriterThatCannotStartDoesNotWedgeOrBreakInit() throws Exception {
        MockWebServer server = serving(1, flagsResponse("f", true));
        SharedFeatureflipCore.Testing t = testing();
        t.writerSpawner = task -> { throw new OutOfMemoryError("unable to create native thread"); };
        SharedFeatureflipCore core = core(config(server.url("/").toString(), new MemoryStore()).build(), t);
        assertThat(core.isInitialized()).isTrue();
        assertThat(core.startupInfo()).isEqualTo(StartupInfo.live());
        assertThat(bool(core, "f", false)).isTrue();
        assertThat(snapshotWarnings).anyMatch(w -> w.contains("could not start the writer"));
    }

    // ---------------------------------------------------------------- failed init, with and without a store

    @Test
    void withAStoreAFailedInitWithNoSnapshotIsAnInitializedDefaultsServingClient() throws Exception {
        MockWebServer server = failing(401);
        SharedFeatureflipCore[] holder = new SharedFeatureflipCore[1];
        String err = captureStderr(() -> holder[0] =
            core(config(server.url("/").toString(), new MemoryStore()).build(), testing()));
        SharedFeatureflipCore core = holder[0];
        assertThat(core.isInitialized()).isTrue();
        core.waitForInitialization(); // does not throw
        assertThat(bool(core, "f", true)).isTrue();
        // The warning names the cause (a revoked key reads differently from an unreachable host)
        // and never the key.
        assertThat(err).contains("no snapshot stored", "401").doesNotContain(SDK_KEY);
    }

    @Test
    void withoutAStoreAFailedInitStillThrowsFromWaitForInitialization() throws Exception {
        MockWebServer server = failing(401);
        SharedFeatureflipCore core = core(
            config(server.url("/").toString(), null).initTimeout(Duration.ofMillis(200)).build(), testing());
        assertThat(core.isInitialized()).isFalse();
        org.junit.jupiter.api.Assertions.assertThrows(FeatureFlagInitializationException.class,
            core::waitForInitialization);
        assertThat(core.startupInfo()).isEqualTo(StartupInfo.none());
    }

    // ---------------------------------------------------------------- snapshot -> live

    private GetFlagsResponse parsed(String key, boolean value) throws Exception {
        return parseWire(flagsResponse(key, value));
    }

    @Test
    void snapshotStartThenLiveRecoveryFiresTheDiffAndMovesStartupInfoToLive() throws Exception {
        MockWebServer server = failing(500);
        String base = server.url("/").toString();
        MemoryStore memory = new MemoryStore();
        memory.text = snapshotText(base, SDK_KEY, NOW - 5_000, "f", true);
        SharedFeatureflipCore core = core(config(base, memory).build(), testing());
        assertThat(core.startupInfo().getSource()).isEqualTo(StartupInfo.Source.SNAPSHOT);

        List<List<String>> fired = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<StartupInfo> seenInListener = new java.util.concurrent.CopyOnWriteArrayList<>();
        core.addUpdateListener(keys -> {
            fired.add(keys);
            seenInListener.add(core.startupInfo());
        });

        // The first live full apply: flag f flips, g is new. Snapshot load was the baseline.
        GetFlagsResponse live = parsed("f", false);
        live.getFlags().addAll(parsed("g", true).getFlags());
        core.getStoreForTesting().replace(live.getFlags(), live.getSegments());

        assertThat(fired).containsExactly(List.of("f", "g"));
        assertThat(seenInListener).as("startup info is already LIVE inside the listener")
            .containsExactly(StartupInfo.live());
        assertThat(core.startupInfo()).isEqualTo(StartupInfo.live());
        assertThat(bool(core, "f", true)).isFalse();
        assertThat(eventually(5, () -> memory.puts.size() == 1)).isTrue();
    }

    @Test
    void aFirstLiveApplyAfterANoneStartIsASilentBaseline() throws Exception {
        MockWebServer server = failing(500);
        SharedFeatureflipCore core = core(config(server.url("/").toString(), new MemoryStore()).build(), testing());
        assertThat(core.startupInfo()).isEqualTo(StartupInfo.none());

        List<List<String>> fired = new java.util.concurrent.CopyOnWriteArrayList<>();
        core.addUpdateListener(fired::add);
        GetFlagsResponse live = parsed("f", true);
        core.getStoreForTesting().replace(live.getFlags(), live.getSegments());
        assertThat(fired).isEmpty();
        assertThat(core.startupInfo()).isEqualTo(StartupInfo.live());

        // Later applies diff normally.
        GetFlagsResponse changed = parsed("f", false);
        core.getStoreForTesting().replace(changed.getFlags(), changed.getSegments());
        assertThat(fired).containsExactly(List.of("f"));
    }

    @Test
    void deltasPersistOnlyOnceLive() throws Exception {
        MockWebServer server = failing(500);
        String base = server.url("/").toString();
        MemoryStore memory = new MemoryStore();
        memory.text = snapshotText(base, SDK_KEY, NOW - 5_000, "f", true);
        SharedFeatureflipCore core = core(config(base, memory).build(), testing());

        io.featureflip.client.internal.FlagStore store = core.getStoreForTesting();
        store.upsertFlag(parsed("g", true).getFlags().get(0));
        store.removeFlag("g");
        Thread.sleep(150);
        assertThat(memory.puts).as("no write right after a snapshot start").isEmpty();

        GetFlagsResponse live = parsed("f", true);
        store.replace(live.getFlags(), live.getSegments());
        assertThat(eventually(5, () -> memory.puts.size() == 1)).isTrue();

        store.upsertFlag(parsed("g", true).getFlags().get(0));
        assertThat(eventually(5, () -> memory.puts.size() == 2)).isTrue();
        store.removeFlag("g");
        assertThat(eventually(5, () -> memory.puts.size() == 3)).isTrue();
    }

    @Test
    void refreshRuleHonorsTheClockAndNeverRefreshesBeforeTheFirstLiveApply() throws Exception {
        MockWebServer server = serving(1, flagsResponse("f", true));
        String base = server.url("/").toString();
        MemoryStore memory = new MemoryStore();
        SharedFeatureflipCore core = core(config(base, memory).build(), testing());
        assertThat(eventually(5, () -> memory.puts.size() == 1)).isTrue();

        nanos.set(TimeUnit.MINUTES.toNanos(4));
        core.onStreamPing();
        Thread.sleep(100);
        assertThat(memory.puts).hasSize(1);

        nanos.set(TimeUnit.MINUTES.toNanos(5));
        wall.addAndGet(300_000);
        core.onStreamPing();
        assertThat(eventually(5, () -> memory.puts.size() == 2)).isTrue();
        assertThat(memory.puts.get(1)).contains("\"fetchedAt\":" + (NOW + 300_000));

        // Unchanged full apply inside the window is skipped, a changed one is written at once.
        GetFlagsResponse same = parsed("f", true);
        core.getStoreForTesting().replace(same.getFlags(), same.getSegments());
        Thread.sleep(100);
        assertThat(memory.puts).hasSize(2);
        GetFlagsResponse changed = parsed("f", false);
        core.getStoreForTesting().replace(changed.getFlags(), changed.getSegments());
        assertThat(eventually(5, () -> memory.puts.size() == 3)).isTrue();
    }

    @Test
    void aSnapshotStartedClientNeverRefreshesOnPing() throws Exception {
        MockWebServer server = failing(500);
        String base = server.url("/").toString();
        MemoryStore memory = new MemoryStore();
        memory.text = snapshotText(base, SDK_KEY, NOW - 5_000, "f", true);
        SharedFeatureflipCore core = core(config(base, memory).build(), testing());
        nanos.set(TimeUnit.MINUTES.toNanos(30));
        core.onStreamPing();
        Thread.sleep(150);
        assertThat(memory.puts).isEmpty();
    }

    @Test
    void noWriteHappensAfterClose() throws Exception {
        MockWebServer server = serving(1, flagsResponse("f", true));
        MemoryStore memory = new MemoryStore();
        SharedFeatureflipCore core = core(config(server.url("/").toString(), memory).build(), testing());
        assertThat(eventually(5, () -> memory.puts.size() == 1)).isTrue();
        core.release();
        GetFlagsResponse changed = parsed("f", false);
        core.getStoreForTesting().replace(changed.getFlags(), changed.getSegments());
        Thread.sleep(150);
        assertThat(memory.puts).hasSize(1);
    }

    // ---------------------------------------------------------------- over the real SSE data source

    private static MockResponse sse(String eventLines) {
        return new MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(eventLines)
            .build();
    }

    @Test
    void sseSyncPingAndSegmentRefetchPersistThroughTheRealDataSource() throws Exception {
        String twoFlags = "{\"environment\":\"t\",\"version\":2,\"flags\":[" + flagJson("f", true) + ","
            + flagJson("g", true) + "],\"segments\":[]}";
        java.util.concurrent.atomic.AtomicInteger flagsCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger streams = new java.util.concurrent.atomic.AtomicInteger();
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new mockwebserver3.Dispatcher() {
            @Override
            public MockResponse dispatch(mockwebserver3.RecordedRequest request) {
                String path = request.getUrl().encodedPath();
                if (path.equals("/v1/sdk/flags")) {
                    // Initial fetch: f only. The segment.updated refetch: f flipped, g present.
                    String body = flagsCalls.getAndIncrement() == 0
                        ? flagsResponse("f", true)
                        : "{\"environment\":\"t\",\"version\":3,\"flags\":[" + flagJson("f", false) + ","
                            + flagJson("g", true) + "],\"segments\":[]}";
                    return new MockResponse.Builder().body(body).addHeader("Content-Type", "application/json").build();
                }
                if (path.equals("/v1/sdk/stream")) {
                    switch (streams.getAndIncrement()) {
                        case 0:
                            return sse("event: sync\ndata: " + twoFlags + "\n\n");
                        case 1:
                            // 300 s pass before the heartbeat arrives.
                            nanos.addAndGet(TimeUnit.SECONDS.toNanos(300));
                            wall.addAndGet(300_000);
                            return sse("event: ping\ndata: {}\n\n");
                        case 2:
                            return sse("event: segment.updated\ndata: {}\n\n");
                        default:
                            return new MockResponse.Builder().code(500).build();
                    }
                }
                return new MockResponse.Builder().code(404).build();
            }
        });
        server.start();
        closeables.add(server::close);
        MemoryStore memory = new MemoryStore();
        SharedFeatureflipCore.Testing t = testing();
        t.startDataSources = true;
        SharedFeatureflipCore core = core(
            config(server.url("/").toString(), memory).streaming(true).build(), t);

        // 1. Healthy start writes. 2. A sync with changed content writes at once.
        assertThat(eventually(15, () -> memory.puts.size() >= 2)).as("sync with new content writes").isTrue();
        assertThat(memory.puts.get(0)).doesNotContain("\"key\":\"g\"");
        assertThat(memory.puts.get(1)).contains("\"key\":\"g\"").contains("\"fetchedAt\":" + NOW);

        // 3. A ping after 300 s rewrites the unchanged state with a fresh fetchedAt.
        assertThat(eventually(15, () -> memory.puts.size() >= 3)).as("ping refresh writes").isTrue();
        String third = memory.puts.get(2);
        assertThat(third).contains("\"fetchedAt\":" + (NOW + 300_000));
        assertThat(third.substring(third.indexOf("\"data\":")))
            .isEqualTo(memory.puts.get(1).substring(memory.puts.get(1).indexOf("\"data\":")));

        // 4. segment.updated with a changed refetch writes the new state.
        assertThat(eventually(15, () -> memory.puts.size() >= 4)).as("segment refetch writes").isTrue();
        assertThat(memory.puts.get(3)).contains("\"key\":\"g\"");
        assertThat(memory.puts.get(3)).isNotEqualTo(third);
        assertThat(bool(core, "f", true)).isFalse();
        assertThat(core.startupInfo()).isEqualTo(StartupInfo.live());
    }

    @Test
    void stubClientsExposeLiveStartupInfo() {
        assertThat(FeatureflipClient.forTesting(java.util.Map.of("f", true)).startupInfo())
            .isEqualTo(StartupInfo.live());
        SharedFeatureflipCore c = SharedFeatureflipCore.createForTesting();
        cores.add(c);
        assertThat(c.startupInfo()).isEqualTo(StartupInfo.live());
    }
}
