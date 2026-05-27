package io.featureflip.client;

public class FeatureFlagInitializationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FeatureFlagInitializationException(String message) { super(message); }
    public FeatureFlagInitializationException(String message, Throwable cause) { super(message, cause); }
}
