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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SseDataSource {
    private static final Logger log = LoggerFactory.getLogger(SseDataSource.class);
    private static final int MAX_BACKOFF_SECONDS = 30;
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
    private final ScheduledExecutorService executor;
    private final AtomicReference<EventSource> eventSourceRef = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile boolean closed = false;

    public SseDataSource(FlagHttpClient httpClient, FlagStore store,
                         ScheduledExecutorService executor,
                         Runnable onInitialized, Runnable onFallbackToPolling) {
        this.httpClient = httpClient;
        this.store = store;
        this.objectMapper = httpClient.objectMapper;
        this.executor = executor;
        this.onInitialized = onInitialized;
        this.onFallbackToPolling = onFallbackToPolling;
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
        EventSource es = factory.newEventSource(request, new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                log.debug("SSE connection opened");
                consecutiveFailures.set(0);
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
                log.warn("SSE connection failed (attempt {}): {}",
                    failures, t != null ? t.getMessage() : "unknown");

                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("SSE failed {} consecutive times, falling back to polling", failures);
                    onFallbackToPolling.run();
                    return;
                }
                reconnect();
            }
        });

        eventSourceRef.set(es);
    }

    private void reconnect() {
        if (closed) return;
        int failures = consecutiveFailures.get();
        long backoffSeconds = Math.min((1L << failures), MAX_BACKOFF_SECONDS);
        log.debug("Reconnecting SSE in {}s", backoffSeconds);

        executor.schedule(this::connect, backoffSeconds, TimeUnit.SECONDS);
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
        List<FlagConfiguration> flags = response.getFlags();
        List<Segment> segments = response.getSegments();
        store.replace(flags, segments);
        onInitialized.run();
        log.debug("SSE sync: replaced store with {} flags, {} segments",
            flags != null ? flags.size() : 0, segments != null ? segments.size() : 0);
    }
}
