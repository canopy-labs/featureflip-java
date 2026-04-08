# Changelog

## 2.0.0 — 2026-04-07

### BREAKING

- **Java package renamed `dev.featureflip.sdk` → `io.featureflip.client`.** This aligns the Java package with the Maven group ID (`io.featureflip`) and matches the C# SDK naming pattern (`Featureflip.Client`). Update your imports:

  Before:
  ```java
  import dev.featureflip.sdk.FeatureflipClient;
  import dev.featureflip.sdk.EvaluationContext;
  ```

  After:
  ```java
  import io.featureflip.client.FeatureflipClient;
  import io.featureflip.client.EvaluationContext;
  ```

- **Singleton-by-construction via the new `FeatureflipClient.get(sdkKey)` factory.** Multiple `get()` calls (or `builder().build()` calls) with the same SDK key now return handles sharing one underlying client. Closing one handle when multiple share a client does not shut down the underlying background tasks — the real shutdown runs only when the last handle is closed. This makes scoped/prototype DI registrations in Spring Boot, Micronaut, or hand-rolled containers harmless instead of leaking SSE connections per request.

  **Migration:**

  ```java
  // Recommended new style:
  try (FeatureflipClient client = FeatureflipClient.get("your-sdk-key")) {
      boolean enabled = client.boolVariation("flag-key", context, false);
  }

  // Existing builder style still works (now also dedupes):
  try (FeatureflipClient client = FeatureflipClient.builder("your-sdk-key")
          .baseUrl("https://eval.featureflip.io")
          .streaming(true)
          .build()) {
      boolean enabled = client.boolVariation("flag-key", context, false);
  }
  ```

### Added

- `FeatureflipClient.get(sdkKey)` and `FeatureflipClient.get(sdkKey, config)` — static factory, the new primary entry point.
- Package-private `SharedFeatureflipCore` separating expensive resources from the public handle.
- `FeatureFlagConfig.builder()` and `FeatureFlagConfig.Builder.build()` are now public, so callers can construct a standalone `FeatureFlagConfig` to pass to `FeatureflipClient.get(sdkKey, config)`.
- `EvaluationDetail.getVariationKey()` returns the variation key that was served (or `null` if evaluation did not produce a variation).

### Changed

- `FeatureflipClient` is now a thin handle over `SharedFeatureflipCore`. All evaluation, flush, and close operations delegate to the core.
- `FeatureflipClient.Builder.build()` routes through `FeatureflipClient.get()` internally — no public API change, but two builder calls with the same SDK key now share an underlying client.

## 1.0.4

Previous release.

## 1.0.3

Previous release.

## 1.0.0 — 1.0.1

Initial stable releases.
