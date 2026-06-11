package sh.libre.scim.core;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.logging.Logger;

/**
 * A {@link RejectedExecutionHandler} that applies back-pressure: when the
 * executor's bounded queue is full, it blocks the submitting (producer) thread
 * until a worker frees a slot, instead of dropping the task or growing an
 * unbounded queue. The task is never dropped.
 *
 * <p>This is the mechanism that bounds the SCIM dispatch pool's memory
 * footprint. A slow downstream SCIM sink paces the producer (the federation
 * import's post-commit submit, or the reconciler) to the sink's drain rate,
 * rather than letting the in-flight backlog — and Keycloak's heap — grow with
 * the sync size N.
 *
 * <p>While blocked, it logs a WARN every {@code warnMs} (and counts it via
 * {@link #blockedWarnings()}) so a prolonged block behind a wedged sink is
 * visible to operators rather than appearing as a silent slow sync. On
 * executor shutdown it throws {@link RejectedExecutionException} rather than
 * blocking forever during JVM teardown.
 */
final class BlockingPolicy implements RejectedExecutionHandler {

    private static final Logger LOGGER = Logger.getLogger(BlockingPolicy.class);

    private final int capacity;
    private final long warnMs;
    private final AtomicLong blockedWarnings = new AtomicLong();

    /**
     * @param capacity the bounded-queue depth, echoed in the WARN log; must match
     *                 the {@link java.util.concurrent.ArrayBlockingQueue} capacity
     *                 given to the executor this policy is registered with.
     * @param warnMs   back-pressure warning interval in milliseconds — a WARN is
     *                 logged and {@link #blockedWarnings()} incremented each time a
     *                 blocked producer waits this long. Must be positive; a
     *                 non-positive value would turn {@code offer} into a busy-spin.
     */
    BlockingPolicy(int capacity, long warnMs) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        if (warnMs <= 0) {
            throw new IllegalArgumentException("warnMs must be positive: " + warnMs);
        }
        this.capacity = capacity;
        this.warnMs = warnMs;
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("SCIM dispatch pool is shut down");
        }
        long blockedStartNanos = System.nanoTime();
        boolean queued = false;
        while (!queued) {
            try {
                queued = executor.getQueue().offer(r, warnMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("interrupted while applying SCIM back-pressure", e);
            }
            if (!queued) {
                if (executor.isShutdown()) {
                    // Shutdown raced in while we were parked; workers have
                    // stopped polling, so the queue will never drain.
                    throw new RejectedExecutionException("SCIM dispatch pool shut down while blocked");
                }
                long blockedMs = (System.nanoTime() - blockedStartNanos) / 1_000_000L;
                blockedWarnings.incrementAndGet();
                LOGGER.warnf("SCIM dispatch queue full (capacity=%d); producer blocked for %d ms so far "
                    + "waiting for a worker slot — downstream SCIM sink may be slow or unavailable.",
                    capacity, blockedMs);
            }
        }
    }

    /** Count of back-pressure WARN events emitted (one per {@code warnMs} interval blocked). */
    long blockedWarnings() {
        return blockedWarnings.get();
    }
}
