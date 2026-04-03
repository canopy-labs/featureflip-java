package dev.featureflip.sdk.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ConditionOperator {
    @JsonProperty("Equals") EQUALS,
    @JsonProperty("NotEquals") NOT_EQUALS,
    @JsonProperty("Contains") CONTAINS,
    @JsonProperty("NotContains") NOT_CONTAINS,
    @JsonProperty("StartsWith") STARTS_WITH,
    @JsonProperty("EndsWith") ENDS_WITH,
    @JsonProperty("In") IN,
    @JsonProperty("NotIn") NOT_IN,
    @JsonProperty("MatchesRegex") MATCHES_REGEX,
    @JsonProperty("GreaterThan") GREATER_THAN,
    @JsonProperty("LessThan") LESS_THAN,
    @JsonProperty("GreaterThanOrEqual") GREATER_THAN_OR_EQUAL,
    @JsonProperty("LessThanOrEqual") LESS_THAN_OR_EQUAL,
    @JsonProperty("Before") BEFORE,
    @JsonProperty("After") AFTER
}
