package io.featureflip.client;

import java.util.Objects;
import java.util.TreeMap;

public final class EvaluationContext {
    private final String userId;
    private final TreeMap<String, Object> attributes;

    private EvaluationContext(String userId, TreeMap<String, Object> attributes) {
        this.userId = userId;
        this.attributes = attributes;
    }

    public String getUserId() { return userId; }

    public Object getAttribute(String key) {
        // Built-in user id takes precedence over custom attributes. The
        // "userId"/"user_id" alias is matched case-sensitively (not
        // equalsIgnoreCase) to mirror the engine's EvaluationContext.GetAttribute
        // and the other SDKs — a single documented aliasing rule across engine +
        // SDKs (#1460). A differently cased key falls through to a custom lookup.
        if (key.equals("userId") || key.equals("user_id")) {
            return userId;
        }

        // Fall back to custom attributes
        return attributes.get(key);
    }

    public static Builder builder(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return new Builder(userId);
    }

    public static final class Builder {
        private final String userId;
        private final TreeMap<String, Object> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        private Builder(String userId) {
            this.userId = userId;
        }

        public Builder set(String key, Object value) {
            attributes.put(key, value);
            return this;
        }

        public EvaluationContext build() {
            return new EvaluationContext(userId, new TreeMap<>(attributes));
        }
    }
}
