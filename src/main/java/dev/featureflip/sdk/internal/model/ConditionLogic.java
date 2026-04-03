package dev.featureflip.sdk.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ConditionLogic {
    @JsonProperty("And") AND,
    @JsonProperty("Or") OR
}
