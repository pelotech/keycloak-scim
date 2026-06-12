# SCIM /Bulk Drain-Batching (User Creates) — Design

**Status:** Approved (design phase)
**Date:** 2026-06-11
**Builds on:** `2026-06-11-dispatch-backpressure-design.md` (bounded queue + back-pressure)

## Problem & goal

A federation full sync emits one `POST /Users` per imported user. With ~98% of
per-op cost being the HTTP round-trip, a 10k-user sync is thousands of
sequential round-trips across 8 workers. SCIM `/Bulk` lets many operations ride
one HTTP request, amortizing per-request cost.

The goal of this round is **two-fold**: (1) build a real, shippable batched
create path, and (2) **characterize its payoff** across sink latencies, so the
decision to invest further in `/Bulk` (replace/delete/membership) rests on
measured data, not speculation.

Scope is deliberately narrow: **user CREATE operations on the federation-sync
path only.** Replace/delete/group-membership/reconciler/admin-event paths are
out of scope and continue through the existing per-op dispatch unchanged. The
create flood is the dominant sync cost and the cleanest to coalesce (all
`POST /Users`, no inter-op dependencies), so it is the right probe.

## Why drain-batching (not producer-side accumulation)

A consumer-side drain-batch — a worker takes one queued op then `drainTo`s
whatever else is queued (up to a cap) and sends them as one bulk request — is
the natural fit:

- `drainTo` **is** the batching primitive and self-adjusts: a sync flood yields
  large batches (the payoff); a trickle yields batches of one (no artificial
  latency).
- **No flush timer and no "sync-end" signal are required** — the exact obstacles
  that sank a producer-side accumulator sketch (Keycloak's LDAP sync has a
  sync-begin hook but no sync-end hook, confirmed earlier).
- **Back-pressure is preserved**: the producer blocks on a bounded `put()` when
  the queue is full, the same principle the dispatch back-pressure work shipped.

## SDK capability (verified)

SCIM SDK `1.25.1` (de.captaingoldfish) client supports bulk end-to-end:

- `ScimRequestBuilder.bulk()` → `BulkBuilder`.
- `BulkBuilder.bulkRequestOperation(path).method(HttpMethod.POST).bulkId(id).data(json).next()…`
  composes operations; `.sendRequest(false)` returns `ServerResponse<BulkResponse>`.
  `failOnErrors` is left unset so the server attempts every operation.
- `BulkResponse.getByBulkId(bulkId)` → `BulkResponseOperation` with
  `getStatus()` and `getResourceId()` (the assigned external id, for the
  mapping save); `getSuccessfulOperations()` / `getFailedOperations()` for
  per-op triage.
- `BulkBuilder.getResource()` returns the serialized request body, enabling a
  no-HTTP wire-shape unit test (as `GroupMembershipPatchTest` does for the
  membership PATCH).

## Architecture — four units

### 1. `BulkUserOp` (record)

Immutable: `(String componentId, String kcUserId, String scimUserJson)`. The
typed, coalescable unit. `scimUserJson` is built **eagerly** at import time from
the live `UserModel` (via `UserAdapter.apply(user).toSCIM(false)` serialized),
so the consumer never re-fetches the user. New file
`src/main/java/sh/libre/scim/core/BulkUserOp.java`.

### 2. `ScimBulkLane`

Owns a bounded `ArrayBlockingQueue<BulkUserOp>` and N daemon consumer threads.
Single responsibility: queue management, back-pressure, and the drain-batch
loop. New file `src/main/java/sh/libre/scim/core/ScimBulkLane.java`.

- `submit(BulkUserOp)` — blocking enqueue with back-pressure. Reuses the same
  blocking-`offer`-with-periodic-WARN logic as `BlockingPolicy`; that logic is
  **extracted to a shared helper** (e.g. `BackpressureSupport.blockingPut(queue,
  item, warnMs, capacity, counter)`) so the executor lane and the bulk lane
  don't duplicate it. (Refactor `BlockingPolicy` to call the shared helper.)
