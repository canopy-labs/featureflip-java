package io.featureflip.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.featureflip.client.internal.FlagStore;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.FlagType;
import io.featureflip.client.internal.model.ServeConfig;
import io.featureflip.client.internal.model.ServeType;
import io.featureflip.client.internal.model.Variation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The handle-level half of flag-update subscriptions: what a caller registers,
 * and what happens to it when the caller lets go of the client.
 */
class FlagUpdateSubscriptionTest {

    private static FlagConfiguration boolFlag(String key, int version, JsonNode value) {
        FlagConfiguration flag = new FlagConfiguration();
        flag.setKey(key);
        flag.setVersion(version);
        flag.setType(FlagType.BOOLEAN);
        flag.setEnabled(true);

        Variation variation = new Variation();
        variation.setKey("on");
        variation.setValue(value);
        flag.setVariations(new ArrayList<>(Collections.singletonList(variation)));

        ServeConfig fallthrough = new ServeConfig();
        fallthrough.setType(ServeType.FIXED);
        fallthrough.setVariation("on");
        flag.setFallthrough(fallthrough);
        flag.setOffVariation("on");
        return flag;
    }

    @Test
    void aRegisteredListenerSeesLaterUpdates() {
        FlagStore store = new FlagStore();
        store.replace(List.of(boolFlag("gate", 1, BooleanNode.TRUE)), Collections.emptyList());
        List<List<String>> seen = new CopyOnWriteArrayList<>();

        try (FeatureflipClient client = new FeatureflipClient(SharedFeatureflipCore.createForTesting(store))) {
            client.onUpdate(seen::add);
            store.replace(List.of(boolFlag("gate", 2, BooleanNode.FALSE)), Collections.emptyList());
        }

        assertThat(seen).containsExactly(List.of("gate"));
    }

    @Test
    void theInitialFlagLoadDoesNotFire() {
        // A cold start is not a change. The load happens before a caller can hold the
        // client, so there is nobody to tell — but a listener registered afterwards
        // must not be handed the whole catalogue on the next identical snapshot
        // either.
        FlagStore store = new FlagStore();
        List<List<String>> seen = new CopyOnWriteArrayList<>();

        try (FeatureflipClient client = new FeatureflipClient(SharedFeatureflipCore.createForTesting(store))) {
            client.onUpdate(seen::add);
            store.replace(List.of(boolFlag("gate", 1, BooleanNode.TRUE)), Collections.emptyList());
            seen.clear();
            store.replace(List.of(boolFlag("gate", 1, BooleanNode.TRUE)), Collections.emptyList());
        }

        assertThat(seen).isEmpty();
    }

    @Test
    void theReturnedActionUnsubscribesAndIsIdempotent() {
        FlagStore store = new FlagStore();
        List<List<String>> seen = new CopyOnWriteArrayList<>();

        try (FeatureflipClient client = new FeatureflipClient(SharedFeatureflipCore.createForTesting(store))) {
            Runnable unsubscribe = client.onUpdate(seen::add);
            unsubscribe.run();
            unsubscribe.run();
            store.replace(List.of(boolFlag("gate", 1, BooleanNode.TRUE)), Collections.emptyList());
        }

        assertThat(seen).isEmpty();
    }

    @Test
    void closingTheClientDropsItsSubscriptions() {
        // The core outlives this handle here — a second handle holds it — and its
        // data sources keep running, so a listener left registered would go on firing
        // for a client the caller has already closed.
        FlagStore store = new FlagStore();
        SharedFeatureflipCore core = SharedFeatureflipCore.createForTesting(store);
        core.tryAcquire(); // a second handle, so close() below does not shut the core down
        List<List<String>> seen = new CopyOnWriteArrayList<>();

        FeatureflipClient client = new FeatureflipClient(core);
        client.onUpdate(seen::add);
        client.close();

        store.replace(List.of(boolFlag("gate", 1, BooleanNode.TRUE)), Collections.emptyList());

        assertThat(seen).isEmpty();
        core.release();
    }

    @Test
    void subscribingOnAClosedClientIsANoOp() {
        FlagStore store = new FlagStore();
        SharedFeatureflipCore core = SharedFeatureflipCore.createForTesting(store);
        core.tryAcquire();
        List<List<String>> seen = new CopyOnWriteArrayList<>();

        FeatureflipClient client = new FeatureflipClient(core);
        client.close();
        Runnable unsubscribe = client.onUpdate(seen::add);
        unsubscribe.run(); // must not throw

        store.replace(List.of(boolFlag("gate", 1, BooleanNode.TRUE)), Collections.emptyList());

        assertThat(seen).isEmpty();
        core.release();
    }

    @Test
    void aNullListenerIsIgnored() {
        FlagStore store = new FlagStore();
        try (FeatureflipClient client = new FeatureflipClient(SharedFeatureflipCore.createForTesting(store))) {
            client.onUpdate(null).run(); // must not throw
        }
    }

    @Test
    void aStubClientAcceptsAListenerAndNeverFires() {
        // forTesting() serves fixed values with no store behind them, so there is
        // nothing that could report a change. Code under test should not have to know
        // which kind of client it was handed, so subscribing has to be callable.
        List<List<String>> seen = new CopyOnWriteArrayList<>();

        try (FeatureflipClient client = FeatureflipClient.forTesting(Map.of("gate", true))) {
            Runnable unsubscribe = client.onUpdate(seen::add);
            unsubscribe.run();
        }

        assertThat(seen).isEmpty();
    }

    @Test
    void twoListenersOnTheSameClientBothFire() {
        FlagStore store = new FlagStore();
        List<List<String>> first = new CopyOnWriteArrayList<>();
        List<List<String>> second = new CopyOnWriteArrayList<>();

        try (FeatureflipClient client = new FeatureflipClient(SharedFeatureflipCore.createForTesting(store))) {
            client.onUpdate(first::add);
            client.onUpdate(second::add);
            store.replace(List.of(boolFlag("gate", 1, BooleanNode.TRUE)), Collections.emptyList());
        }

        assertThat(first).containsExactly(List.of("gate"));
        assertThat(second).containsExactly(List.of("gate"));
    }
}
