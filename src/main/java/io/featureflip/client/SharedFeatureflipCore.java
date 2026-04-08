package io.featureflip.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.internal.EventProcessor;
import io.featureflip.client.internal.FlagEvaluator;
import io.featureflip.client.internal.FlagHttpClient;
import io.featureflip.client.internal.FlagStore;
import io.featureflip.client.internal.PollingDataSource;
import io.featureflip.client.internal.SseDataSource;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.SdkEvent;
import io.featureflip.client.internal.model.SdkEventType;
import io.featureflip.client.internal.model.Variation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internal shared core that owns the expensive resources (HTTP client, executors,
 * SSE/polling data sources, flag store, event processor) of a FeatureflipClient.
 * Refcounted: multiple FeatureflipClient handles can share one core, and the
 * real shutdown runs when the last handle is released.
 *
 * <p>Package-private. The only legitimate users of this class are
 * {@link FeatureflipClient} (which wraps it in a handle) and the unit tests.
 */
final class SharedFeatureflipCore {
    private static final Logger log = LoggerFactory.getLogger(SharedFeatureflipCore.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AtomicInteger refCount = new AtomicInteger(1);
    private final AtomicInteger isShutDown = new AtomicInteger(0); // 0 = alive, 1 = shut down

    private java.util.concurrent.ConcurrentHashMap<String, SharedFeatureflipCore> owningMap;
    private String owningKey;

    private final FlagStore store;
    private final FlagEvaluator evaluator;
    private final EventProcessor eventProcessor;
    private final FeatureFlagConfig config;

    /**
     * Test-mode value stub: when set, evaluate() short-circuits to these values
     * instead of consulting the flag store. Used by FeatureflipClient.forTesting(Map)
     * — preserves the existing public test-helper API.
     */
    private final Map<String, Object> testValues;

    private final FlagHttpClient httpClient;
    private final SseDataSource sseDataSource;
    private final PollingDataSource pollingDataSource;
    private final ScheduledExecutorService executor;
    private final CountDownLatch initLatch;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicReference<PollingDataSource> fallbackPoller = new AtomicReference<>();
    private ScheduledFuture<?> flushTask;

    /** Current refcount. For testing/diagnostics only. */
    int getRefCount() { return refCount.get(); }

    /** Whether the core has been fully shut down (refcount reached 0). */
    boolean isShutDown() { return isShutDown.get() != 0; }

    FeatureFlagConfig getConfig() { return config; }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Test-only constructor backed by a pre-built FlagStore. */
    private SharedFeatureflipCore(FlagStore store) {
        this.store = store;
        this.evaluator = new FlagEvaluator(store);
        this.config = FeatureFlagConfig.builder().build();
        this.eventProcessor = new EventProcessor(this.config.getFlushBatchSize());
        this.testValues = null;

        this.httpClient = null;
        this.sseDataSource = null;
        this.pollingDataSource = null;
        this.executor = null;
        this.initLatch = new CountDownLatch(0); // already counted down
        this.initialized.set(true);
        this.flushTask = null;
    }

    /** Test-only stub constructor. Returns fixed values without touching the flag store. */
    private SharedFeatureflipCore(Map<String, Object> testValues) {
        this.store = null;
        this.evaluator = null;
        this.config = null;
        this.eventProcessor = null;
        this.testValues = Map.copyOf(testValues);

        this.httpClient = null;
        this.sseDataSource = null;
        this.pollingDataSource = null;
        this.executor = null;
        this.initLatch = new CountDownLatch(0);
        this.initialized.set(true);
        this.flushTask = null;
    }

    /** Production constructor. Package-private so only FeatureflipClient can call it. */
    SharedFeatureflipCore(String sdkKey, FeatureFlagConfig config) {
        if (sdkKey == null || sdkKey.isBlank()) {
            throw new IllegalArgumentException("sdkKey must not be null or blank");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        this.config = config;
        this.store = new FlagStore();
        this.evaluator = new FlagEvaluator(store);
        this.eventProcessor = new EventProcessor(config.getFlushBatchSize());
        this.initLatch = new CountDownLatch(1);
        this.testValues = null;

        this.httpClient = new FlagHttpClient(sdkKey, config);
        this.executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "featureflip-bg");
            t.setDaemon(true);
            return t;
        });

        // Initial flag fetch
        try {
            var response = httpClient.fetchFlags();
            store.replace(response.getFlags(), response.getSegments());
            initialized.set(true);
            initLatch.countDown();
            log.debug("Initialized with {} flags", response.getFlags().size());
        } catch (Exception e) {
            log.warn("Initial flag fetch failed: {}", e.getMessage());
        }

        // Start data source
        Runnable initCallback = () -> {
            if (initialized.compareAndSet(false, true)) {
                initLatch.countDown();
            }
        };

