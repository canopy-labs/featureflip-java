package io.featureflip.client;

import java.time.Duration;

public final class FeatureFlagConfig {
    private final String baseUrl;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final boolean streaming;
    private final Duration pollInterval;
    private final Duration flushInterval;
    private final int flushBatchSize;
    private final Duration initTimeout;

    private FeatureFlagConfig(Builder builder) {
        this.baseUrl = builder.baseUrl.replaceAll("/+$", "");
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.streaming = builder.streaming;
        this.pollInterval = builder.pollInterval;
        this.flushInterval = builder.flushInterval;
        this.flushBatchSize = builder.flushBatchSize;
        this.initTimeout = builder.initTimeout;
    }

    public String getBaseUrl() { return baseUrl; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public boolean isStreaming() { return streaming; }
    public Duration getPollInterval() { return pollInterval; }
    public Duration getFlushInterval() { return flushInterval; }
    public int getFlushBatchSize() { return flushBatchSize; }
    public Duration getInitTimeout() { return initTimeout; }

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

        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder connectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; return this; }
        public Builder readTimeout(Duration readTimeout) { this.readTimeout = readTimeout; return this; }
        public Builder streaming(boolean streaming) { this.streaming = streaming; return this; }
        public Builder pollInterval(Duration pollInterval) { this.pollInterval = pollInterval; return this; }
        public Builder flushInterval(Duration flushInterval) { this.flushInterval = flushInterval; return this; }
        public Builder flushBatchSize(int flushBatchSize) { this.flushBatchSize = flushBatchSize; return this; }
        public Builder initTimeout(Duration initTimeout) { this.initTimeout = initTimeout; return this; }

        public FeatureFlagConfig build() {
            return new FeatureFlagConfig(this);
        }
    }
}
