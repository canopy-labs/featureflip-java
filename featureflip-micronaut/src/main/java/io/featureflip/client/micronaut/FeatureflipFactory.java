package io.featureflip.client.micronaut;

import io.featureflip.client.FeatureFlagConfig;
import io.featureflip.client.FeatureflipClient;
import io.featureflip.client.SnapshotStore;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.VersionUtils;

/**
 * Provides a {@link FeatureflipClient} bean from the {@code featureflip.*} configuration.
 *
 * <p>A thin wrapper over {@link FeatureflipClient#get(String, FeatureFlagConfig)}: the SDK's
 * factory already guarantees one shared client per SDK key, so there is no lifetime logic
 * here beyond closing this handle when the context stops. The bean is created eagerly
 * ({@code @Context}), so a missing key or invalid option fails startup and the client
 * begins initializing at boot, as in the Spring starter. A bean of your own with
 * {@code @Replaces(FeatureflipClient.class)} (or any other {@code FeatureflipClient} bean)
 * takes precedence.
 */
@Factory
@Requires(condition = FeatureflipEnabledCondition.class)
public class FeatureflipFactory {

    static final String WRAPPER_NAME = "featureflip-micronaut";
    private static final String SDK_KEY_ENV_VAR = "FEATUREFLIP_SDK_KEY";

    @Context
    @Bean(preDestroy = "close")
    @Requires(missingBeans = FeatureflipClient.class)
    public FeatureflipClient featureflipClient(FeatureflipConfiguration properties,
                                               @Nullable SnapshotStore snapshotStore) {
        FeatureFlagConfig.Builder config = FeatureFlagConfig.builder()
            .wrapper(WRAPPER_NAME, VersionUtils.getMicronautVersion());
        if (properties.getBaseUrl() != null) config.baseUrl(properties.getBaseUrl());
        if (properties.getConnectTimeout() != null) config.connectTimeout(properties.getConnectTimeout());
        if (properties.getReadTimeout() != null) config.readTimeout(properties.getReadTimeout());
        if (properties.getStreaming() != null) config.streaming(properties.getStreaming());
        if (properties.getPollInterval() != null) config.pollInterval(properties.getPollInterval());
        if (properties.getFlushInterval() != null) config.flushInterval(properties.getFlushInterval());
        if (properties.getFlushBatchSize() != null) config.flushBatchSize(properties.getFlushBatchSize());
        if (properties.getInitTimeout() != null) config.initTimeout(properties.getInitTimeout());
        if (properties.getMaxSnapshotAgeSeconds() != null) {
            config.maxSnapshotAgeSeconds(properties.getMaxSnapshotAgeSeconds());
        }
        if (snapshotStore != null) config.snapshotStore(snapshotStore);

        // Built outside the try: an invalid option throws IllegalArgumentException too,
        // and must surface as itself, not as a missing key.
        FeatureFlagConfig built = config.build();
        try {
            return FeatureflipClient.get(properties.getSdkKey(), built);
        } catch (IllegalArgumentException e) {
            // The core's message names its own factory method; point at the configuration.
            // The key itself is never part of any message here.
            throw new IllegalStateException("No Featureflip SDK key is configured. Set the "
                + "featureflip.sdk-key property or the " + SDK_KEY_ENV_VAR + " environment variable.", e);
        }
    }
}
