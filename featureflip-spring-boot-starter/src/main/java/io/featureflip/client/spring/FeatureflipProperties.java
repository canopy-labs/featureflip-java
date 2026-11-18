package io.featureflip.client.spring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code featureflip.*} configuration properties. Every option except {@code sdk-key}
 * and {@code enabled} is optional; an unset option keeps the SDK's own default.
 */
@ConfigurationProperties(prefix = "featureflip")
public class FeatureflipProperties {

    /** Server-side SDK key. Falls back to the FEATUREFLIP_SDK_KEY environment variable. */
    private String sdkKey;

    /** Set to false to skip registering the client. */
    private boolean enabled = true;

    /** Base URL of the Evaluation API. */
    private String baseUrl;

    /** Timeout for establishing connections. */
    private Duration connectTimeout;

    /** Timeout for reading responses. */
    private Duration readTimeout;

    /** Whether to receive flag updates over SSE. */
    private Boolean streaming;

    /** Polling interval, used when streaming is off or has dropped. */
    private Duration pollInterval;

    /** Interval between event flush batches. */
    private Duration flushInterval;

    /** Maximum number of events per flush batch. */
    private Integer flushBatchSize;

    /** Maximum time to wait for initialization. */
    private Duration initTimeout;

    /** Oldest snapshot, in seconds, the client will start from. Only used with a SnapshotStore bean. */
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
