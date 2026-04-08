package io.featureflip.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final java.util.concurrent.ConcurrentHashMap<String, SharedFeatureflipCore> LIVE_CORES =
        new java.util.concurrent.ConcurrentHashMap<>();

    private final SharedFeatureflipCore core;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Internal constructor used by the Builder and the static factory. */
    FeatureflipClient(SharedFeatureflipCore core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    /** Returns a Builder that constructs a client via {@link Builder#build()}. */
    public static Builder builder(String sdkKey) {
        Objects.requireNonNull(sdkKey, "sdkKey must not be null");
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
     * @throws IllegalArgumentException if sdkKey is null, empty, or whitespace
     */
    public static FeatureflipClient get(String sdkKey, FeatureFlagConfig config) {
        if (sdkKey == null || sdkKey.isBlank()) {
            throw new IllegalArgumentException("sdkKey must not be null or blank");
        }
        Objects.requireNonNull(config, "config must not be null");

        // Retry loop handles the race where a cached core is found but has already begun
        // shutting down (refcount hit 0 between lookup and tryAcquire). Progress is
        // guaranteed on every iteration: we either acquire a live core and return, clean
        // up a stale entry and retry (map shrinks), successfully add a new core and return,
        // or lose a putIfAbsent race and retry against the winner (which is now live in the map).
        while (true) {
            SharedFeatureflipCore existing = LIVE_CORES.get(sdkKey);
            if (existing != null) {
                if (existing.tryAcquire()) {
                    if (!configsEqual(existing.getConfig(), config)) {
                        log.warn("FeatureflipClient.get called with different config for SDK key already in use. " +
                            "The cached instance's config is preserved; the passed config is ignored.");
                    }
                    return new FeatureflipClient(existing);
                }
                // Stale entry — core shut down between lookup and acquire. Remove and retry.
                LIVE_CORES.remove(sdkKey, existing);
                continue;
            }

            SharedFeatureflipCore newCore = new SharedFeatureflipCore(sdkKey, config);
            SharedFeatureflipCore winner = LIVE_CORES.putIfAbsent(sdkKey, newCore);
            if (winner == null) {
                // We won the race. Set the owning-map back-reference and return a handle.
                newCore.setOwningMap(LIVE_CORES, sdkKey);
                return new FeatureflipClient(newCore);
            }

            // Another thread added one concurrently — release our speculative core
            // (drives its refcount to 0 and triggers immediate shutdown) and retry.
            newCore.release();
        }
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

    // --- Event Tracking ---

    public void track(String eventName, EvaluationContext context, Map<String, Object> metadata) {
        core.track(eventName, context, metadata);
    }

    public void flush() {
        core.flush();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        core.release();
    }

    // --- Internal ---

    private <T> EvaluationDetail<T> evaluate(String key, EvaluationContext context, T defaultValue, Class<T> type) {
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
