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
import java.util.Collections;
import java.util.List;
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

    /**
     * Evaluation observers, already null-filtered and unmodifiable (see
     * {@link FeatureFlagConfig#getInspectors()}). Immutable after construction, so
     * the evaluation hot path reads it without synchronization.
     */
    private final List<EvaluationInspector> inspectors;

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
    private SharedFeatureflipCore(FlagStore store, FeatureFlagConfig config) {
        this.store = store;
        this.evaluator = new FlagEvaluator(store);
        this.config = config;
        this.eventProcessor = new EventProcessor(this.config.getFlushBatchSize());
        this.testValues = null;
        this.inspectors = config.getInspectors();

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
        this.inspectors = Collections.emptyList();

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
        this.inspectors = config.getInspectors();

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
        return new SharedFeatureflipCore(new FlagStore(), FeatureFlagConfig.builder().build());
    }

    /** Creates a test core backed by the given pre-populated FlagStore. */
    static SharedFeatureflipCore createForTesting(FlagStore store) {
        return new SharedFeatureflipCore(store, FeatureFlagConfig.builder().build());
    }

    /**
     * Creates a test core backed by the given pre-populated FlagStore and config —
     * lets tests exercise config-driven behaviour (e.g. inspectors) without
     * starting background tasks or opening network connections.
     */
    static SharedFeatureflipCore createForTesting(FlagStore store, FeatureFlagConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        return new SharedFeatureflipCore(store, config);
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
     *
     * <p>This is the single evaluation choke point — every {@code boolVariation} /
     * {@code stringVariation} / {@code intVariation} / {@code doubleVariation} /
     * {@code jsonVariation} / {@code *VariationDetail} call funnels through it — so
     * registered inspectors are notified exactly once per variation call here, on
     * every exit path (success, flag-not-found and error alike), with the value the
     * caller actually receives.
     */
    <T> EvaluationDetail<T> evaluate(String key, EvaluationContext context, T defaultValue, Class<T> type) {
        Outcome<T> outcome = evaluateInternal(key, context, defaultValue, type);
        notifyInspectors(key, context, outcome);
        return outcome.detail;
    }

    private <T> Outcome<T> evaluateInternal(String key, EvaluationContext context, T defaultValue,
                                            Class<T> type) {
        try {
            if (testValues != null) {
                Object value = testValues.get(key);
                if (value == null) {
                    return Outcome.of(
                        new EvaluationDetail<>(defaultValue, EvaluationReason.FLAG_NOT_FOUND, null, null));
                }
                @SuppressWarnings("unchecked")
                T typedValue = (T) value;
                return Outcome.of(new EvaluationDetail<>(typedValue, EvaluationReason.FALLTHROUGH, null, null));
            }

            FlagConfiguration flag = store.getFlag(key);
            if (flag == null) {
                return Outcome.of(new EvaluationDetail<>(defaultValue, EvaluationReason.FLAG_NOT_FOUND, null,
                    "Flag '" + key + "' not found"));
            }

            FlagEvaluator.Result result = evaluator.evaluate(flag, context, store.getAllFlags());
            Variation variation = flag.getVariationByKey(result.getVariationKey());

            if (variation == null) {
                // Malformed config: the evaluator picked a variation key the flag does not
                // define (e.g. a fallthrough/rule naming a since-deleted variation). The
                // caller receives the fail-safe default with reason ERROR — mirroring the
                // engine's ServeVariation + the C# SDK (#1989) — while the detail keeps the
                // attempted variation key for diagnostics. Inspectors additionally see null
                // variation/rule/prerequisite keys (see Outcome).
                return Outcome.reportedAsError(
                    new EvaluationDetail<>(defaultValue, EvaluationReason.ERROR, result.getRuleId(),
                        "Variation '" + result.getVariationKey() + "' not found",
                        result.getVariationKey(), result.getPrerequisiteKey()));
            }

            T value;
            try {
                value = deserializeValue(variation.getValue(), type);
            } catch (TypeMismatchException e) {
                // The config is healthy here — the caller simply asked for a type the
                // served value isn't. They get their own fail-safe default with reason
                // ERROR so the mismatch is detectable, while the returned detail keeps
                // the variation key for diagnostics. Inspectors see ERROR with null
                // variation/rule/prerequisite keys, honouring EvaluationEvent's contract
                // that those are null when evaluation errored (same shape as #1989).
                log.warn("Type mismatch reading flag '{}': {}", key, e.getMessage());
                return Outcome.reportedAsError(
                    new EvaluationDetail<>(defaultValue, EvaluationReason.ERROR, result.getRuleId(),
                        e.getMessage(), result.getVariationKey(), result.getPrerequisiteKey()));
            }
            return Outcome.of(new EvaluationDetail<>(value, result.getReason(), result.getRuleId(), null,
                result.getVariationKey(), result.getPrerequisiteKey()));
        } catch (Exception e) {
            log.warn("Evaluation error for flag '{}': {}", key, e.getMessage());
            return Outcome.of(new EvaluationDetail<>(defaultValue, EvaluationReason.ERROR, null, e.getMessage()));
        }
    }

    /**
     * One completed evaluation in its two views: the {@link EvaluationDetail} returned
     * to the caller, and the reason/variation/rule/prerequisite reported to inspectors.
     *
     * <p>The two coincide on every exit path but one. When the evaluator picks a
     * variation key the flag does not define (malformed config), the caller receives its
     * own fail-safe default with reason {@link EvaluationReason#ERROR}, while the detail
     * still carries the attempted variation key (and rule/prerequisite key) for
     * diagnostics (#1989). The event drops those keys entirely: forwarding them would
     * publish an event that reads like a healthy exposure of a variation that was never
     * served — so inspectors are told {@link EvaluationReason#ERROR} with a null variation
     * key, rule id and prerequisite key. This matches the event the C# SDK emits for the
     * identical condition, and is consistent with {@link EvaluationEvent#getVariationKey()}'s
     * documented contract that the variation key is null when evaluation errored.
     */
    private static final class Outcome<T> {
        private final EvaluationDetail<T> detail;
        private final EvaluationReason reportedReason;
        private final String reportedVariationKey;
        private final String reportedRuleId;
        private final String reportedPrerequisiteKey;

        private Outcome(EvaluationDetail<T> detail, EvaluationReason reportedReason, String reportedVariationKey,
                        String reportedRuleId, String reportedPrerequisiteKey) {
            this.detail = detail;
            this.reportedReason = reportedReason;
            this.reportedVariationKey = reportedVariationKey;
            this.reportedRuleId = reportedRuleId;
            this.reportedPrerequisiteKey = reportedPrerequisiteKey;
        }

        /** The ordinary case: inspectors observe exactly what the caller receives. */
        static <T> Outcome<T> of(EvaluationDetail<T> detail) {
            return new Outcome<>(detail, detail.getReason(), detail.getVariationKey(), detail.getRuleId(),
                detail.getPrerequisiteKey());
        }

        /** Malformed config: the caller keeps the diagnostic detail, inspectors see an error. */
        static <T> Outcome<T> reportedAsError(EvaluationDetail<T> detail) {
            return new Outcome<>(detail, EvaluationReason.ERROR, null, null, null);
        }
    }

    /**
     * Notifies the registered evaluation inspectors of one completed evaluation.
     *
     * <p>Allocates nothing when no inspectors are registered — the common case, on
     * the hottest path in the SDK. Nothing is reported once the core has shut down
     * (the last client handle was closed): a closed client still returns a value to
     * any caller racing the close, but publishing observability events out of a
     * decommissioned client would be surprising, so the notification alone is
     * suppressed — matching the other Featureflip server SDKs and {@link
     * #trackEvaluation}.
     *
     * <p>Each inspector is isolated: a throwing inspector
     * neither changes the value returned to the caller nor prevents the remaining
     * inspectors from firing. {@link Throwable} is caught rather than
     * {@link Exception} because user code most plausibly escapes via an unchecked
     * {@code RuntimeException} or an {@code Error} (e.g. an assertion inside a test
     * inspector), and neither may break evaluation.
     */
    private void notifyInspectors(String flagKey, EvaluationContext context, Outcome<?> outcome) {
        if (inspectors.isEmpty() || isShutDown()) {
            return;
        }

        EvaluationEvent event = new EvaluationEvent(
            flagKey,
            context != null ? context.copy() : null,
            outcome.detail.getValue(),
            outcome.reportedVariationKey,
            outcome.reportedReason,
            outcome.reportedRuleId,
            outcome.reportedPrerequisiteKey,
            Instant.now().toString());

        for (EvaluationInspector inspector : inspectors) {
            try {
                inspector.onEvaluation(event);
            } catch (Throwable t) {
                log.warn("Evaluation inspector threw for flag '{}': {}", flagKey, t.toString());
            }
        }
    }

    /**
     * Tracks an evaluation event into the event processor queue.
     *
     * <p>The context is optional. A null one is a legitimate "no user" evaluation —
     * the evaluator serves the fallthrough for it and {@link #notifyInspectors}
     * already guards it — so it must not be able to fail a flag read here. This
     * runs <em>after</em> {@code evaluate} has computed the caller's answer, outside
     * the try/catch that converts evaluation failures into the caller's default, so
     * dereferencing a null context threw a bare NPE out of a call that had already
     * succeeded (#2280). The event is still recorded, just unattributed: {@code
     * SdkEvent} serializes NON_NULL, so a null user id is omitted, and the eval-api's
     * {@code SdkEventDto.UserId} is nullable. That matches the Go SDK, whose
     * value-typed context yields an empty user id for the same case.
     */
    void trackEvaluation(String key, EvaluationContext context, String variationKey) {
        if (isShutDown() || eventProcessor == null) return;

        SdkEvent event = new SdkEvent();
        event.setType(SdkEventType.EVALUATION);
        event.setFlagKey(key);
        event.setUserId(context != null ? context.getUserId() : null);
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

    /** Tracks a custom event. The context is optional — see {@link #trackEvaluation}. */
    void track(String eventName, EvaluationContext context, Map<String, Object> metadata) {
        if (testValues != null || eventProcessor == null) return;
        SdkEvent event = new SdkEvent();
        event.setType(SdkEventType.CUSTOM);
        event.setFlagKey(eventName);
        event.setUserId(context != null ? context.getUserId() : null);
        event.setTimestamp(Instant.now());
        setMetadata(event, metadata);
        eventProcessor.enqueue(event);
        if (eventProcessor.shouldFlush()) flushEvents();
    }

    /**
     * Records an identify event. The context is optional — see
     * {@link #trackEvaluation}.
     *
     * <p>The caller's attributes ride along as metadata. There is no alias to
     * strip: {@link EvaluationContext} keeps the user id in its own field, so
     * the attribute bag never carries the identity.
     */
    void identify(EvaluationContext context) {
        if (testValues != null || eventProcessor == null) return;
        SdkEvent event = new SdkEvent();
        event.setType(SdkEventType.IDENTIFY);
        event.setFlagKey("$identify");
        event.setUserId(context != null ? context.getUserId() : null);
        event.setTimestamp(Instant.now());
        if (context != null) {
            setMetadata(event, context.attributes());
        }
        eventProcessor.enqueue(event);
        if (eventProcessor.shouldFlush()) flushEvents();
    }

    /**
     * Converts a caller-supplied bag to Jackson nodes and attaches it. An empty
     * bag is left unset so {@code NON_NULL} omits the field entirely, matching
     * the other SDKs (#2359).
     */
    private void setMetadata(SdkEvent event, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || httpClient == null) return;
        try {
            ObjectMapper mapper = httpClient.getObjectMapper();
            Map<String, JsonNode> jsonMeta = new java.util.HashMap<>();
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                jsonMeta.put(entry.getKey(), mapper.valueToTree(entry.getValue()));
            }
            event.setMetadata(jsonMeta);
        } catch (Exception e) {
            log.warn("Failed to serialize event metadata: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a served variation value to the type the caller asked for, strictly.
     *
     * <p>Jackson's {@code asText()}/{@code asInt()}/{@code asBoolean()} family coerces
     * across JSON types and never throws, so a boolean flag read through
     * {@code intVariation} yielded {@code 0} — a plausible-looking wrong number that
     * flows straight into caller logic — instead of the caller's default. These checks
     * mirror the C# SDK's strict {@code JsonElement} accessors so a mismatch surfaces
     * rather than being papered over (#2281).
     *
     * @throws TypeMismatchException if the served value is not of the requested type
     */
    @SuppressWarnings("unchecked")
    private <T> T deserializeValue(JsonNode node, Class<T> type) {
        if (node == null) {
            throw new TypeMismatchException("variation has no value");
        }
        if (type == Boolean.class || type == boolean.class) {
            if (!node.isBoolean()) throw new TypeMismatchException(describeMismatch(node, "boolean"));
            return (T) Boolean.valueOf(node.booleanValue());
        }
        if (type == String.class) {
            if (!node.isTextual()) throw new TypeMismatchException(describeMismatch(node, "string"));
            return (T) node.textValue();
        }
        if (type == Integer.class || type == int.class) {
            // isIntegralNumber() rejects 42.5 the way C#'s GetInt32() does rather than
            // truncating it; canConvertToInt() additionally rejects out-of-range longs.
            if (!node.isIntegralNumber() || !node.canConvertToInt()) {
                throw new TypeMismatchException(describeMismatch(node, "int"));
            }
            return (T) Integer.valueOf(node.intValue());
        }
        if (type == Double.class || type == double.class) {
            // Any JSON number satisfies a double read, matching C#'s GetDouble().
            if (!node.isNumber()) throw new TypeMismatchException(describeMismatch(node, "double"));
            return (T) Double.valueOf(node.doubleValue());
        }
        try {
            return OBJECT_MAPPER.treeToValue(node, type);
        } catch (Exception e) {
            throw new TypeMismatchException(
                "value is not convertible to " + type.getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String describeMismatch(JsonNode node, String requested) {
        return "value of JSON type " + node.getNodeType() + " cannot be read as " + requested;
    }

    /** Signals that a served variation value is not of the type the caller requested. */
    private static final class TypeMismatchException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TypeMismatchException(String message) {
            super(message);
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
