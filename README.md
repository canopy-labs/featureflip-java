# Featureflip Java SDK

Java SDK for [Featureflip](https://featureflip.io) - evaluate feature flags locally with near-zero latency.

## Installation

### Gradle

```groovy
implementation 'io.featureflip:featureflip-java:1.0.0'
```

### Maven

```xml
<dependency>
    <groupId>io.featureflip</groupId>
    <artifactId>featureflip-java</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

```java
import dev.featureflip.sdk.FeatureflipClient;
import dev.featureflip.sdk.EvaluationContext;

FeatureflipClient client = FeatureflipClient.builder("your-sdk-key").build();
client.waitForInitialization();

boolean enabled = client.boolVariation("my-feature",
    EvaluationContext.of("user-123"), false);

if (enabled) {
    System.out.println("Feature is enabled!");
}

client.close();
```

## Configuration

```java
FeatureflipClient client = FeatureflipClient.builder("your-sdk-key")
    .baseUrl("https://eval.featureflip.io")          // Evaluation API URL (default)
    .streaming(true)                                   // SSE for real-time updates (default)
    .pollInterval(Duration.ofSeconds(30))              // Polling interval if streaming=false
    .flushInterval(Duration.ofSeconds(30))             // Event flush interval
    .flushBatchSize(100)                               // Events per batch
    .initTimeout(Duration.ofSeconds(10))               // Max wait for initialization
    .connectTimeout(Duration.ofSeconds(5))             // HTTP connection timeout
    .readTimeout(Duration.ofSeconds(10))               // HTTP read timeout
    .build();
```

The SDK key can also be set via the `FEATUREFLIP_SDK_KEY` environment variable.

## Evaluation

```java
EvaluationContext context = EvaluationContext.of("user-123");

// Boolean flag
boolean enabled = client.boolVariation("feature-key", context, false);

// String flag
String tier = client.stringVariation("pricing-tier", context, "free");

// Integer flag
int limit = client.intVariation("rate-limit", context, 100);

// Double flag
double ratio = client.doubleVariation("rollout-ratio", context, 0.5);

// JSON flag
UiConfig config = client.jsonVariation("ui-config", context,
    new UiConfig("light"), UiConfig.class);
```

### Detailed Evaluation

```java
EvaluationDetail<Boolean> detail = client.boolVariationDetail(
    "feature-key", EvaluationContext.of("123"), false);

System.out.println(detail.getValue());        // The evaluated value
System.out.println(detail.getReason());        // RULE_MATCH, FALLTHROUGH, FLAG_DISABLED, etc.
System.out.println(detail.getRuleId());        // Rule ID if reason is RULE_MATCH
System.out.println(detail.getErrorMessage());  // Error details if reason is ERROR
```

## Event Tracking

```java
// Track custom events
client.track("checkout-completed",
    EvaluationContext.of("123"),
    Map.of("total", 99.99));

// Force flush pending events
client.flush();
```

## Resource Management

The client implements `AutoCloseable` for try-with-resources:

```java
try (var client = FeatureflipClient.builder("your-sdk-key").build()) {
    client.waitForInitialization();
    boolean enabled = client.boolVariation("feature", context, false);
}
// Automatically closed and flushed
```

## Testing

Use `forTesting()` to create a client with predetermined flag values -- no network calls.

```java
FeatureflipClient client = FeatureflipClient.forTesting(Map.of(
    "my-feature", true,
    "pricing-tier", "pro"
));

client.boolVariation("my-feature", context, false);     // true
client.stringVariation("pricing-tier", context, "free"); // "pro"
client.boolVariation("unknown", context, false);         // false (default)
```

## Features

- **Local evaluation** - Near-zero latency after initialization
- **Real-time updates** - SSE streaming with automatic polling fallback
- **Event tracking** - Automatic batching and background flushing
- **Test support** - `forTesting()` factory for deterministic unit tests
- **AutoCloseable** - Works with try-with-resources
- **Thread-safe** - Safe for concurrent access from multiple threads

## Requirements

- Java 11+

## License

MIT
