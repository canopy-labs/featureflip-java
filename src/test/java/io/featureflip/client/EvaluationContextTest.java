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

    // ---------------------------------------------------------------------
    // #2665: the anonymous context — attributes with no identity.
    // ---------------------------------------------------------------------

    // Before this existed, builder(userId) was the ONLY way to build a context,
    // so a caller wanting an anonymous event carrying attributes had to reach for
    // builder(""), which puts "userId":"" on the wire — a present-but-empty
    // identity, precisely the shape #2397 removed from php.
    @Test
    void anonymousBuilderCarriesAttributesWithNoIdentity() {
        EvaluationContext ctx = EvaluationContext.builder()
            .set("plan", "pro")
            .build();

        assertThat(ctx.getUserId()).isNull();
        assertThat(ctx.getAttribute("plan")).isEqualTo("pro");
    }

    // The alias resolves to the built-in field either way, so on an anonymous
    // context both spellings report the absent identity rather than falling
    // through to a same-named custom attribute.
    @Test
    void anonymousContextResolvesBothIdentityAliasesToNull() {
        EvaluationContext ctx = EvaluationContext.builder().set("plan", "pro").build();

        assertThat(ctx.getAttribute("userId")).isNull();
        assertThat(ctx.getAttribute("user_id")).isNull();
    }

    // copy() feeds evaluation inspectors, so it has to survive a null identity —
    // it is on the path of every evaluation an anonymous caller makes.
    @Test
    void anonymousContextSurvivesCopy() {
        EvaluationContext copy = EvaluationContext.builder().set("plan", "pro").build().copy();

        assertThat(copy.getUserId()).isNull();
        assertThat(copy.getAttribute("plan")).isEqualTo("pro");
    }

    // Settled explicitly rather than left implicit (#2665): builder("") KEEPS its
    // present-but-empty identity. js, python, php and ruby all attribute events off
    // null rather than emptiness, so 4 of the 5 map-context SDKs carry an explicit
    // "" through to the wire; only go drops it, and does so by accident of
    // `omitempty` rather than by an absent-vs-empty decision. Folding "" into
    // "absent" here would move java onto go's accidental side of a split the fleet
    // has never arbitrated, and would silently change the wire shape of existing
    // callers. Callers who never had an identity now say builder() instead.
    @Test
    void emptyUserIdRemainsAPresentIdentity() {
        assertThat(EvaluationContext.builder("").build().getUserId()).isEmpty();
    }
}