        if (config.isStreaming()) {
            this.sseDataSource = new SseDataSource(httpClient, store, executor,
                initCallback, this::startPolling);
            this.pollingDataSource = null;
            executor.execute(() -> sseDataSource.start());
        } else {
            this.sseDataSource = null;
            this.pollingDataSource = new PollingDataSource(
                httpClient, store, config.getPollInterval().toMillis(), executor, initCallback);
            pollingDataSource.start();
        }

        // Start flush loop
        flushTask = executor.scheduleWithFixedDelay(
            this::flushEvents,
            config.getFlushInterval().toMillis(),
            config.getFlushInterval().toMillis(),
            TimeUnit.MILLISECONDS);
    }

    // -------------------------------------------------------------------------
    // Static factories
    // -------------------------------------------------------------------------

    /** Creates a minimal core for unit tests (empty FlagStore, no background tasks). */
    static SharedFeatureflipCore createForTesting() {
        return new SharedFeatureflipCore(new FlagStore());
    }

    /** Creates a test core backed by the given pre-populated FlagStore. */
    static SharedFeatureflipCore createForTesting(FlagStore store) {
        return new SharedFeatureflipCore(store);
    }

    /** Creates a test-stub core that returns fixed values from the map. */
    static SharedFeatureflipCore createForTestingStub(Map<String, Object> testValues) {
        return new SharedFeatureflipCore(testValues);
    }

    // -------------------------------------------------------------------------
    // Owning-map back-reference (set by the static factory after successful insert)
    // -------------------------------------------------------------------------

    /**
     * Called by the factory after this core is successfully inserted into the static map.
     * When the refcount hits zero, shutdown() will remove the entry via this reference.
     */
    void setOwningMap(java.util.concurrent.ConcurrentHashMap<String, SharedFeatureflipCore> map, String key) {
        this.owningMap = map;
        this.owningKey = key;
    }

    /**
     * Test-only: called by FeatureflipClient.resetForTesting to decommission this core.
     * Calls release() once. Note that the factory map does NOT hold its own refcount
     * increment — the "first handle" refcount baked into the constructor's refCount=1
     * is owned by the first returned handle, not by the map. So calling release() here
     * borrows against whichever handle still holds that slot. The advisory over-release
     * guard in release() makes any resulting double-decrement a safe no-op.
     */
    void forceShutdownFromReset() {
        release();
    }

    // -------------------------------------------------------------------------
    // Refcount lifecycle
    // -------------------------------------------------------------------------

    /**
     * Atomically increments the refcount if the core is still alive.
     * Returns false if the core has already shut down (caller must construct a new one).
     * Safe against over-release: negative refcount values are treated as shut down.
     */
    boolean tryAcquire() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                return false;
            }
            if (refCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Decrements the refcount. When it reaches zero, runs the real shutdown exactly once.
     * Over-release (calling release more times than tryAcquire was called) is a no-op —
     * the advisory guard prevents the counter from drifting below zero for the common
     * case, and tryAcquire's <= 0 check is the backstop for any racing over-release.
     */
    void release() {
        int current = refCount.get();
        if (current <= 0) {
            return;
        }
        int newCount = refCount.decrementAndGet();
        if (newCount == 0) {
            if (isShutDown.compareAndSet(0, 1)) {
                shutdown();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Initialization accessors
    // -------------------------------------------------------------------------

    boolean isInitialized() {
        return initialized.get();
    }

    void waitForInitialization() {
        if (isInitialized()) return;
        try {
            var timeout = config != null ? config.getInitTimeout() : java.time.Duration.ofSeconds(10);
            if (!initLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new FeatureFlagInitializationException(
                    "Initialization timed out after " + timeout.toMillis() + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeatureFlagInitializationException("Initialization interrupted", e);
        }
    }

    // -------------------------------------------------------------------------
    // Evaluation
    // -------------------------------------------------------------------------

    /**
     * Evaluates a flag and returns full detail. Does not track the evaluation event.
     * In test-stub mode, returns the value from the test map directly.
     */
    <T> EvaluationDetail<T> evaluate(String key, EvaluationContext context, T defaultValue, Class<T> type) {
        try {
            if (testValues != null) {
                Object value = testValues.get(key);
                if (value == null) {
                    return new EvaluationDetail<>(defaultValue, EvaluationReason.FLAG_NOT_FOUND, null, null);
                }
                @SuppressWarnings("unchecked")
                T typedValue = (T) value;
                return new EvaluationDetail<>(typedValue, EvaluationReason.FALLTHROUGH, null, null);
            }

            FlagConfiguration flag = store.getFlag(key);
            if (flag == null) {
                return new EvaluationDetail<>(defaultValue, EvaluationReason.FLAG_NOT_FOUND, null,
                    "Flag '" + key + "' not found");
            }

            FlagEvaluator.Result result = evaluator.evaluate(flag, context);
            Variation variation = flag.getVariationByKey(result.getVariationKey());

            if (variation == null) {
                return new EvaluationDetail<>(defaultValue, result.getReason(), result.getRuleId(),
                    "Variation '" + result.getVariationKey() + "' not found");
            }

            T value = deserializeValue(variation.getValue(), defaultValue, type);
            return new EvaluationDetail<>(value, result.getReason(), result.getRuleId(), null, result.getVariationKey());
        } catch (Exception e) {
            log.warn("Evaluation error for flag '{}': {}", key, e.getMessage());
            return new EvaluationDetail<>(defaultValue, EvaluationReason.ERROR, null, e.getMessage());
        }
    }

    /** Tracks an evaluation event into the event processor queue. */
    void trackEvaluation(String key, EvaluationContext context, String variationKey) {
        if (isShutDown() || eventProcessor == null) return;

        SdkEvent event = new SdkEvent();
        event.setType(SdkEventType.EVALUATION);
        event.setFlagKey(key);
        event.setUserId(context.getUserId());
        event.setVariation(variationKey);
        event.setTimestamp(Instant.now());
        eventProcessor.enqueue(event);

        if (eventProcessor.shouldFlush()) {
            flushEvents();
        }
    }

    // -------------------------------------------------------------------------
    // Background machinery (event tracking, flush, SSE/polling)
    // -------------------------------------------------------------------------

    void flush() {
        flushEvents();
    }

    void track(String eventName, EvaluationContext context, Map<String, Object> metadata) {
        if (testValues != null || eventProcessor == null) return;
        SdkEvent event = new SdkEvent();
        event.setType(SdkEventType.CUSTOM);
        event.setFlagKey(eventName);
        event.setUserId(context.getUserId());
        event.setTimestamp(Instant.now());
        if (metadata != null && !metadata.isEmpty() && httpClient != null) {
            try {
                ObjectMapper mapper = httpClient.getObjectMapper();
                Map<String, JsonNode> jsonMeta = new java.util.HashMap<>();
                for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                    jsonMeta.put(entry.getKey(), mapper.valueToTree(entry.getValue()));
                }
                event.setMetadata(jsonMeta);
            } catch (Exception e) {
                log.warn("Failed to serialize track metadata: {}", e.getMessage());
            }
        }
        eventProcessor.enqueue(event);
        if (eventProcessor.shouldFlush()) flushEvents();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <T> T deserializeValue(JsonNode node, T defaultValue, Class<T> type) {
        try {
            if (node == null) return defaultValue;
            if (type == Boolean.class || type == boolean.class) return (T) Boolean.valueOf(node.asBoolean());
            if (type == String.class) return (T) node.asText();
            if (type == Integer.class || type == int.class) return (T) Integer.valueOf(node.asInt());
            if (type == Double.class || type == double.class) return (T) Double.valueOf(node.asDouble());
            return OBJECT_MAPPER.treeToValue(node, type);
        } catch (Exception e) {
            log.warn("Failed to deserialize value: {}", e.getMessage());
            return defaultValue;
        }
    }

    private void flushEvents() {
        if (eventProcessor == null || httpClient == null) return;
        try {
            var events = eventProcessor.drain();
            if (!events.isEmpty()) {
                httpClient.sendEvents(events);
            }
        } catch (Exception e) {
            log.warn("Event flush failed: {}", e.getMessage());
        }
    }

    private void startPolling() {
        if (isShutDown()) return;
        Runnable initCallback = () -> {
            if (initialized.compareAndSet(false, true)) {
                initLatch.countDown();
            }
        };
        PollingDataSource poller = new PollingDataSource(
            httpClient, store, config.getPollInterval().toMillis(), executor, initCallback);
        if (fallbackPoller.compareAndSet(null, poller)) {
            log.info("Starting polling fallback");
            poller.start();
        }
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    private void shutdown() {
        if (owningMap != null && owningKey != null) {
            // ConcurrentHashMap.remove(K, V) is the atomic value-comparing remove —
            // only removes the entry if it still points at this core.
            owningMap.remove(owningKey, this);
        }

        // Stop data sources
        if (sseDataSource != null) sseDataSource.close();
        if (pollingDataSource != null) pollingDataSource.close();
        PollingDataSource fb = fallbackPoller.getAndSet(null);
        if (fb != null) fb.close();

        // Final flush
        flushEvents();

        // Shutdown executor
        if (executor != null) {
            if (flushTask != null) flushTask.cancel(false);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Executor did not terminate in 5s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (eventProcessor != null) eventProcessor.close();
        if (httpClient != null) httpClient.close();

        log.debug("SharedFeatureflipCore shut down");
    }
}
