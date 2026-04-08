package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ConditionLogic {
    @JsonProperty("And") AND,
    @JsonProperty("Or") OR
}
