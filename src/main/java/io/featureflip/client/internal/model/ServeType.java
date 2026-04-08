package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ServeType {
    @JsonProperty("Fixed") FIXED,
    @JsonProperty("Rollout") ROLLOUT
}
