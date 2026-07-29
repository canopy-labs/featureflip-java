package io.featureflip.client;

/**
 * A single flag evaluation, handed to every registered {@link EvaluationInspector}.
 *
 * <p>The field set is the frozen cross-SDK inspector contract — the same names
 * and semantics are surfaced by every Featureflip SDK. {@link #getReason()} uses
 * this SDK's native {@link EvaluationReason} representation rather than another
 * SDK's string casing.
 *
 * <p>Instances are immutable. New fields must be added as an <em>additive</em>
 * constructor overload — shorter constructors delegate forward with null
 * defaults to the longest signature — so existing compiled callers keep working
 * (same rule as {@link EvaluationDetail}).
 */
public final class EvaluationEvent {
    private final String flagKey;
    private final EvaluationContext context;
    private final Object value;
    private final String variationKey;
    private final EvaluationReason reason;
    private final String ruleId;
    private final String prerequisiteKey;
    private final String timestamp;

    /**
     * @param flagKey         the key of the flag that was evaluated
     * @param context         the evaluation context; a copy, safe to retain
     * @param value           the value the caller receives (the default value when
     *                        the flag could not be evaluated)
     * @param variationKey    the winning variation key, or {@code null} when the
     *                        flag was not found or evaluation errored
     * @param reason          why this value was served
     * @param ruleId          the matched rule's id, set only on a rule match
     * @param prerequisiteKey the failing prerequisite's flag key, set only on a
     *                        prerequisite failure
     * @param timestamp       ISO-8601 timestamp of the evaluation
     */
    public EvaluationEvent(String flagKey, EvaluationContext context, Object value, String variationKey,
                           EvaluationReason reason, String ruleId, String prerequisiteKey, String timestamp) {
        this.flagKey = flagKey;
        this.context = context;
        this.value = value;
        this.variationKey = variationKey;
        this.reason = reason;
        this.ruleId = ruleId;
        this.prerequisiteKey = prerequisiteKey;
        this.timestamp = timestamp;
    }

    /** Returns the key of the flag that was evaluated. */
    public String getFlagKey() { return flagKey; }

    /**
     * Returns the full evaluation context. This is a copy of the caller's context,
     * so retaining it cannot be affected by — and cannot affect — the caller.
     */
    public EvaluationContext getContext() { return context; }

    /**
     * Returns the value the caller actually receives: the served value, or the
     * caller's default when the flag was missing, disabled without a usable
     * variation, or evaluation errored.
     */
    public Object getValue() { return value; }

    /**
     * Returns the winning variation key, or {@code null} when the flag was not
     * found or evaluation errored.
     */
    public String getVariationKey() { return variationKey; }

    /** Returns why this value was served. */
    public EvaluationReason getReason() { return reason; }

    /**
     * Returns the id of the targeting rule that matched, when {@link #getReason()}
     * is {@link EvaluationReason#RULE_MATCH}. {@code null} otherwise.
     */
    public String getRuleId() { return ruleId; }

    /**
     * Returns the key of the prerequisite flag that failed, when {@link #getReason()}
     * is {@link EvaluationReason#PREREQUISITE_FAILED}. {@code null} otherwise.
     */
    public String getPrerequisiteKey() { return prerequisiteKey; }

    /** Returns the ISO-8601 timestamp of the evaluation (UTC). */
    public String getTimestamp() { return timestamp; }
}
