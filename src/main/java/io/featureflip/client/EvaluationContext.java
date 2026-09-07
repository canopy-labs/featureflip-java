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

    /**
     * The identity this context carries, or {@code null} when it is anonymous
     * (see {@link #builder()}).
     *
     * @return the user id, or {@code null} when this context is anonymous
     */
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

    /**
     * Starts a context identified by {@code userId}, which must not be null — use
     * {@link #builder()} for an anonymous one.
     *
     * <p>Passing {@code ""} builds a context whose identity is <em>present but
     * empty</em>. That is deliberate and unchanged by #2665: it cannot be bucketed,
     * so a rollout serves the control variation (#1457), and analytics events carry
     * {@code "userId": ""} — matching js, python, php and ruby, which all attribute
     * events off a null identity rather than an empty one. A caller who never had an
     * identity wants {@link #builder()} instead, whose events omit the field.
     *
     * @param userId the identity to carry; must not be null
     * @return a builder for a context identified by {@code userId}
     */
    public static Builder builder(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return new Builder(userId);
    }

    /**
     * Starts an anonymous context: attributes, no identity.
     *
     * <p>Targeting rules still read the attribute bag, so an anonymous context
     * segments normally. It cannot be bucketed, so a percentage rollout keyed on the
     * identity serves the control variation deterministically, exactly as a keyless
     * context does (#1457).
     *
     * <p>Analytics events built from such a context <strong>omit</strong>
     * {@code userId} rather than sending an empty string, which is the shape every
     * other SDK puts on the wire for an unattributed event (#2665). Before this
     * existed the closest spelling was {@code builder("")}, whose events claim a
     * present-but-empty identity — the shape #2397 removed from php.
     *
     * @return a builder for a context with attributes and no identity
     */
    public static Builder builder() {
        return new Builder(null);
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
