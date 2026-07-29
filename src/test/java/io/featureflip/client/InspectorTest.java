package io.featureflip.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.featureflip.client.internal.FlagStore;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.FlagType;
import io.featureflip.client.internal.model.Prerequisite;
import io.featureflip.client.internal.model.ServeConfig;
import io.featureflip.client.internal.model.ServeType;
import io.featureflip.client.internal.model.TargetingRule;
import io.featureflip.client.internal.model.Variation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the {@code onEvaluation} inspector contract: an inspector fires once per
 * variation call on every exit path, carries the frozen cross-SDK payload, and is
 * fully isolated from the caller (a throwing inspector cannot change the returned
 * value or silence its siblings).
 */
class InspectorTest {

    private static final EvaluationContext CONTEXT = EvaluationContext.builder("user-1")
        .set("country", "US")
        .build();

    // ---------------------------------------------------------------------
    // Payload per exit path
    // ---------------------------------------------------------------------

    @Test
    void inspectorFires_OnFallthrough_WithFullPayload() {
        List<EvaluationEvent> events = new ArrayList<>();
        SharedFeatureflipCore core = coreWith(events, boolFlag("my-flag", true, "on"));
        try {
            EvaluationDetail<Boolean> detail = core.evaluate("my-flag", CONTEXT, false, Boolean.class);

            assertEquals(1, events.size());
            EvaluationEvent event = events.get(0);
            assertEquals("my-flag", event.getFlagKey());
            assertEquals(Boolean.TRUE, event.getValue());
            assertEquals(detail.getValue(), event.getValue());
            assertEquals("on", event.getVariationKey());
            assertEquals(EvaluationReason.FALLTHROUGH, event.getReason());
            assertNull(event.getRuleId());
            assertNull(event.getPrerequisiteKey());
            assertIso8601(event.getTimestamp());
            assertNotNull(event.getContext());
            assertEquals("user-1", event.getContext().getUserId());
            assertEquals("US", event.getContext().getAttribute("country"));
        } finally {
            core.release();
        }
    }

    @Test
    void inspectorFires_OnRuleMatch_WithRuleId() {
        FlagConfiguration flag = boolFlag("rule-flag", true, "off");
        TargetingRule rule = new TargetingRule();
        rule.setId("rule-123");
        rule.setPriority(0);
        // No condition groups → the rule matches every context.
        rule.setConditionGroups(new ArrayList<>());
        rule.setServe(fixedServe("on"));
        flag.setRules(new ArrayList<>(Collections.singletonList(rule)));

        List<EvaluationEvent> events = new ArrayList<>();
        SharedFeatureflipCore core = coreWith(events, flag);
        try {
            core.evaluate("rule-flag", CONTEXT, false, Boolean.class);

            assertEquals(1, events.size());
            EvaluationEvent event = events.get(0);
            assertEquals(EvaluationReason.RULE_MATCH, event.getReason());
            assertEquals("rule-123", event.getRuleId());
            assertEquals("on", event.getVariationKey());
            assertEquals(Boolean.TRUE, event.getValue());
            assertNull(event.getPrerequisiteKey());
        } finally {
            core.release();
        }
    }

    @Test
    void inspectorFires_OnPrerequisiteFailure_WithPrerequisiteKey() {
        FlagConfiguration child = boolFlag("child-flag", true, "on");
        Prerequisite prereq = new Prerequisite();
        prereq.setPrerequisiteFlagKey("missing-parent");
        prereq.setExpectedVariationKey("on");
        child.setPrerequisites(new ArrayList<>(Collections.singletonList(prereq)));

        List<EvaluationEvent> events = new ArrayList<>();
        SharedFeatureflipCore core = coreWith(events, child);
        try {
            core.evaluate("child-flag", CONTEXT, true, Boolean.class);

            assertEquals(1, events.size());
            EvaluationEvent event = events.get(0);
            assertEquals(EvaluationReason.PREREQUISITE_FAILED, event.getReason());
            assertEquals("missing-parent", event.getPrerequisiteKey());
            // Off variation is served when a prerequisite fails.
            assertEquals("off", event.getVariationKey());
            assertEquals(Boolean.FALSE, event.getValue());
            assertNull(event.getRuleId());
        } finally {
            core.release();
        }
    }

