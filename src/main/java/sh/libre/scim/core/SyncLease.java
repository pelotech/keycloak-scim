package sh.libre.scim.core;

import java.time.Duration;

/**
 * The lease a sync run holds on its component. One run per component at a
 * time, across cluster nodes.
 *
 * <p>The rules and the reasons for them are in the design record. In short: a
 * run takes the lease under a row lock, a heartbeat renews it, a holder that
 * cannot renew stops itself, a holder that makes no progress stops renewing,
 * and a lease whose renewal is old belongs to nobody.
 */
final class SyncLease {

    /** How often the holder renews. */
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    /** Age of the last renewal past which any node may take the lease. */
    static final Duration STALE_THRESHOLD = Duration.ofSeconds(120);
    /** Age of the holder's own last success past which it stops itself. */
    static final Duration SELF_FENCE_THRESHOLD = Duration.ofSeconds(90);
    /** Renewal stops when the run has reported no progress for this long. */
    static final Duration PROGRESS_WINDOW = Duration.ofMinutes(10);

    enum Decision { TAKE, REFUSE }

    /**
     * Whether a run may take the lease described by the row. Pure, so the
     * rule is testable without a database.
     *
     * @param holder the row's holder, or null
     * @param renewedAt the row's last renewal in epoch milliseconds, or null
     * @param now the caller's clock in epoch milliseconds
     */
    static Decision decide(String holder, Long renewedAt, long now) {
        if (holder == null || renewedAt == null) {
            return Decision.TAKE;
        }
        // Strict: an age equal to the threshold is not past it.
        return now - renewedAt > STALE_THRESHOLD.toMillis() ? Decision.TAKE : Decision.REFUSE;
    }

    private SyncLease() {}
}
