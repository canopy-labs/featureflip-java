package dev.featureflip.sdk.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class PollingDataSource {
    private static final Logger log = LoggerFactory.getLogger(PollingDataSource.class);

    private final FlagHttpClient httpClient;
    private final FlagStore store;
    private final long pollIntervalMs;
    private final ScheduledExecutorService executor;
    private final Runnable onInitialized;
    private ScheduledFuture<?> task;

    public PollingDataSource(FlagHttpClient httpClient, FlagStore store,
                             long pollIntervalMs, ScheduledExecutorService executor,
                             Runnable onInitialized) {
        this.httpClient = httpClient;
        this.store = store;
        this.pollIntervalMs = pollIntervalMs;
        this.executor = executor;
        this.onInitialized = onInitialized;
    }

    public void start() {
        // Use a short initial delay so the first poll fires quickly (e.g., as a retry
        // if the constructor's initial fetch failed). Subsequent polls use pollIntervalMs.
        long initialDelayMs = Math.min(pollIntervalMs, 1000);
        task = executor.scheduleWithFixedDelay(this::poll, initialDelayMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void close() {
        if (task != null) {
            task.cancel(false);
        }
    }

    private void poll() {
        try {
            var response = httpClient.fetchFlags();
            store.replace(response.getFlags(), response.getSegments());
            log.debug("Polling: updated {} flags", response.getFlags().size());
            if (onInitialized != null) onInitialized.run();
        } catch (Exception e) {
            log.warn("Polling failed: {}", e.getMessage());
        }
    }
}
