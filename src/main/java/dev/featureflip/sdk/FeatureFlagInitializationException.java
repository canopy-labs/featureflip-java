package dev.featureflip.sdk;

public class FeatureFlagInitializationException extends RuntimeException {
    public FeatureFlagInitializationException(String message) { super(message); }
    public FeatureFlagInitializationException(String message, Throwable cause) { super(message, cause); }
}
