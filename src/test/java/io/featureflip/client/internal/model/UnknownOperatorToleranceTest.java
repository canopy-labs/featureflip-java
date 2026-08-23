package io.featureflip.client.internal.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.featureflip.client.FeatureFlagConfig;
import io.featureflip.client.internal.FlagHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An operator name this SDK build does not know must degrade ONE condition, not the
 * whole payload (#2372).
 *
 * <p>Jackson raises {@code InvalidFormatException} on an unknown enum name. Because that
 * surfaced while reading the entire flags response, a single unrecognised operator in a
 * single condition of a single flag failed the whole fetch — every flag in the
 * environment fell back to the caller's defaults. The trigger is ordinary: a new
 * operator shipped server-side reaching an SDK a customer has pinned.
 *
 * <p>The companion rule lives in {@code FlagEvaluatorTest}: a null operator fails CLOSED
 * (#2262), so tolerating it here cannot turn into a match-everyone.
 */
class UnknownOperatorToleranceTest {

    /**
     * The SDK's own mapper, not a locally-built replica — the subject under test is how
     * the <i>shipped</i> mapper is configured, and a replica would keep passing if the
     * real configuration regressed. Constructing FlagHttpClient performs no I/O.
     */
    private static ObjectMapper sdkMapper() {
        FeatureFlagConfig config = FeatureFlagConfig.builder()
            .baseUrl("http://localhost:1")
            .build();
        return new FlagHttpClient("test-sdk-key", config).getObjectMapper();
    }

    private static String flagWithOperator(String key, String operator) {
        return "{\"key\":\"" + key + "\",\"version\":1,\"type\":\"Boolean\",\"enabled\":true,"
            + "\"rules\":[{\"id\":\"r1\",\"priority\":0,"
            + "\"conditionGroups\":[{\"operator\":\"And\",\"conditions\":[{"
            + "\"attribute\":\"country\",\"operator\":\"" + operator + "\","
            + "\"values\":[\"US\"],\"negate\":true}]}],"
            + "\"serve\":{\"type\":\"Fixed\",\"variation\":\"on\"}}],"
            + "\"fallthrough\":{\"type\":\"Fixed\",\"variation\":\"off\"},\"offVariation\":\"off\"}";
    }

    @Test
    void unknownOperatorParsesAsNullInsteadOfThrowing() throws Exception {
        FlagConfiguration flag = sdkMapper()
            .readValue(flagWithOperator("f", "SomeFutureOperator"), FlagConfiguration.class);

        assertThat(flag.getRules().get(0).getConditionGroups().get(0)
            .getConditions().get(0).getOperator()).isNull();
    }

    @Test
    void knownOperatorsStillParse() throws Exception {
        FlagConfiguration flag = sdkMapper()
            .readValue(flagWithOperator("f", "SemverGreaterThanOrEqual"), FlagConfiguration.class);

        assertThat(flag.getRules().get(0).getConditionGroups().get(0)
            .getConditions().get(0).getOperator())
            .isEqualTo(ConditionOperator.SEMVER_GREATER_THAN_OR_EQUAL);
    }

    /** The point of the issue: one bad flag must not take its neighbours down. */
    @Test
    void oneUnknownOperatorDoesNotFailTheWholeResponse() throws Exception {
        String json = "{\"flags\":["
            + flagWithOperator("broken", "SomeFutureOperator") + ","
            + flagWithOperator("healthy", "Equals")
            + "],\"segments\":[]}";

        GetFlagsResponse response = sdkMapper().readValue(json, GetFlagsResponse.class);

        assertThat(response.getFlags()).hasSize(2);
        assertThat(response.getFlags().get(1).getRules().get(0).getConditionGroups().get(0)
            .getConditions().get(0).getOperator()).isEqualTo(ConditionOperator.EQUALS);
    }

    /**
     * The tolerance is scoped, not global. {@code FlagType} joined it in #2395 because
     * nothing evaluates that field (see {@link UnknownFlagTypeToleranceTest}), but
     * {@code ServeType} and {@code ConditionLogic} ARE consulted, and each dispatches on a
     * two-way branch: a null {@code ServeType} falls to the rollout arm and a null
     * {@code ConditionLogic} to OR semantics. Nulling them would convert a loud failure
     * into silently wrong targeting — so they stay loud until they get the entity-drop
     * treatment that actually fits them (follow-up to #2395). Enabling
     * READ_UNKNOWN_ENUM_VALUES_AS_NULL globally is what this pins against; the ordinal
     * case is IntegerEnumRejectionTest's (#2283).
     */
    @Test
    void evaluatedEnumsStillFailLoudly() {
        String badServeType = "{\"key\":\"f\",\"version\":1,\"type\":\"Boolean\",\"enabled\":true,"
            + "\"fallthrough\":{\"type\":\"Canary\",\"variation\":\"off\"},\"offVariation\":\"off\"}";

        assertThatThrownBy(() -> sdkMapper().readValue(badServeType, FlagConfiguration.class))
            .isInstanceOf(InvalidFormatException.class);

        String badConditionLogic = "{\"key\":\"s\",\"version\":1,\"conditions\":[],"
            + "\"conditionLogic\":\"Xor\"}";

        assertThatThrownBy(() -> sdkMapper().readValue(badConditionLogic, Segment.class))
            .isInstanceOf(InvalidFormatException.class);
    }
}
