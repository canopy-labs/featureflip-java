package io.featureflip.client;

public final class EvaluationDetail<T> {
    private final T value;
    private final EvaluationReason reason;
    private final String ruleId;
    private final String errorMessage;
    private final String variationKey;

    public EvaluationDetail(T value, EvaluationReason reason, String ruleId, String errorMessage) {
        this(value, reason, ruleId, errorMessage, null);
    }

    public EvaluationDetail(T value, EvaluationReason reason, String ruleId, String errorMessage, String variationKey) {
        this.value = value;
        this.reason = reason;
        this.ruleId = ruleId;
        this.errorMessage = errorMessage;
        this.variationKey = variationKey;
    }

    public T getValue() { return value; }
    public EvaluationReason getReason() { return reason; }
    public String getRuleId() { return ruleId; }
    public String getErrorMessage() { return errorMessage; }
    /** Returns the variation key that was served, or {@code null} if evaluation did not produce a variation. */
    public String getVariationKey() { return variationKey; }
}
