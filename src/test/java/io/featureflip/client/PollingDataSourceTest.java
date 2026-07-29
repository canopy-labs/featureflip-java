package io.featureflip.client;

import io.featureflip.client.internal.FlagHttpClient;
import io.featureflip.client.internal.FlagStore;
import io.featureflip.client.internal.PollingDataSource;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PollingDataSourceTest {
    private MockWebServer server;
    private FlagStore store;
    private ScheduledExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new FlagStore();
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        server.close();
    }

    private PollingDataSource pollerFor(String body, Runnable onInitialized) {
        server.enqueue(new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build());

        FeatureFlagConfig config = FeatureFlagConfig.builder()
            .baseUrl(server.url("/").toString())
            .build();
        FlagHttpClient httpClient = new FlagHttpClient("test-sdk-key", config);

        return new PollingDataSource(httpClient, store, 60_000, executor, onInitialized);
    }

    @Test
    void signalsInitializedWhenPayloadSendsNullFlags() throws Exception {
        // A `"flags": null` payload must degrade to an empty snapshot, not break the
        // poll. The NPE previously thrown by the `.size()` log line was swallowed by
        // poll()'s catch AFTER the store was replaced but BEFORE onInitialized ran —
        // so the client hung in awaitInitialization() and re-failed every poll.
        var initialized = new CountDownLatch(1);
        PollingDataSource poller = pollerFor(
            "{\"environment\":\"prod\",\"version\":1,\"flags\":null,\"segments\":null}",
            initialized::countDown);

        poller.start();

        assertThat(initialized.await(5, TimeUnit.SECONDS))
            .as("poller must signal initialization even when the payload sends null flags")
            .isTrue();
        assertThat(store.getAllFlags()).isEmpty();

        poller.close();
    }

    @Test
    void signalsInitializedOnNormalPayload() throws Exception {
        var initialized = new CountDownLatch(1);
        PollingDataSource poller = pollerFor(
            "{\"environment\":\"prod\",\"version\":1,\"flags\":[],\"segments\":[]}",
            initialized::countDown);

        poller.start();

        assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();

        poller.close();
    }
}
