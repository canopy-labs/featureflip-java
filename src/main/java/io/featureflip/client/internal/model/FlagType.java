package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum FlagType {
    @JsonProperty("Boolean") BOOLEAN,
    @JsonProperty("String") STRING,
    @JsonProperty("Number") NUMBER,
    @JsonProperty("Json") JSON
}
