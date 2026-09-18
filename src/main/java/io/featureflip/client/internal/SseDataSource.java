package io.featureflip.client.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.GetFlagsResponse;
import io.featureflip.client.internal.model.Segment;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SseDataSource {
    private static final Logger log = LoggerFactory.getLogger(SseDataSource.class);
    static final int MAX_BACKOFF_SECONDS = 30;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    // Liveness watchdog: a finite SSE read timeout well above the ~30s server
    // ping (≈3 missed pings). With readTimeout(0) a half-open socket (silent
    // LB/NAT idle-drop, no FIN/RST) would block the reader forever; a finite
    // timeout surfaces as a read failure that onFailure() reconnects/falls back.
    static final int SSE_READ_TIMEOUT_SECONDS = 90;

    private final FlagHttpClient httpClient;
    private final FlagStore store;
    private final ObjectMapper objectMapper;
    private final Runnable onInitialized;
    private final Runnable onFallbackToPolling;
    private final Runnable onStreamRecovered;
    private final ScheduledExecutorService executor;
    private final AtomicReference<EventSource> eventSourceRef = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    // True once a stream has opened, cleared when it fails. Distinguishes a routine
    // sever of a working stream from one that never carried any configuration (#2457).
    private final AtomicBoolean streamOpened = new AtomicBoolean(false);
    // True between handing over to the polling fallback and the next `sync`. Gates the
    // recovery signal so it fires once per outage, not on every routine re-sync.
    private final AtomicBoolean polledFallbackActive = new AtomicBoolean(false);
    private volatile boolean closed = false;

    /** Retained overload: a source with no recovery signal never retires its fallback. */
    public SseDataSource(FlagHttpClient httpClient, FlagStore store,
                         ScheduledExecutorService executor,
                         Runnable onInitialized, Runnable onFallbackToPolling) {
        this(httpClient, store, executor, onInitialized, onFallbackToPolling, () -> { });
    }

    public SseDataSource(FlagHttpClient httpClient, FlagStore store,
                         ScheduledExecutorService executor,
                         Runnable onInitialized, Runnable onFallbackToPolling,
                         Runnable onStreamRecovered) {
        this.httpClient = httpClient;
        this.store = store;
        this.objectMapper = httpClient.objectMapper;
        this.executor = executor;
        this.onInitialized = onInitialized;
        this.onFallbackToPolling = onFallbackToPolling;
        this.onStreamRecovered = onStreamRecovered;
    }

    public void start() {
        connect();
    }

    public void close() {
        closed = true;
        EventSource es = eventSourceRef.getAndSet(null);
        if (es != null) es.cancel();
    }

    // Visible for testing. A finite read timeout acts as a liveness watchdog —
    // see SSE_READ_TIMEOUT_SECONDS.
    public OkHttpClient buildSseClient() {
        return httpClient.getHttpClient().newBuilder()
            .readTimeout(SSE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();
    }

    private void connect() {
        if (closed) return;

        OkHttpClient sseClient = buildSseClient();

        // Auth header is added by the interceptor in httpClient; only add Accept
        Request request = new Request.Builder()
            .url(httpClient.getBaseUrl() + "/v1/sdk/stream")
            .addHeader("Accept", "text/event-stream")
            .build();

        EventSource.Factory factory = EventSources.createFactory(sseClient);
        EventSource es = factory.newEventSource(request, buildSseListener());

        eventSourceRef.set(es);
    }

    // Visible for testing. Holds the reconnect-severity policy (see onFailure), which
    // is asserted by driving this listener directly -- racing a real socket into an
    // abrupt mid-body sever is not reproducible enough to gate on.
    public EventSourceListener buildSseListener() {
        return new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                log.debug("SSE connection opened");
                consecutiveFailures.set(0);
                streamOpened.set(true);
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                handleEvent(type, data);
            }

            @Override
            public void onClosed(EventSource eventSource) {
                log.debug("SSE connection closed");
                reconnect();
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                if (closed) return;
                int failures = consecutiveFailures.incrementAndGet();
                String cause = t != null ? t.getMessage() : "unknown";

                // A stream that had opened and was then severed is routine behind a CDN
                // or proxy (#2457): reconnect() heals it and the server replays a full
                // `sync`, so no configuration is missed and nothing is degraded. A stream
                // that never opened carried nothing, so it keeps its warning -- as does
                // the fallback below. Mirrors the `reached` split in the python and C#
                // cores. Backoff is unchanged; only the severity moves.
                if (streamOpened.getAndSet(false)) {
                    log.debug("SSE stream severed after opening, reconnecting: {}", cause);
                } else {
                    log.warn("SSE connection failed (attempt {}): {}", failures, cause);
                }

                // The fallback is ADDITIVE, never terminal (#3071). Polling covers the
                // outage; the stream keeps retrying underneath at the capped backoff, and
                // the next `sync` retires the poller. Returning here instead left every
                // instance polling — and blind to real-time updates — until it restarted,
                // after only ~31s of unreachability.
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    if (polledFallbackActive.compareAndSet(false, true)) {
                        log.warn("SSE failed {} consecutive times, falling back to polling "
                            + "while the stream keeps retrying", failures);
                        onFallbackToPolling.run();
                    }
                }
                reconnect();
            }
        };
    }

    private void reconnect() {
        if (closed) return;
        long backoff = backoffMillis(consecutiveFailures.get());
        log.debug("Reconnecting SSE in {}ms", backoff);

        executor.schedule(this::connect, backoff, TimeUnit.MILLISECONDS);
    }

    /**
     * Capped exponential reconnect backoff, jittered to [d/2, d] at every level.
     *
     * <p>The jitter is load-bearing on the FIRST reconnect, not just the escalating
     * ones: the drops this absorbs are fleet-wide — one edge event severs every stream
     * at once (#2457) — so every client re-enters here at the same failure count
     * together. A constant there replayed the drop's own synchronisation as a
     * reconnect spike one delay later (#2508). The band's lower bound is strictly
     * positive, so a clean sever still cannot busy-loop.
     */
    static long backoffMillis(int failures) {
        long seconds = Math.min(1L << Math.min(failures, 32), MAX_BACKOFF_SECONDS);
        long millis = seconds * 1000L;
        long half = millis / 2;
        return half + ThreadLocalRandom.current().nextLong(half + 1);
    }

    private void handleEvent(String type, String data) {
        try {
            if (type == null) return;
            switch (type) {
                case "flag.created":
                case "flag.updated":
                    handleFlagUpdate(data);
                    break;
                case "flag.deleted":
                    handleFlagDeleted(data);
                    break;
                case "segment.updated":
                    handleSegmentUpdated();
                    break;
                case "sync":
                    handleSync(data);
                    break;
                case "ping":
                    log.debug("SSE ping received");
                    break;
                default:
                    log.debug("Unknown SSE event type: {}", type);
            }
        } catch (Exception e) {
            log.warn("Error handling SSE event '{}': {}", type, e.getMessage());
        }
    }

    private void handleFlagUpdate(String data) throws Exception {
        JsonNode node = objectMapper.readTree(data);
        String key = node.path("key").asText(null);
        if (key == null || key.isEmpty()) return;

        FlagConfiguration flag = httpClient.fetchFlag(key);
        // null means the flag carried an enum this build cannot evaluate and was dropped
        // at the parse boundary (#2402). fetchFlag has already said so; upserting null
        // here would wipe the store's previous copy, which is the opposite of the intent.
        if (flag == null) return;
        store.upsertFlag(flag);
        onInitialized.run();
        log.debug("SSE flag update: upserted flag '{}'", key);
    }

    private void handleFlagDeleted(String data) throws Exception {
        JsonNode node = objectMapper.readTree(data);
        String key = node.path("key").asText(null);
        if (key == null || key.isEmpty()) return;

        store.removeFlag(key);
        onInitialized.run();
        log.debug("SSE flag deleted: removed flag '{}'", key);
    }

    private void handleSegmentUpdated() throws Exception {
        GetFlagsResponse response = httpClient.fetchFlags();
        List<FlagConfiguration> flags = response.getFlags();
        List<Segment> segments = response.getSegments();
        store.replace(flags, segments);
        onInitialized.run();
        log.debug("SSE segment updated: replaced {} flags, {} segments",
            flags != null ? flags.size() : 0, segments != null ? segments.size() : 0);
    }

    private void handleSync(String data) throws Exception {
        // Full config snapshot the server sends on (re)connect. Replace the whole
        // store so flags changed — or deleted — during a disconnect are re-synced.
        // Full replace, never merge; the payload is in the event (no refetch).
        // getFlags()/getSegments() may be null on a `"flags": null` payload — the
        // store's replace() and the log below both null-coalesce.
        GetFlagsResponse response = objectMapper.readValue(data, GetFlagsResponse.class);
        // The sync frame is parsed here rather than through fetchFlags(), so the
        // entity-drop has to be applied explicitly — letting the two transports diverge
        // on the same payload shape is its own bug class (#2279).
        UnevaluableEntities.dropUnevaluable(response);
        List<FlagConfiguration> flags = response.getFlags();
        List<Segment> segments = response.getSegments();
        store.replace(flags, segments);
        onInitialized.run();
        log.debug("SSE sync: replaced store with {} flags, {} segments",
            flags != null ? flags.size() : 0, segments != null ? segments.size() : 0);

        // The stream is carrying configuration again, so the fallback poller has nothing
        // left to cover. Signalled off the `sync` rather than off onOpen: a connection
        // that opens and is severed before replaying its snapshot has recovered nothing.
        if (polledFallbackActive.compareAndSet(true, false)) {
            onStreamRecovered.run();
        }
    }
}
