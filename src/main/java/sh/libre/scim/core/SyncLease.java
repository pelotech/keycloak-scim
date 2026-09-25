package sh.libre.scim.core;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    private static final Logger LOGGER = Logger.getLogger(SyncLease.class);

    /** How often the holder renews. */
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    /** Age of the last renewal past which any node may take the lease. */
    static final Duration STALE_THRESHOLD = Duration.ofSeconds(120);
    /** Age of the holder's own last success past which it stops itself. */
    static final Duration SELF_FENCE_THRESHOLD = Duration.ofSeconds(90);
    /** Renewal stops when the run has reported no progress for this long. */
    static final Duration PROGRESS_WINDOW = Duration.ofMinutes(10);
    /**
     * How long a stop waits for a tick in flight. One tick is one short
     * transaction, so this bounds a tick stuck on the pool. After it,
     * release clears the holder anyway, and a late tick can only match
     * nothing.
     */
    static final Duration STOP_WAIT = Duration.ofSeconds(10);

    enum Decision { TAKE, REFUSE }

    /** What the lock transaction decided, and the clock it wrote to the row. */
    private record Outcome(Decision decision, long now) { }

    private final LeaseStore store;
    private final String componentId;
    private final Clock clock;
    /** Token of this run. Per run, not per node, so two runs on one node never share a lease. */
    private final String token = UUID.randomUUID().toString();
    /**
     * This run's last successful renewal. Set after acquisition, so the
     * self-fence does not trip at the first check; zero until then. Written
     * by the run thread and by the heartbeat, read by both, so volatile.
     */
    private volatile long lastRenewal;
    /** Set after acquisition and on every report, so renewal starts open. Read by the heartbeat, so volatile. */
    private volatile long lastProgress;
    /** Set by a tick that matched nothing. Read by the heartbeat and by the run thread, so volatile. */
    private volatile boolean lostFlag;
    /** Non-null while the heartbeat runs. Atomic, so a stop from any thread takes the one scheduler. */
    private final AtomicReference<ScheduledExecutorService> heartbeat = new AtomicReference<>();

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

    /**
     * Takes the lease or refuses it. Two short transactions: first ensure the
     * row exists, then lock it and decide.
     *
     * <p>The ensure step is lenient. A duplicate row is the common case, and
     * the failure surfaces at commit, wrapped, so it cannot be told apart
     * from a real fault. The lock step reads the row under a lock. A row
     * still absent after both steps means the insert failed for a reason
     * other than a duplicate, and that failure is what this method reports.
     * Whatever the lock transaction itself throws also propagates from this
     * method, for example a failure to reach the database.
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
        Outcome outcome = store.inOneTransaction(locked -> {
            var row = locked.lockAndRead(componentId).orElseThrow(() -> new IllegalStateException(
                "no sync lease row for component " + componentId
                    + (ensureCause == null ? "" : ": " + ensureCause.getMessage()), ensureCause));
            long now = clock.millis();
            var d = decide(row.holder(), row.renewedAt(), now);
            if (d == Decision.TAKE) {
                locked.take(componentId, token, now);
            } else {
                long ageSeconds = (now - row.renewedAt()) / 1000;
                LOGGER.warnf("Sync of component %s refused: run %s holds the lease, last renewed %d s ago",
                    componentId, row.holder(), ageSeconds);
            }
            return new Outcome(d, now);
        });
        if (outcome.decision() == Decision.TAKE) {
            // Set only after the transaction commits, and to the clock the row
            // holds: run state must not lead the database.
            lastRenewal = outcome.now();
            lastProgress = outcome.now();
            LOGGER.infof("Sync lease for component %s taken by run %s", componentId, token);
        }
        return outcome.decision();
    }

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
                LOGGER.warnf("Sync lease for component %s was not released: this run no longer holds it", componentId);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Sync lease for component %s could not be released; it will go stale", componentId);
        }
    }

    /**
     * Whether this run still holds the lease. True when a tick matched
     * nothing, because another run took the lease. Also true when this run's
     * own last successful renewal is older than the self-fence threshold,
     * whatever the flag says. A holder that cannot reach the database never
     * sees a matched-nothing update, so the flag alone would leave the
     * overlap after a takeover unbounded. The self-fence bounds it. A run
     * that never took the lease has nothing to hold, so it is lost as well.
     */
    boolean lost() {
        if (lostFlag || lastRenewal == 0) {
            return true;
        }
        // Strict, like the decision: an age equal to the threshold is not past it.
        return clock.millis() - lastRenewal > SELF_FENCE_THRESHOLD.toMillis();
    }

    /**
     * The run reports progress for every resource it examines, whatever the
     * outcome. A run that fails users is still alive. Renewal is gated on
     * these reports, so a run hung in a call that never returns loses its
     * lease and another node can sync.
     */
    void reportProgress() {
        lastProgress = clock.millis();
    }

    /**
     * One heartbeat. Renews unless the run has made no progress for the
     * progress window. A matched-nothing renewal means another run took the
     * lease, so it sets the lost flag. A thrown renewal is not a loss: the
     * database may be away for a moment and the row may still name this
     * run, so the next tick tries again. The self-fence covers the case
     * where the throws go on for too long. Catches every throwable, because
     * a throw would cancel the schedule and a driver can throw an Error.
     */
    void tick() {
        if (lostFlag) {
            return; // the loss is logged once, not on every tick
        }
        try {
            long now = clock.millis();
            // One read: a second could straddle a report and log a nonsense age.
            long idle = now - lastProgress;
            if (idle > PROGRESS_WINDOW.toMillis()) {
                // Logged on every gated tick on purpose: it is the liveness signal for a hung run.
                LOGGER.warnf("Sync lease for component %s not renewed: no progress for %d s; "
                    + "another node may take it", componentId, idle / 1000);
                return;
            }
            int matched = store.renew(componentId, token, now);
            if (matched == 0) {
                LOGGER.errorf("Sync lease for component %s was taken by another run; this run will stop",
                    componentId);
                lostFlag = true;
                return;
            }
            lastRenewal = now;
        } catch (Throwable t) {
            LOGGER.warnf(t, "Sync lease heartbeat for component %s failed; will retry", componentId);
        }
    }

    /**
     * Starts the heartbeat on this run's own daemon thread. Call after the
     * acquisition transaction has committed. Not the shared timer thread: a
     * scheduled sync runs on that thread, so a heartbeat there would never
     * fire during a scheduled sync.
     *
     * @throws IllegalStateException when a heartbeat is already running; a
     *     second start would silently drop the first scheduler and its thread
     */
    void startHeartbeat() {
        var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "scim-sync-lease-" + componentId);
            t.setDaemon(true); // must not keep the JVM alive
            return t;
        });
        if (!heartbeat.compareAndSet(null, scheduler)) {
            scheduler.shutdown(); // nothing scheduled yet, so this only frees the thread
            throw new IllegalStateException("sync lease heartbeat for component " + componentId + " already running");
        }
        long interval = HEARTBEAT_INTERVAL.toMillis();
        scheduler.scheduleAtFixedRate(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the heartbeat and waits a bounded time for a tick in flight, so
     * it cannot log a false loss after the run. {@code shutdown}, not
     * {@code shutdownNow}: an interrupted tick inside a database call would
     * throw, log a false retry, and could leave a pool connection broken. A
     * scheduled executor drops the pending periodic task on shutdown and
     * lets the running one finish. A tick stuck on the database must not
     * hold the run, so after the wait {@code release} clears the holder
     * anyway. Safe to call before a start and more than once, from any
     * thread: the swap takes the scheduler exactly once, so no caller can
     * read a stale null and leak the thread.
     */
    void stopHeartbeat() {
        var scheduler = heartbeat.getAndSet(null);
        if (scheduler == null) {
            return;
        }
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