    @Test
    void inspectorFires_OnFlagNotFound_WithDefaultValue() {
        List<EvaluationEvent> events = new ArrayList<>();
        SharedFeatureflipCore core = coreWith(events);
        try {
            core.evaluate("nope", CONTEXT, true, Boolean.class);

            assertEquals(1, events.size());
            EvaluationEvent event = events.get(0);
            assertEquals("nope", event.getFlagKey());
            assertEquals(EvaluationReason.FLAG_NOT_FOUND, event.getReason());
            assertEquals(Boolean.TRUE, event.getValue());
            assertNull(event.getVariationKey());
            assertNull(event.getRuleId());
            assertNull(event.getPrerequisiteKey());
            assertIso8601(event.getTimestamp());
        } finally {
            core.release();
        }
    }

    @Test
    void inspectorFires_OnError_WithDefaultValue() {
        List<EvaluationEvent> events = new ArrayList<>();
        SharedFeatureflipCore core = coreWith(events, corruptFlag("broken-flag"));
        try {
            EvaluationDetail<Boolean> detail = core.evaluate("broken-flag", CONTEXT, true, Boolean.class);
            assertEquals(EvaluationReason.ERROR, detail.getReason());

            assertEquals(1, events.size());
            EvaluationEvent event = events.get(0);
            assertEquals("broken-flag", event.getFlagKey());
            assertEquals(EvaluationReason.ERROR, event.getReason());
            assertEquals(Boolean.TRUE, event.getValue());
            assertNull(event.getVariationKey());
            assertNull(event.getRuleId());
            assertNull(event.getPrerequisiteKey());
            assertIso8601(event.getTimestamp());
        } finally {
            core.release();
        }
    }

    @Test
    void inspectorFires_OnUndefinedVariation_AsErrorWithNullVariationKey() {
        List<EvaluationEvent> events = new ArrayList<>();
        // Fallthrough names a variation the flag does not define — e.g. a variation
        // deleted from the flag while this serve config still points at it.
        SharedFeatureflipCore core = coreWith(events, boolFlag("ghost-flag", true, "deleted-variation"));
        try {
            EvaluationDetail<Boolean> detail = core.evaluate("ghost-flag", CONTEXT, false, Boolean.class);

            // The caller receives the fail-safe default with reason Error — the
            // served variation is not defined on the flag, so this is not a
            // healthy fallthrough (#1989). The attempted variation key is kept
            // on the detail for diagnostics.
            assertEquals(Boolean.FALSE, detail.getValue());
            assertEquals(EvaluationReason.ERROR, detail.getReason());
            assertEquals("deleted-variation", detail.getVariationKey());

            // The inspector must NOT see that as a healthy "deleted-variation" exposure:
            // the value carried is the caller's default, not that variation's value.
            assertEquals(1, events.size());
            EvaluationEvent event = events.get(0);
            assertEquals("ghost-flag", event.getFlagKey());
            assertEquals(EvaluationReason.ERROR, event.getReason());
            assertNull(event.getVariationKey());
            assertNull(event.getRuleId());
            assertNull(event.getPrerequisiteKey());
            assertEquals(Boolean.FALSE, event.getValue());
            assertIso8601(event.getTimestamp());
        } finally {
            core.release();
        }
    }

    @Test
    void inspectorFires_OnUndefinedVariationFromRule_WithNullRuleId() {
        FlagConfiguration flag = boolFlag("ghost-rule-flag", true, "on");
        TargetingRule rule = new TargetingRule();
        rule.setId("rule-123");
        rule.setPriority(0);
        rule.setConditionGroups(new ArrayList<>());
        rule.setServe(fixedServe("deleted-variation"));
        flag.setRules(new ArrayList<>(Collections.singletonList(rule)));

        List<EvaluationEvent> events = new ArrayList<>();
        SharedFeatureflipCore core = coreWith(events, flag);
        try {
            EvaluationDetail<Boolean> detail = core.evaluate("ghost-rule-flag", CONTEXT, false, Boolean.class);

            assertEquals(Boolean.FALSE, detail.getValue());
            assertEquals(EvaluationReason.ERROR, detail.getReason());
            assertEquals("rule-123", detail.getRuleId());

            assertEquals(1, events.size());
            EvaluationEvent event = events.get(0);
            assertEquals(EvaluationReason.ERROR, event.getReason());
            assertNull(event.getVariationKey());
            assertNull(event.getRuleId());
            assertNull(event.getPrerequisiteKey());
            assertEquals(Boolean.FALSE, event.getValue());
        } finally {
            core.release();
        }
    }

    // ---------------------------------------------------------------------
    // Firing semantics
    // ---------------------------------------------------------------------

