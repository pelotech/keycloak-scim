# Bounded Dispatch Queue + Back-Pressure Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the SCIM dispatch pool's unbounded queue with a bounded queue that blocks (back-pressures) the producer instead of dropping tasks, so memory is bounded and worst-case behavior is predictable.

**Architecture:** Extract a `BlockingPolicy` `RejectedExecutionHandler` that blocks the submitting thread on a full bounded queue (never drops), then swap `ScimDispatcher`'s `Executors.newFixedThreadPool` for an explicit `ThreadPoolExecutor` over an `ArrayBlockingQueue` using that policy. Both existing submit paths (`runAsync` post-commit `submit`, `dispatchAsync`'s `CompletableFuture.runAsync`) funnel through `execute()`, so they inherit back-pressure with no call-site changes.

**Tech Stack:** Java 17, `java.util.concurrent` (`ThreadPoolExecutor`, `ArrayBlockingQueue`, `RejectedExecutionHandler`), JBoss Logging, JUnit 5, Testcontainers (Keycloak + WireMock) for the worst-case IT.

**Spec:** `docs/superpowers/specs/2026-06-11-dispatch-backpressure-design.md`

---

## File Structure

- **Create** `src/main/java/sh/libre/scim/core/BlockingPolicy.java` — the back-pressure rejection handler (package-private, one responsibility: block-on-full, never drop, warn-when-blocked, count warnings). Its own file so it is unit-testable in isolation with a tiny real executor.
- **Create** `src/test/java/sh/libre/scim/core/BlockingPolicyTest.java` — unit tests for the handler (blocks/never-drops, warn counter increments, shutdown throws).
- **Modify** `src/main/java/sh/libre/scim/core/ScimDispatcher.java` — swap the executor construction (`:46-68`), add `QUEUE_CAPACITY` / `BLOCK_WARN_MS` constants, extract the thread factory to a field, hold the policy instance, expose `backpressureWarnings()`. Update imports and the field's doc comment.
- **Modify** `src/perfTest/java/sh/libre/scim/perf/DispatchMemoryWorstCaseIT.java` — flip the slow-sink scenario's observations into hard assertions that prove the bound (`:159-206`).
- **Modify** `docs/performance.md` — mark the dispatch-queue fix implemented; document the three config properties.

Single chunk — the change set is small and logically cohesive.

---

## Chunk 1: Back-pressure

### Task 1: `BlockingPolicy` rejection handler

