/**
 * Featureflip Java Server SDK.
 *
 * <p>Main entry point: {@link dev.featureflip.sdk.FeatureflipClient}
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
package dev.featureflip.sdk;
