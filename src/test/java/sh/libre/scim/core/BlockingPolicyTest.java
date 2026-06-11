package sh.libre.scim.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockingPolicyTest {

    /** pool=1, queue=1, so the executor saturates after one running + one queued task. */
    private ThreadPoolExecutor newSaturableExecutor(BlockingPolicy policy) {
        return new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            policy);
    }

    /** Spin until the executor's queue holds at least {@code size} tasks (or fail after 2s). */
    private void awaitQueueSize(ThreadPoolExecutor executor, int size) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (executor.getQueue().size() < size) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("queue never reached size " + size
                    + " (was " + executor.getQueue().size() + ")");
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void blocksProducerWhenFullThenRunsTaskNeverDropping() throws Exception {
        var policy = new BlockingPolicy(1, 10_000L);
        var executor = newSaturableExecutor(policy);
        var ran = new AtomicInteger();

        // Occupy the single worker until we release it.
        var release = new CountDownLatch(1);
        var workerStarted = new CountDownLatch(1);
        executor.execute(() -> {
            workerStarted.countDown();
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            ran.incrementAndGet();
        });
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS), "worker should start");

        // Fill the queue (capacity 1).
        executor.execute(ran::incrementAndGet);

        // Determinism gate: the worker is parked on `release`, so it cannot pull
        // the filler task — wait until it is actually sitting in the queue before
        // testing the 3rd submit. Guards the "started != parked" race where the
        // worker hasn't yet reached release.await() and could steal the filler,
        // leaving the queue empty so the 3rd submit wouldn't block.
        awaitQueueSize(executor, 1);

        // Third submit must BLOCK in the policy (queue full, worker busy).
        var submitReturned = new CountDownLatch(1);
        var submitter = new Thread(() -> {
            executor.execute(ran::incrementAndGet);
            submitReturned.countDown();
        });
        submitter.start();

        // It should still be blocked after a short wait — not dropped, not returned.
        assertFalse(submitReturned.await(500, TimeUnit.MILLISECONDS),
            "third submit should block while the queue is full");

        // Release the worker: queue drains, the blocked submit enqueues, all three run.
        release.countDown();
        assertTrue(submitReturned.await(5, TimeUnit.SECONDS), "blocked submit should unblock");
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "executor should drain");
        assertEquals(3, ran.get(), "every task must run — none dropped");
    }

    @Test
    void incrementsWarningCounterWhileBlocked() throws Exception {
        var policy = new BlockingPolicy(1, 50L); // warn every 50ms while blocked
        var executor = newSaturableExecutor(policy);

        var release = new CountDownLatch(1);
        var workerStarted = new CountDownLatch(1);
        executor.execute(() -> {
            workerStarted.countDown();
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
        executor.execute(() -> {}); // fill queue
        awaitQueueSize(executor, 1); // same parked-worker gate as the blocking test

        var submitter = new Thread(() -> executor.execute(() -> {}));
        submitter.start();

        // Blocked ~250ms with a 50ms warn interval => at least one warning.
        // (Best-effort timing: 5x margin makes a total miss very unlikely.)
        Thread.sleep(250);
        assertTrue(policy.blockedWarnings() >= 1,
            "expected at least one back-pressure warning, got " + policy.blockedWarnings());

        release.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void throwsWhenExecutorAlreadyShutDown() {
        var policy = new BlockingPolicy(1, 10_000L);
        var executor = newSaturableExecutor(policy);
        executor.shutdown();

        // Calling the handler directly models a rejection arriving after shutdown.
        assertThrows(RejectedExecutionException.class,
            () -> policy.rejectedExecution(() -> {}, executor));
    }
}
