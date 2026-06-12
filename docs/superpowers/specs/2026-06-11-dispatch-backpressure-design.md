# Bounded Dispatch Queue + Back-Pressure — Design

**Status:** Approved (design phase)
**Date:** 2026-06-11

## Problem

The async SCIM dispatch worker pool is created with
`Executors.newFixedThreadPool(8)` (`ScimDispatcher.java:58`), which is backed by
an **unbounded** `LinkedBlockingQueue`. Keycloak's federation import enlists one
SCIM operation per user as a post-commit task (`runAsync` →
`enlistAfterCompletion` → `commit()` → `ASYNC_EXECUTOR.submit(...)`,
`ScimDispatcher.java:160-188`), and it does so far faster than 8 workers can
drain. The queue absorbs the entire backlog with **no back-pressure**.

Measured worst case (`DispatchMemoryWorstCaseIT`, slow sink 200 ms, 10k users):

- The import (`triggerFullSync`) **returns in 4.9 s with 9,824 of 10,000 SCIM
  POSTs still queued**; the queue then drains over **264 s**.
- Peak Keycloak container memory scales with N: fast-sink **742 MiB at 1k →
  1143 MiB at 10k**; the plugin's delta over Keycloak-alone is ~105 MiB (fast)
  to ~181 MiB (slow), and it grows with the in-flight backlog.

So the current model's memory footprint is **unbounded and proportional to sync
size**, and a slow/unavailable downstream produces a heap spike rather than
throttling the producer. This violates the project's stated priority that a
**predictable (bounded) memory footprint and predictable worst-case behavior**
matter at least as much as throughput.

## Goal

Bound the dispatch queue and apply back-pressure so that:

- **Memory is bounded** — capped at `queueCapacity × task-closure-size`,
  independent of sync size N.
- **Worst-case is predictable** — a slow sink paces the import to the sink's
  drain rate; a wedged sink stalls the sync visibly (and Keycloak's own sync
  transaction timeout aborts it) **without losing data or creating silent
  inconsistency**.

Non-goal: increasing throughput. Back-pressure caps the sync rate at the sink's
drain rate by design; raising that ceiling is the separate `/Bulk` question
(see "Relationship to SCIM /Bulk").

## Design

### Mechanism

Replace the unbounded-queue factory with an explicit bounded `ThreadPoolExecutor`
and a blocking rejection handler:

```java
private static final int POOL_SIZE      = Integer.getInteger("scim.dispatch.threads", 8);
private static final int QUEUE_CAPACITY = Integer.getInteger("scim.dispatch.queueCapacity", 256);
private static final long BLOCK_WARN_MS = Long.getLong("scim.dispatch.blockWarnMs", 10_000L);

private static final ThreadPoolExecutor ASYNC_EXECUTOR = new ThreadPoolExecutor(
    POOL_SIZE, POOL_SIZE,
    0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(QUEUE_CAPACITY),
    threadFactory,
    new BlockingPolicy(BLOCK_WARN_MS));
```

`BlockingPolicy` is a `RejectedExecutionHandler` that **blocks the submitting
(producer) thread until a worker frees a slot — the task is never dropped**:

```java
static final class BlockingPolicy implements RejectedExecutionHandler {
    private final long warnMs;
    private static final AtomicLong BLOCKED_WARNINGS = new AtomicLong();

    BlockingPolicy(long warnMs) { this.warnMs = warnMs; }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            // JVM teardown — do not block forever.
            throw new RejectedExecutionException("SCIM dispatch pool is shut down");
        }
        long blockedStartNanos = System.nanoTime();
        boolean queued = false;
        while (!queued) {
            try {
                queued = executor.getQueue().offer(r, warnMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("interrupted applying SCIM back-pressure", e);
            }
            if (!queued) {
                if (executor.isShutdown()) {
                    // Shutdown raced in after we passed the initial guard and
                    // while we were parked in offer(). Workers have stopped
                    // polling, so the queue will never drain — stop spinning.
                    throw new RejectedExecutionException("SCIM dispatch pool shut down while blocked");
                }
                long blockedMs = (System.nanoTime() - blockedStartNanos) / 1_000_000L;
                BLOCKED_WARNINGS.incrementAndGet();
                LOGGER.warnf("SCIM dispatch queue full (capacity=%d); producer blocked %d ms "
                    + "waiting for a worker slot — downstream SCIM sink may be slow or unavailable.",
                    QUEUE_CAPACITY, blockedMs);
            }
        }
    }

    static long blockedWarnings() { return BLOCKED_WARNINGS.get(); }
}
```

