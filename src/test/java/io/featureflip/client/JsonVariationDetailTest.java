package io.featureflip.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.internal.FlagStore;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.FlagType;
import io.featureflip.client.internal.model.ServeConfig;
import io.featureflip.client.internal.model.ServeType;
import io.featureflip.client.internal.model.Variation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FeatureflipClient#jsonVariationDetail}, the detail counterpart the JSON
 * accessor was missing.
 *
 * <p>Its {@code Object.class} form is the one the OpenFeature provider is built
 * on, so the shapes asserted here are a contract, not an implementation detail:
 * the provider decides {@code TYPE_MISMATCH} by inspecting the value it gets
 * back, which only works if reading as {@code Object} can never itself fail as a
 * mismatch.
 */
class JsonVariationDetailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(raw, e);
        }
    }

    private static FeatureflipClient clientServing(FlagConfiguration... configs) {
        FlagStore store = new FlagStore();
        store.replace(new ArrayList<>(Arrays.asList(configs)), Collections.emptyList());
        return new FeatureflipClient(SharedFeatureflipCore.createForTesting(store));
    }

    private static FlagConfiguration valueFlag(String key, FlagType type, JsonNode value) {
        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey(key);
        flag.setVersion(1);
        flag.setType(type);
        flag.setEnabled(true);

        Variation only = new Variation();
        only.setKey("v");
        only.setValue(value);
        flag.setVariations(new ArrayList<>(Collections.singletonList(only)));
        flag.setRules(new ArrayList<>());

        ServeConfig fallthrough = new ServeConfig();
        fallthrough.setType(ServeType.FIXED);
        fallthrough.setVariation("v");
        flag.setFallthrough(fallthrough);
        flag.setOffVariation("v");
        return flag;
    }

    private static EvaluationContext user() {
        return EvaluationContext.builder("user-1").build();
    }

    @Test
    void carriesTheReasonAndVariationKeyAnObjectReadPreviouslyCouldNotSee() {
        // jsonVariation returns only a value, so before this method a JSON flag was
        // the one type whose reason and served variation were unreachable.
        try (FeatureflipClient client = clientServing(
                valueFlag("theme", FlagType.JSON, json("{\"color\":\"blue\",\"size\":3}")))) {

            EvaluationDetail<Object> detail =
                    client.jsonVariationDetail("theme", user(), null, Object.class);

            assertThat(detail.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
            assertThat(detail.getVariationKey()).isEqualTo("v");
            assertThat(detail.getValue()).isInstanceOf(Map.class);
            assertThat(detail.getValue()).isEqualTo(Map.of("color", "blue", "size", 3));
        }
    }

    @Test
    void readingAsObjectYieldsPlainJavaShapesForEveryFlagType() {
        // The provider's own type guards run against these, so what each JSON shape
        // deserializes to is the contract. A number is the tell: JSON does not
        // distinguish 1 from 1.0, and the provider has to accept both for an integer
        // read — which it can only do if it receives the number rather than a
        // pre-coerced Integer.
        try (FeatureflipClient client = clientServing(
                valueFlag("flag-bool", FlagType.BOOLEAN, json("true")),
                valueFlag("flag-string", FlagType.STRING, json("\"hello\"")),
                valueFlag("flag-int", FlagType.NUMBER, json("42")),
                valueFlag("flag-whole-double", FlagType.NUMBER, json("42.0")),
                valueFlag("flag-double", FlagType.NUMBER, json("1.5")),
                valueFlag("flag-array", FlagType.JSON, json("[1,2]")))) {

            assertThat(client.jsonVariationDetail("flag-bool", user(), null, Object.class).getValue())
                    .isEqualTo(Boolean.TRUE);
            assertThat(client.jsonVariationDetail("flag-string", user(), null, Object.class).getValue())
                    .isEqualTo("hello");
            assertThat(client.jsonVariationDetail("flag-int", user(), null, Object.class).getValue())
                    .isEqualTo(42);
            assertThat(client.jsonVariationDetail("flag-whole-double", user(), null, Object.class).getValue())
                    .isEqualTo(42.0d);
            assertThat(client.jsonVariationDetail("flag-double", user(), null, Object.class).getValue())
                    .isEqualTo(1.5d);
            assertThat(client.jsonVariationDetail("flag-array", user(), null, Object.class).getValue())
                    .isEqualTo(List.of(1, 2));
        }
    }

    @Test
    void readingAsObjectNeverReportsATypeMismatch() {
        // The property the provider depends on: every served shape satisfies an
        // Object read, so a reason of ERROR coming back means a genuine evaluation
        // failure and the provider can report the two distinctly. The typed
        // accessors fold both into ERROR, which is why they cannot be used here.
        try (FeatureflipClient client = clientServing(
                valueFlag("flag-string", FlagType.STRING, json("\"hello\"")))) {

            EvaluationDetail<Object> detail =
                    client.jsonVariationDetail("flag-string", user(), null, Object.class);

            assertThat(detail.getReason()).isEqualTo(EvaluationReason.FALLTHROUGH);
            assertThat(detail.getErrorMessage()).isNull();

            // The same flag read as a boolean is the mismatch that Object hides.
            assertThat(client.boolVariationDetail("flag-string", user(), false).getReason())
                    .isEqualTo(EvaluationReason.ERROR);
        }
    }

    @Test
    void anUnknownFlagYieldsTheCallersDefault() {
        try (FeatureflipClient client = clientServing()) {
            EvaluationDetail<Object> detail =
                    client.jsonVariationDetail("missing", user(), "fallback", Object.class);

            assertThat(detail.getReason()).isEqualTo(EvaluationReason.FLAG_NOT_FOUND);
            assertThat(detail.getValue()).isEqualTo("fallback");
        }
    }

    @Test
    void aTypedReadStillReportsAMismatchAgainstTheRequestedClass() {
        // Object.class is the provider's use; the method is generic and a caller
        // asking for a concrete shape still gets the mismatch signal.
        try (FeatureflipClient client = clientServing(
                valueFlag("theme", FlagType.JSON, json("{\"color\":\"blue\"}")))) {

            EvaluationDetail<Theme> detail =
                    client.jsonVariationDetail("theme", user(), null, Theme.class);
            assertThat(detail.getValue().color).isEqualTo("blue");

            EvaluationDetail<Theme> mismatch =
                    client.jsonVariationDetail("missing", user(), null, Theme.class);
            assertThat(mismatch.getReason()).isEqualTo(EvaluationReason.FLAG_NOT_FOUND);
        }
    }

    /** A caller-supplied shape, to prove the generic form still deserializes. */
    public static final class Theme {
        public String color;
    }
}
