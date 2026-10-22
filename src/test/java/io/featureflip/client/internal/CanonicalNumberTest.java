package io.featureflip.client.internal;

import io.featureflip.client.EvaluationContext;
import io.featureflip.client.EvaluationReason;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.ServeConfig;
import io.featureflip.client.internal.model.ServeType;
import io.featureflip.client.internal.model.TargetingRule;
import io.featureflip.client.internal.model.Variation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalNumberTest {
    @ParameterizedTest
    @CsvSource({
        "1.0, 1",
        "1.5, 1.5",
        "0.1, 0.1",
        "0.30000000000000004, 0.30000000000000004",
        "-0.0, 0",
        "100.25, 100.25",
        "1e21, 1e+21",
        "1.5e21, 1.5e+21",
        "1e20, 100000000000000000000",
        "1e-7, 1e-7",
        "0.000001, 0.000001",
        "2.5e-7, 2.5e-7",
        "123456.789, 123456.789",
        "-42.5, -42.5",
        "4.9E-324, 5e-324",
        "1.7976931348623157E308, 1.7976931348623157e+308",
        "5.684341886080802E-14, 5.684341886080802e-14",
        "1.0E23, 1e+23",
    })
    void doubleMatchesEcmaScriptSpelling(double x, String expected) {
        assertThat(CanonicalNumber.format(x)).isEqualTo(expected);
    }

    @Test
    void nonFinite() {
        assertThat(CanonicalNumber.format(Double.NaN)).isEqualTo("NaN");
        assertThat(CanonicalNumber.format(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
        assertThat(CanonicalNumber.format(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
    }

    @Test
    void floatUsesShortestSinglePrecisionSpelling() {
        assertThat(CanonicalNumber.format(0.1f)).isEqualTo("0.1");
        assertThat(CanonicalNumber.format(1.0f)).isEqualTo("1");
    }

    @Test
    void bucketValueOfBigDecimalKeepsIntegralDigitsElseGoesThroughDouble() {
        String[][] cases = {
            {"1.0", "1"}, {"100", "100"}, {"1E+2", "100"}, {"-0", "0"}, {"0.000", "0"}, {"-5.00", "-5"},
            {"12345678901234567890", "12345678901234567890"},
            {"1E+30", "1000000000000000000000000000000"},
            {"1.5", "1.5"}, {"0.1", "0.1"}, {"1E-7", "1e-7"}, {"2.50", "2.5"},
        };
        for (String[] c : cases) {
            assertThat(FlagEvaluator.bucketValueOf(new java.math.BigDecimal(c[0])))
                .as("BigDecimal(%s)", c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    void bucketValueLeavesIntegersBooleansAndStringsAlone() {
        assertThat(FlagEvaluator.bucketValueOf(42)).isEqualTo("42");
        assertThat(FlagEvaluator.bucketValueOf(-7L)).isEqualTo("-7");
        assertThat(FlagEvaluator.bucketValueOf(new BigInteger("12345678901234567890"))).isEqualTo("12345678901234567890");
        assertThat(FlagEvaluator.bucketValueOf(true)).isEqualTo("true");
        assertThat(FlagEvaluator.bucketValueOf(false)).isEqualTo("false");
        assertThat(FlagEvaluator.bucketValueOf("1.0")).isEqualTo("1.0");
        assertThat(FlagEvaluator.bucketValueOf(1.0)).isEqualTo("1");
        assertThat(FlagEvaluator.bucketValueOf(null)).isEqualTo("");
    }

    @Test
    void userIdAttributeBucketsOnlyOnTheBucketPath() {
        EvaluationContext ctx = EvaluationContext.builder().set("user_id", 0).build();
        assertThat(ctx.getAttribute("user_id")).isNull();
        assertThat(ctx.getBucketAttribute("user_id")).isEqualTo(0);
        assertThat(EvaluationContext.builder("u").set("user_id", 0).build().getBucketAttribute("user_id"))
            .isEqualTo("u");
    }

    private static FlagConfiguration flag(String served, boolean inRule) {
        FlagConfiguration f = new FlagConfiguration();
        f.setKey("f");
        f.setEnabled(true);
        f.setOffVariation("a");
        Variation a = new Variation();
        a.setKey("a");
        f.setVariations(List.of(a));
        ServeConfig fixed = new ServeConfig();
        fixed.setType(ServeType.FIXED);
        fixed.setVariation(served);
        ServeConfig ok = new ServeConfig();
        ok.setType(ServeType.FIXED);
        ok.setVariation("a");
        if (inRule) {
            TargetingRule r = new TargetingRule();
            r.setId("r1");
            r.setPriority(0);
            r.setServe(fixed);
            f.setRules(List.of(r));
            f.setFallthrough(ok);
        } else {
            f.setRules(Collections.emptyList());
            f.setFallthrough(fixed);
        }
        return f;
    }

    @Test
    void ruleServingUnknownVariationIsErrorWithoutRuleId() {
        FlagEvaluator.Result r = new FlagEvaluator(new FlagStore())
            .evaluate(flag("nope", true), EvaluationContext.builder("u").build());
        assertThat(r.getReason()).isEqualTo(EvaluationReason.ERROR);
        assertThat(r.getVariationKey()).isEqualTo("nope");
        assertThat(r.getRuleId()).isNull();
    }

    @Test
    void fallthroughServingUnknownVariationIsError() {
        FlagEvaluator.Result r = new FlagEvaluator(new FlagStore())
            .evaluate(flag("nope", false), EvaluationContext.builder("u").build());
        assertThat(r.getReason()).isEqualTo(EvaluationReason.ERROR);
        assertThat(r.getVariationKey()).isEqualTo("nope");
    }
}
