package io.featureflip.client;

/**
 * In-process observer notified on every flag evaluation.
 *
 * <p>Register inspectors at client construction via
 * {@link FeatureFlagConfig.Builder#inspectors(java.util.List)}. Each registered
 * inspector is invoked exactly once per variation call — on the success,
 * flag-not-found and error paths alike — with the value and reason the caller
 * actually receives.
 *
 * <p>Inspectors are invoked <em>synchronously</em> on the calling thread, so a
 * slow inspector slows down evaluation. Keep the callback cheap: hand the event
 * to a queue or an analytics client rather than doing blocking work in it.
 *
 * <p>Exceptions thrown by an inspector are caught and logged: they never change
 * the value returned to the caller and never prevent the remaining inspectors
 * from firing.
 */
@FunctionalInterface
public interface EvaluationInspector {

    /**
     * Called once per variation call with the details of the evaluation.
     *
     * @param event the evaluation event; never {@code null}
     */
    void onEvaluation(EvaluationEvent event);
}
