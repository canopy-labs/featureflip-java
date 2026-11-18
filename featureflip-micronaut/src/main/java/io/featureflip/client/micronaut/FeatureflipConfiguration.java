package io.featureflip.client.micronaut;

import java.time.Duration;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * The {@code featureflip.*} configuration. Every option except {@code sdk-key} and
 * {@code enabled} is optional; an unset option keeps the SDK's own default.
 */
@ConfigurationProperties("featureflip")
public class FeatureflipConfiguration {

    private String sdkKey;
    private boolean enabled = true;
    private String baseUrl;
    private Duration connectTimeout;
    private Duration readTimeout;
    private Boolean streaming;
    private Duration pollInterval;
    private Duration flushInterval;
    private Integer flushBatchSize;
    private Duration initTimeout;
    private Double maxSnapshotAgeSeconds;

    public String getSdkKey() { return sdkKey; }
    public void setSdkKey(String sdkKey) { this.sdkKey = sdkKey; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

    public Boolean getStreaming() { return streaming; }
    public void setStreaming(Boolean streaming) { this.streaming = streaming; }

    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }

    public Duration getFlushInterval() { return flushInterval; }
    public void setFlushInterval(Duration flushInterval) { this.flushInterval = flushInterval; }

    public Integer getFlushBatchSize() { return flushBatchSize; }
    public void setFlushBatchSize(Integer flushBatchSize) { this.flushBatchSize = flushBatchSize; }

    public Duration getInitTimeout() { return initTimeout; }
    public void setInitTimeout(Duration initTimeout) { this.initTimeout = initTimeout; }

    public Double getMaxSnapshotAgeSeconds() { return maxSnapshotAgeSeconds; }
    public void setMaxSnapshotAgeSeconds(Double maxSnapshotAgeSeconds) {
        this.maxSnapshotAgeSeconds = maxSnapshotAgeSeconds;
    }
}
