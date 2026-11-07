package io.featureflip.client;

import java.util.Objects;

/**
 * Where a client's flag data came from at startup, and whether it has since gone live.
 *
 * <ul>
 *   <li>{@link Source#NONE}: there is no data yet, so caller defaults are served.</li>
 *   <li>{@link Source#SNAPSHOT}: the client started from its {@link SnapshotStore}.</li>
 *   <li>{@link Source#LIVE}: a successful fetch from the Evaluation API.</li>
 * </ul>
 *
 * <p>{@link #getReason()} is {@code "snapshot"} for a snapshot start, and
 * {@code "snapshot-expired"} or {@code "snapshot-invalid"} when a stored snapshot was
 * ignored. It is {@code null} once the client is live, or when nothing was stored.
 * {@link #getSnapshotAgeSeconds()} is the age in whole seconds of the snapshot that was
 * used or rejected as expired, otherwise {@code null}.
 */
public final class StartupInfo {
    /** Where the data came from. */
    public enum Source { NONE, SNAPSHOT, LIVE }

    /** Reason reported for a snapshot start. */
    public static final String REASON_SNAPSHOT = "snapshot";
    /** Reason reported when the stored snapshot was older than the configured limit. */
    public static final String REASON_EXPIRED = "snapshot-expired";
    /** Reason reported when the stored text failed a check. */
    public static final String REASON_INVALID = "snapshot-invalid";

    private final Source source;
    private final String reason;
    private final Long snapshotAgeSeconds;

    StartupInfo(Source source, String reason, Long snapshotAgeSeconds) {
        this.source = Objects.requireNonNull(source, "source");
        this.reason = reason;
        this.snapshotAgeSeconds = snapshotAgeSeconds;
    }

    static StartupInfo live() { return new StartupInfo(Source.LIVE, null, null); }

    static StartupInfo none() { return new StartupInfo(Source.NONE, null, null); }

    public Source getSource() { return source; }

    /** The reason, or {@code null}. */
    public String getReason() { return reason; }

    /** Age in whole seconds, or {@code null}. */
    public Long getSnapshotAgeSeconds() { return snapshotAgeSeconds; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StartupInfo)) return false;
        StartupInfo other = (StartupInfo) o;
        return source == other.source
            && Objects.equals(reason, other.reason)
            && Objects.equals(snapshotAgeSeconds, other.snapshotAgeSeconds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, reason, snapshotAgeSeconds);
    }

    @Override
    public String toString() {
        return "StartupInfo{source=" + source + ", reason=" + reason
            + ", snapshotAgeSeconds=" + snapshotAgeSeconds + "}";
    }
}
