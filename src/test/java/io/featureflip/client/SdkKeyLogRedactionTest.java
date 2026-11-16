package io.featureflip.client;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #5166: drives the noisy paths with a sentinel key and asserts it appears nowhere in the
 * captured log output (slf4j-simple at TRACE, see simplelogger.properties) nor in the
 * message of any exception the SDK raises. Several paths log an exception whose message
 * quotes the key, as a third-party HTTP stack quoting the Authorization header would, so
 * this proves the redactor (#5167) and not just clean call sites.
 */
class SdkKeyLogRedactionTest {
    private static final String KEY = "sdk_server_SentinelKeyMustNeverAppearInLogs-0123456789";
    private static final String FLAGS = "{\"environment\":\"t\",\"version\":1,\"flags\":[{\"key\":\"f\",\"version\":1,"
        + "\"type\":\"Boolean\",\"enabled\":true,\"variations\":[{\"key\":\"on\",\"value\":true},"
        + "{\"key\":\"off\",\"value\":false}],\"rules\":[],\"fallthrough\":{\"type\":\"Fixed\",\"variation\":\"on\"},"
        + "\"offVariation\":\"off\"}],\"segments\":[]}";

    private final PrintStream originalErr = System.err;
    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream captured;
    private final List<Throwable> raised = new ArrayList<>();
    private MockWebServer server;

    @BeforeEach
    void setUp() {
        FeatureflipClient.resetForTesting();
        captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setErr(sink);
        System.setOut(sink);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.setErr(originalErr);
        System.setOut(originalOut);
        FeatureflipClient.resetForTesting();
        if (server != null) server.close();
    }

    /** The flags body, with the caller's Authorization header echoed into a boolean field. */
    private static String echoBody(String authorization) {
        return FLAGS.replace("\"enabled\":true", "\"enabled\":\"" + authorization + "\"");
    }

    /** Same flag set plus a second flag, so applying it is a change that fires update listeners. */
    private static final String FLAGS_CHANGED = FLAGS.replace("\"version\":1,\"flags\":[",
        "\"version\":2,\"flags\":[{\"key\":\"g\",\"version\":1,\"type\":\"Boolean\",\"enabled\":true,"
        + "\"variations\":[{\"key\":\"on\",\"value\":true},{\"key\":\"off\",\"value\":false}],\"rules\":[],"
        + "\"fallthrough\":{\"type\":\"Fixed\",\"variation\":\"on\"},\"offVariation\":\"off\"},");

    /** Scripts the flags endpoint by 1-based request number: a status, or the echo body for 200. */
    private MockWebServer serve(IntFunction<String> flagsScript, IntFunction<Integer> eventsStatus) throws Exception {
        AtomicInteger events = new AtomicInteger();
        AtomicInteger flagsCalls = new AtomicInteger();
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/v1/sdk/events".equals(request.getUrl().encodedPath())) {
                    return new MockResponse.Builder().code(eventsStatus.apply(events.incrementAndGet())).build();
                }
                String what = flagsScript.apply(flagsCalls.incrementAndGet());
                if (what.startsWith("status:")) {
                    return new MockResponse.Builder().code(Integer.parseInt(what.substring(7))).build();
                }
                String body = what.equals("echo") ? echoBody(request.getHeaders().get("Authorization"))
                    : what.equals("changed") ? FLAGS_CHANGED : FLAGS;
                return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build();
            }
        });
        server.start();
        return server;
    }

    private MockWebServer serve(int flagsStatus, IntFunction<Integer> eventsStatus) throws Exception {
        return serve(n -> flagsStatus == 200 ? "ok" : "status:" + flagsStatus, eventsStatus);
    }

    private void awaitOutput(String needle) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!captured.toString(StandardCharsets.UTF_8).contains(needle)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for log output containing: " + needle);
            }
            Thread.onSpinWait();
            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
        }
    }

    private FeatureFlagConfig config(String baseUrl, List<EvaluationInspector> inspectors) {
        return FeatureFlagConfig.builder()
            .baseUrl(baseUrl)
            .streaming(false)
            .pollInterval(Duration.ofHours(1))
            .flushInterval(Duration.ofMinutes(10))
            .initTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(2))
            .connectTimeout(Duration.ofSeconds(2))
            .inspectors(inspectors)
            .build();
    }

    private void attempt(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            raised.add(e);
        }
    }

    private void drive(FeatureflipClient client) {
        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        attempt(client::waitForInitialization);
        attempt(() -> client.boolVariation("f", ctx, false));
        attempt(() -> client.boolVariation("missing", ctx, false));
        attempt(() -> client.stringVariation("f", ctx, "d")); // type mismatch
        attempt(() -> client.track("purchase", ctx, Map.of("k", "v")));
        attempt(() -> client.identify(ctx));
        attempt(client::flush);
        attempt(client::close);
    }

    private void assertNoLeak() {
        String out = captured.toString(StandardCharsets.UTF_8);
        assertThat(out).as("captured log output").isNotEmpty().doesNotContain(KEY);
        for (Throwable t : raised) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            assertThat(sw.toString()).as("exception raised by the SDK").doesNotContain(KEY);
        }
    }

    /** An inspector that throws with the key in its message, like an HTTP stack quoting the header. */
    private static List<EvaluationInspector> leakyInspector() {
        return List.of(event -> {
            throw new IllegalStateException("request failed, Authorization: " + KEY);
        });
    }

    @Test
    void healthyStartThenFailingEventsEndpoint() throws Exception {
        serve(200, n -> 500);
        FeatureflipClient client = FeatureflipClient.get(KEY, config(server.url("/").toString(), leakyInspector()));
        drive(client);
        assertNoLeak();
    }

    @Test
    void unauthorizedFlagsFetch() throws Exception {
        serve(401, n -> 401);
        drive(FeatureflipClient.get(KEY, config(server.url("/").toString(), leakyInspector())));
        assertNoLeak();
    }

    @Test
    void serverErrorOnFlagsFetch() throws Exception {
        serve(500, n -> 500);
        drive(FeatureflipClient.get(KEY, config(server.url("/").toString(), leakyInspector())));
        assertNoLeak();
    }

    @Test
    void transportErrorOnFlagsFetch() throws Exception {
        serve(200, n -> 200);
        String url = server.url("/").toString();
        server.close(); // connection refused
        server = null;
        drive(FeatureflipClient.get(KEY, config(url, leakyInspector())));
        assertNoLeak();
    }

    @Test
    void differentConfigOnTheSameKeyWarnsWithoutTheKey() throws Exception {
        serve(200, n -> 202);
        String url = server.url("/").toString();
        FeatureflipClient first = FeatureflipClient.get(KEY, config(url, leakyInspector()));
        FeatureflipClient second = FeatureflipClient.get(KEY,
            FeatureFlagConfig.builder().baseUrl(url).streaming(false).pollInterval(Duration.ofSeconds(7)).build());
        attempt(first::waitForInitialization);
        drive(second);
        drive(first);
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("different config");
        assertNoLeak();
    }

    @Test
    void theHttpStackQuotingTheKeyOnInitIsRedacted() throws Exception {
        // The 200 body carries the Authorization header in a boolean field, so Jackson throws
        // an InvalidFormatException quoting the key; the SDK logs e.getMessage().
        serve(n -> "echo", n -> 202);
        drive(FeatureflipClient.get(KEY, config(server.url("/").toString(), List.of())));
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("Initial flag fetch failed").contains("[redacted]");
        assertNoLeak();
    }

    @Test
    void theHttpStackQuotingTheKeyOnAPollIsRedacted() throws Exception {
        serve(n -> n == 1 ? "ok" : "echo", n -> 202);
        FeatureFlagConfig cfg = FeatureFlagConfig.builder().baseUrl(server.url("/").toString()).streaming(false)
            .pollInterval(Duration.ofMillis(200)).flushInterval(Duration.ofMinutes(10)).build();
        FeatureflipClient client = FeatureflipClient.get(KEY, cfg);
        client.waitForInitialization();
        awaitOutput("Polling failed");
        drive(client);
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("[redacted]");
        assertNoLeak();
    }

    @Test
    void aSnapshotStoreReadThatQuotesTheKeyIsRedacted() throws Exception {
        serve(n -> "echo", n -> 202); // init fails, so the SDK falls back to the snapshot store
        java.util.concurrent.CountDownLatch read = new java.util.concurrent.CountDownLatch(1);
        SnapshotStore store = new SnapshotStore() {
            @Override public String get() { read.countDown(); throw new IllegalStateException("redis AUTH " + KEY); }
            @Override public void put(String text) { }
        };
        FeatureFlagConfig cfg = FeatureFlagConfig.builder().baseUrl(server.url("/").toString()).streaming(false)
            .pollInterval(Duration.ofHours(1)).initTimeout(Duration.ofSeconds(5)).snapshotStore(store).build();
        FeatureflipClient client = FeatureflipClient.get(KEY, cfg);
        assertThat(read.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        awaitOutput("snapshot store read failed");
        drive(client);
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("[redacted]");
        assertNoLeak();
    }

    @Test
    void aSnapshotStoreWriteAndAListenerThatQuoteTheKeyAreRedacted() throws Exception {
        serve(n -> n == 1 ? "ok" : "changed", n -> 202);
        java.util.concurrent.CountDownLatch written = new java.util.concurrent.CountDownLatch(1);
        SnapshotStore store = new SnapshotStore() {
            @Override public String get() { return null; }
            @Override public void put(String text) { written.countDown(); throw new IllegalStateException("redis AUTH " + KEY); }
        };
        FeatureFlagConfig cfg = FeatureFlagConfig.builder().baseUrl(server.url("/").toString()).streaming(false)
            .pollInterval(Duration.ofMillis(200)).snapshotStore(store).build();
        FeatureflipClient client = FeatureflipClient.get(KEY, cfg);
        client.onUpdate(keys -> { throw new IllegalStateException("listener " + KEY); });
        client.waitForInitialization();
        assertThat(written.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        awaitOutput("Flag update listener threw");
        drive(client);
        assertNoLeak();
    }
}
