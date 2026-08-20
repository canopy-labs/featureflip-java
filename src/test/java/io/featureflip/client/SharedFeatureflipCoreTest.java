package io.featureflip.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.featureflip.client.internal.FlagStore;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.FlagType;
import io.featureflip.client.internal.model.ServeConfig;
import io.featureflip.client.internal.model.ServeType;
import io.featureflip.client.internal.model.Variation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SharedFeatureflipCoreTest {

    @Test
    void newCore_StartsAtRefcountOne() {
        SharedFeatureflipCore core = SharedFeatureflipCore.createForTesting();
        try {
            assertEquals(1, core.getRefCount());
        } finally {
            core.release();
        }
    }

    @Test
    void tryAcquire_IncrementsRefcount() {
        SharedFeatureflipCore core = SharedFeatureflipCore.createForTesting();
        try {
            assertTrue(core.tryAcquire());
            assertEquals(2, core.getRefCount());
        } finally {
            core.release();
            core.release();
        }
    }

    @Test
    void release_DecrementsRefcount() {
        SharedFeatureflipCore core = SharedFeatureflipCore.createForTesting();
        core.tryAcquire(); // refcount = 2
        core.release();    // refcount = 1
        assertEquals(1, core.getRefCount());
        core.release();    // refcount = 0, core shuts down
    }

    @Test
    void release_AtZero_MarksCoreShutDown() {
        SharedFeatureflipCore core = SharedFeatureflipCore.createForTesting();
        core.release(); // 1 -> 0
        assertTrue(core.isShutDown());
    }

    @Test
    void tryAcquire_AfterShutdown_ReturnsFalse() {
        SharedFeatureflipCore core = SharedFeatureflipCore.createForTesting();
        core.release(); // shut down
        assertFalse(core.tryAcquire());
        assertEquals(0, core.getRefCount());
    }

    @Test
    void tryAcquire_AfterOverRelease_ReturnsFalse() {
        // Regression test: over-release must not produce a phantom successful acquire
        SharedFeatureflipCore core = SharedFeatureflipCore.createForTesting();
        core.release(); // 1 -> 0, shut down
        core.release(); // spurious extra release — should be a no-op
        assertFalse(core.tryAcquire());
        assertTrue(core.isShutDown());
    }

    @Test
    void coreEvaluate_FlagNotFound_ReturnsDefault() {
        FlagStore store = new FlagStore();
        store.replace(new ArrayList<>(), new ArrayList<>());
        SharedFeatureflipCore core = SharedFeatureflipCore.createForTesting(store);
        try {
            EvaluationContext context = EvaluationContext.builder("user-1").build();
            EvaluationDetail<Boolean> detail = core.evaluate("nonexistent", context, true, Boolean.class);
            assertTrue(detail.getValue());
            assertEquals(EvaluationReason.FLAG_NOT_FOUND, detail.getReason());
        } finally {
            core.release();
        }
    }

    @Test
    void coreEvaluate_FlagExists_ReturnsEvaluatedValue() {
        FlagStore store = new FlagStore();
        List<FlagConfiguration> flags = new ArrayList<>();
        flags.add(boolFlag("my-flag", true, "on"));
        store.replace(flags, new ArrayList<>());
        SharedFeatureflipCore core = SharedFeatureflipCore.createForTesting(store);
        try {
            EvaluationContext context = EvaluationContext.builder("user-1").build();
            EvaluationDetail<Boolean> detail = core.evaluate("my-flag", context, false, Boolean.class);
            assertTrue(detail.getValue());
        } finally {
            core.release();
        }
    }

    private static FlagConfiguration boolFlag(String key, boolean enabled, String fallthroughVariation) {
        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey(key);
        flag.setVersion(1);
        flag.setType(FlagType.BOOLEAN);
        flag.setEnabled(enabled);

        Variation onVar = new Variation();
        onVar.setKey("on");
        onVar.setValue(JsonNodeFactory.instance.booleanNode(true));

        Variation offVar = new Variation();
        offVar.setKey("off");
        offVar.setValue(JsonNodeFactory.instance.booleanNode(false));

        List<Variation> variations = new ArrayList<>();
        variations.add(onVar);
        variations.add(offVar);
        flag.setVariations(variations);

        flag.setRules(new ArrayList<>());

        ServeConfig fallthrough = new ServeConfig();
        fallthrough.setType(ServeType.FIXED);
        fallthrough.setVariation(fallthroughVariation);
        flag.setFallthrough(fallthrough);
        flag.setOffVariation("off");

        return flag;
    }
    // -------------------------------------------------------------------------
    // Type-mismatched reads (#2281)
    //
    // A typed accessor whose served value is not of the requested type must hand
    // back the caller's default and report ERROR, so the mismatch is detectable.
    // Jackson's asText()/asInt()/asBoolean() coerce instead and never throw, which
    // silently fed plausible-looking wrong values (0, false) into caller logic.
    // -------------------------------------------------------------------------

    @Test
    void coreEvaluate_BoolFlagReadAsString_ReturnsDefaultWithError() {
        SharedFeatureflipCore core = coreServing(boolFlag("bool-flag", true, "off"));
        try {
            EvaluationDetail<String> detail =
                core.evaluate("bool-flag", userContext(), "DEF", String.class);
            assertEquals("DEF", detail.getValue(), "coerced 'false' instead of the caller's default");
            assertEquals(EvaluationReason.ERROR, detail.getReason());
        } finally {
            core.release();
        }
    }

    @Test
    void coreEvaluate_BoolFlagReadAsInt_ReturnsDefaultWithError() {
        SharedFeatureflipCore core = coreServing(boolFlag("bool-flag", true, "off"));
        try {
            EvaluationDetail<Integer> detail =
                core.evaluate("bool-flag", userContext(), -1, Integer.class);
            assertEquals(-1, detail.getValue(), "coerced to 0 — a plausible-looking wrong number");
            assertEquals(EvaluationReason.ERROR, detail.getReason());
        } finally {
            core.release();
        }
    }

    @Test
    void coreEvaluate_BoolFlagReadAsDouble_ReturnsDefaultWithError() {
        SharedFeatureflipCore core = coreServing(boolFlag("bool-flag", true, "off"));
        try {
            EvaluationDetail<Double> detail =
                core.evaluate("bool-flag", userContext(), -1.0, Double.class);
            assertEquals(-1.0, detail.getValue());
            assertEquals(EvaluationReason.ERROR, detail.getReason());
        } finally {
            core.release();
        }
    }

    @Test
    void coreEvaluate_StringFlagReadAsInt_ReturnsDefaultWithError() {
        SharedFeatureflipCore core = coreServing(valueFlag("str-flag", FlagType.STRING,
            JsonNodeFactory.instance.textNode("42")));
        try {
            EvaluationDetail<Integer> detail =
                core.evaluate("str-flag", userContext(), -1, Integer.class);
            assertEquals(-1, detail.getValue(), "parsed the string '42' into the number 42");
            assertEquals(EvaluationReason.ERROR, detail.getReason());
        } finally {
            core.release();
        }
    }

    @Test
    void coreEvaluate_StringFlagReadAsBool_ReturnsDefaultWithError() {
        SharedFeatureflipCore core = coreServing(valueFlag("str-flag", FlagType.STRING,
            JsonNodeFactory.instance.textNode("42")));
        try {
            EvaluationDetail<Boolean> detail =
                core.evaluate("str-flag", userContext(), true, Boolean.class);
            assertTrue(detail.getValue());
            assertEquals(EvaluationReason.ERROR, detail.getReason());
        } finally {
            core.release();
        }
    }

    @Test
    void coreEvaluate_FractionalNumberReadAsInt_ReturnsDefaultWithError() {
        // Mirrors C#'s JsonElement.GetInt32(), which throws on a fractional number
        // rather than silently truncating it.
        SharedFeatureflipCore core = coreServing(valueFlag("num-flag", FlagType.NUMBER,
            JsonNodeFactory.instance.numberNode(42.5)));
        try {
            EvaluationDetail<Integer> detail =
                core.evaluate("num-flag", userContext(), -1, Integer.class);
            assertEquals(-1, detail.getValue(), "truncated 42.5 to 42");
            assertEquals(EvaluationReason.ERROR, detail.getReason());
        } finally {
            core.release();
        }
    }

    @Test
    void coreEvaluate_JsonNullValueReadAsString_ReturnsDefaultWithError() {
        SharedFeatureflipCore core = coreServing(valueFlag("null-flag", FlagType.STRING,
            JsonNodeFactory.instance.nullNode()));
        try {
            EvaluationDetail<String> detail =
                core.evaluate("null-flag", userContext(), "DEF", String.class);
            assertEquals("DEF", detail.getValue());
            assertEquals(EvaluationReason.ERROR, detail.getReason());
        } finally {
            core.release();
        }
    }

    // --- Matching reads must be untouched by the strictness change ---

    @Test
    void coreEvaluate_MatchingTypes_StillServeTheValueWithSuccessReason() {
        SharedFeatureflipCore core = coreServing(
            boolFlag("bool-flag", true, "on"),
            valueFlag("str-flag", FlagType.STRING, JsonNodeFactory.instance.textNode("hello")),
            valueFlag("int-flag", FlagType.NUMBER, JsonNodeFactory.instance.numberNode(42)));
        try {
            EvaluationContext ctx = userContext();

            EvaluationDetail<Boolean> b = core.evaluate("bool-flag", ctx, false, Boolean.class);
            assertTrue(b.getValue());
            assertEquals(EvaluationReason.FALLTHROUGH, b.getReason());

            assertEquals("hello", core.evaluate("str-flag", ctx, "DEF", String.class).getValue());
            assertEquals(42, core.evaluate("int-flag", ctx, -1, Integer.class).getValue());

            // A whole JSON number satisfies a double read, as in C#'s GetDouble().
            assertEquals(42.0, core.evaluate("int-flag", ctx, -1.0, Double.class).getValue());
        } finally {
            core.release();
        }
    }

    private static EvaluationContext userContext() {
        return EvaluationContext.builder("user-1").build();
    }

    private static SharedFeatureflipCore coreServing(FlagConfiguration... configs) {
        FlagStore store = new FlagStore();
        List<FlagConfiguration> flags = new ArrayList<>();
        for (FlagConfiguration c : configs) {
            flags.add(c);
        }
        store.replace(flags, new ArrayList<>());
        return SharedFeatureflipCore.createForTesting(store);
    }

    /** An enabled flag with a single variation "v" serving {@code value} via fallthrough. */
    private static FlagConfiguration valueFlag(String key, FlagType type, JsonNode value) {
        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey(key);
        flag.setVersion(1);
        flag.setType(type);
        flag.setEnabled(true);

        Variation only = new Variation();
        only.setKey("v");
        only.setValue(value);

        List<Variation> variations = new ArrayList<>();
        variations.add(only);
        flag.setVariations(variations);
        flag.setRules(new ArrayList<>());

        ServeConfig fallthrough = new ServeConfig();
        fallthrough.setType(ServeType.FIXED);
        fallthrough.setVariation("v");
        flag.setFallthrough(fallthrough);
        flag.setOffVariation("v");

        return flag;
    }
}
