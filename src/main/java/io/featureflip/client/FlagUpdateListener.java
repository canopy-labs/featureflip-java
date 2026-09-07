package io.featureflip.client;

import java.util.List;

/**
 * Notified when flag configuration changes, with the keys whose evaluated value
 * may have moved batched into one call per update.
 *
 * <p>The listener runs on the SDK's streaming or polling thread — the one
 * delivering flags — so it must not block: a slow listener delays flag delivery
 * and, on the SSE path, can stall the connection. Hand work off to your own
 * executor if it is not trivial.
 *
 * <p>Register one with
 * {@link FeatureflipClient#onUpdate(FlagUpdateListener)}.
 */
@FunctionalInterface
public interface FlagUpdateListener {

    /**
     * Called with the flag keys whose configuration changed, in ascending order.
     *
     * <p>The list is never empty and never null. An exception thrown from here
     * is logged and swallowed; it does not affect flag delivery or the other
     * listeners.
     *
     * @param flagKeys the changed flag keys, sorted and unmodifiable
     */
    void onUpdate(List<String> flagKeys);
}
