package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ConditionLogic {
    @JsonProperty("And") AND,
    @JsonProperty("Or") OR,

    /**
     * A condition logic name this SDK build does not know (#2402). See
     * {@link ServeType#UNRECOGNIZED} for why this is a constant rather than null.
     *
     * <p>Never reaches {@code FlagEvaluator}, which dispatches {@code logic == AND}
     * else ANY — so a sentinel arriving there would make a segment meant to require
     * ALL of its conditions match ANY of them, failing OPEN and over-targeting.
     */
    UNRECOGNIZED
}
