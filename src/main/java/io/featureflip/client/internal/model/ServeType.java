package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ServeType {
    @JsonProperty("Fixed") FIXED,
    @JsonProperty("Rollout") ROLLOUT,

    /**
     * A serve type name this SDK build does not know (#2402).
     *
     * <p>Carries no {@code @JsonProperty}, so no name the server legitimately sends
     * maps here — only {@code FlagHttpClient}'s unknown-enum handler produces it. A
     * distinct constant rather than {@code null} because null is what an ABSENT
     * {@code type} already deserializes to, and the two must stay tellable apart:
     * absent is the missing-required-field axis and its handling is unchanged, while
     * unrecognised drops the containing entity.
     *
     * <p>Never reaches {@code FlagEvaluator} — {@code UnevaluableEntities} drops the
     * flag at the parse boundary — which matters because the evaluator dispatches
     * {@code serve.getType() == FIXED} else ROLLOUT, so a sentinel arriving there
     * would silently bucket against weights that may not exist.
     */
    UNRECOGNIZED
}
