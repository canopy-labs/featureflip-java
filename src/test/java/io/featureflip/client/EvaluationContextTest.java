package io.featureflip.client;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationContextTest {

    @Test
    void builderRequiresUserId() {
        assertThatThrownBy(() -> EvaluationContext.builder(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getUserIdReturnsBuilderValue() {
        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        assertThat(ctx.getUserId()).isEqualTo("user-1");
    }

    @Test
    void getAttributeReturnsCustomAttribute() {
        EvaluationContext ctx = EvaluationContext.builder("user-1")
            .set("country", "US")
            .build();
        assertThat(ctx.getAttribute("country")).isEqualTo("US");
    }

    @Test
    void getAttributeReturnsUserIdForUserIdKey() {
        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        assertThat(ctx.getAttribute("userId")).isEqualTo("user-1");
    }

    @Test
    void getAttributeIsCaseInsensitive() {
        EvaluationContext ctx = EvaluationContext.builder("user-1")
            .set("Country", "US")
            .build();
        assertThat(ctx.getAttribute("country")).isEqualTo("US");
        assertThat(ctx.getAttribute("COUNTRY")).isEqualTo("US");
    }

    @Test
    void builtInUserIdTakesPrecedenceOverCustomAttribute() {
        EvaluationContext ctx = EvaluationContext.builder("user-1")
            .set("userId", "override")
            .build();
        assertThat(ctx.getAttribute("userId")).isEqualTo("user-1");
    }

    @Test
    void builtInUserIdTakesPrecedenceOverCustomAttribute_snakeCase() {
        EvaluationContext ctx = EvaluationContext.builder("user-1")
            .set("user_id", "override")
            .build();
        assertThat(ctx.getAttribute("user_id")).isEqualTo("user-1");
    }

    @Test
    void getAttributeReturnsNullForMissing() {
        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        assertThat(ctx.getAttribute("nonexistent")).isNull();
    }

    // The userId/user_id alias to the built-in user id is case-sensitive,
    // mirroring the engine's EvaluationContext.GetAttribute (#1460). Exact
    // "userId"/"user_id" resolve the built-in; other casings do not (here they
    // fall through to an absent custom attribute → null).
    @Test
    void getAttributeUserIdAliasIsCaseSensitive() {
        EvaluationContext ctx = EvaluationContext.builder("user-1").build();
        assertThat(ctx.getAttribute("userId")).isEqualTo("user-1");
        assertThat(ctx.getAttribute("user_id")).isEqualTo("user-1");
        assertThat(ctx.getAttribute("USERID")).isNull();
        assertThat(ctx.getAttribute("User_Id")).isNull();
        assertThat(ctx.getAttribute("userid")).isNull();
    }
}
