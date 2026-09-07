package io.featureflip.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.internal.EventProcessor;
import io.featureflip.client.internal.EventSendException;
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

import java.io.IOException;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * Monotonic deadline — a {@link System#nanoTime()} reading — before which the
     * batch-size trigger must not start another flush.
     *
     * <p>A re-queued batch leaves the queue at or above {@code flushBatchSize}, so without
     * this gate every subsequent tracked event would start another flush: one request per
     * evaluation against an endpoint that is already failing, which is worse for the server
     * than the dropping this fix replaces. The scheduled flush loop is the retry vehicle;
     * this only suppresses the size trigger between its ticks, and an explicit
     * {@code flush()} is never gated.
     *
     * <p>Seeded with "now" rather than 0 because {@code nanoTime()}'s origin is arbitrary
     * and its readings may be negative; comparisons subtract to stay overflow-safe.
     */
    private final AtomicLong nextAutoFlushAtNanos = new AtomicLong(System.nanoTime());

    /**
     * Monitor guarding the drain-loop coalescing state below.
     *
     * <p>{@code autoFlushInFlight} guards only the SIZE trigger. Nothing stopped the
     * scheduled flush loop, an explicit {@code flush()} from the public client API and a
     * size-triggered flush from entering the drain together — two request streams against
     * the endpoint the backoff gate exists to protect, and a success in one clearing the
     * gate a failure in the other had just armed, which re-opens the
     * one-request-per-evaluation behaviour outright (#2477).
     *
     * <p>Generation counters rather than a bare flag: a waiter has to be able to tell "the
     * drain I was waiting for has finished" from "a later drain is running", or it would
     * sleep through its own completion.
     */
    private final Object flushGate = new Object();

    private boolean drainInFlight;

    private long drainStarted;

    private long drainFinished;

    /**
     * True while a batch-size-triggered flush is in flight.
     *
     * <p>The backoff gate alone is not enough: it is only armed once a flush has FAILED, and
     * the size trigger fires again long before the first HTTP round-trip returns. Without
     * this latch every thread evaluating in a tight loop would start its own concurrent
     * flush against the failing endpoint.
     */
    private final AtomicBoolean autoFlushInFlight = new AtomicBoolean(false);

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

    /**
     * Subscribes to flag-configuration changes; see
     * {@link FeatureflipClient#onUpdate(FlagUpdateListener)}.
     *
     * <p>Registered on the store rather than here, because the store is the one
     * choke point every data source writes through.
     *
     * @return an idempotent unsubscribe action
     */
    Runnable addUpdateListener(FlagUpdateListener listener) {
        // A fixed-value stub client ({@link FeatureflipClient#forTesting}) has no
        // store, so there is nothing that could ever report a change. A no-op keeps
        // onUpdate callable against a stub — code under test should not have to know
        // which kind of client it was handed — rather than throwing.
        if (store == null) {
            return () -> { };
        }
        return store.addUpdateListener(listener);
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
        maybeAutoFlush();
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
        maybeAutoFlush();
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
        maybeAutoFlush();
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

    /**
     * Runs a batch-size-triggered flush, unless a retryable failure has put the size trigger
     * in backoff or an earlier size-triggered flush is still in flight.
     *
     * <p>Kept synchronous on the calling thread, as the size trigger has always been — the
     * latch is what stops concurrent evaluators from each opening their own flush.
     */
    private void maybeAutoFlush() {
        if (!eventProcessor.shouldFlush()) return;
        // Subtraction rather than a bare >=: nanoTime() readings roll over, and only the
        // difference between two of them is meaningful.
        if (System.nanoTime() - nextAutoFlushAtNanos.get() < 0) return;
        if (!autoFlushInFlight.compareAndSet(false, true)) return;
        try {
            flushEvents();
        } finally {
            autoFlushInFlight.set(false);
        }
    }

    /**
     * Drains the event queue, coalescing with any drain already in progress.
     *
     * <p>At most one drain runs at a time. A caller that arrives while one is already going
     * waits for it and returns — it does NOT start its own, and it does NOT return early,
     * because a caller that asked for a flush is asking for its events to be sent. This
     * matches the js/node SDKs, whose {@code flush()} has always returned the in-flight
     * promise.
     */
    private void flushEvents() {
        if (eventProcessor == null || httpClient == null) return;

        long mine;
        synchronized (flushGate) {
            if (drainInFlight) {
                long waitingFor = drainStarted;
                while (drainFinished < waitingFor) {
                    try {
                        flushGate.wait();
                    } catch (InterruptedException e) {
                        // Restore the flag and give up waiting: the drain this caller was
                        // owed is still running and will finish on its own thread.
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                return;
            }
            drainInFlight = true;
            mine = ++drainStarted;
        }

        try {
            drainEvents();
        } finally {
            synchronized (flushGate) {
                drainInFlight = false;
                drainFinished = mine;
                flushGate.notifyAll();
            }
        }
    }

    /** The drain loop itself, callable when coalescing must be bypassed. */
    private void drainEvents() {
        if (eventProcessor == null || httpClient == null) return;

        // One request per batch rather than one for the whole queue: re-queuing failures
        // lets the queue grow towards its bound during an outage, and posting all of it in
        // one body risks a 413 — which is not retryable, so the backlog would be dropped by
        // the very path meant to keep it.
        while (true) {
            // Drained BEFORE the send, so the batch has to be held here to be put back if
            // the send fails. Without that a single transient failure discarded it outright
            // — and the production edge answers this endpoint with a 503 at a low constant
            // rate, so evaluation analytics were being lost steadily (#2456).
            List<SdkEvent> events = eventProcessor.drainBatch(flushBatchSize());
            if (events.isEmpty()) return;

            try {
                httpClient.sendEvents(events);
                // A delivery clears whatever backoff a previous failure armed.
                nextAutoFlushAtNanos.set(System.nanoTime());
            } catch (Exception e) {
                if (isRetryableFlushFailure(e)) {
                    int dropped = eventProcessor.requeue(events);
                    nextAutoFlushAtNanos.set(System.nanoTime() + autoFlushBackoffNanos());
                    log.warn("Event flush failed, {} of {} events re-queued for the next flush "
                        + "({} dropped to stay within the queue bound): {}",
                        events.size() - dropped, events.size(), dropped, e.getMessage());
                    // Stop here. The batch is back at the head of the queue this loop is
                    // draining, so continuing would re-send it immediately and spin for as
                    // long as the endpoint stays down.
                    return;
                }
                // Dropped, so the queue shrinks and the loop still terminates — move on to
                // the next batch rather than letting one poison batch block the backlog
                // behind it.
                log.warn("Event flush failed, dropped {} events because the failure is not "
                    + "retryable: {}", events.size(), e.getMessage());
            }
        }
    }

    /**
     * Whether a failed flush could plausibly succeed if the same batch were sent again.
     *
     * <p>For an HTTP answer the status decides — see {@link EventSendException#isRetryable()}.
     * A serialization failure is permanent by construction: the same objects will not
     * serialize on the next attempt either, and retrying them forever would pin the queue at
     * its bound and starve every later event. It is checked first because Jackson's
     * exception is itself an {@link IOException}. Everything else that reaches an
     * {@code IOException} here is a transport fault (connection reset, DNS, TLS) or a socket
     * timeout, both transient by nature.
     */
    private static boolean isRetryableFlushFailure(Exception e) {
        if (e instanceof EventSendException) return ((EventSendException) e).isRetryable();
        if (e instanceof JsonProcessingException) return false;
        return e instanceof IOException;
    }

    /** How many events go into one request. {@code drainBatch} clamps a non-positive value. */
    private int flushBatchSize() {
        return config != null ? config.getFlushBatchSize() : 100;
    }

    /** How long the size trigger stays gated after a retryable failure. */
    private long autoFlushBackoffNanos() {
        Duration interval = config != null ? config.getFlushInterval() : Duration.ofSeconds(30);
        return TimeUnit.MILLISECONDS.toNanos(interval.toMillis());
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

        // Close the queue BEFORE the final flush, so that flush's re-queue is refused: a
        // batch put back at this point would only be leaked, and looping until it delivered
        // would hang shutdown for as long as the endpoint stayed down. One attempt, then
        // whatever is left is discarded.
        if (eventProcessor != null) eventProcessor.close();
        // drainEvents, not flushEvents: shutdown must never be the call that gets coalesced
        // away. If a scheduled drain happens to be in flight, flushEvents would wait for it
        // and return, and anything enqueued after that loop's last look at the queue would
        // be discarded unsent. Two drains overlapping is safe here precisely because the
        // processor is already closed, so neither can re-queue and there is no backoff left
        // to disarm.
        drainEvents();

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

        if (httpClient != null) httpClient.close();

        log.debug("SharedFeatureflipCore shut down");
    }
}