Both submit paths funnel through `ThreadPoolExecutor.execute()` — `runAsync`'s
post-commit `ASYNC_EXECUTOR.submit(...)` (`submit` wraps the task and calls
`execute`) and `dispatchAsync`'s `CompletableFuture.runAsync(task, ASYNC_EXECUTOR)`.
`execute()` invokes the rejection handler when the bounded queue is full, so both
inherit back-pressure with **no changes at the call sites**.

### Configuration

| System property | Default | Meaning |
|---|---|---|
| `scim.dispatch.threads` | 8 (existing) | worker pool size |
| `scim.dispatch.queueCapacity` | 256 | bounded buffer; memory cap ≈ capacity × task-closure |
| `scim.dispatch.blockWarnMs` | 10000 | warn (and bump counter) each time the producer stays blocked this long |

`256` is ≈32 queued tasks per worker at the default pool size of 8 — deep enough
that a healthy/fast sink never starves a worker between producer scheduling
quanta (so there is no throughput penalty in the common case), shallow enough
that the bounded backlog is a small, fixed memory cost regardless of N. A
smaller buffer (e.g. 64) risks transient worker starvation on bursty producers;
a much larger one weakens the memory bound for no fast-sink benefit. A slow sink
fills the buffer and back-pressures, which is the intent. All three properties
are env-overridable like the existing `scim.dispatch.threads`.

`BlockingPolicy.blockedWarnings()` is exposed as a static accessor for the unit
test and for operational visibility; this design does not wire it into a metrics
endpoint (the plugin has no metrics surface beyond `ScimClientMetrics`, and the
per-interval WARN log is the primary operator signal). Exposing it as a metric is
deferred — noted, not built.

### Behavior under sink conditions

- **Fast/healthy sink:** queue rarely fills; behavior is identical to today.
- **Slow sink:** queue fills; the producer (federation-import thread, in the
  post-commit `afterCompletion.commit()` callback) blocks on `offer` until a
  worker drains a slot. The import paces to the sink's drain rate. Memory stays
  flat at the cap. This is true back-pressure.
- **Wedged sink (down / hanging / 5xx forever):** the producer blocks; a WARN is
  logged every `blockWarnMs` so operators see back-pressure rather than a
  mysterious slow sync. Keycloak's own sync transaction timeout eventually
  aborts the sync — a loud, visible failure — with **no data loss and no silent
  inconsistency**. A stalled sync behind a dead downstream is the safe outcome.

Blocking happens in the post-commit `afterCompletion` callback, where the
caller's transaction is **already committed** (no DB locks held). It only paces
the import loop.

The reconciler is the other producer: it submits deletes via `dispatchAsync` and
then blocks on `CompletableFuture.allOf(...).join()` (`ReconcilerRunner.java:127-148`,
`222-231`). Under back-pressure it blocks identically — the submitting (reconcile)
thread parks in the rejection handler — so a reconcile pass behind a wedged sink
hangs for the back-pressure duration, by design. This is the same safe trade as
the import producer, not a deadlock (the reconcile thread is not a pool worker;
see "Why no deadlock").

### Why never drop (rejected alternative)

We considered dropping an op after a bounded block timeout (so the sync always
makes forward progress), relying on the periodic reconciler to recover the
dropped op. **This is unsafe for creates:**

- The reconciler is **delete-only** — it walks the mapping table and issues SCIM
  DELETEs for mappings whose LDAP entry vanished (`ReconcilerRunner.run`). It
  never re-creates or re-pushes anything.
- A dropped *create* never saves a mapping row. On the next periodic sync the
  user already exists locally, so Keycloak passes `isCreate=false`, routing to
  `ScimClient.replace()`, which looks up the mapping
  (`adapter.query("findById", ...).getSingleResult()`, `ScimClient.java:239`),
  finds none, throws `NoResultException`, logs "scim mapping not found"
  (`ScimClient.java:294-296`), and **gives up**. The user is then permanently
  missing from the SCIM sink until someone resets the mapping table and forces
  an all-creates sync. (`replace()` does have a 404/400 re-create branch at
  `ScimClient.java:269-286`, but it is gated *behind* the mapping lookup — it
  handles "mapping exists locally but the remote resource is gone." A dropped
  create never wrote a mapping, so `getSingleResult()` throws first and that
  branch is never reached.)

