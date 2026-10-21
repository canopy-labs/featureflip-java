package io.featureflip.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FeatureFlagConfig {
    private final String baseUrl;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final boolean streaming;
    private final Duration pollInterval;
    private final Duration flushInterval;
    private final int flushBatchSize;
    private final Duration initTimeout;
    private final List<EvaluationInspector> inspectors;
    private final String wrapperName;
    private final String wrapperVersion;

    private FeatureFlagConfig(Builder builder) {
        this.baseUrl = builder.baseUrl.replaceAll("/+$", "");
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.streaming = builder.streaming;
        this.pollInterval = builder.pollInterval;
        this.flushInterval = builder.flushInterval;
        this.flushBatchSize = builder.flushBatchSize;
        this.initTimeout = builder.initTimeout;
        this.inspectors = normalizeInspectors(builder.inspectors);
        this.wrapperName = builder.wrapperName;
        this.wrapperVersion = builder.wrapperVersion;
    }

    /**
     * Defensive copy of the configured inspectors: null entries are dropped (a
     * caller-supplied list is not trusted to be null-free) and the result is
     * unmodifiable. Config is immutable after construction, so the evaluation hot
     * path can read this list without synchronization.
     */
    private static List<EvaluationInspector> normalizeInspectors(List<EvaluationInspector> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<EvaluationInspector> copy = new ArrayList<>(source.size());
        for (EvaluationInspector inspector : source) {
            if (inspector != null) {
                copy.add(inspector);
            }
        }
        return copy.isEmpty() ? Collections.<EvaluationInspector>emptyList() : Collections.unmodifiableList(copy);
    }

    public String getBaseUrl() { return baseUrl; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public boolean isStreaming() { return streaming; }
    public Duration getPollInterval() { return pollInterval; }
    public Duration getFlushInterval() { return flushInterval; }
    public int getFlushBatchSize() { return flushBatchSize; }
    public Duration getInitTimeout() { return initTimeout; }

    /**
     * Returns the registered evaluation inspectors, in registration order. Never
     * {@code null}: an unmodifiable (possibly empty) list with any null entries
     * already filtered out.
     */
    public List<EvaluationInspector> getInspectors() { return inspectors; }

    /** Name of the library wrapping this SDK, or {@code null}. See {@link Builder#wrapper}. */
    public String getWrapperName() { return wrapperName; }

    /** Version of the wrapping library, or {@code null}. See {@link Builder#wrapper}. */
    public String getWrapperVersion() { return wrapperVersion; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseUrl = "https://eval.featureflip.io";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);
        private boolean streaming = true;
        private Duration pollInterval = Duration.ofSeconds(30);
        private Duration flushInterval = Duration.ofSeconds(30);
        private int flushBatchSize = 100;
        private Duration initTimeout = Duration.ofSeconds(10);
        private List<EvaluationInspector> inspectors = Collections.emptyList();
        private String wrapperName;
        private String wrapperVersion;

        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder connectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; return this; }
        public Builder readTimeout(Duration readTimeout) { this.readTimeout = readTimeout; return this; }
        public Builder streaming(boolean streaming) { this.streaming = streaming; return this; }
        public Builder pollInterval(Duration pollInterval) { this.pollInterval = pollInterval; return this; }
        public Builder flushInterval(Duration flushInterval) { this.flushInterval = flushInterval; return this; }
        public Builder flushBatchSize(int flushBatchSize) { this.flushBatchSize = flushBatchSize; return this; }
        public Builder initTimeout(Duration initTimeout) { this.initTimeout = initTimeout; return this; }

        /**
         * Registers in-process observers notified on every flag evaluation. Like
         * every other option, inspectors are honored on the <em>first</em>
         * {@link FeatureflipClient#get(String, FeatureFlagConfig)} call per SDK key
         * (later calls with the same key reuse the cached client and its config).
         *
         * <p>The list is defensively copied at {@link #build()} time; null entries
         * are dropped. Passing {@code null} clears the inspectors.
         */
        public Builder inspectors(List<EvaluationInspector> inspectors) { this.inspectors = inspectors; return this; }

        /**
         * Identifies a library that wraps this SDK, such as an OpenFeature provider. Its
         * token is sent after the SDK's own in the User-Agent
         * ({@code featureflip-java/<version> my-wrapper/1.0.0}) so usage through the
         * wrapper can be counted. Both parts must be valid HTTP tokens; an invalid wrapper
         * is ignored with a warning. {@code version} may be {@code null}. Leave unset when
         * calling the SDK directly.
         *
         * <p>Not part of the "different config" check: it identifies the caller rather than
         * changing behavior, so a wrapper sharing an SDK key with the host application does
         * not warn. Like every other option, honored on the first {@code get()} per SDK key.
         *
         * @param name the wrapper's product name, e.g. {@code featureflip-openfeature-java}
         * @param version the wrapper's version, or {@code null}
         * @return this builder
         */
        public Builder wrapper(String name, String version) {
            this.wrapperName = name;
            this.wrapperVersion = version;
            return this;
        }

        public FeatureFlagConfig build() {
            return new FeatureFlagConfig(this);
        }
    }
}
