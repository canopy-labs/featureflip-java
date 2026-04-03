package dev.featureflip.sdk;

public final class EvaluationDetail<T> {
    private final T value;
    private final EvaluationReason reason;
    private final String ruleId;
    private final String errorMessage;

    public EvaluationDetail(T value, EvaluationReason reason, String ruleId, String errorMessage) {
        this.value = value;
        this.reason = reason;
        this.ruleId = ruleId;
        this.errorMessage = errorMessage;
    }

    public T getValue() { return value; }
    public EvaluationReason getReason() { return reason; }
    public String getRuleId() { return ruleId; }
    public String getErrorMessage() { return errorMessage; }
}