    @Test
    void inspectorFires_ExactlyOncePerVariationCall() {
        List<EvaluationEvent> events = new ArrayList<>();
        SharedFeatureflipCore core = coreWith(events, boolFlag("my-flag", true, "on"));
        try {
            core.evaluate("my-flag", CONTEXT, false, Boolean.class);
            core.evaluate("my-flag", CONTEXT, false, Boolean.class);
            core.evaluate("missing", CONTEXT, false, Boolean.class);

            assertEquals(3, events.size());
            assertEquals("my-flag", events.get(0).getFlagKey());
            assertEquals("my-flag", events.get(1).getFlagKey());
            assertEquals("missing", events.get(2).getFlagKey());
        } finally {
            core.release();
        }
    }

    @Test
    void inspectorFires_ThroughPublicVariationMethods() {
        List<EvaluationEvent> events = new ArrayList<>();
        SharedFeatureflipCore core = coreWith(events, boolFlag("my-flag", true, "on"));
        try (FeatureflipClient client = new FeatureflipClient(core)) {
            assertTrue(client.boolVariation("my-flag", CONTEXT, false));

            assertEquals(1, events.size());
            assertEquals("my-flag", events.get(0).getFlagKey());
            assertEquals(Boolean.TRUE, events.get(0).getValue());
        }
    }

    @Test
    void multipleInspectors_AllFire() {
        List<EvaluationEvent> first = new ArrayList<>();
        List<EvaluationEvent> second = new ArrayList<>();
        FeatureFlagConfig config = configWith(Arrays.asList(first::add, second::add));

        SharedFeatureflipCore core = core(config, boolFlag("my-flag", true, "on"));
        try {
            core.evaluate("my-flag", CONTEXT, false, Boolean.class);

            assertEquals(1, first.size());
            assertEquals(1, second.size());
            // Every inspector observes the same event instance.
            assertSame(first.get(0), second.get(0));
        } finally {
            core.release();
        }
    }

    @Test
    void inspectorDoesNotFire_AfterTheCoreIsShutDown() {
        List<EvaluationEvent> events = new ArrayList<>();
        SharedFeatureflipCore core = coreWith(events, boolFlag("my-flag", true, "on"));

        core.evaluate("my-flag", CONTEXT, false, Boolean.class);
        assertEquals(1, events.size());

        core.release();
        assertTrue(core.isShutDown());

        // A closed core still answers — only the notification is suppressed.
        EvaluationDetail<Boolean> detail = core.evaluate("my-flag", CONTEXT, false, Boolean.class);
        assertEquals(Boolean.TRUE, detail.getValue());
        assertEquals(EvaluationReason.FALLTHROUGH, detail.getReason());
        assertEquals(1, events.size());
    }

    @Test
    void inspectorDoesNotFire_AfterTheClientIsClosed() {
        List<EvaluationEvent> events = new ArrayList<>();
        SharedFeatureflipCore core = coreWith(events, boolFlag("my-flag", true, "on"));

        FeatureflipClient client = new FeatureflipClient(core);
        assertTrue(client.boolVariation("my-flag", CONTEXT, false));
        assertEquals(1, events.size());

        client.close();

        // The value the caller gets is unchanged by closing.
        assertTrue(client.boolVariation("my-flag", CONTEXT, false));
        assertEquals(1, events.size());
    }

    // ---------------------------------------------------------------------
    // Isolation and defensive handling
    // ---------------------------------------------------------------------

    @Test
    void throwingInspector_DoesNotChangeValue_AndDoesNotStopSiblings() {
        List<EvaluationEvent> before = new ArrayList<>();
        List<EvaluationEvent> after = new ArrayList<>();
        EvaluationInspector thrower = event -> {
            throw new IllegalStateException("boom");
        };
        FeatureFlagConfig config = configWith(Arrays.asList(before::add, thrower, after::add));

        SharedFeatureflipCore core = core(config, boolFlag("my-flag", true, "on"));
        try {
            EvaluationDetail<Boolean> detail =
                assertDoesNotThrow(() -> core.evaluate("my-flag", CONTEXT, false, Boolean.class));

            assertEquals(Boolean.TRUE, detail.getValue());
            assertEquals(EvaluationReason.FALLTHROUGH, detail.getReason());
            assertEquals(1, before.size());
            assertEquals(1, after.size());
        } finally {
            core.release();
        }
    }

