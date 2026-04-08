package io.featureflip.client;

public enum EvaluationReason {
    RULE_MATCH,
    FALLTHROUGH,
    FLAG_DISABLED,
    FLAG_NOT_FOUND,
    ERROR
}
