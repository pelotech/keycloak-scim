# SCIM /Bulk Drain-Batching (User Creates) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a consumer-side drain-batching lane that coalesces federation-sync user CREATEs into SCIM `/Bulk` requests, then characterize its payoff across sink latencies.

**Architecture:** A bounded `BlockingQueue<BulkUserOp>` drained by N daemon workers; each worker `take()`s one op, `drainTo`s up to K, and POSTs them as one `/Bulk` request via the SDK's `BulkBuilder`, saving K mappings in one transaction. Producer back-pressure (shared with the executor's `BlockingPolicy`) blocks on a full queue. Creates route through `ScimDispatcher.dispatchUserCreate`, which picks the bulk lane or the existing per-op path per the component's `bulk-enabled` flag. Replace/delete/membership/reconciler are untouched.

**Tech Stack:** Java 17, `java.util.concurrent`, SCIM SDK 1.25.1 (`de.captaingoldfish` `BulkBuilder`/`BulkResponse`), JPA mappings, JUnit 5, Testcontainers (Keycloak + WireMock + OpenLDAP).

**Spec:** `docs/superpowers/specs/2026-06-11-scim-bulk-drain-batching-design.md`

---

## File Structure

- **Create** `src/main/java/sh/libre/scim/core/BulkUserOp.java` — immutable op record `(realmId, componentId, kcUserId)` — identifiers only; the worker re-fetches + builds the payload post-commit (gate-spike finding: email/name aren't populated on the import thread).
- **Create** `src/main/java/sh/libre/scim/core/BackpressureSupport.java` — shared blocking-put helper; `BlockingPolicy` refactored to delegate.
- **Create** `src/main/java/sh/libre/scim/core/ScimBulkLane.java` — bounded queue + consumer threads + drain-batch loop.
- **Modify** `src/main/java/sh/libre/scim/core/ScimClient.java` — add `bulkCreateUsers(List<BulkUserOp>)` + a `BulkResult` record.
- **Modify** `src/main/java/sh/libre/scim/core/BlockingPolicy.java` — delegate to `BackpressureSupport`.
- **Modify** `src/main/java/sh/libre/scim/core/ScimDispatcher.java` — add `dispatchUserCreate(UserModel)`.
- **Modify** `src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java` — create path → `dispatchUserCreate`.
- **Modify** `src/main/java/sh/libre/scim/storage/ScimStorageProviderFactory.java` — add `bulk-enabled` config property.
- **Tests:** `BulkEagerPayloadSpikeIT` (spike), `BlockingPolicyTest` (unchanged-green after refactor), `ScimClientBulkTest`, `ScimBulkLaneTest`, a `/Bulk` integration scenario + `IntegrationTestBase` stub, `BulkLatencySweepIT` (perf).

---

## Chunk 1: Spike — prove eager payload materialization

The whole design hinges on building the SCIM user JSON **eagerly on the import thread, pre-commit** (today the code deliberately never reads the `UserModel` outside the worker). `UserAdapter.apply(user)` walks `getRoleMappingsStream` (roles filtered by `scim=true`) and `toSCIM` reads `realm.getComponent(...)`. If these don't fully materialize at `onImportUserFromLDAP` time, the design must pivot (back to worker re-fetch). **Prove it before building anything else.**

### Task 1: Spike — eager `apply(UserModel).toSCIM()` on the import thread

**Files:**
- Create (temporary): `src/main/java/sh/libre/scim/core/BulkSpikeProbe.java`
- Modify (temporary): `src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java`
- Test: `src/integrationTest/java/sh/libre/scim/integration/BulkEagerPayloadSpikeIT.java`

- [ ] **Step 1: Add a test-observable probe holder**

Create `BulkSpikeProbe.java`:

```java
package sh.libre.scim.core;

/** TEMPORARY spike instrumentation — removed in Task 1 Step 6. Captures the
 *  eagerly-built SCIM user JSON so an IT can assert it materialized fully. */
public final class BulkSpikeProbe {
    public static volatile String lastJson;
    private BulkSpikeProbe() {}
}
```

- [ ] **Step 2: Instrument the mapper's create path (guarded by a system property)**

In `ScimLdapStorageMapper.onImportUserFromLDAP`, at the top of the `if (isCreate)` branch, add (TEMPORARY):

```java
if (isCreate && Boolean.getBoolean("scim.bulk.spike")) {
    realm.getComponentsStream()
        .filter(m -> "scim".equals(m.getProviderId())
            && m.get("enabled", true) && m.get("propagation-user", false))
        .findFirst().ifPresent(component -> {
            var adapter = new sh.libre.scim.core.UserAdapter(
                // the mapper has no KeycloakSession field; obtain it from the realm's session
                realm.getClass() != null ? null : null, component.getId());
        });
}
```

STOP — the mapper does not hold a `KeycloakSession`. Resolve this in Step 3 before writing code: the spike needs a session to construct `UserAdapter(session, componentId)`. Check how `ScimDispatcher` (the mapper's `dispatcher` field) exposes its session, or whether the mapper can be handed the session. The real `dispatchUserCreate` (Task 7) will live in `ScimDispatcher`, which *does* hold a `session`. So run the spike **through the dispatcher** instead of the mapper.

Revised Step 2 — add a temporary spike method to `ScimDispatcher` (which holds `session`):

```java
// TEMPORARY spike — removed in Task 1 Step 6.
public void spikeBuildEagerPayload(org.keycloak.models.UserModel user) {
    if (!Boolean.getBoolean("scim.bulk.spike")) return;
    var realm = session.getContext().getRealm();
    realm.getComponentsStream()
        .filter(m -> ScimStorageProviderFactory.ID.equals(m.getProviderId())
            && m.get("enabled", true) && m.get("propagation-user", false))
        .findFirst().ifPresent(component -> {
            var adapter = new UserAdapter(session, component.getId());
            adapter.apply(user);
            BulkSpikeProbe.lastJson = adapter.toSCIM(false).toString();
        });
}
```

And call it from `ScimLdapStorageMapper.onImportUserFromLDAP` (TEMPORARY), in the `isCreate` branch before the existing `runAsync`:

```java
if (isCreate) {
    dispatcher.spikeBuildEagerPayload(user); // TEMPORARY (Task 1)
    ...
}
```

- [ ] **Step 3: Write the spike IT**

Create `BulkEagerPayloadSpikeIT.java`. It must seed a federated LDAP user **with a realm role marked `scim=true`** (to exercise role materialization), trigger a sync, and assert the captured JSON is complete. Use `IntegrationTestBase` helpers (`newRealmWithScimAndLdap`, `seedLdap*`, `stubScimUserCreateOk`). Skeleton:

```java
package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;
import sh.libre.scim.core.BulkSpikeProbe;
import static org.assertj.core.api.Assertions.assertThat;

class BulkEagerPayloadSpikeIT extends IntegrationTestBase {

    @Test
    void eagerPayloadMaterializesUsernameEmailAndRolesOnImportThread() throws Exception {
        System.setProperty("scim.bulk.spike", "true");
        try {
            BulkSpikeProbe.lastJson = null;
            stubScimUserCreateOk();
            var r = newRealmWithScimAndLdap();
            // Seed an LDAP user with an email; assign a realm role flagged scim=true
            // so UserAdapter.apply walks getRoleMappingsStream. (Mirror existing
            // role-bearing-user setup in the group/role ITs.)
            seedLdapUserWithRole(r, "spikeuser", "spike@test.local", "scim-role");
            r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");

            await().atMost(60, SECONDS).until(() -> BulkSpikeProbe.lastJson != null);
            String json = BulkSpikeProbe.lastJson;
            assertThat(json).contains("\"userName\":\"spikeuser\"");
            assertThat(json).contains("spike@test.local");
            assertThat(json).contains("scim-role"); // role materialized pre-commit
        } finally {
            System.clearProperty("scim.bulk.spike");
        }
    }
}
```

No `seedLdapUserWithRole` helper exists today, and the existing role/group seeding
is LDIF/group-based (`seed.ldif`, `seedLdapGroup`) with no programmatic
realm-role-with-`scim=true` helper — so expect to take the documented fallback:
assert `userName` + `email` materialize and note in the report that role
materialization was not separately exercised. Attempt the role assertion first
(it's the stronger proof of lazy-collection materialization), but the username +
email assertion alone still validates the core risk (building `apply(UserModel)` +
`toSCIM` on the import thread pre-commit). Either outcome passes the gate; only an
*exception* or empty username/email fails it.

- [ ] **Step 4: Run the spike IT**

Run: `./gradlew integrationTest --tests 'sh.libre.scim.integration.BulkEagerPayloadSpikeIT'`
Expected: PASS — the eagerly-built JSON contains username, email, and the role.

> **GATE.** If it FAILS (roles/email empty, or an exception building the adapter on the import thread), STOP and escalate: the eager-payload design is unsafe and the plan must pivot (e.g. carry the kcUserId and build the payload in the worker after re-fetch, batching at the worker). Do not proceed to Chunk 2.

- [ ] **Step 5: Record the finding**

Note the outcome (pass + what was asserted) in the task report. This is the evidence the design rests on.

- [ ] **Step 6: Revert the temporary instrumentation, keep the IT**

Remove `spikeBuildEagerPayload` from `ScimDispatcher`, the TEMPORARY call in the mapper, and delete `BulkSpikeProbe.java`. Convert `BulkEagerPayloadSpikeIT` into a disabled/documented marker **or** delete it (the real coverage comes in Chunk 3). Recommended: delete the IT and the probe; the finding is recorded in the report and the real path is tested end-to-end later.

Run: `./gradlew compileJava integrationTestClasses` → BUILD SUCCESSFUL (instrumentation gone, tree clean).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "test(bulk): spike-verify eager SCIM payload materializes on the import thread"
```

---

## Chunk 2: Core units

### Task 2: Shared back-pressure helper + `BlockingPolicy` refactor

**Files:**
- Create: `src/main/java/sh/libre/scim/core/BackpressureSupport.java`
- Modify: `src/main/java/sh/libre/scim/core/BlockingPolicy.java`
- Test: `src/test/java/sh/libre/scim/core/BlockingPolicyTest.java` (must stay green, unchanged)

- [ ] **Step 1: Write the helper**

Create `BackpressureSupport.java`:

```java
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
```

- [ ] **Step 2: Refactor `BlockingPolicy` to delegate**

Replace the body of `BlockingPolicy.rejectedExecution` so the offer-loop comes from the helper, keeping the pre-loop `isShutdown` guard and the interrupt→`RejectedExecutionException` wrap:

```java
@Override
public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
    if (executor.isShutdown()) {
        throw new RejectedExecutionException("SCIM dispatch pool is shut down");
    }
    try {
        BackpressureSupport.blockingPut(
            executor.getQueue(), r, warnMs, capacity, blockedWarnings, executor::isShutdown);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RejectedExecutionException("interrupted while applying SCIM back-pressure", e);
    }
}
```

Keep the `capacity`, `warnMs`, `blockedWarnings` fields, the `(capacity, warnMs)` constructor (with its positive-value validation), and `blockedWarnings()`. Remove the now-unused inline loop/`TimeUnit` import if it's no longer referenced; keep `RejectedExecutionException`, `ThreadPoolExecutor`.

- [ ] **Step 3: Run the existing BlockingPolicy tests (behavior must be identical)**

Run: `./gradlew test --tests 'sh.libre.scim.core.BlockingPolicyTest'`
Expected: PASS (3 tests) — blocks/never-drops, warn counter increments, shutdown throws. The refactor is behavior-preserving; the `stop` predicate is `executor::isShutdown`, matching the old in-loop check.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/sh/libre/scim/core/BackpressureSupport.java src/main/java/sh/libre/scim/core/BlockingPolicy.java
git commit -m "refactor(dispatch): extract shared BackpressureSupport.blockingPut"
```

---

### Task 3: `BulkUserOp` record

**Files:**
- Create: `src/main/java/sh/libre/scim/core/BulkUserOp.java`

- [ ] **Step 1: Write the record**

```java
package sh.libre.scim.core;

/**
 * One queued SCIM user-create operation, coalescable by {@link ScimBulkLane} —
 * identifiers only, no payload. The bulk worker re-fetches the user by id and
 * builds the SCIM JSON in its own post-commit session (the gate spike showed
 * email/name are not yet populated on the import thread, so the payload cannot
 * be built eagerly). {@code realmId} + {@code componentId} let the worker open
 * its session and locate the SCIM component; {@code kcUserId} is the bulkId
 * correlation handle and the mapping local id.
 */
record BulkUserOp(String realmId, String componentId, String kcUserId) {}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/sh/libre/scim/core/BulkUserOp.java
git commit -m "feat(bulk): add BulkUserOp record"
```

---

### Task 4: `ScimClient.bulkCreateUsers`

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java`
- Test: `src/test/java/sh/libre/scim/core/ScimClientBulkTest.java`

- [ ] **Step 1: Write the wire-shape + response-handling test**

Mirror `GroupMembershipPatchTest` (real `ScimRequestBuilder` over a dummy base URL, assert on the serialized request via the builder; mock the EM for mapping queries). Create `ScimClientBulkTest.java`:

```java
package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import de.captaingoldfish.scim.sdk.client.ScimClientConfig;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire shape of the bulk-create request via a real {@link ScimRequestBuilder}
 * and {@code BulkBuilder.getResource()} — no HTTP, no Keycloak (mirrors
 * GroupMembershipPatchTest). The re-fetch / apply / filter / mapping-save path
 * needs a live session and is covered by the integration scenario (Task 8).
 */
class ScimClientBulkTest {

    @Test
    void buildsOneBulkRequestWithPostOpPerUser() {
        var builder = new ScimRequestBuilder(
            "https://scim.example/scim/v2", ScimClientConfig.builder().build());
        // assembleBulkCreate takes (bulkId, json) pairs — the materialized
        // payloads the worker builds after re-fetching each user.
        String body = ScimClient.assembleBulkCreate(builder, List.of(
            new String[]{"kc-1", "{\"userName\":\"a\"}"},
            new String[]{"kc-2", "{\"userName\":\"b\"}"})).getResource();
        assertThat(body).contains("\"method\":\"POST\"");
        assertThat(body).contains("\"path\":\"/Users\"");
        assertThat(body).contains("\"bulkId\":\"kc-1\"");
        assertThat(body).contains("\"bulkId\":\"kc-2\"");
    }
}
```

Note: `assembleBulkCreate(ScimRequestBuilder, List<String[]>)` is a package-private static seam (Step 3) building the `BulkBuilder` from `(bulkId, json)` pairs — no HTTP, no session. The full re-fetch→send→mapping-save path is covered end-to-end by the integration scenario (Task 8), because constructing a realistic `ServerResponse<BulkResponse>` plus a live `UserModel`/JPA in a unit test is brittle.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'sh.libre.scim.core.ScimClientBulkTest'`
Expected: FAIL — `assembleBulkCreate`/`bulkCreateUsers` don't exist.

- [ ] **Step 3: Implement `bulkCreateUsers` + the `assembleBulkCreate` seam + `BulkResult`**

Add imports to `ScimClient.java`:
```java
import java.util.ArrayList;
import java.util.List;
import de.captaingoldfish.scim.sdk.common.constants.enums.HttpMethod;
import de.captaingoldfish.scim.sdk.common.response.BulkResponse;
import de.captaingoldfish.scim.sdk.client.builder.BulkBuilder;
```

Add a result record (top-level or nested static in `ScimClient`):
```java
public record BulkResult(int created, int skipped, int failed) {
    static final BulkResult EMPTY = new BulkResult(0, 0, 0);
}
```

Add the assembly seam (package-private STATIC so the unit test needs no session):
```java
/** Builds the bulk-create request from (bulkId, json) pairs. Static + package-private = test seam. */
static BulkBuilder assembleBulkCreate(ScimRequestBuilder builder, List<String[]> idJsonPairs) {
    BulkBuilder bulk = builder.bulk();
    for (var p : idJsonPairs) { // p[0] = bulkId (kcUserId), p[1] = SCIM user JSON
        bulk = bulk.bulkRequestOperation("/Users")
            .bulkId(p[0])
            .method(HttpMethod.POST)
            .data(p[1])
            .next();
    }
    return bulk;
}
```
(Add `import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;` — already imported in ScimClient.)

Add the method (worker re-fetch — builds payloads here, NOT eagerly; the spike showed email/name aren't populated on the import thread):
```java
/**
 * Batched user create via SCIM /Bulk. Re-fetches each user by id in THIS worker
 * session (attributes populated post-commit), applies the adapter, skips
 * scim-skip + already-mapped users, POSTs the rest as one bulk request, then
 * persists a mapping for each operation the server accepted. Per-op failures are
 * logged, not fatal to the batch. {@code failOnErrors} is intentionally unset
 * (server attempts every op). Runs inside the lane's worker transaction.
 */
public BulkResult bulkCreateUsers(List<BulkUserOp> ops) {
    if (ops.isEmpty()) return BulkResult.EMPTY;
    var realm = session.getContext().getRealm();

    // 0. Re-fetch + apply + filter. apply(UserModel) sets the adapter id to the
    //    KC user id (the same id create()/saveMapping rely on), so adapter.getId()
    //    is the bulkId + mapping local id below.
    var pending = new ArrayList<UserAdapter>(ops.size());
    int skipped = 0;
    for (var op : ops) {
        var user = session.users().getUserById(realm, op.kcUserId());
        if (user == null) { skipped++; continue; }
        var adapter = new UserAdapter(session, model.getId());
        adapter.apply(user);
        if (adapter.skip) { skipped++; continue; }
        if (!adapter.query("findById", adapter.getId()).getResultList().isEmpty()) { skipped++; continue; } // already mapped
        pending.add(adapter);
    }
    if (pending.isEmpty()) return new BulkResult(0, skipped, 0);

    // Materialize (bulkId, json) pairs from the fully-applied adapters.
    var pairs = new ArrayList<String[]>(pending.size());
    for (var a : pending) {
        pairs.add(new String[]{ a.getId(), a.toSCIM(false).toString() });
    }

    // 2 + 3. send one bulk request through auth-refresh + retry.
    var retry = registry.retry("bulkCreate");
    ServerResponse<BulkResponse> response;
    try (var span = TRACING.startSpan("scim.bulkCreate", "User", scimApplicationBaseUrl)) {
        response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() ->
            assembleBulkCreate(scimRequestBuilder, pairs).sendRequest(false)));
        span.setHttpStatus(response.getHttpStatus());
    }

    // transport-level failure (404/501 no-bulk, 413 oversize, 5xx after retries…)
    if (!response.isSuccess()) {
        LOGGER.warnf("SCIM /Bulk request failed: HTTP %d %s — %d user create(s) lost this round",
            response.getHttpStatus(), response.getResponseBody(), pending.size());
        return new BulkResult(0, skipped, pending.size());
    }

    // 4. per-op triage + mapping save (this transaction). Null/Optional-guarded.
    var bulk = response.getResource();
    int created = 0, failed = 0;
    for (var adapter : pending) {
        var maybe = bulk.getByBulkId(adapter.getId());
        if (maybe.isEmpty()) { failed++; LOGGER.warnf("No bulk response op for user %s", adapter.getId()); continue; }
        var rop = maybe.get();
        Integer status = rop.getStatus();
        var extId = rop.getResourceId();
        if (status != null && status >= 200 && status < 300 && extId.isPresent()) {
            adapter.setExternalId(extId.get());
            adapter.saveMapping();
            created++;
        } else {
            failed++;
            LOGGER.warnf("Bulk create failed for user %s: status=%s", adapter.getId(), String.valueOf(status));
        }
    }
    ScimClientMetrics.CREATE_COUNT.add(created);
    return new BulkResult(created, skipped, failed);
}
```

(`ScimClientMetrics.CREATE_COUNT` is a `LongAdder` — `add(long)` is valid. The import list in Step 3 no longer needs `getEM`-based query imports beyond what `ScimClient` already has; `adapter.query(...)` reuses `Adapter`'s query helper.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'sh.libre.scim.core.ScimClientBulkTest'`
Expected: PASS — the bulk body has one POST `/Users` op per user with the right `bulkId`s.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimClient.java src/test/java/sh/libre/scim/core/ScimClientBulkTest.java
git commit -m "feat(bulk): ScimClient.bulkCreateUsers via SCIM /Bulk with per-op mapping saves"
```

---

### Task 5: `ScimBulkLane`

**Files:**
- Create: `src/main/java/sh/libre/scim/core/ScimBulkLane.java`
- Test: `src/test/java/sh/libre/scim/core/ScimBulkLaneTest.java`

- [ ] **Step 1: Write the drain-batching test**

The lane's queue/drain/grouping logic is the testable core; the per-batch SCIM call needs a session factory. Make the batch sink injectable so the unit test asserts batching without Keycloak. Create `ScimBulkLaneTest.java`:

```java
package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScimBulkLaneTest {

    @Test
    void coalescesQueuedOpsIntoBatchesGroupedByComponent() throws Exception {
        var batches = new ConcurrentLinkedQueue<List<BulkUserOp>>();
        var seen = new CountDownLatch(30);
        // test ctor: batchSize=10, 1 worker, sink captures each (component-grouped) batch
        var lane = ScimBulkLane.forTest(10, 1, group -> { batches.add(group); group.forEach(o -> seen.countDown()); });
        for (int i = 0; i < 30; i++) {
            lane.submit(new BulkUserOp("r", "comp-A", "u" + i));
        }
        assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
        lane.close();
        // 30 ops, batchSize 10 → batches of size <= 10; all same component.
        assertThat(batches.stream().mapToInt(List::size).sum()).isEqualTo(30);
        assertThat(batches).allSatisfy(b -> assertThat(b).allMatch(o -> o.componentId().equals("comp-A")));
        assertThat(batches).allSatisfy(b -> assertThat(b.size()).isLessThanOrEqualTo(10));
    }

    @Test
    void splitsAMixedComponentBatchPerComponent() throws Exception {
        var batches = new ConcurrentLinkedQueue<List<BulkUserOp>>();
        var seen = new CountDownLatch(4);
        var lane = ScimBulkLane.forTest(10, 1, group -> { batches.add(group); group.forEach(o -> seen.countDown()); });
        lane.submit(new BulkUserOp("r", "comp-A", "u1"));
        lane.submit(new BulkUserOp("r", "comp-B", "u2"));
        lane.submit(new BulkUserOp("r", "comp-A", "u3"));
        lane.submit(new BulkUserOp("r", "comp-B", "u4"));
        assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
        lane.close();
        // every emitted batch is single-component
        assertThat(batches).allSatisfy(b ->
            assertThat(b.stream().map(BulkUserOp::componentId).distinct().count()).isEqualTo(1L));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'sh.libre.scim.core.ScimBulkLaneTest'`
Expected: FAIL — `ScimBulkLane` doesn't exist.

- [ ] **Step 3: Implement `ScimBulkLane`**

Production path runs batches through Keycloak sessions + `ScimClient.bulkCreateUsers`; the test ctor injects a sink. Use a `Consumer<List<BulkUserOp>>` batch sink internally; the production sink opens a worker transaction per component group.

```java
package sh.libre.scim.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;

import sh.libre.scim.storage.ScimStorageProviderFactory;

/**
 * Batched user-create lane: a bounded queue of pre-built create ops drained by
 * N daemon workers, each coalescing up to {@code batchSize} ops into one SCIM
 * /Bulk request (grouped by component). Producer back-pressure when full
 * (shared {@link BackpressureSupport}); never drops. JVM-global, mirroring
 * {@link ScimDispatcher}'s executor.
 */
final class ScimBulkLane implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ScimBulkLane.class);
    private static final int THREADS = Integer.getInteger("scim.dispatch.threads", 8);
    private static final int CAPACITY = Integer.getInteger("scim.dispatch.queueCapacity", 256);
    private static final int BATCH_SIZE = Integer.getInteger("scim.dispatch.bulkBatchSize", 20);
    private static final long BLOCK_WARN_MS = Long.getLong("scim.dispatch.blockWarnMs", 10_000L);

    private static volatile ScimBulkLane instance;

    static ScimBulkLane get(KeycloakSession session) {
        var local = instance;
        if (local == null) {
            synchronized (ScimBulkLane.class) {
                local = instance;
                if (local == null) {
                    local = production(session.getKeycloakSessionFactory());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final BlockingQueue<BulkUserOp> queue;
    private final int batchSize;
    private final int capacity;
    private final AtomicLong blockedWarnings = new AtomicLong();
    private final Consumer<List<BulkUserOp>> componentGroupSink;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = true;

    private ScimBulkLane(int batchSize, int threads, int capacity,
                         Consumer<List<BulkUserOp>> componentGroupSink) {
        this.batchSize = batchSize;
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.componentGroupSink = componentGroupSink;
        for (int i = 0; i < threads; i++) {
            var t = new Thread(this::consumeLoop, "scim-bulk-" + (i + 1));
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
    }

    /** Production lane: each component group runs through a worker session + ScimClient. */
    private static ScimBulkLane production(KeycloakSessionFactory factory) {
        return new ScimBulkLane(BATCH_SIZE, THREADS, CAPACITY,
            group -> sendGroup(factory, group));
    }

    /** Test lane: caller-supplied sink, no Keycloak. */
    static ScimBulkLane forTest(int batchSize, int threads, Consumer<List<BulkUserOp>> sink) {
        return new ScimBulkLane(batchSize, threads, Math.max(batchSize * 4, 64), sink);
    }

    void submit(BulkUserOp op) {
        try {
            BackpressureSupport.blockingPut(queue, op, BLOCK_WARN_MS, capacity, blockedWarnings, () -> !running);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("interrupted submitting to SCIM bulk lane", e);
        }
    }

    private void consumeLoop() {
        while (running) {
            List<BulkUserOp> batch;
            try {
                BulkUserOp first = queue.take();
                batch = new ArrayList<>(batchSize);
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                Map<String, List<BulkUserOp>> byComponent =
                    batch.stream().collect(Collectors.groupingBy(BulkUserOp::componentId));
                for (var group : byComponent.values()) {
                    componentGroupSink.accept(group);
                }
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "scim bulk lane: batch failed");
            }
        }
    }

    private static void sendGroup(KeycloakSessionFactory factory, List<BulkUserOp> group) {
        String realmId = group.get(0).realmId();
        String componentId = group.get(0).componentId();
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            var realm = session.realms().getRealm(realmId);
            if (realm == null) return;
            session.getContext().setRealm(realm);
            var component = realm.getComponent(componentId);
            if (component == null || !ScimStorageProviderFactory.ID.equals(component.getProviderId())) {
                return;
            }
            var client = new ScimClient(component, session);
            try {
                client.bulkCreateUsers(group);
            } finally {
                client.close();
            }
        });
    }

    long blockedWarnings() { return blockedWarnings.get(); }

    @Override
    public void close() {
        running = false;
        workers.forEach(Thread::interrupt);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'sh.libre.scim.core.ScimBulkLaneTest'`
Expected: PASS — 30 ops coalesce into ≤10-sized single-component batches; mixed batches split per component.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimBulkLane.java src/test/java/sh/libre/scim/core/ScimBulkLaneTest.java
git commit -m "feat(bulk): ScimBulkLane drain-batching queue with back-pressure"
```

---

### Task 6: `bulk-enabled` config property

**Files:**
- Modify: `src/main/java/sh/libre/scim/storage/ScimStorageProviderFactory.java`

- [ ] **Step 1: Add the config property**

Find the existing boolean property definitions (e.g. `user-patchOp` / `group-patchOp`, built with `ProviderConfigProperty` / the config-properties builder). Add a new boolean property mirroring that exact style:

- key: `bulk-enabled`
- label: `Batch user creates via SCIM /Bulk`
- helpText: `When on, federation-sync user CREATE operations are coalesced into SCIM /Bulk requests. Requires the SCIM server to support /Bulk; set scim.dispatch.bulkBatchSize <= the server's maxOperations. Default off.`
- type: `BooleanType`, default `"false"`.

- [ ] **Step 2: Compile + existing config tests**

Run: `./gradlew test` (includes any `ReconcilerConfigValidator`/factory tests)
Expected: PASS — new optional property doesn't break existing config.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/sh/libre/scim/storage/ScimStorageProviderFactory.java
git commit -m "feat(bulk): add bulk-enabled component config property"
```

---

### Task 7: Route creates through `ScimDispatcher.dispatchUserCreate`

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimDispatcher.java`
- Modify: `src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java`
- Test: `src/test/java/sh/libre/scim/ldap/ScimLdapStorageMapperTest.java` (update create-path expectation)

- [ ] **Step 1: Implement `dispatchUserCreate`**

Add to `ScimDispatcher` (it holds `session`). It must reproduce `runAsync`'s commit-deferral and per-component routing, sending bulk-enabled components to the lane (eager payload) and the rest through the existing per-op create worker. Add imports `org.keycloak.models.UserModel`, `java.util.ArrayList`.

```java
/**
 * Route a federation-imported user CREATE. Per propagation-user component:
 * bulk-enabled → build the SCIM payload eagerly (live model) and submit to the
 * bulk lane on commit; otherwise → the existing per-op create worker. Mirrors
 * runAsync's afterCompletion deferral (rollback → skip).
 */
public void dispatchUserCreate(UserModel user) {
    var realm = session.getContext().getRealm();
    String realmId = realm.getId();
    String userId = user.getId();

    var components = realm.getComponentsStream()
        .filter(m -> ScimStorageProviderFactory.ID.equals(m.getProviderId())
            && m.get("enabled", true) && m.get("propagation-user", false))
        .toList();
    if (components.isEmpty()) return;

    // Partition matching components into bulk vs per-op. No eager payload build —
    // we only capture ids; the bulk worker re-fetches + builds post-commit (the
    // spike showed email/name aren't populated on the import thread). scim-skip is
    // applied in the worker (bulkCreateUsers) / by client.create, as today.
    var bulkOps = new ArrayList<BulkUserOp>();
    var perOpComponentIds = new ArrayList<String>();
    for (var component : components) {
        if (component.get("bulk-enabled", false)) {
            bulkOps.add(new BulkUserOp(realmId, component.getId(), userId));
        } else {
            perOpComponentIds.add(component.getId());
        }
    }

    KeycloakSessionFactory factory = session.getKeycloakSessionFactory();
    var laneRef = bulkOps.isEmpty() ? null : ScimBulkLane.get(session);

    session.getTransactionManager().enlistAfterCompletion(new KeycloakTransaction() {
        private volatile boolean done = false;
        @Override public void begin() {}
        @Override public void commit() {
            for (var op : bulkOps) {
                laneRef.submit(op); // blocking put = back-pressure
            }
            for (var componentId : perOpComponentIds) {
                ASYNC_EXECUTOR.submit(() -> KeycloakModelUtils.runJobInTransaction(factory, ws -> {
                    var wr = ws.realms().getRealm(realmId);
                    if (wr == null) return;
                    ws.getContext().setRealm(wr);
                    var component = wr.getComponent(componentId);
                    if (component == null
                        || !ScimStorageProviderFactory.ID.equals(component.getProviderId())) return;
                    var u = ws.users().getUserById(wr, userId);
                    if (u == null) return;
                    var client = new ScimClient(component, ws);
                    try { client.create(UserAdapter::new, u); } finally { client.close(); }
                }));
            }
            done = true;
        }
        @Override public void rollback() { done = true; }
        @Override public void setRollbackOnly() {}
        @Override public boolean getRollbackOnly() { return false; }
        @Override public boolean isActive() { return !done; }
    });
}
```

> Note the per-op branch is deliberately the create-only equivalent of `runAsync`'s worker (re-fetch by id, `client.create`). It does NOT reuse `runAsync` because `runAsync(SCOPE_USER, create)` would re-include the bulk-enabled components and double-create.

- [ ] **Step 2: Wire the mapper's create path**

In `ScimLdapStorageMapper.onImportUserFromLDAP`, replace the `if (isCreate) { dispatcher.runAsync(SCOPE_USER, create…) }` branch with a single call; keep the `else`/replace branch and the SCOPE_GROUP membership block unchanged:

```java
if (isCreate) {
    dispatcher.dispatchUserCreate(user);
} else {
    dispatcher.runAsync(ScimDispatcher.SCOPE_USER, (client, workerSession) -> {
        var u = workerSession.users().getUserById(workerSession.getContext().getRealm(), userId);
        if (u != null) client.replace(UserAdapter::new, u);
    });
}
```

- [ ] **Step 3: Update the mapper unit test**

`ScimLdapStorageMapperTest.onImportRoutesCreateWhenIsCreateTrue` currently does
`verify(dispatcher).runAsync(eq(SCOPE_USER), captor.capture())` and then drives the
captured `BiConsumer` to `client.create`. After Task 7 the create path no longer
calls `runAsync(SCOPE_USER, …)` at all — it calls `dispatcher.dispatchUserCreate(user)`,
and the only remaining `runAsync` on import is `SCOPE_GROUP`. So **delete the entire
BiConsumer-capture-and-invoke machinery for the create case** (it's invalid now, not
tweakable) and replace it with a simple `verify(dispatcher).dispatchUserCreate(user)`
on `isCreate=true` (plus, if desired, `verify(dispatcher).runAsync(eq(SCOPE_GROUP), any())`).
Keep the replace-path test (`isCreate=false`) asserting `runAsync(SCOPE_USER…) →
client.replace` unchanged. Use the existing Mockito setup; mock `UserModel.getId()`.

- [ ] **Step 4: Run unit tests**

Run: `./gradlew test`
Expected: PASS — mapper routes create → `dispatchUserCreate`, replace unchanged; all core tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimDispatcher.java src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java src/test/java/sh/libre/scim/ldap/ScimLdapStorageMapperTest.java
git commit -m "feat(bulk): route federation user creates through dispatchUserCreate (bulk or per-op)"
```

---

## Chunk 3: Integration + measurement

### Task 8: `/Bulk` integration scenario

**Files:**
- Modify: `src/integrationTest/java/sh/libre/scim/integration/IntegrationTestBase.java` (add a `/Bulk` stub helper; **pull up `seedLdapUsers`**)
- Test: `src/integrationTest/java/sh/libre/scim/integration/ScimBulkUserCreateIT.java`

- [ ] **Step 0: Pull bulk LDAP seeding up into `IntegrationTestBase`**

`seedLdapUsers(String prefix, int count)` and `ldapUserDn(String)` currently live
in `PerfTestBase` (the `perfTest` source set). The `integrationTest` source set
**cannot** extend `PerfTestBase` (dependency direction is perfTest → integrationTest,
not the reverse), so `ScimBulkUserCreateIT extends IntegrationTestBase` can't see
them. Move `seedLdapUsers(String, int)` and `ldapUserDn(String)` **up** into
`IntegrationTestBase` (they only use `newLdapEnv()`, already there). `PerfTestBase`
keeps them via inheritance — after the move, run `./gradlew perfTestClasses` to
confirm `DispatchMemoryWorstCaseIT` / `BulkUserImportPerfTest` still compile (they
call `seedLdapUsers`/`ldapUserDn` and will resolve via inheritance). Do NOT
duplicate the methods; move them.

- [ ] **Step 1: Add a `/Bulk` WireMock stub helper to `IntegrationTestBase`**

Mirror the existing `stubScimUserCreateOk` style. The stub must return a SCIM `BulkResponse` whose `Operations[]` echo each request op's `bulkId` with `status:"201"` and a unique `location`/`id`. Because per-op ids must correlate to request `bulkId`s, use a WireMock response transformer (or a body template) that reads the request body's `Operations[].bulkId` and emits a matching response op. Provide:

```java
protected void stubScimBulkOk() { /* POST /Bulk → 200, per-op 201 with echoed bulkId + random id */ }
```

If a response transformer is heavier than warranted, an acceptable simpler stub: return a fixed `BulkResponse` with `status:"201"` ops carrying the same `bulkId`s for the known seeded user ids in the test (the test controls the ids). Keep it minimal but correct enough that `getByBulkId` resolves.

- [ ] **Step 2: Write the integration test**

```java
class ScimBulkUserCreateIT extends IntegrationTestBase {
    @Test
    void bulkEnabledSyncEmitsBulkNotPerUserPosts() throws Exception {
        stubScimBulkOk();
        // newRealmWithScimAndLdapAndConfig takes Consumer<MultivaluedHashMap<String,String>>;
        // use putSingle (NOT put — put expects a List value and won't compile).
        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("bulk-enabled", "true"));
        var users = seedLdapUsers("bulk", 25); // > one batch boundary at K=20 (seedLdapUsers pulled up in Step 0)
        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");

        // Assert /Bulk was used and NOT N individual /Users POSTs.
        await().atMost(60, SECONDS).until(() -> bulkPostCount() >= 1);
        assertThat(perUserPostCount()).isZero();
        // mappings persisted for all seeded users (verify via a follow-up that
        // a re-sync is a no-op, or query the SCIM_RESOURCE count via admin/db).
    }
}
```

Add `bulkPostCount()` / `perUserPostCount()` WireMock-count helpers next to the existing `awaitUserPostFor`. Confirm `newRealmWithScimAndLdapAndConfig` exists (the explore found `newRealmWithScimAndLdapAndConfig(Consumer<Map<...>>)`); if the config seam differs, set `bulk-enabled` via the same mechanism the group ITs use for `group-patchOp`.

- [ ] **Step 3: Run the integration test**

Run: `./gradlew integrationTest --tests 'sh.libre.scim.integration.ScimBulkUserCreateIT'`
Expected: PASS — one or more `POST /Bulk`, zero `POST /Users`, mappings saved.

- [ ] **Step 4: Run the full integration suite (no regressions)**

Run: `./gradlew integrationTest`
Expected: PASS — bulk-disabled realms (the default) still use per-op `POST /Users` exactly as before; the dispatch refactor is transparent.

- [ ] **Step 5: Commit**

```bash
git add src/integrationTest/java/sh/libre/scim/integration/IntegrationTestBase.java src/integrationTest/java/sh/libre/scim/integration/ScimBulkUserCreateIT.java
git commit -m "test(bulk): integration-cover /Bulk user-create sync path"
```

---

### Task 9: Latency-swept characterization IT

**Files:**
- Test: `src/perfTest/java/sh/libre/scim/perf/BulkLatencySweepIT.java`

- [ ] **Step 1: Write the sweep**

Matrix: bulk {on, off} × latency {fast ~5 ms, medium ~50 ms, slow ~200 ms}, fixed N (e.g. `perf.userCount`, default 2_000 to keep the 6 cells tractable), plus one K-sensitivity cell. Each cell: stub the relevant endpoint (`/Bulk` with `withFixedDelay(d)` when on, `/Users` with `withFixedDelay(d)` when off), seed N LDAP users, trigger sync, await drain, capture metrics. Reuse `PerfTestBase` seeding, `ContainerMemorySampler`, `PerfReport.timedWithNotes`, and the WireMock delay stubs from `DispatchMemoryWorstCaseIT`.

```java
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BulkLatencySweepIT extends PerfTestBase {
    private static final int N = Integer.getInteger("perf.userCount", 2_000);
    private static final PerfReport report = new PerfReport("BulkLatencySweepIT");
    // 6 cells: {on,off} × {5,50,200} ms. One extra cell: on @200ms with bulkBatchSize=5.

    // For each cell:
    //   - if bulk on: stub POST /Bulk withFixedDelay(d), counter on /Bulk;
    //                 realm config bulk-enabled=true
    //   - if bulk off: stub POST /Users withFixedDelay(d), counter on /Users
    //   - seed N users; sampler.start(); time the full sync + drain; sampler.stop()
    //   - record notes: bulkEnabled, sinkDelayMs, batchSize, httpRequests,
    //     requestRatio (off:on or N:requests), wallSeconds, drainRatePerSec, peakMemMiB
    //   - print a [perf] line per cell (captured in test-results system-out)
}
```

The report/table must include, per cell: **HTTP request count and the request-count ratio** (≈N for off vs ≈N/K for on — proves batching engaged), wall-time, effective drain rate, peak container memory. The K-sensitivity cell sets `-Dscim.dispatch.bulkBatchSize` (note: this is a container JVM prop — see constraint below).

> **Container-config constraint.** `scim.dispatch.bulkBatchSize` and `bulk-enabled`-independent sizing live in the **Keycloak container** JVM, not the test JVM, and the shared container starts once. So the K-sensitivity cell can't change `bulkBatchSize` at runtime per test. Options: (a) start a dedicated container for the K-sensitivity cell with `JAVA_OPTS_APPEND=-Dscim.dispatch.bulkBatchSize=5`; (b) drop the K-sensitivity cell from the IT and instead report the K effect analytically (requests ≈ N/K). Prefer (b) for the first pass — it keeps the IT on the shared container — and note in the report that K-sensitivity is analytic (requests scale as N/K) unless a dedicated-container cell is added later. Decide during implementation; do not silently omit it.

- [ ] **Step 2: Run the sweep**

Run: `./gradlew performanceTest --tests 'sh.libre.scim.perf.BulkLatencySweepIT'`
Expected: PASS. Capture the printed `[perf]` lines (request counts, ratios, wall-times, peak mem) for the docs table.

- [ ] **Step 3: Record the comparison + honesty caveat in docs**

Append a "SCIM /Bulk — latency-swept characterization" section to `docs/performance.md` with the measured table (bulk vs per-op × fast/medium/slow), the request-count ratios, and the **explicit caveat**: WireMock applies a per-request delay only (round-trip amortization), so the measured benefit is a **lower bound** on real-world gain — it does not assert server-side per-op amortization. This is the data that decides further `/Bulk` investment (replace/delete/membership).

- [ ] **Step 4: Commit**

```bash
git add src/perfTest/java/sh/libre/scim/perf/BulkLatencySweepIT.java docs/performance.md
git commit -m "test(bulk): latency-swept /Bulk characterization + docs table"
```

---

## Done criteria

- Spike proved (or the plan pivoted) that eager payload materialization is safe on the import thread.
- `BackpressureSupport` shared by `BlockingPolicy` (still green) and `ScimBulkLane`.
- Unit: `ScimClientBulkTest` (wire shape), `ScimBulkLaneTest` (drain-batch + per-component split) green.
- Integration: bulk-enabled sync emits `POST /Bulk` (not N × `POST /Users`), mappings persist; full `integrationTest` suite green (default bulk-off path unchanged).
- `BulkLatencySweepIT` produces the bulk-vs-per-op × latency table with request-count ratios and the lower-bound caveat, written into `docs/performance.md`.
