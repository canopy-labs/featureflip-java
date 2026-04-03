package dev.featureflip.sdk.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ServeType {
    @JsonProperty("Fixed") FIXED,
    @JsonProperty("Rollout") ROLLOUT
}