**Files:**
- Create: `src/main/java/sh/libre/scim/core/BlockingPolicy.java`
- Test: `src/test/java/sh/libre/scim/core/BlockingPolicyTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/sh/libre/scim/core/BlockingPolicyTest.java`:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'sh.libre.scim.core.BlockingPolicyTest'`
Expected: FAIL — `BlockingPolicy` does not exist (compilation error).

- [ ] **Step 3: Write the implementation**

Create `src/main/java/sh/libre/scim/core/BlockingPolicy.java`:

```java
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

    BlockingPolicy(int capacity, long warnMs) {
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
                LOGGER.warnf("SCIM dispatch queue full (capacity=%d); producer blocked %d ms "
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'sh.libre.scim.core.BlockingPolicyTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/BlockingPolicy.java \
        src/test/java/sh/libre/scim/core/BlockingPolicyTest.java
git commit -m "feat(dispatch): add BlockingPolicy back-pressure rejection handler"
```

---

### Task 2: Wire the bounded executor into `ScimDispatcher`

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimDispatcher.java` (imports `:3-17`, executor block `:46-68`)

- [ ] **Step 1: Update imports**

In `src/main/java/sh/libre/scim/core/ScimDispatcher.java`, replace the two executor imports:

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
```

with:

```java
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
```

(Keep the existing `import java.util.concurrent.ThreadFactory;` and `import java.util.concurrent.atomic.AtomicInteger;`.)

- [ ] **Step 2: Replace the executor construction**

Replace the current block (`ScimDispatcher.java:46-68` — the `POOL_SIZE` Javadoc, `POOL_SIZE`, and the `Executors.newFixedThreadPool(...)` assignment) with:

```java
    /**
     * Worker pool for {@link #runAsync}. Sized via system property
     * {@code scim.dispatch.threads} (default 8). The pool is JVM-global —
     * shared across all dispatcher instances and Keycloak sessions, with
     * daemon threads so it doesn't block JVM shutdown.
     *
     * <p>Tuning: 8 is a defensible default for "make 10k-user sync tractable
     * against a single SCIM sink" — most SCIM servers tolerate that
     * concurrency; raising it further runs into either the sink's
     * connection limit or the local Apache HttpClient pool size.
     */
    private static final int POOL_SIZE = Integer.getInteger("scim.dispatch.threads", 8);

    /**
     * Bounded buffer between producers and the worker pool. Caps the in-flight
     * backlog — and therefore the dispatch memory footprint — at ~capacity
     * tasks regardless of the sync size N. When full, {@link BlockingPolicy}
     * blocks the producer (back-pressure) instead of growing the queue.
     * Default 256 ≈ 32 deep per worker at the default pool size: deep enough
     * not to starve a worker on a fast sink, shallow enough to keep the memory
     * bound tight. Tunable via {@code scim.dispatch.queueCapacity}.
     */
    private static final int QUEUE_CAPACITY = Integer.getInteger("scim.dispatch.queueCapacity", 256);

    /** How long a producer may stay blocked before each back-pressure WARN. */
    private static final long BLOCK_WARN_MS = Long.getLong("scim.dispatch.blockWarnMs", 10_000L);

    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            var t = new Thread(r, "scim-dispatch-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    };

    private static final BlockingPolicy BACKPRESSURE_POLICY =
        new BlockingPolicy(QUEUE_CAPACITY, BLOCK_WARN_MS);

    private static final ThreadPoolExecutor ASYNC_EXECUTOR = new ThreadPoolExecutor(
        POOL_SIZE, POOL_SIZE,
        0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
        THREAD_FACTORY,
        BACKPRESSURE_POLICY);

    /**
     * Count of back-pressure WARN events emitted by the dispatch pool, for
     * operational visibility (a rising count means a slow/wedged SCIM sink is
     * throttling syncs). Not wired to a metrics endpoint — the per-interval
     * WARN log is the primary operator signal.
     */
    public static long backpressureWarnings() {
        return BACKPRESSURE_POLICY.blockedWarnings();
    }
```

Note: `ASYNC_EXECUTOR`'s type changes from `ExecutorService` to `ThreadPoolExecutor`. Both call sites still compile — `dispatchAsync` passes it to `CompletableFuture.runAsync(task, ASYNC_EXECUTOR)` (an `Executor`), and `runAsync`'s `commit()` calls `ASYNC_EXECUTOR.submit(...)`. Do not change those call sites.

- [ ] **Step 3: Verify it compiles and existing unit tests pass**

Run: `./gradlew test`
Expected: PASS — the existing `ScimLdapStorageMapperTest`, `GroupMembershipPatchTest`, and the new `BlockingPolicyTest` are all green. The executor swap is transparent for the fast-sink unit tests.

- [ ] **Step 4: Verify integration tests still pass**

Run: `./gradlew integrationTest`
Expected: PASS — import/membership/reconciler ITs are unaffected (fast WireMock sink never fills the 256-deep queue, so back-pressure never engages).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimDispatcher.java
git commit -m "feat(dispatch): bound the worker queue and back-pressure producers"
```

---

### Task 3: Prove the bound in the worst-case IT

**Files:**
- Modify: `src/perfTest/java/sh/libre/scim/perf/DispatchMemoryWorstCaseIT.java:159-206` (the `slowSinkWorstCase_10k_200ms` scenario)

**Context:** Before this change the scenario *observed* (and printed) that the import returned in ~4.9 s with ~9,824 of 10,000 ops still queued — the no-back-pressure symptom. After the change the bounded queue (default capacity 256) plus the 8 in-flight workers cap the backlog, and the producer is paced to the sink's ~40 ops/sec drain rate, so the import cannot return until most of the 10k has drained. Turn those observations into hard assertions. The shared Keycloak container runs with the code-default `queueCapacity` (256), so assert against a fixed bound (512) comfortably above `256 + poolSize` and far below N.

- [ ] **Step 1: Update the class Javadoc to the new behavior**

The class Javadoc at `DispatchMemoryWorstCaseIT.java:19-46` describes the *old*
unbounded behavior ("backed by an UNBOUNDED `LinkedBlockingQueue`… with NO
back-pressure… the import finishes before the queue drains"). After this task's
assertions flip, that prose is false. Revise it to describe what the test now
characterizes: the bounded queue (default capacity 256) + `BlockingPolicy` cap
the in-flight backlog and pace the import to the sink's drain rate, and the
slow-sink scenario asserts that bound. Keep the scenario list, but reword
scenario 4 from "does the import finish before the queue drains = no
back-pressure?" to "is the backlog bounded and the import paced to the sink =
back-pressure engaged?".

- [ ] **Step 2: Add the assertions**

In `slowSinkWorstCase_10k_200ms`, immediately after the existing block that computes `backlogAtImportReturn` and puts it into `notes` (just before the `System.out.println("[perf] slow-sink-10k-200ms: ...")` line), insert:

```java
        // --- Back-pressure assertions (the point of this scenario) ---
        // 1) The bounded queue + blocking producer cap the in-flight backlog at
        //    ~queueCapacity (256 default) + the 8 workers, regardless of N.
        //    Before back-pressure this was ~9,824.
        org.junit.jupiter.api.Assertions.assertTrue(backlogAtImportReturn <= 512,
            "expected bounded backlog (<=512) under back-pressure, got " + backlogAtImportReturn);
        // 2) Because the producer is paced to the slow sink, the import (sync
        //    trigger) cannot return until the queue has drained most of the
        //    10k. Its return time therefore reflects the slow drain (~240s),
        //    not the ~4.9s it took with the unbounded queue.
        org.junit.jupiter.api.Assertions.assertTrue(importMillisHolder[0] >= 100_000L,
            "expected the import back-pressured by the slow sink (>=100s), got "
                + importMillisHolder[0] + "ms");
```

- [ ] **Step 3: Run the worst-case scenario**

Run: `./gradlew performanceTest --tests 'sh.libre.scim.perf.DispatchMemoryWorstCaseIT'`
Expected: PASS. The `slow-sink-10k-200ms` line now reports `backlogStillQueued` ≤ ~264 (was 9,824) and `importReturnMs` in the ~200,000+ ms range (was 4,941). The run is slow (~5–9 min) because the slow-sink drain dominates wall-time as before. Capture the printed `[perf]` lines for the docs update in Task 4.

> If `importMillisHolder[0]` comes back small (import still returns fast) or the `await(...)` for 10k posts times out, STOP and investigate before weakening the assertion — that would mean Keycloak's sync is not actually paced by the post-commit back-pressure (e.g. an unexpected sync-level timeout), which is a real finding the spec's verification is meant to surface, not a test bug to paper over. Note the `await().atMost(10, MINUTES)` ceiling at `DispatchMemoryWorstCaseIT.java:185`: the projected ~250 s drain fits within 600 s, but if the effective drain rate is worse than ~40 ops/sec the await (not the assertion) is what fails first — still a real finding, not flakiness.

- [ ] **Step 4: Commit**

```bash
git add src/perfTest/java/sh/libre/scim/perf/DispatchMemoryWorstCaseIT.java
git commit -m "test(dispatch): assert bounded backlog + paced import under slow sink"
```

---

### Task 4: Document the implemented fix

**Files:**
- Modify: `docs/performance.md` (the "Memory & worst-case under load (dispatch queue)" section)

- [ ] **Step 1: Update the docs**

In `docs/performance.md`, under the existing "Memory & worst-case under load (dispatch queue)" section, append a subsection noting the fix is implemented, with the new measured numbers from Task 3 and a config table:

```markdown
### Resolution: bounded queue + back-pressure (implemented)

The dispatch worker pool now uses a bounded `ArrayBlockingQueue`
(`scim.dispatch.queueCapacity`, default 256). When the queue is full,
`BlockingPolicy` blocks the producer (the federation-import post-commit submit,
or the reconciler) until a worker frees a slot — it never drops the task. This
caps the in-flight backlog, and therefore the dispatch memory footprint, at
~capacity tasks regardless of the sync size N.

Worst-case re-measured (slow sink, 200 ms, 10k users) after the change:

| Metric | Unbounded (before) | Bounded + back-pressure (after) |
|---|---|---|
| Backlog still queued at import return | ~9,824 / 10,000 | ≤ ~264 (≈ capacity) |
| Import (sync trigger) return time | ~4.9 s | ~240 s (paced to the sink) |

A slow sink now paces the sync to the sink's drain rate instead of buffering the
whole backlog; a wedged sink blocks the producer (a WARN is logged every
`scim.dispatch.blockWarnMs`, and `ScimDispatcher.backpressureWarnings()` counts
them) and is eventually aborted by Keycloak's own sync timeout — no data loss,
no silent inconsistency. Dropping on timeout was rejected because a dropped
*create* is unrecoverable (the reconciler is delete-only and `replace` will not
recreate without a mapping); see the design spec for detail.

| System property | Default | Meaning |
|---|---|---|
| `scim.dispatch.threads` | 8 | worker pool size |
| `scim.dispatch.queueCapacity` | 256 | bounded buffer; memory cap ≈ capacity × task |
| `scim.dispatch.blockWarnMs` | 10000 | warn each time a producer stays blocked this long |
```

Fill the "after" numbers with the actual values captured in Task 3, Step 2.

- [ ] **Step 2: Commit**

```bash
git add docs/performance.md
git commit -m "docs(perf): document bounded-queue back-pressure resolution"
```

---

## Done criteria

- `BlockingPolicy` unit tests pass (blocks/never-drops, warn counter, shutdown throws).
- Full `test` and `integrationTest` suites green (executor swap transparent for fast sinks).
- `DispatchMemoryWorstCaseIT` slow-sink scenario asserts and proves bounded backlog (≤512) and a back-pressured import (≥100 s).
- `docs/performance.md` reflects the implemented fix with measured numbers and the config knobs.
