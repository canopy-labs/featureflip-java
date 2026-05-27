package io.featureflip.client;

public enum EvaluationReason {
    RULE_MATCH,
    FALLTHROUGH,
    FLAG_DISABLED,
    FLAG_NOT_FOUND,
    ERROR,
    /** A prerequisite flag did not serve its expected variation; off variation was returned. */
    PREREQUISITE_FAILED
}
