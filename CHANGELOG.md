# Changelog

## 2.5.0 — 2026-08-20

### Fixed

- A closed handle serves the caller's default from every accessor and reports not-initialized. `close()` releases the shared core — stopping streaming and polling, shutting down the event processor — but the in-memory store stayed readable, so a closed client kept evaluating against a frozen snapshot that could never update again while still reporting itself initialized. ([#2282](https://github.com/canopy-labs/featureflip/issues/2282))

- A null `EvaluationContext` no longer throws an unguarded `NullPointerException` out of a *successful* evaluation. The analytics hop runs after the value has been computed and outside the try/catch that converts evaluation failures into the caller's default, so `boolVariation("existing-flag", null, true)` raised an NPE while the same call against a missing flag returned cleanly. A null context is now treated as an anonymous evaluation: the event is still recorded, with the user id omitted. `track()` accepts a null context on the same terms. ([#2280](https://github.com/canopy-labs/featureflip/issues/2280))

### Changed

- A type-mismatched read returns the caller's default and reports `EvaluationReason.ERROR`, instead of coercing it — Jackson's lenient `asText()`/`asInt()`/`asBoolean()` never throw, so a boolean flag read through `intVariation` returned `0`, a plausible-looking wrong number. Reading a String flag through a number accessor, say, is now detectable rather than silent. Matching reads and the generic/JSON accessors are unchanged. ([#2281](https://github.com/canopy-labs/featureflip/issues/2281))

- Enum fields on the wire are now required to be strings. Jackson resolves an integer into an enum by **ordinal**, bypassing the `@JsonProperty` names on the constants — so the SDK decoded integer enums correctly only because its declaration order happened to match the server's, and a value inserted into the middle of a server-side enum would have silently shifted every later value here to the wrong one. `FAIL_ON_NUMBERS_FOR_ENUMS` makes that a visible error instead. ([#2283](https://github.com/canopy-labs/featureflip/issues/2283))

  This requires the evaluation API's matching SSE enum fix ([#2279](https://github.com/canopy-labs/featureflip/issues/2279)), which the hosted evaluation API carries as of this release. An evaluation API older than that sends integer enums over SSE, which this version rejects — every `sync` frame is refused and reconnect resync stops working, the opposite of what the change is for.

## 2.4.2 — 2026-08-05

### Fixed

- The README's Gradle and Maven snippets pinned `2.0.0`, four minor versions behind. It is the copy mirrored to `canopy-labs/featureflip-java`, so that was the install line anyone reading the public repo got.

## 2.4.1 — 2026-08-05

### Fixed

- The published POM's `<url>` points at featureflip.io instead of the GitHub mirror, so Maven Central and mvnrepository.com render a "Project URL" link to the project site. The Android SDK's POM already did this; the Java one was the odd artifact out. `<scm>` still points at the repo.
- `LICENSE` is now the verbatim Apache-2.0 text. Three phrases in the operative sections had been reworded and the appendix dropped, which left automated license scanners unable to identify it. The license itself is unchanged; the file now says what it always claimed to.
- The README's License section said MIT. `LICENSE`, the published POM and the Maven Central listing have always said Apache-2.0, which is the actual license.

## 2.4.0 — 2026-07-29

### Added

- **`onEvaluation` inspector callback.** `inspectors` config option registering in-process observers fired on every evaluation (#1914).

### Fixed

- A served variation key the flag does not define now reports reason `Error` with the caller's default, instead of a misleading success reason. The inspector event's `variationKey`/`ruleId`/`prerequisiteKey` are nulled on this path (#1989, #1987).
- An explicit `"flags": null` in a response payload no longer hangs initialization. `GetFlagsResponse` normalizes in the setter, so the throw can no longer land after the store update but before the initialization signal — which left the client stuck in `waitForInitialization` and re-failing every poll (#1934).

## 2.3.0 — 2026-07-13

### Fixed

- Outage-recovery hardening roll-up (#1896).
- The connect-time `sync` snapshot is applied as a full store replace, so flags deleted during a disconnect are dropped (#1877).

## 2.2.0 — 2026-06-19

### Added

- **Semantic-version condition operators** (`SemverEquals`, `SemverGreaterThan`, `SemverGreaterThanOrEqual`, `SemverLessThan`, `SemverLessThanOrEqual`) for local rule evaluation, comparing per semver precedence rather than as decimals (#1409).

### Fixed

- Per-flag rollout salt aligns bucketing with the engine and every other SDK; the previous `flagKey` fallback re-bucketed users (#1452).
- Relational operators match against **any** supplied condition value (#1443).
- `MatchesRegex` is case-sensitive, matching the engine (#1453).
- Numeric operators return no-match on non-numeric operands instead of falling back to a lexical compare (#1456).
- `Before`/`After` date operators aligned with the engine (#1455).
- Type-aware numeric coercion for `Equals`/`In` (#1458).
- Keyless rollouts serve the control variation deterministically (#1457).
- Segment-keyed rules with no segment source fail closed (#1459).
- Environment-level percentage rollouts with no variations no longer throw (#1469).

## 2.1.1 — 2026-06-03

### Changed

- Bumped `com.fasterxml.jackson.core:jackson-databind` and `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` from 2.21.3 to 2.22.0.

## 2.1.0 — 2026-05-27

### Added

- **Prerequisite flag support.** The local evaluator now resolves prerequisite flags declared on a flag's `prerequisites` field, mirroring the JS core and .NET server-side evaluators. A failing prerequisite (mismatch, missing flag, or disabled prereq serving the off variation) short-circuits to the flag's off variation with `EvaluationReason.PREREQUISITE_FAILED`, and `EvaluationDetail.getPrerequisiteKey()` returns the failing prerequisite's key. Chained prerequisites are resolved recursively with per-call memoization and a depth cap of 10; exceeding the cap returns `EvaluationReason.ERROR`. `FlagEvaluator.evaluateWithSharedMemo(...)` lets batch callers share a memo so a common prerequisite is evaluated once across multiple top-level calls. The new constructor `EvaluationDetail(value, reason, ruleId, errorMessage, variationKey, prerequisiteKey)` is additive — existing constructors remain source- and binary-compatible.

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
