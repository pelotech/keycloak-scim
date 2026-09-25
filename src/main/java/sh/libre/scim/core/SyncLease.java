package sh.libre.scim.core;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.jboss.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(SyncLease.class);

    private final LeaseStore store;
    private final String componentId;
    private final Clock clock;
    /** Token of this run. Per run, not per node, so two runs on one node never share a lease. */
    private final String token = UUID.randomUUID().toString();

    SyncLease(LeaseStore store, String componentId, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.componentId = Objects.requireNonNull(componentId);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The token this run carries. A run's log lines name it; no node name is needed. */
    String token() {
        return token;
    }

    /**
     * Takes the lease or refuses it. Two short transactions: first ensure the
     * row exists, then lock it and decide.
     *
     * <p>The ensure step is lenient. A duplicate row is the common case, and
     * the failure surfaces at commit, wrapped, so telling it apart from a
     * real fault would be guesswork. The lock step reads the truth: a row
     * still absent after both steps means the insert was refused for another
     * reason, and that is the failure this method reports.
     *
     * @throws IllegalStateException when no row exists after the ensure step
     */
    Decision acquire() {
        RuntimeException ensureFailure = null;
        try {
            store.ensureRow(componentId);
        } catch (RuntimeException e) {
            ensureFailure = e;
            LOGGER.debugf(e, "Sync lease row for component %s was not inserted; it may already exist", componentId);
        }
        final RuntimeException ensureCause = ensureFailure;
        return store.inOneTransaction(locked -> {
            var row = locked.lockAndRead(componentId).orElseThrow(() -> new IllegalStateException(
                "no sync lease row for component " + componentId
                    + (ensureCause == null ? "" : ": " + ensureCause.getMessage()), ensureCause));
            long now = clock.millis();
            var decision = decide(row.holder(), row.renewedAt(), now);
            if (decision == Decision.TAKE) {
                locked.take(componentId, token, now);
                lastRenewal = now;
            } else {
                long age = row.renewedAt() == null ? -1 : now - row.renewedAt();
                LOGGER.warnf("Sync of component %s refused: run %s holds the lease, last renewed %d ms ago",
                    componentId, row.holder(), age);
            }
            return decision;
        });
    }

    /** Set at acquisition, so the self-fence does not trip at the first check. */
    private volatile long lastRenewal;

    /**
     * Stops the heartbeat, then clears the holder if this run still holds it.
     * The order matters: a tick after the clear would see no holder and log
     * a false loss. A failure to clear is logged and swallowed, because the
     * heartbeat has already stopped, so the lease goes stale on its own.
     */
    void release() {
        stopHeartbeat();
        try {
            int matched = store.release(componentId, token);
            if (matched == 0) {
                LOGGER.warnf("Sync lease for component %s was not released: another run holds it", componentId);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Sync lease for component %s could not be released; it will go stale", componentId);
        }
    }

    // Replaced in the next task, which adds the heartbeat thread this stub stands in for.
    void stopHeartbeat() {
        /* no heartbeat yet */
    }
}