- Consumer loop, per cycle:
  ```
  op = queue.take();
  batch = new ArrayList<>(); batch.add(op);
  queue.drainTo(batch, batchSize - 1);
  for (group : batch grouped by componentId)
      runJobInTransaction(session -> new ScimClient(component, session)
          .bulkCreateUsers(group));
  ```
  Grouping by `componentId` keeps each bulk request single-target (a drained
  batch is almost always one component, but `drainTo` could mix them).
- Lifecycle: JVM-global static (like `ASYNC_EXECUTOR`), daemon threads named
  `scim-bulk-N`. Sizing reuses `scim.dispatch.threads` (thread count) and
  `scim.dispatch.queueCapacity` (queue depth); the lane has its own instances.

### 3. `ScimClient.bulkCreateUsers(List<BulkUserOp> ops)`

The SCIM-protocol boundary, beside `create`/`replace`/`delete`. Runs inside the
lane's worker session/transaction.

1. **Idempotency pre-filter:** batch-query existing mappings (`findById`) for the
   ops' `kcUserId`s; drop already-mapped ops (preserves today's skip-if-mapped
   behavior so re-sync doesn't duplicate).
2. **Build** one `BulkRequest`: for each remaining op, a `POST` operation at the
   Users endpoint with `bulkId = kcUserId` and `data = scimUserJson`.
3. **Send** through the existing `auth.sendWithAuthRefresh(() ->
   retry.executeSupplier(...))` wrapper (so token refresh + 429/5xx/IO retry
   apply to the whole bulk request).
4. **Apply response:** for each op, `response.getByBulkId(kcUserId)`; status 2xx
   → save the mapping (`kcUserId` → `getResourceId()` external id) via the
   existing mapping path; non-2xx → WARN, no mapping. All surviving mappings are
   persisted in the one worker transaction.
5. Returns a small summary (counts of created / skipped / failed) for metrics
   and logging.

### 4. `ScimDispatcher.dispatchUserCreate(UserModel user)`

Routing, encapsulating the bulk/per-op decision so the mapper stays thin.

- If the component's `bulk-enabled` flag is on: for each `propagation-user`
  component, build the per-component SCIM payload eagerly, and on the caller's
  `afterCompletion` **commit** (rollback → skip, matching the existing
  deferral), `lane.submit(new BulkUserOp(componentId, user.getId(), json))`.
- Else: fall through to today's `runAsync(SCOPE_USER, (c, s) -> c.create(...))`.

`ScimLdapStorageMapper.onImportUserFromLDAP(..., isCreate=true)` calls only
`dispatchUserCreate(user)`.

## Data flow (bulk-enabled full sync)

```
LDAP import user → mapper.onImportUserFromLDAP(isCreate=true)
  → dispatcher.dispatchUserCreate(user)
      → per propagation-user component: build payload; enlist afterCompletion
  → [caller tx commits] → lane.submit(BulkUserOp)         // blocking put = back-pressure
ScimBulkLane consumer:
  op = take(); drainTo(batch, K-1); groupBy(componentId)
  → per group: runJobInTransaction { client.bulkCreateUsers(group) }
       → drop already-mapped → BulkRequest(K × POST /Users) → send
       → per-op 2xx ⇒ save mapping; non-2xx ⇒ WARN
```

The two lanes coexist: creates on `ScimBulkLane`, everything else on the
untouched `ASYNC_EXECUTOR`. Both bounded, both back-pressured.

## Error handling & failure semantics

- **Partial failure:** the request does not fail-fast; per-op results are
  triaged. A failed create-in-bulk leaves no mapping — exactly as (un)recoverable
  as a failed *single* create today (next sync's `replace` finds no mapping and
  gives up). This is a **pre-existing durability gap**, neither introduced nor
  fixed here; noted as a known limitation.
- **Whole-request failure:** a bulk POST that fails after retries loses that
  batch's ops (logged). Back-pressure unaffected (bounded queue).
- **Server lacks `/Bulk`:** if a bulk POST returns `404`/`501`, log an error and
  fall back to per-op creates for those ops via the existing path. No
  `ServiceProviderConfig` auto-discovery (YAGNI) — `bulk-enabled` is an operator
  opt-in asserting bulk support.

## Configuration