So a drop-on-timeout policy would **silently and permanently** drop every user
whose create timed out during a slow first-time sync — the opposite of the
predictability we are after. (Membership add/remove ops *do* self-heal, because
the delta worker only updates its `scim-propagated-groups` bookkeeping on
success, so a dropped membership task is recomputed and retried next sync. It is
specifically creates that are unrecoverable.) Blocking, never dropping, is the
correct trade.

### Why no deadlock

The only threads that *submit* are the federation-import thread (post-commit
callback) and the reconciler thread — neither is a pool worker. Worker tasks
(`create`/`replace`/`delete`) never submit back into the pool. So a full queue
can only block a non-worker producer, never starve the workers that drain it.

## Testing

### Unit — `BlockingPolicy` / bounded executor

With pool size 1 and capacity 1:

1. Occupy the single worker with a latch-gated blocker task.
2. Fill the queue to capacity (1 task).
3. From a separate thread, submit one more task and assert the submit **blocks**
   (does not return, does not drop) until the latch releases — then assert the
   task **does** eventually run.
4. With `blockWarnMs` set tiny, assert `BlockingPolicy.blockedWarnings()`
   increments while blocked.
5. After `executor.shutdown()`, assert a further submit throws
   `RejectedExecutionException`.

### Worst-case IT — extend `DispatchMemoryWorstCaseIT`

Re-run slow-sink-10k-200ms and flip the assertions to the fixed behavior:

- `backlogStillQueued ≤ queueCapacity` (was 9,824).
- `importReturnMs ≈ drainSeconds` (the producer is back-pressured; the import no
  longer returns in 4.9 s while the backlog drains).
- Peak memory **flat across 1k vs 10k** (the headline win): the per-sync memory
  delta no longer scales with N.

### Regression

Full unit + integration suites — the executor swap is transparent for fast
sinks, so existing import/membership/reconciler tests must stay green.

## Relationship to SCIM /Bulk

Back-pressure and `/Bulk` are **orthogonal**: this design bounds *memory* and
makes the *worst case* predictable; `/Bulk` would raise *throughput* (drain
rate). Importantly, landing back-pressure first changes the `/Bulk` design for
the better and reframes its payoff:

- **It removes the flush-signal problem.** An earlier `/Bulk` sketch batched on
  the *producer* side and needed a "sync end" signal to flush the final partial
  batch — which Keycloak's LDAP sync does not provide (confirmed: a sync-begin
  hook exists, but no sync-end hook). With a bounded queue, `/Bulk` becomes
  *consumer-side drain-batching*: a worker pulls up to K queued tasks targeting
  the same component and emits one `BulkRequest`. No flush signal is needed (a
  worker batches whatever is currently queued), and memory stays bounded by the
  same `queueCapacity`.
- **Its payoff shifts from "fewer requests" to "faster drain → shorter
  back-pressure."** Because HTTP is ~98% of per-op cost, batching K ops into one
  request could raise the drain rate up to ~K× (bounded by the server's
  advertised `maxOperations`), which directly shortens full-sync wall-time and
  reduces how long/often the producer is back-pressured. That is a more
  compelling justification than the raw request-count reduction we had
  deprioritized.

**Open question for a separate measurement (do not resolve here):** the
magnitude depends entirely on whether the *real* SCIM server processes a bulk
request materially faster than K individual requests (i.e., amortizes its own
per-request overhead). A WireMock-based perf test would **overstate** the
benefit, because the stub has no realistic per-op server cost and returns a
batch as cheaply as a single op. So a meaningful `/Bulk` payoff number requires
measuring drain rate / sync wall-time, batched vs unbatched, against a realistic
sink — not WireMock. Recommendation: land back-pressure, then run that
measurement against a realistic target before committing to the `/Bulk` refactor
(which is non-trivial — it requires tasks to declare their operation
declaratively so a worker can coalesce same-component ops, rather than today's
opaque per-op `Runnable`s).

## Known limitations (out of scope)

- **No durable outbox.** A wedged sink followed by a Keycloak crash loses the
  in-memory queued ops. Surviving that requires a persistent outbox — a larger
  change, not addressed here.
- **Pre-existing "failed create is never retried" gap.** Independent of this
  change: a SCIM create that the server rejects (e.g. 5xx after retries) is
  logged and abandoned, and `replace` will not recreate it without a mapping.
  Back-pressure does not introduce this, and does not fix it. Noted for a future
  durability pass.