    @Test
    void nullInspectorEntries_AreFilteredOutAtConstruction() {
        List<EvaluationEvent> events = new ArrayList<>();
        List<EvaluationInspector> configured = new ArrayList<>();
        configured.add(null);
        configured.add(events::add);
        configured.add(null);

        FeatureFlagConfig config = configWith(configured);
        assertEquals(1, config.getInspectors().size());

        SharedFeatureflipCore core = core(config, boolFlag("my-flag", true, "on"));
        try {
            assertDoesNotThrow(() -> core.evaluate("my-flag", CONTEXT, false, Boolean.class));
            assertEquals(1, events.size());
        } finally {
            core.release();
        }
    }

    @Test
    void inspectorList_IsCopiedAndUnmodifiable() {
        List<EvaluationInspector> configured = new ArrayList<>();
        configured.add(event -> { });
        FeatureFlagConfig config = configWith(configured);

        // Mutating the caller's list after build() must not affect the config.
        configured.add(event -> { });
        assertEquals(1, config.getInspectors().size());
        assertThrows(UnsupportedOperationException.class,
            () -> config.getInspectors().add(event -> { }));
    }

    @Test
    void noInspectorsConfigured_IsANoOp() {
        FeatureFlagConfig config = FeatureFlagConfig.builder().build();
        assertTrue(config.getInspectors().isEmpty());

        SharedFeatureflipCore core = core(config, boolFlag("my-flag", true, "on"));
        try {
            EvaluationDetail<Boolean> detail =
                assertDoesNotThrow(() -> core.evaluate("my-flag", CONTEXT, false, Boolean.class));
            assertEquals(Boolean.TRUE, detail.getValue());
        } finally {
            core.release();
        }
    }

    @Test
    void nullInspectorList_IsTreatedAsNone() {
        FeatureFlagConfig config = FeatureFlagConfig.builder().inspectors(null).build();
        assertTrue(config.getInspectors().isEmpty());
    }

    @Test
    void eventContext_IsACopyOfTheCallersContext() {
        List<EvaluationEvent> events = new ArrayList<>();
        SharedFeatureflipCore core = coreWith(events, boolFlag("my-flag", true, "on"));
        try {
            EvaluationContext caller = EvaluationContext.builder("copy-user").set("plan", "pro").build();
            core.evaluate("my-flag", caller, false, Boolean.class);

            assertEquals(1, events.size());
            EvaluationContext observed = events.get(0).getContext();
            // A distinct instance carrying the same values: an inspector holding on
            // to (or, in the other SDKs, mutating) the event context cannot reach the
            // caller's object.
            assertNotSame(caller, observed);
            assertEquals("copy-user", observed.getUserId());
            assertEquals("pro", observed.getAttribute("plan"));
            assertEquals("copy-user", caller.getUserId());
            assertEquals("pro", caller.getAttribute("plan"));
        } finally {
            core.release();
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static void assertIso8601(String timestamp) {
        assertNotNull(timestamp);
        assertDoesNotThrow(() -> Instant.parse(timestamp),
            "timestamp '" + timestamp + "' is not an ISO-8601 instant");
    }

    private static FeatureFlagConfig configWith(List<EvaluationInspector> inspectors) {
        return FeatureFlagConfig.builder().inspectors(inspectors).build();
    }

    private static SharedFeatureflipCore coreWith(List<EvaluationEvent> sink, FlagConfiguration... flags) {
        return core(configWith(Collections.<EvaluationInspector>singletonList(sink::add)), flags);
    }

    private static SharedFeatureflipCore core(FeatureFlagConfig config, FlagConfiguration... flags) {
        FlagStore store = new FlagStore();
        store.replace(new ArrayList<>(Arrays.asList(flags)), new ArrayList<>());
        return SharedFeatureflipCore.createForTesting(store, config);
    }

    private static ServeConfig fixedServe(String variation) {
        ServeConfig serve = new ServeConfig();
        serve.setType(ServeType.FIXED);
        serve.setVariation(variation);
        return serve;
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
        flag.setFallthrough(fixedServe(fallthroughVariation));
        flag.setOffVariation("off");

        return flag;
    }

    /**
     * A flag whose rule list is null — the evaluator dereferences it, so evaluation
     * throws internally and the caller gets the ERROR exit path.
     */
    private static FlagConfiguration corruptFlag(String key) {
        FlagConfiguration flag = boolFlag(key, true, "on");
        flag.setRules(null);
        return flag;
    }
}