| Key | Scope | Default | Meaning |
|---|---|---|---|
| `bulk-enabled` | component | **false** | route user creates through the bulk lane |
| `scim.dispatch.bulkBatchSize` | system prop | **20** | max ops per bulk request (K); set ≤ server `maxOperations` |
| `scim.dispatch.threads` | system prop | 8 (existing) | also sizes the lane's consumer threads |
| `scim.dispatch.queueCapacity` | system prop | 256 (existing) | also sizes the lane's queue |

Default-off keeps shipped behavior unchanged and is the on/off toggle the
measurement needs. `bulk-enabled` is added to the storage provider factory's
config properties (`ScimStorageProviderFactory`).

## Testing

### Unit

- **`ScimBulkLaneTest`** — enqueue N ops; assert the consumer coalesces into
  ⌈N/K⌉ batches (a fake sink captures batch sizes); back-pressure blocks the
  producer on a full queue (latch + `awaitQueueSize` gate, as in
  `BlockingPolicyTest`); a mixed-`componentId` batch splits into one bulk per
  component.
- **`ScimClientBulkTest`** — pin the bulk request wire shape via
  `BulkBuilder.getResource()` (no HTTP, mirroring `GroupMembershipPatchTest`):
  K `POST` ops at the Users endpoint with correct `bulkId` / `data`. Response
  handling: feed a `BulkResponse` with mixed per-op statuses; assert mappings
  saved for 2xx, skipped for failures, idempotency pre-filter drops a
  pre-mapped id.

### Integration

- A `/Bulk` WireMock stub returning per-op `201`s with assigned external ids.
  Assert a bulk-enabled sync emits `POST /Bulk` (not N × `POST /Users`),
  mappings persist, and users propagate. Add the stub to `IntegrationTestBase`.

### Measurement — latency-swept characterization IT (the deliverable)

New `BulkLatencySweepIT` in `src/perfTest`. Matrix: **bulk {on, off}** ×
**sink latency {fast ~5 ms, medium ~50 ms, slow ~200 ms}**, fixed N. Per cell,
capture: HTTP request count, sync wall-time, effective drain rate, and peak
container memory (`ContainerMemorySampler`). Emit a comparison table via
`PerfReport`.

**Honesty caveat (stated in the report):** WireMock applies a per-*request*
fixed delay (the round-trip component) but models no per-*op* server processing
cost. So the IT measures bulk's **round-trip amortization only** — saving
(K−1) round-trips per batch. This is the **lower bound** of real-world benefit;
a real SCIM server also amortizes per-request parse/auth/dispatch/framework
overhead that WireMock cannot represent. The table reads as "at least this
much," not "exactly this much."

## Known limitations (out of scope)

- Failed creates (single or bulk) are not retried across syncs — pre-existing
  gap (the reconciler is delete-only; `replace` won't recreate without a
  mapping). Unchanged here.
- Only user creates are batched. Replace/delete/membership remain per-op pending
  the measurement outcome.
- No durable outbox: a crash loses in-memory queued ops (same as the existing
  dispatch).
- No `ServiceProviderConfig` bulk auto-discovery; `bulk-enabled` is a manual
  opt-in with a 404/501 fallback safety net.

## File structure

- Create: `src/main/java/sh/libre/scim/core/BulkUserOp.java`
- Create: `src/main/java/sh/libre/scim/core/ScimBulkLane.java`
- Create: `src/main/java/sh/libre/scim/core/BackpressureSupport.java` (shared
  blocking-put helper; `BlockingPolicy` refactored to use it)
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (add `bulkCreateUsers`)
- Modify: `src/main/java/sh/libre/scim/core/ScimDispatcher.java` (add
  `dispatchUserCreate`, own/expose `ScimBulkLane`)
- Modify: `src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java` (create
  path → `dispatchUserCreate`)
- Modify: `src/main/java/sh/libre/scim/storage/ScimStorageProviderFactory.java`
  (add `bulk-enabled` config property)
- Tests: `ScimBulkLaneTest`, `ScimClientBulkTest` (unit), a `/Bulk` integration
  scenario + stub, and `BulkLatencySweepIT` (perf).
