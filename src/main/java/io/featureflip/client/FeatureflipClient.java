package io.featureflip.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The main client for evaluating feature flags. This is a thin handle over an internal
 * shared core: the static factory {@link #get(String, FeatureFlagConfig)} makes multiple
 * calls with the same SDK key share one underlying client (refcounted); the real shutdown
 * runs only when the last handle is closed.
 *
 * <p>Obtain instances via {@link #get(String)} (recommended) or {@link #builder(String)}
 * (which routes through the factory). The {@link #forTesting(Map)} factory remains
 * available for unit tests that want a fixed-value stub client.
 */
public final class FeatureflipClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FeatureflipClient.class);

    private static final String SDK_KEY_ENV_VAR = "FEATUREFLIP_SDK_KEY";

    private static final java.util.concurrent.ConcurrentHashMap<String, SharedFeatureflipCore> LIVE_CORES =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Indirection over {@link System#getenv(String)}, so the environment fallback can be
     * tested. Java has no supported way to mutate its own environment in-process — the
     * reflection route into {@code ProcessEnvironment} is sealed by module encapsulation
     * from 17 onward, and CI runs 11, 17 and 21. Production always reads the real
     * environment; only {@link #setEnvReaderForTesting(Function)} ever changes this.
     */
    private static volatile Function<String, String> envReader = System::getenv;

    private final SharedFeatureflipCore core;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * This handle's flag-update subscriptions, dropped when it is closed.
     *
     * <p>Tracked per handle rather than per core because the core may outlive this
     * handle — another handle sharing the same SDK key keeps it alive, and its data
     * sources keep running — so a listener left registered would go on firing for a
     * client the caller has already closed.
     */
    private final CopyOnWriteArrayList<Runnable> subscriptions = new CopyOnWriteArrayList<>();

    /** Internal constructor used by the Builder and the static factory. */
    FeatureflipClient(SharedFeatureflipCore core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    /**
     * Returns a Builder that constructs a client via {@link Builder#build()}.
     *
     * <p>The key resolves exactly as it does for {@link #get(String, FeatureFlagConfig)}:
     * pass null or blank to fall back to {@code FEATUREFLIP_SDK_KEY}. Resolution — and the
     * error when neither source supplies a key — happens at {@link Builder#build()}, which
     * is where the client is actually constructed.
     */
    public static Builder builder(String sdkKey) {
        return new Builder(sdkKey);
    }

    /**
     * Returns a fixed-value stub client for unit tests. Does not start any background
     * tasks or open network connections.
     */
    public static FeatureflipClient forTesting(Map<String, Object> values) {
        return new FeatureflipClient(SharedFeatureflipCore.createForTestingStub(values));
    }

    /**
     * Returns a client for the given SDK key, using default configuration.
     */
    public static FeatureflipClient get(String sdkKey) {
        return get(sdkKey, FeatureFlagConfig.builder().build());
    }

    /**
     * Returns a client for the given SDK key. The first call with a given key constructs
     * and initializes a shared underlying client; subsequent calls with the same key return
     * a new handle pointing at the cached client. When the last handle for a key is closed,
     * the underlying client shuts down and is removed from the cache.
     *
     * <p>If a later call passes a different config than the cached instance was constructed
     * with, the cached instance's config is preserved and a warning is logged.
     *
     * <p>If sdkKey is null or blank the {@code FEATUREFLIP_SDK_KEY} environment variable is
     * used instead; an explicitly passed key always wins.
     *
     * @throws IllegalArgumentException if neither sdkKey nor {@code FEATUREFLIP_SDK_KEY}
     *                                  supplies a non-blank key
     */
    public static FeatureflipClient get(String sdkKey, FeatureFlagConfig config) {
        // Resolved BEFORE the cache lookup: LIVE_CORES is keyed by SDK key, so resolving
        // afterwards would give a caller who passes "" a different core from one who names
        // the same key explicitly — two clients streaming against one environment.
        final String resolvedKey = resolveSdkKey(sdkKey);
        Objects.requireNonNull(config, "config must not be null");

        // Retry loop handles the race where a cached core is found but has already begun
        // shutting down (refcount hit 0 between lookup and tryAcquire). Progress is
        // guaranteed on every iteration: we either acquire a live core and return, clean
        // up a stale entry and retry (map shrinks), successfully add a new core and return,
        // or lose a putIfAbsent race and retry against the winner (which is now live in the map).
        while (true) {
            SharedFeatureflipCore existing = LIVE_CORES.get(resolvedKey);
            if (existing != null) {
                if (existing.tryAcquire()) {
                    if (!configsEqual(existing.getConfig(), config)) {
                        log.warn("FeatureflipClient.get called with different config for SDK key already in use. " +
                            "The cached instance's config is preserved; the passed config is ignored.");
                    }
                    return new FeatureflipClient(existing);
                }
                // Stale entry — core shut down between lookup and acquire. Remove and retry.
                LIVE_CORES.remove(resolvedKey, existing);
                continue;
            }

            SharedFeatureflipCore newCore = new SharedFeatureflipCore(resolvedKey, config);
            SharedFeatureflipCore winner = LIVE_CORES.putIfAbsent(resolvedKey, newCore);
            if (winner == null) {
                // We won the race. Set the owning-map back-reference and return a handle.
                newCore.setOwningMap(LIVE_CORES, resolvedKey);
                return new FeatureflipClient(newCore);
            }

            // Another thread added one concurrently — release our speculative core
            // (drives its refcount to 0 and triggers immediate shutdown) and retry.
            newCore.release();
        }
    }

    /**
     * Resolves the SDK key from the argument, then the environment.
     *
     * <p>{@code FEATUREFLIP_SDK_KEY} was advertised in the README from the SDK's first
     * release while nothing read it, and {@code get()} rejected the very input that should
     * have triggered the fallback. The convention is real — python, go, csharp, ruby and
     * php all implement this exact resolution — so java was the outlier, not the
     * documentation (#2273).
     */
    static String resolveSdkKey(String sdkKey) {
        if (sdkKey != null && !sdkKey.isBlank()) {
            return sdkKey;
        }
        String fromEnvironment = envReader.apply(SDK_KEY_ENV_VAR);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        throw new IllegalArgumentException(
            "An SDK key is required: pass it to FeatureflipClient.get() or set " + SDK_KEY_ENV_VAR);
    }

    /**
     * Swaps the environment lookup used by {@link #resolveSdkKey(String)}. For test
     * isolation only; pass null to restore {@link System#getenv}.
     */
    static void setEnvReaderForTesting(Function<String, String> reader) {
        envReader = reader != null ? reader : System::getenv;
    }

    /** Diagnostic: current number of live shared cores in the static map. Test-only. */
    static int debugLiveCoreCount() {
        return LIVE_CORES.size();
    }

    /**
     * Resets the static core map. For test isolation only. Forces shutdown of each
     * live core's map-held reference; any handles still outstanding will continue to
     * function on their own references until they are closed.
     */
    static void resetForTesting() {
        for (var entry : LIVE_CORES.entrySet()) {
            if (LIVE_CORES.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().forceShutdownFromReset();
            }
        }
    }

    /**
     * Structural comparison of two configs, used only to warn when a later
     * {@code get()} passes meaningfully different options. Inspectors are
     * deliberately excluded — callbacks aren't structurally comparable, and a
     * differing lambda must not trigger a spurious "different options" warning.
     */
    private static boolean configsEqual(FeatureFlagConfig a, FeatureFlagConfig b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return java.util.Objects.equals(a.getBaseUrl(), b.getBaseUrl())
            && a.isStreaming() == b.isStreaming()
            && java.util.Objects.equals(a.getPollInterval(), b.getPollInterval())
            && java.util.Objects.equals(a.getFlushInterval(), b.getFlushInterval())
            && a.getFlushBatchSize() == b.getFlushBatchSize()
            && java.util.Objects.equals(a.getInitTimeout(), b.getInitTimeout())
            && java.util.Objects.equals(a.getConnectTimeout(), b.getConnectTimeout())
            && java.util.Objects.equals(a.getReadTimeout(), b.getReadTimeout());
    }

    public void waitForInitialization() {
        core.waitForInitialization();
    }

    public boolean isInitialized() {
        if (closed.get()) {
            return false;
        }
        return core.isInitialized();
    }

    // --- Typed Evaluation Methods ---

    public boolean boolVariation(String key, EvaluationContext context, boolean defaultValue) {
        return evaluate(key, context, defaultValue, Boolean.class).getValue();
    }

    public String stringVariation(String key, EvaluationContext context, String defaultValue) {
        return evaluate(key, context, defaultValue, String.class).getValue();
    }

    public int intVariation(String key, EvaluationContext context, int defaultValue) {
        return evaluate(key, context, defaultValue, Integer.class).getValue();
    }

    public double doubleVariation(String key, EvaluationContext context, double defaultValue) {
        return evaluate(key, context, defaultValue, Double.class).getValue();
    }

    public <T> T jsonVariation(String key, EvaluationContext context, T defaultValue, Class<T> type) {
        return evaluate(key, context, defaultValue, type).getValue();
    }

    // --- Detail Methods ---

    public EvaluationDetail<Boolean> boolVariationDetail(String key, EvaluationContext context, boolean defaultValue) {
        return evaluate(key, context, defaultValue, Boolean.class);
    }

    public EvaluationDetail<String> stringVariationDetail(String key, EvaluationContext context, String defaultValue) {
        return evaluate(key, context, defaultValue, String.class);
    }

    public EvaluationDetail<Integer> intVariationDetail(String key, EvaluationContext context, int defaultValue) {
        return evaluate(key, context, defaultValue, Integer.class);
    }

    public EvaluationDetail<Double> doubleVariationDetail(String key, EvaluationContext context, double defaultValue) {
        return evaluate(key, context, defaultValue, Double.class);
    }

    /**
     * The detail counterpart of {@link #jsonVariation(String, EvaluationContext, Object, Class)}
     * — the served value together with the reason, rule id, variation key and
     * prerequisite key behind it.
     *
     * <p>Pass {@code Object.class} to read a value without asserting its type: the
     * served JSON arrives as the plain Java shapes Jackson produces
     * ({@code Map}, {@code List}, {@code String}, {@code Integer}, {@code Double},
     * {@code Boolean}), and no read can then fail as a type mismatch. That is what a
     * caller wants when it means to inspect or coerce the value itself — the
     * OpenFeature provider does exactly this, so that it can report
     * {@code TYPE_MISMATCH} distinctly from a genuine evaluation error, which the
     * typed accessors above fold together into {@link EvaluationReason#ERROR}.
     *
     * @param type the type to deserialize the served value into
     * @param <T>  the value type
     */
    public <T> EvaluationDetail<T> jsonVariationDetail(String key, EvaluationContext context,
                                                       T defaultValue, Class<T> type) {
        return evaluate(key, context, defaultValue, type);
    }

    // --- Flag Update Subscription ---

    /**
     * Subscribes to flag-configuration changes.
     *
     * <p>The listener is called with the flag keys whose configuration changed,
     * batched into one call per update. It fires on the SDK's streaming or polling
     * thread, so it must not block: a slow listener delays flag delivery and, on the
     * SSE path, can stall the connection.
     *
     * <p>The initial flag load does NOT fire — a cold start is not a change. Only
     * later updates do. Keys are reported when a flag is added, removed or modified,
     * and additionally for flags dragged along by the change: those referencing an
     * edited segment, and those depending on a changed flag through a prerequisite
     * (their evaluated value moves even though their own configuration did not).
     *
     * <p>An exception thrown by a listener is logged and swallowed; it does not
     * affect flag delivery or the other listeners.
     *
     * <p>The returned action unsubscribes and is idempotent. Subscriptions are also
     * dropped when this client is closed, so a caller that closes its client need not
     * unsubscribe first.
     *
     * <pre>{@code
     * Runnable unsubscribe = client.onUpdate(keys -> log.info("flags changed: {}", keys));
     * // ...
     * unsubscribe.run();
     * }</pre>
     *
     * @param listener the listener to register; null is ignored
     * @return an idempotent unsubscribe action
     */
    public Runnable onUpdate(FlagUpdateListener listener) {
        // Nothing will ever fire for a closed handle, so hand back a no-op rather than
        // registering a listener on a core this handle no longer participates in.
        if (listener == null || closed.get()) {
            return () -> { };
        }

        Runnable unsubscribe = core.addUpdateListener(listener);
        AtomicBoolean done = new AtomicBoolean(false);
        Runnable idempotent = () -> {
            if (done.compareAndSet(false, true)) {
                unsubscribe.run();
            }
        };
        subscriptions.add(idempotent);
        return idempotent;
    }

    // --- Event Tracking ---

    public void track(String eventName, EvaluationContext context, Map<String, Object> metadata) {
        if (closed.get()) {
            return;
        }
        core.track(eventName, context, metadata);
    }

    /**
     * Records an identify event for analytics. This does not affect flag
     * evaluation — targeting rules and segments are matched against the context
     * passed to each variation call, and identify() neither sets a context for
     * later calls nor persists attributes for targeting.
     */
    public void identify(EvaluationContext context) {
        if (closed.get()) {
            return;
        }
        core.identify(context);
    }

    public void flush() {
        if (closed.get()) {
            return;
        }
        core.flush();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Dropped before the core is released: the core may survive (another handle
        // holds it) and its data sources keep running, so a listener left registered
        // would go on firing for a client the caller has already closed.
        List<Runnable> pending = new ArrayList<>(subscriptions);
        subscriptions.clear();
        for (Runnable unsubscribe : pending) {
            unsubscribe.run();
        }
        core.release();
    }

    // --- Internal ---

    private <T> EvaluationDetail<T> evaluate(String key, EvaluationContext context, T defaultValue, Class<T> type) {
        // A closed handle serves the caller's default (#2282). close() releases the
        // core — stopping streaming/polling and shutting down the event processor —
        // but the in-memory store stays readable, so without this guard the handle
        // would keep serving a frozen snapshot that can never update again. Guarding
        // the single choke point covers every typed and detail accessor, and also
        // suppresses the evaluation event a closed client must not emit.
        if (closed.get()) {
            return new EvaluationDetail<>(defaultValue, EvaluationReason.ERROR, null, "Client is closed");
        }
        EvaluationDetail<T> detail = core.evaluate(key, context, defaultValue, type);
        if (detail.getVariationKey() != null) {
            core.trackEvaluation(key, context, detail.getVariationKey());
        }
        return detail;
    }

    // --- Builder ---

    public static final class Builder {
        private final String sdkKey;
        private final FeatureFlagConfig.Builder configBuilder = FeatureFlagConfig.builder();

        private Builder(String sdkKey) {
            this.sdkKey = sdkKey;
        }

        public Builder baseUrl(String baseUrl) { configBuilder.baseUrl(baseUrl); return this; }
        public Builder connectTimeout(Duration timeout) { configBuilder.connectTimeout(timeout); return this; }
        public Builder readTimeout(Duration timeout) { configBuilder.readTimeout(timeout); return this; }
        public Builder streaming(boolean streaming) { configBuilder.streaming(streaming); return this; }
        public Builder pollInterval(Duration interval) { configBuilder.pollInterval(interval); return this; }
        public Builder flushInterval(Duration interval) { configBuilder.flushInterval(interval); return this; }
        public Builder flushBatchSize(int size) { configBuilder.flushBatchSize(size); return this; }
        public Builder initTimeout(Duration timeout) { configBuilder.initTimeout(timeout); return this; }
        /** @see FeatureFlagConfig.Builder#inspectors(java.util.List) */
        public Builder inspectors(java.util.List<EvaluationInspector> inspectors) {
            configBuilder.inspectors(inspectors);
            return this;
        }

        /**
         * Builds a client by routing through {@link FeatureflipClient#get(String, FeatureFlagConfig)}.
         * Multiple builders with the same SDK key return handles sharing one underlying
         * refcounted client; the config is honored only on the first call for a given SDK key
         * (subsequent calls with different config log a warning and return the cached instance).
         */
        public FeatureflipClient build() {
            return FeatureflipClient.get(sdkKey, configBuilder.build());
        }
    }
}
