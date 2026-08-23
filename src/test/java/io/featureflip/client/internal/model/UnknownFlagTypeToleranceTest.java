package io.featureflip.client.internal.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.featureflip.client.FeatureFlagConfig;
import io.featureflip.client.internal.FlagHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A flag type this SDK build does not know must not fail the fetch (#2395).
 *
 * <p>Jackson raises {@code InvalidFormatException} on an unknown enum name, and because
 * that surfaced while reading the whole flags payload, the day a new flag type ships
 * server-side every pinned SDK stops serving EVERY flag — not just the one carrying the
 * new type. That is the same total-outage shape {@link UnknownOperatorToleranceTest}
 * covers for operators (#2372), reached by an ordinary additive server change.
 *
 * <p>Tolerating it is unconditionally safe here in a way it is NOT for {@code ServeType}
 * or {@code ConditionLogic}: nothing evaluates {@code FlagConfiguration.getType()}. It is
 * parsed, stored, and never read — go, ruby and php do not even model the field. So there
 * is no evaluator that could mis-serve on a null type, and equally none that could warn
 * about it, which is why the diagnostic is emitted at parse time.
 */
class UnknownFlagTypeToleranceTest {

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

    private static String flagWithType(String key, String type) {
        return "{\"key\":\"" + key + "\",\"version\":1,\"type\":\"" + type + "\",\"enabled\":true,"
            + "\"variations\":[{\"key\":\"on\",\"value\":true},{\"key\":\"off\",\"value\":false}],"
            + "\"rules\":[],"
            + "\"fallthrough\":{\"type\":\"Fixed\",\"variation\":\"off\"},\"offVariation\":\"off\"}";
    }

    @Test
    void unknownFlagTypeParsesAsNullInsteadOfThrowing() throws Exception {
        FlagConfiguration flag = sdkMapper()
            .readValue(flagWithType("f", "Timestamp"), FlagConfiguration.class);

        assertThat(flag.getType()).isNull();
        assertThat(flag.getKey()).isEqualTo("f");
    }

    @Test
    void knownFlagTypesStillParse() throws Exception {
        FlagConfiguration flag = sdkMapper()
            .readValue(flagWithType("f", "Json"), FlagConfiguration.class);

        assertThat(flag.getType()).isEqualTo(FlagType.JSON);
    }

    /** The point of the issue: one new-typed flag must not take its neighbours down. */
    @Test
    void oneUnknownFlagTypeDoesNotFailTheWholeResponse() throws Exception {
        String json = "{\"flags\":["
            + flagWithType("future", "Timestamp") + ","
            + flagWithType("healthy", "Boolean")
            + "],\"segments\":[]}";

        GetFlagsResponse response = sdkMapper().readValue(json, GetFlagsResponse.class);

        assertThat(response.getFlags()).hasSize(2);
        assertThat(response.getFlags().get(1).getType()).isEqualTo(FlagType.BOOLEAN);
    }

    /**
     * The OTHER axis. An integer is the server and SDK disagreeing about the wire format
     * (#2279) and stays a wholesale rejection (#2283) — resolving it by ordinal is the
     * silent-wrong hazard {@code FAIL_ON_NUMBERS_FOR_ENUMS} exists to prevent. Tolerance
     * is for an unknown NAME only.
     */
    @Test
    void integerFlagTypeIsStillRejected() {
        String json = "{\"key\":\"f\",\"version\":1,\"type\":0,\"enabled\":true,"
            + "\"fallthrough\":{\"type\":\"Fixed\",\"variation\":\"off\"},\"offVariation\":\"off\"}";

        assertThatThrownBy(() -> sdkMapper().readValue(json, FlagConfiguration.class))
            .isInstanceOf(InvalidFormatException.class);
    }
}
