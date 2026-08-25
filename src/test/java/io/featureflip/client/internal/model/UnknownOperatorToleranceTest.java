package io.featureflip.client.internal.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.featureflip.client.FeatureFlagConfig;
import io.featureflip.client.internal.FlagHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
     * The tolerance is scoped, and the two evaluated enums are scoped DIFFERENTLY.
     *
     * <p>{@code FlagType} maps to null (#2395) because nothing evaluates that field, so a
     * tolerated value has no downstream consumer to mis-serve on. {@code ServeType} and
     * {@code ConditionLogic} ARE consulted, and each dispatches on a two-way branch — a
     * null {@code ServeType} would fall to the rollout arm and a null
     * {@code ConditionLogic} to OR semantics, converting a loud failure into silently
     * wrong targeting. So they map to a distinct {@code UNRECOGNIZED} sentinel instead,
     * which {@code UnevaluableEntities} uses to drop the containing flag or segment while
     * the rest of the payload applies (#2402).
     *
     * <p>What this pins is the CONVERTER half: the payload parses, and the value is
     * tellable apart both from a legitimate one and from an ABSENT one (which still
     * deserializes to null, and whose handling is deliberately unchanged). The drop itself
     * is pinned by {@code GoldenMalformedTest} against the shared fixture. Enabling
     * READ_UNKNOWN_ENUM_VALUES_AS_NULL globally is what this guards against, because it
     * would null these two rather than sentinel them and the drop could no longer tell
     * unrecognised from absent; the ordinal case is IntegerEnumRejectionTest's (#2283).
     */
    @Test
    void evaluatedEnumsSentinelRatherThanThrow() throws Exception {
        String badServeType = "{\"key\":\"f\",\"version\":1,\"type\":\"Boolean\",\"enabled\":true,"
            + "\"fallthrough\":{\"type\":\"Canary\",\"variation\":\"off\"},\"offVariation\":\"off\"}";

        FlagConfiguration flag = sdkMapper().readValue(badServeType, FlagConfiguration.class);
        assertThat(flag.getFallthrough().getType()).isEqualTo(ServeType.UNRECOGNIZED);

        String badConditionLogic = "{\"key\":\"s\",\"version\":1,\"conditions\":[],"
            + "\"conditionLogic\":\"Xor\"}";

        Segment segment = sdkMapper().readValue(badConditionLogic, Segment.class);
        assertThat(segment.getConditionLogic()).isEqualTo(ConditionLogic.UNRECOGNIZED);
    }

    /**
     * The sentinel must not swallow an ABSENT value. Absent is the
     * missing-required-field axis, whose handling this change deliberately leaves alone —
     * if it also produced UNRECOGNIZED, every payload omitting the field would start
     * losing entities.
     */
    @Test
    void absentEvaluatedEnumIsNotSentinelled() throws Exception {
        String noServeType = "{\"key\":\"f\",\"version\":1,\"type\":\"Boolean\",\"enabled\":true,"
            + "\"fallthrough\":{\"variation\":\"off\"},\"offVariation\":\"off\"}";

        FlagConfiguration flag = sdkMapper().readValue(noServeType, FlagConfiguration.class);
        assertThat(flag.getFallthrough().getType()).isNull();

        String noConditionLogic = "{\"key\":\"s\",\"version\":1,\"conditions\":[]}";

        Segment segment = sdkMapper().readValue(noConditionLogic, Segment.class);
        assertThat(segment.getConditionLogic()).isNull();
    }
}
