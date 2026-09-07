# Changelog

## 2.9.0 — 2026-09-06

### Added

- `FeatureflipClient.onUpdate(FlagUpdateListener)` — subscribe to flag-configuration changes. The listener is called with the flag keys whose evaluated value may have moved, batched into one call per update, and the returned action unsubscribes (idempotently; subscriptions are also dropped when the client is closed). Brings Java level with the JS, Python and Go SDKs, and is what lets the OpenFeature provider emit `PROVIDER_CONFIGURATION_CHANGED`. (#1867)

  The reported set is deliberately wider than "flags whose own config changed", because two kinds of movement leave no trace on the flag that moved. A **segment** edit changes what a rule matches without touching — or versioning — any flag that references it. A **prerequisite** toggle bumps only the prerequisite's version, while every dependent flips to its off variation with `PREREQUISITE_FAILED`. Both are followed, the second transitively, so a listener that re-reads what it is told about sees every value that actually changed.

  The initial flag load does not fire — a cold start is not a change — and neither does a snapshot identical to the one held. That second point is load-bearing rather than an optimization: the store is handed a full snapshot on every poll tick and on every SSE reconnect, so without the comparison a listener would fire once per poll interval forever. Comparison is structural over the serialized configuration rather than a version check, since a segment edit moves a flag's value without moving its version.

- `FeatureflipClient.jsonVariationDetail(key, context, defaultValue, type)` — the detail counterpart of `jsonVariation`. Until now JSON was the one flag type whose evaluation reason, rule id and served variation key were unreachable: every other type had a `*VariationDetail` twin and JSON did not.

  Passing `Object.class` reads a value without asserting its type, yielding the plain Java shapes Jackson produces (`Map`, `List`, `String`, `Integer`, `Double`, `Boolean`). That form cannot fail as a type mismatch, which is what lets a caller — the OpenFeature provider being the first — distinguish a genuine evaluation error from a value of the wrong type. The typed accessors fold both into `ERROR`.

### Fixed

- The README's quickstart did not compile. It reached for `EvaluationContext.of(...)`, a factory this SDK has never had, in three of its examples — including the very first one — so anyone copying the getting-started snippet hit `cannot find symbol` before they hit anything else. All three now use `EvaluationContext.builder(userId).build()`, which is what the API actually offers.

## 2.8.0 — 2026-09-03

### Added

- `EvaluationContext.builder()` — a no-arg builder producing an **anonymous** context: attributes, no identity. Until now `builder(userId)` was the only constructor and it rejected a null id, so a context carrying attributes with no identity could not be built at all — a shape every other SDK expresses trivially. Analytics events from such a context omit `userId` rather than sending an empty string. (#2665)

  The workaround this replaces was actively wrong on the wire. A caller wanting an anonymous event with attributes had two spellings and both were broken: `identify(null)` omitted `userId` correctly but a null context carries no attributes, while `identify(builder("").set(…))` carried the attributes but emitted `"userId": ""` — a present-but-empty identity, the exact shape #2397 removed from the PHP SDK. Ordinary evaluation pushed callers toward the broken one, since `builder("")` is how a keyless context was spelled: **evaluation was always correct** (an empty bucket value serves the control variation, #1457), so a caller doing the supported thing for anonymous *evaluation* silently emitted non-conforming *events*.

  `builder("")` is **unchanged** and still carries a present-but-empty identity. That is deliberate: the JS, Python, PHP and Ruby SDKs all attribute events off a *null* identity rather than an empty one, so an explicit `""` reaches the wire in four of the five map-context SDKs; only Go drops it, and by accident of `omitempty` rather than by an absent-vs-empty decision. Folding `""` into "absent" here would have silently changed the wire shape for existing callers to match the one SDK that never decided it.

## 2.7.0 — 2026-08-26

### Changed

- A long-lived SSE stream severed mid-frame is now logged at `DEBUG` instead of `WARN`. Such a sever is routine behind a CDN or reverse proxy: the client reconnects and the server replays a full `sync` snapshot, so no configuration is missed and nothing is degraded — but reporting it at `WARN` turned ordinary operation into a recurring alarm. A stream that never opened is still logged at `WARN`, and the fallback-to-polling warning is unchanged, so a genuinely broken stream stays visible. (#2457)

### Fixed

- An explicit `client.flush()` no longer opens a second drain loop while one is already running. The in-flight latch added for #2456 guarded only the batch-size trigger, so the periodic flush, an explicit `client.flush()` and a size-triggered flush could enter the loop together — two request streams against an endpoint the backoff gate exists to protect, and worse, a success in one cleared the gate a failure in the other had just armed, re-opening the one-request-per-evaluation behaviour outright. A caller arriving while a drain is running now waits for it and returns, matching the js and node SDKs. Shutdown still bypasses coalescing, because it is the last drain there will ever be. (#2477)

- A `Before`/`After` date operand that resolves outside the representable date range now matches nothing, where it previously resolved to a real instant. The evaluation engine parses with `DateTimeOffset.TryParse`, so its accepted range is 0001-01-01T00:00:00Z to 9999-12-31T23:59:59.999Z and it matches nothing outside that; this SDK resolved past **both** ends, so a single saved rule served different variations to two users purely by which SDK their service ran. (#2500)

- The two reachable shapes are a **year-zero** operand and an operand carried out of range **by its offset**. `0000-01-01` is inside the ISO grammar and is a real proleptic date (`0000-02-29` exists — year 0 is divisible by 400), so neither #2480's grammar guard nor #2491's calendar-day check excluded it. Separately, `[0-9]{4}` constrains only the **written** year while a timezone offset moves the resolved instant, so `0001-01-01T00:00:00+05:00` fell below the floor and `9999-12-31T23:59:59-05:00` rose above the ceiling from years the grammar allows. The check therefore runs on the **resolved** instant — deliberately unlike #2491's, which runs on the written date. (#2500)

- The exact boundaries remain accepted: `0001-01-01`, `0001-01-01T05:00:00+05:00`, `9999-12-31T23:59:59Z` and `9999-12-31T18:59:59-05:00` all still resolve. (#2500)

**If you have a targeting rule using one of these operands**, rewrite it as the date you meant. The Management API has rejected them on write since #2480 (`PortableDateOperand` round-trips every grammar-matched operand through the engine's own parser, so it inherits the range bound), meaning only rules saved before that release can carry one.

- The first SSE reconnect after a healthy stream drops is now jittered to `[d/2, d]`, like every other backoff level. The drops this absorbs are fleet-wide — a single edge event severs every stream at once — so every client re-entered the backoff together and waited an identical delay, republishing the drop's own synchronisation as a reconnect spike one backoff later. Measured in production: a drop spread across 2.5–3.0 ms produced a reconnect spread of 26–46 ms. The delay never exceeds the previous one and stays strictly positive, so a stream that fails immediately still cannot busy-loop. (#2508)

## 2.6.1 — 2026-08-24

### Fixed

- A date operand is now trimmed of exactly the whitespace the evaluation engine trims (tab, newline, vertical tab, form feed, carriage return and space), and is rejected outright if it still carries a NUL, another control character, or a non-ASCII whitespace character. Each SDK had been relying on its own language's `trim`, and no two of those cover the same set, so the same operand could match on one SDK and match nothing on another. (#2468)
- A date operand written with a space separator (`2024-01-01 00:00:00`), without seconds (`2024-01-01T00:00`), or with a basic offset (`+0500`) now parses. `java.time`'s ISO parsers reject all three, so they were matching nothing here while the engine accepted them. (#2468)

## 2.6.0 — 2026-08-24

### Fixed

- Analytics events now survive a transient failure of the events endpoint. The queue is drained before the batch is sent, so until now any non-2xx or network error discarded that batch outright — the public edge answers this endpoint with a 503 at a low but constant rate, so evaluation analytics were being lost steadily. A retryable failure (5xx, 429, transport fault, timeout) returns the batch to the front of the queue for the next flush; a permanent one (401/403/400) still drops it, because retrying a rejected SDK key forever would starve every later event. (#2456)
- A rejected event flush is now reported at all. `sendEvents` logged the response status and returned normally, so a rejected batch was indistinguishable from a delivered one; it throws `EventSendException` instead, which carries the status the flush path needs to decide whether to keep the batch. (#2456)

### Changed

- The 10,000-event queue bound now sheds the OLDEST events rather than refusing the newest, so a re-queued batch is not discarded the moment it comes back and memory still stays bounded through a sustained outage. (#2456)
- A flush sends one request per batch instead of one request for the whole queue. Re-queuing failures is what lets the queue grow towards its bound during an outage, and posting all of it in a single body invites a 413 — which is not retryable, so the entire backlog would have been dropped by the very path that exists to preserve it. A permanently rejected batch is dropped and the flush moves on to the next one, so one poison batch cannot block the backlog behind it. (#2456)
- The batch-size flush trigger backs off for one flush interval after a retryable failure, and will not start a second flush while one is in flight. A re-queued batch leaves the queue at or above the batch size, so without this every recorded event would fire another request at an endpoint that is already failing. The scheduled flush remains the retry vehicle, and an explicit `flush()` is never gated. (#2456)

## 2.5.1 — 2026-08-23

### Fixed

- A `Before`/`After` operand written in non-ASCII digits no longer resolves to a unix timestamp. `Long.parseLong` accepts every character in Unicode category `Nd`, so `"١٢٣"` (Arabic-Indic) read as 123 seconds past the epoch and `"５"` (fullwidth) as 5 — matching in Java where the evaluation service and every other SDK matched nothing. (#2467)
- An unrecognised `FlagType` no longer fails the entire config fetch. One unknown value in one flag discarded the whole payload, so a single new server-side type could blank out every flag the SDK served. (#2401)
- An unrecognised condition operator now fails closed instead of matching every user. The default arm returned `false`, which a negated condition then inverted to `true` — so a config naming an operator the SDK did not know could silently target everyone. (#2262)
- An unrecognised condition operator is tolerated at deserialization instead of discarding the whole config, matching the string-typed SDKs. (#2372)
- `identify()` and `track()` put the same payload on the wire as every other server SDK. The field set and shapes had drifted per language, so the same call produced different events depending on which SDK sent it. (#2359)
- `FEATUREFLIP_SDK_KEY` is read from the environment, as the README already promised. (#2273)

## 2.5.0 — 2026-08-20

### Fixed

- A closed handle serves the caller's default from every accessor and reports not-initialized. `close()` releases the shared core — stopping streaming and polling, shutting down the event processor — but the in-memory store stayed readable, so a closed client kept evaluating against a frozen snapshot that could never update again while still reporting itself initialized. (#2282)

- A null `EvaluationContext` no longer throws an unguarded `NullPointerException` out of a *successful* evaluation. The analytics hop runs after the value has been computed and outside the try/catch that converts evaluation failures into the caller's default, so `boolVariation("existing-flag", null, true)` raised an NPE while the same call against a missing flag returned cleanly. A null context is now treated as an anonymous evaluation: the event is still recorded, with the user id omitted. `track()` accepts a null context on the same terms. (#2280)

### Changed

- A type-mismatched read returns the caller's default and reports `EvaluationReason.ERROR`, instead of coercing it — Jackson's lenient `asText()`/`asInt()`/`asBoolean()` never throw, so a boolean flag read through `intVariation` returned `0`, a plausible-looking wrong number. Reading a String flag through a number accessor, say, is now detectable rather than silent. Matching reads and the generic/JSON accessors are unchanged. (#2281)

- Enum fields on the wire are now required to be strings. Jackson resolves an integer into an enum by **ordinal**, bypassing the `@JsonProperty` names on the constants — so the SDK decoded integer enums correctly only because its declaration order happened to match the server's, and a value inserted into the middle of a server-side enum would have silently shifted every later value here to the wrong one. `FAIL_ON_NUMBERS_FOR_ENUMS` makes that a visible error instead. (#2283)

  This requires the evaluation API's matching SSE enum fix (#2279), which the hosted evaluation API carries as of this release. An evaluation API older than that sends integer enums over SSE, which this version rejects — every `sync` frame is refused and reconnect resync stops working, the opposite of what the change is for.

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
  ``java
  import dev.featureflip.sdk.FeatureflipClient;
  import dev.featureflip.sdk.EvaluationContext;
  ``

  After:
  ``java
  import io.featureflip.client.FeatureflipClient;
  import io.featureflip.client.EvaluationContext;
  ``

- **Singleton-by-construction via the new `FeatureflipClient.get(sdkKey)` factory.** Multiple `get()` calls (or `builder().build()` calls) with the same SDK key now return handles sharing one underlying client. Closing one handle when multiple share a client does not shut down the underlying background tasks — the real shutdown runs only when the last handle is closed. This makes scoped/prototype DI registrations in Spring Boot, Micronaut, or hand-rolled containers harmless instead of leaking SSE connections per request.

  **Migration:**

  ``java
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
  ``

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
