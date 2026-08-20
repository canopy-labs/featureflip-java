package io.featureflip.client.internal.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.featureflip.client.FeatureFlagConfig;
import io.featureflip.client.internal.FlagHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wire contract is that enums arrive as strings ("Boolean", "Fixed", "And").
 *
 * <p>Jackson deserializes an integer into an enum by <b>ordinal index</b>, and that
 * path bypasses the {@code @JsonProperty} names on the constants entirely — so those
 * annotations give a false impression that matching is name-based and therefore safe
 * against reordering. It is not.
 *
 * <p>Before this was pinned, an integer-enum payload decoded <i>correctly</i> — but
 * only because the .NET and Java declaration orders happened to be identical, all 20
 * {@link ConditionOperator} values included. Inserting a value into the middle of the
 * .NET enum (say {@code NotMatchesRegex} after {@code MatchesRegex}) would have
 * silently shifted every later operator here to the wrong one: no exception, no log,
 * just wrong targeting decisions. Nothing in either repo linked the two orderings, and
 * nothing would have caught the drift.
 *
 * <p>These tests pin the loud failure, which is what converts that latent silent-wrong
 * into an immediately visible error. See canopy-labs/featureflip#2283, and #2279 for
 * the server-side bug that made integer enums reach SDKs in the first place.
 */
class IntegerEnumRejectionTest {

    /**
     * Deliberately the SDK's own mapper rather than a locally-built one: the whole
     * subject of this test is how the <i>shipped</i> mapper is configured, and a
     * test-local replica would keep passing if the real configuration regressed.
     * Constructing FlagHttpClient performs no I/O.
     */
    private static ObjectMapper sdkMapper() {
        FeatureFlagConfig config = FeatureFlagConfig.builder()
            .baseUrl("http://localhost:1")
            .build();
        return new FlagHttpClient("test-sdk-key", config).getObjectMapper();
    }

    @Test
    void stringEnumsStillDeserialize() throws Exception {
        // The actual contract must keep working — this guards against "fixing" the
        // ordinal hazard by breaking the format the server really sends.
        var json = "{\"key\":\"f\",\"version\":1,\"type\":\"Boolean\",\"enabled\":true,"
            + "\"fallthrough\":{\"type\":\"Fixed\",\"variation\":\"off\"},\"offVariation\":\"off\"}";

        FlagConfiguration flag = sdkMapper().readValue(json, FlagConfiguration.class);

        assertThat(flag.getType()).isEqualTo(FlagType.BOOLEAN);
        assertThat(flag.getFallthrough().getType()).isEqualTo(ServeType.FIXED);
    }

    @Test
    void integerFlagTypeIsRejectedRatherThanMappedByOrdinal() {
        var json = "{\"key\":\"f\",\"version\":1,\"type\":0,\"enabled\":true,"
            + "\"fallthrough\":{\"type\":\"Fixed\",\"variation\":\"off\"},\"offVariation\":\"off\"}";

        assertThatThrownBy(() -> sdkMapper().readValue(json, FlagConfiguration.class))
            .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void integerServeTypeIsRejectedRatherThanMappedByOrdinal() {
        var json = "{\"key\":\"f\",\"version\":1,\"type\":\"Boolean\",\"enabled\":true,"
            + "\"fallthrough\":{\"type\":0,\"variation\":\"off\"},\"offVariation\":\"off\"}";

        assertThatThrownBy(() -> sdkMapper().readValue(json, FlagConfiguration.class))
            .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void integerConditionLogicIsRejectedRatherThanMappedByOrdinal() {
        var json = "{\"key\":\"s\",\"version\":1,\"conditionLogic\":0,\"conditions\":[]}";

        assertThatThrownBy(() -> sdkMapper().readValue(json, Segment.class))
            .isInstanceOf(InvalidFormatException.class);
    }

    /**
     * The highest-stakes case. ConditionOperator carries 20 values whose Java order
     * matches .NET's only by convention, so this is the enum where a future insertion
     * would do the most silent damage — ordinal 18 currently resolves to
     * SEMVER_LESS_THAN purely because both lists happen to agree.
     */
    @Test
    void integerConditionOperatorIsRejectedRatherThanMappedByOrdinal() {
        var json = "{\"key\":\"s\",\"version\":1,\"conditionLogic\":\"And\",\"conditions\":"
            + "[{\"attribute\":\"v\",\"operator\":18,\"values\":[\"1.0.0\"]}]}";

        assertThatThrownBy(() -> sdkMapper().readValue(json, Segment.class))
            .isInstanceOf(InvalidFormatException.class);
    }
}
