package io.featureflip.client;

public class FeatureFlagInitializationException extends RuntimeException {
    public FeatureFlagInitializationException(String message) { super(message); }
    public FeatureFlagInitializationException(String message, Throwable cause) { super(message, cause); }
}
