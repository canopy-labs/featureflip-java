package io.featureflip.client;

/**
 * Pluggable persistence for the last good flag configuration, so a process that starts
 * while the Evaluation API is unreachable can serve its last-known values instead of
 * every default.
 *
 * <p>The store is opaque text. The SDK owns the stored text end to end (its envelope, the
 * SDK key and base URL check, the age check and every startup reason), so an
 * implementation only has to hand back exactly what it was last given. One instance may
 * safely be given to several SDK keys: the SDK rejects text written for another key or
 * base URL itself.
 *
 * <p>Both methods may be called from a background thread. A store that throws is
 * handled: a failing {@link #get()} is a miss and a failing {@link #put(String)} is a
 * dropped write, each with a logged warning. Exceptions never reach initialization or
 * evaluation.
 *
 * <p>A skeleton for a Redis-style store:
 *
 * <pre>{@code
 * public final class RedisSnapshotStore implements SnapshotStore {
 *     private final JedisPool pool;
 *
 *     public String get() {
 *         try (Jedis jedis = pool.getResource()) {
 *             return jedis.get("featureflip:snapshot");
 *         }
 *     }
 *
 *     public void put(String text) {
 *         try (Jedis jedis = pool.getResource()) {
 *             jedis.set("featureflip:snapshot", text);
 *         }
 *     }
 * }
 * }</pre>
 */
public interface SnapshotStore {
    /**
     * Returns the text last stored, or {@code null} if nothing is stored.
     *
     * <p>Called only after the initial fetch fails or times out, and bounded by the
     * configured init timeout: a read still running at the deadline is abandoned.
     */
    String get();

    /** Stores the text, replacing any previous value. Called from a background thread. */
    void put(String text);
}
