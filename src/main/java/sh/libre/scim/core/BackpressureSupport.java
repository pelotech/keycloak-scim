package sh.libre.scim.core;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.jboss.logging.Logger;

/**
 * Shared back-pressure primitive: block the producer on a full bounded queue,
 * warning periodically, until a slot frees — never dropping. Used by both the
 * dispatch executor's {@link BlockingPolicy} and {@link ScimBulkLane}.
 */
final class BackpressureSupport {

    private static final Logger LOGGER = Logger.getLogger(BackpressureSupport.class);

    private BackpressureSupport() {}

    /**
     * Block until {@code item} is enqueued. Every {@code warnMs} interval still
     * blocked emits a WARN and increments {@code warnings}. After each timed-out
     * offer, {@code stop} is polled; when it returns true this throws
     * {@link RejectedExecutionException} rather than blocking forever (the
     * shutdown signal — an executor's {@code isShutdown}, or the lane's
     * {@code !running}).
     *
     * @throws InterruptedException if the producer thread is interrupted while waiting
     */
    static <T> void blockingPut(BlockingQueue<T> queue, T item, long warnMs,
                                int capacity, AtomicLong warnings, BooleanSupplier stop)
            throws InterruptedException {
        long start = System.nanoTime();
        while (!queue.offer(item, warnMs, TimeUnit.MILLISECONDS)) {
            if (stop.getAsBoolean()) {
                throw new RejectedExecutionException("SCIM dispatch queue stopped while blocked");
            }
            long blockedMs = (System.nanoTime() - start) / 1_000_000L;
            warnings.incrementAndGet();
            LOGGER.warnf("SCIM dispatch queue full (capacity=%d); producer blocked for %d ms so far "
                + "waiting for a worker slot — downstream SCIM sink may be slow or unavailable.",
                capacity, blockedMs);
        }
    }
}
