package io.featureflip.client.internal;

import java.io.IOException;

/**
 * Thrown when the events endpoint answers a flush with a non-success status.
 *
 * <p>{@code sendEvents} used to log the status and return normally, so the flush path could
 * not tell a rejected batch from a delivered one and discarded both (#2456). The status has
 * to reach the caller because it is what decides whether the batch is kept: only the caller
 * knows there is a queue to put it back into.
 *
 * <p>Extends {@link IOException} — the type {@code sendEvents} already declares — so every
 * existing catch site keeps compiling and behaving as it did.
 */
public final class EventSendException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public EventSendException(int statusCode) {
        super("Events endpoint responded " + statusCode + ".");
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Whether the same batch could succeed if sent again: any 5xx (the production edge
     * answers this endpoint with a 503 at a low constant rate) and 429, where the server is
     * explicitly asking the caller to come back later.
     *
     * <p>Everything else is permanent — 401/403 means the SDK key was rejected, 400 means
     * the body is malformed — and will fail identically next time.
     *
     * @return true when the batch is worth keeping for a later flush
     */
    public boolean isRetryable() {
        return statusCode >= 500 || statusCode == 429;
    }
}
