package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SdkEvent {
    private SdkEventType type;
    private String flagKey;
    private String userId;
    private String variation;
    private Instant timestamp;
    private Map<String, JsonNode> metadata;

    public SdkEventType getType() { return type; }
    public void setType(SdkEventType type) { this.type = type; }
    public String getFlagKey() { return flagKey; }
    public void setFlagKey(String flagKey) { this.flagKey = flagKey; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getVariation() { return variation; }
    public void setVariation(String variation) { this.variation = variation; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public Map<String, JsonNode> getMetadata() { return metadata; }
    public void setMetadata(Map<String, JsonNode> metadata) { this.metadata = metadata; }
}
