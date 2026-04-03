package dev.featureflip.sdk.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum SdkEventType {
    @JsonProperty("Evaluation") EVALUATION,
    @JsonProperty("Impression") IMPRESSION,
    @JsonProperty("Identify") IDENTIFY,
    @JsonProperty("Custom") CUSTOM
}
