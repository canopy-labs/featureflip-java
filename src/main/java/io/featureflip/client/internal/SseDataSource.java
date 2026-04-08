package io.featureflip.client.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.GetFlagsResponse;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SseDataSource {
    private static final Logger log = LoggerFactory.getLogger(SseDataSource.class);
    private static final int MAX_BACKOFF_SECONDS = 30;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

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

    private void connect() {
        if (closed) return;

        // SSE needs long read timeout
        OkHttpClient sseClient = httpClient.getHttpClient().newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

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
        store.replace(response.getFlags(), response.getSegments());
        onInitialized.run();
        log.debug("SSE segment updated: replaced {} flags, {} segments",
            response.getFlags().size(), response.getSegments().size());
    }
}
