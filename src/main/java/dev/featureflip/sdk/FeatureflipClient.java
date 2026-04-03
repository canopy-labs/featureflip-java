package dev.featureflip.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.featureflip.sdk.internal.*;
import dev.featureflip.sdk.internal.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class FeatureflipClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FeatureflipClient.class);

    private final FlagStore store;
    private final FlagEvaluator evaluator;
    private final EventProcessor eventProcessor;
    private final FlagHttpClient httpClient;
    private final SseDataSource sseDataSource;
    private final PollingDataSource pollingDataSource;
    private final ScheduledExecutorService executor;
    private final CountDownLatch initLatch;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final FeatureFlagConfig config;
    private final AtomicReference<PollingDataSource> fallbackPoller = new AtomicReference<>();
    private ScheduledFuture<?> flushTask;

    // Test mode
    private final Map<String, Object> testValues;

    private FeatureflipClient(String sdkKey, FeatureFlagConfig config) {
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
                initCallback, () -> startPolling());
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

    // Test-mode constructor
    private FeatureflipClient(Map<String, Object> testValues) {
        this.testValues = testValues;
        this.store = null;
        this.evaluator = null;
        this.eventProcessor = null;
        this.httpClient = null;
        this.sseDataSource = null;
        this.pollingDataSource = null;
        this.executor = null;
        this.initLatch = null;
        this.config = null;
        this.initialized.set(true);
    }

    public static Builder builder(String sdkKey) {
        Objects.requireNonNull(sdkKey, "sdkKey must not be null");
        return new Builder(sdkKey);
    }

    public static FeatureflipClient forTesting(Map<String, Object> values) {
        return new FeatureflipClient(Map.copyOf(values));
    }

    public void waitForInitialization() {
        if (isInitialized()) return;
        try {
            Duration timeout = config != null ? config.getInitTimeout() : Duration.ofSeconds(10);
            if (!initLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new FeatureFlagInitializationException(
                    "Initialization timed out after " + timeout.toMillis() + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeatureFlagInitializationException("Initialization interrupted", e);
        }
    }

    public boolean isInitialized() { return initialized.get(); }

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

    public void flush() {
        flushEvents();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        if (testValues != null) return; // test mode, nothing to close

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
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (eventProcessor != null) eventProcessor.close();
        if (httpClient != null) httpClient.close();

        log.debug("FeatureflipClient closed");
    }

    // --- Internal ---

    private <T> EvaluationDetail<T> evaluate(String key, EvaluationContext context, T defaultValue, Class<T> type) {
        try {
            // Test mode
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

            // Track evaluation event
            if (eventProcessor != null) {
                SdkEvent event = new SdkEvent();
                event.setType(SdkEventType.EVALUATION);
                event.setFlagKey(key);
                event.setUserId(context.getUserId());
                event.setVariation(result.getVariationKey());
                event.setTimestamp(Instant.now());
                eventProcessor.enqueue(event);
                if (eventProcessor.shouldFlush()) flushEvents();
            }

            return new EvaluationDetail<>(value, result.getReason(), result.getRuleId(), null);
        } catch (Exception e) {
            log.warn("Evaluation error for flag '{}': {}", key, e.getMessage());
            return new EvaluationDetail<>(defaultValue, EvaluationReason.ERROR, null, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T deserializeValue(JsonNode node, T defaultValue, Class<T> type) {
        try {
            if (node == null) return defaultValue;
            if (type == Boolean.class || type == boolean.class) return (T) Boolean.valueOf(node.asBoolean());
            if (type == String.class) return (T) node.asText();
            if (type == Integer.class || type == int.class) return (T) Integer.valueOf(node.asInt());
            if (type == Double.class || type == double.class) return (T) Double.valueOf(node.asDouble());
            return httpClient.getObjectMapper().treeToValue(node, type);
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
        if (closed.get()) return;
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

        public FeatureflipClient build() {
            return new FeatureflipClient(sdkKey, configBuilder.build());
        }
    }
}
