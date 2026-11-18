package io.featureflip.client.micronaut;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/**
 * Passes unless {@code featureflip.enabled} is set to a false value.
 *
 * <p>{@code @Requires(notEquals = "false")} compares strings case-sensitively, so
 * {@code FALSE} or {@code False} would leave the client on. This reads the property as a
 * {@link Boolean} through the environment's conversion service instead, which matches
 * Spring's {@code @ConditionalOnProperty}. A missing or unparseable value keeps the
 * client enabled.
 */
public final class FeatureflipEnabledCondition implements Condition {

    @Override
    @SuppressWarnings("rawtypes") // Condition declares the raw ConditionContext
    public boolean matches(ConditionContext context) {
        return context.getProperty("featureflip.enabled", Boolean.class).orElse(Boolean.TRUE);
    }
}
