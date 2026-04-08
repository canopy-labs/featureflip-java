package io.featureflip.client;

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
}
