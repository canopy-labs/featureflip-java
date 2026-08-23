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

    /**
     * The custom attributes, excluding the built-in user id. Package-private:
     * only {@link SharedFeatureflipCore#identify} needs the whole bag, to carry
     * it as an identify event's metadata.
     */
    java.util.Map<String, Object> attributes() {
        return java.util.Collections.unmodifiableMap(attributes);
    }

    /**
     * Returns a shallow copy of this context, carrying the same user id and a
     * fresh attribute map (attribute <em>values</em> are shared by reference).
     *
     * <p>Used to hand evaluation inspectors a context that is decoupled from the
     * caller's object. {@code EvaluationContext} is already immutable, so this is
     * belt-and-braces — it keeps the Java SDK's inspector payload identical in
     * spirit to the other SDKs, where the context is a mutable map.
     */
    EvaluationContext copy() {
        // TreeMap's SortedMap constructor is selected here (most specific
        // overload), which preserves the case-insensitive comparator; the
        // Map overload would silently fall back to natural ordering.
        return new EvaluationContext(userId, new TreeMap<>(attributes));
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
