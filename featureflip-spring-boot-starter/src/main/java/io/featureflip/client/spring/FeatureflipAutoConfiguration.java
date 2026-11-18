package io.featureflip.client.spring;

import io.featureflip.client.FeatureFlagConfig;
import io.featureflip.client.FeatureflipClient;
import io.featureflip.client.SnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers a {@link FeatureflipClient} bean from the {@code featureflip.*} properties.
 *
 * <p>A thin wrapper over {@link FeatureflipClient#get(String, FeatureFlagConfig)}: the SDK's
 * factory already guarantees one shared client per SDK key, so there is no lifetime logic
 * here. The User-Agent carries a {@code featureflip-spring-boot/<Spring Boot version>}
 * token after the SDK's own. Spring closes the bean on context shutdown (it infers {@code close()}), which
 * releases this handle.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "featureflip", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(FeatureflipProperties.class)
public class FeatureflipAutoConfiguration {

    static final String WRAPPER_NAME = "featureflip-spring-boot";
    private static final String SDK_KEY_ENV_VAR = "FEATUREFLIP_SDK_KEY";

    @Bean
    @ConditionalOnMissingBean
    public FeatureflipClient featureflipClient(FeatureflipProperties properties,
                                               ObjectProvider<SnapshotStore> snapshotStore) {
        FeatureFlagConfig.Builder config = FeatureFlagConfig.builder()
            .wrapper(WRAPPER_NAME, SpringBootVersion.getVersion());
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
        SnapshotStore store = snapshotStore.getIfAvailable();
        if (store != null) config.snapshotStore(store);

        // Built outside the try: an invalid option throws IllegalArgumentException too,
        // and must surface as itself, not as a missing key.
        FeatureFlagConfig built = config.build();
        try {
            return FeatureflipClient.get(properties.getSdkKey(), built);
        } catch (IllegalArgumentException e) {
            // The core's message names its own factory method; point at the Spring settings.
            // The key itself is never part of any message here.
            throw new IllegalStateException("No Featureflip SDK key is configured. Set the "
                + "featureflip.sdk-key property or the " + SDK_KEY_ENV_VAR + " environment variable.", e);
        }
    }
}
