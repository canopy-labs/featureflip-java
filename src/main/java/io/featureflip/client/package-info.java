/**
 * Featureflip Java Server SDK.
 *
 * <p>Main entry point: {@link io.featureflip.client.FeatureflipClient}
 *
 * <pre>{@code
 * FeatureflipClient client = FeatureflipClient.builder("sdk-key")
 *     .baseUrl("https://eval.example.com")
 *     .build();
 * client.waitForInitialization();
 *
 * boolean enabled = client.boolVariation("flag", context, false);
 * client.close();
 * }</pre>
 */
package io.featureflip.client;
