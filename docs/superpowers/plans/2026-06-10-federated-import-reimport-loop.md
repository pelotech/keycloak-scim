# Federated-Import Re-import Loop Fix — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the runaway federated re-import loop by provisioning groups for membership without enumerating their members.

**Architecture:** Add a member-less `GroupAdapter.applyForProvisioning(GroupModel)` (id + displayName + `scim-skip`, no `getGroupMembersStream`). Extract `ScimClient.create`'s post-apply send/persist body into a shared `sendCreate(adapter)` helper (behavior-preserving), then have `ensureGroupMembership` provision via `applyForProvisioning` + `sendCreate` instead of the member-enumerating `create`. The admin `create`/`replace` paths keep full member enumeration. Verify by re-measurement that the storm is gone.

**Tech Stack:** Java 21, Keycloak SPI, Captain Goldfish SCIM SDK, JUnit 5 + Mockito (unit), Testcontainers + OpenLDAP + WireMock (integration).

**Spec:** `docs/superpowers/specs/2026-06-10-federated-import-reimport-loop-design.md`

**Root cause (confirmed):** `ensureGroupMembership → create → GroupAdapter.apply(GroupModel)` calls `session.users().getGroupMembersStream(realm, group)`; on a federated `groupOfNames` this re-imports every member, re-firing `onImportUserFromLDAP` → re-dispatch → unbounded (2,776 invocations / 2 members). The member list is not needed for the delta-PATCH membership flow.

---

## Chunk 1: The fix

### Task 1: `GroupAdapter.applyForProvisioning` (member-less)

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/GroupAdapter.java` (add after `apply(GroupModel)`, ~line 57)
- Test: `src/test/java/sh/libre/scim/core/GroupProvisioningApplyTest.java` (create)

- [ ] **Step 1: Write the failing test** (mirror `GroupMembershipPatchTest`'s mock setup — read it for the exact `@Mock`/`@BeforeEach` shape):

```java
package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jakarta.persistence.EntityManager;

/**
 * Pins that provisioning a group for membership does NOT enumerate members
 * (the federated re-import-loop trigger): applyForProvisioning sets id +
 * displayName + scim-skip only, and the resulting SCIM payload carries no
 * members. Crucially it must NOT touch session.users().getGroupMembersStream.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupProvisioningApplyTest {

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock JpaConnectionProvider jpaConnectionProvider;
    @Mock EntityManager entityManager;
    @Mock GroupModel group;

    private GroupAdapter adapter;

    @BeforeEach
    void setUp() {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-id");
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpaConnectionProvider);
        when(jpaConnectionProvider.getEntityManager()).thenReturn(entityManager);
        adapter = new GroupAdapter(session, "component-id");
    }

    @Test
    void applyForProvisioning_setsIdAndName_noMemberEnumeration() {
        when(group.getId()).thenReturn("grp-1");
        when(group.getName()).thenReturn("engineers");
        when(group.getFirstAttribute("scim-skip")).thenReturn(null);

        adapter.applyForProvisioning(group);

        // SCIM payload has the identity but NO members.
        var scim = adapter.toSCIM(false);
        assertThat(scim.getDisplayName().orElse(null)).isEqualTo("engineers");
        assertThat(scim.getMembers()).isEmpty();
        assertThat(adapter.skip).isFalse();
        // The member-enumeration call must never happen (it is the loop trigger).
        org.mockito.Mockito.verify(session, org.mockito.Mockito.never())
            .users();
    }

    @Test
    void applyForProvisioning_honorsScimSkip() {
        when(group.getId()).thenReturn("grp-1");
        when(group.getName()).thenReturn("engineers");
        when(group.getFirstAttribute("scim-skip")).thenReturn("true");

        adapter.applyForProvisioning(group);

        assertThat(adapter.skip).isTrue();
    }
}
```

> Note: `toSCIM(false)` builds members only when `members.size() > 0`; with the member set left empty, `getMembers()` is null/empty. If `getMembers()` returns `null` rather than an empty list, assert `assertThat(scim.getMembers()).isNullOrEmpty();`. Use whichever the first run shows. The `verify(session, never()).users()` is the key anti-regression assertion — it proves no member enumeration. (Adjust if the GroupAdapter constructor or `toSCIM` touches `session.users()` for unrelated reasons — it should not.)

- [ ] **Step 2: Run, watch it fail** — `./gradlew test --tests "sh.libre.scim.core.GroupProvisioningApplyTest"` (method missing).

- [ ] **Step 3: Implement** — add after `apply(GroupModel)` in `GroupAdapter.java`:

```java
    /**
     * Like {@link #apply(GroupModel)} but WITHOUT enumerating the group's
     * members. Used to provision a group for membership propagation, where the
     * member list is neither needed (members are added via the single-member
     * delta PATCH) nor safe to read: on a federated group,
     * {@code getGroupMembersStream} re-imports every member, re-firing
     * {@code onImportUserFromLDAP} and causing an unbounded re-import loop.
     * Sets id, displayName, and the {@code scim-skip} flag only.
     */
    public void applyForProvisioning(GroupModel group) {
        setId(group.getId());
        setDisplayName(group.getName());
        this.skip = StringUtils.equals(group.getFirstAttribute("scim-skip"), "true");
    }
```
(`StringUtils` is already imported in `GroupAdapter`.)

- [ ] **Step 4: Run** — `./gradlew test --tests "sh.libre.scim.core.GroupProvisioningApplyTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/GroupAdapter.java \
        src/test/java/sh/libre/scim/core/GroupProvisioningApplyTest.java
git commit -m "feat(group): add member-less applyForProvisioning (breaks federated re-import loop)"
```

### Task 2: Member-less provisioning in `ensureGroupMembership`

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (extract `sendCreate`; add provisioning path; rewire `ensureGroupMembership`)

This is two sub-changes: (2a) a **behavior-preserving** refactor of `create` to delegate its post-apply body to a shared `sendCreate(adapter)`, and (2b) a member-less group-provisioning path used by `ensureGroupMembership`.

- [ ] **Step 1: Read `create` (around lines 140-179)** to capture its exact post-apply body (skip short-circuit → mapping-exists short-circuit → retry/span → POST `toSCIM(false)` → `handleCreateResponse`) and its `ScimClientMetrics` timings.

- [ ] **Step 2: Extract `sendCreate`** — add a private generic helper holding `create`'s body from the `skip` check onward, then make `create` call `adapter.apply(kcModel)` and delegate:

```java
    // Shared create send/persist path: skip + idempotent short-circuit on an
    // existing mapping, then POST and persist the mapping. Used by create()
    // (full apply) and the member-less group-membership provisioning path.
    private <S extends ResourceNode> void sendCreate(Adapter<?, S> adapter) {
        if (adapter.skip) {
            return;
        }
        if (adapter.query("findById", adapter.getId()).getResultList().size() != 0) {
            return; // mapping exists -> created already -> idempotent no-op
        }
        var retry = registry.retry("create");
        try (var span = TRACING.startSpan("scim.create", adapter.getType(), scimApplicationBaseUrl)) {
            ServerResponse<S> response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                try {
                    return scimRequestBuilder
                        .create(adapter.getResourceClass(), ("/" + adapter.getSCIMEndpoint()).formatted())
                        .setResource(adapter.toSCIM(false))
                        .sendRequest();
                } catch (ResponseException e) {
                    throw new RuntimeException(e);
                }
            }));
            span.setHttpStatus(response.getHttpStatus());
            handleCreateResponse(adapter, response);
        }
    }
```
Then `create` becomes (preserve the `APPLY_MODEL_NANOS` timing around `apply`; the `QUERY_NANOS`/`HTTP_NANOS` timings move into or are dropped from `sendCreate` — keep them if trivially preservable, otherwise note their removal in the commit; do NOT change observable create behavior):
```java
    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void create(
            AdapterFactory<M, S, A> factory, M kcModel) {
        var adapter = getAdapter(factory);
        adapter.apply(kcModel);
        sendCreate(adapter);
    }
```
> Behavior-preservation is the bar: the admin GROUP create path (and user create) must behave identically. The `ScimGroupPropagationIT` and `ScimPropagationFromLdapIT` ITs cover this. If preserving the exact `ScimClientMetrics` timing is awkward across the split, prefer correctness + a one-line note over contorting the code; the metrics are diagnostic only.

- [ ] **Step 3: Add the member-less provisioning path + rewire `ensureGroupMembership`:**

```java
    // Provision the group for membership propagation WITHOUT enumerating its
    // members. The member-enumerating create()/apply(GroupModel) re-imports
    // every member on a federated group and triggers an unbounded re-import
    // loop; this path sets id + displayName + scim-skip only.
    // Package-private (not private) so EnsureGroupMembershipTest can spy-verify it.
    void provisionGroupForMembership(
            AdapterFactory<GroupModel, Group, GroupAdapter> factory, GroupModel group) {
        var adapter = getAdapter(factory);
        adapter.applyForProvisioning(group);
        sendCreate(adapter);
    }
```
> **Visibility matters:** `provisionGroupForMembership` MUST be package-private (no modifier), not `private`. `EnsureGroupMembershipTest` is a Mockito spy in the same package (`sh.libre.scim.core`); a `private` method can't be stubbed/verified, so the spy would execute the real provisioning → `getAdapter` → `new GroupAdapter(session,…)` → `session.getProvider(...).getEntityManager()` NPE on the bare session mock. Package-private makes it spy-stubbable.
In `ensureGroupMembership`, replace `this.create(factory, group);` with `provisionGroupForMembership(factory, group);`:
```java
        if (this.model.get(GROUP_PATCH_OP_KEY, false)) {
            provisionGroupForMembership(factory, group);
        }
        this.patchGroupMembership(factory, groupId, userId, true);
```

- [ ] **Step 4: Update `EnsureGroupMembershipTest` (REQUIRED — it breaks otherwise).** The spy-based test stubbed/verified the public `create`; `ensureGroupMembership` now calls `provisionGroupForMembership` instead. Because that method is package-private (Step 3), the spy CAN stub/verify it — do so (replace `create` with `provisionGroupForMembership` in the spy stubs/verifications, keeping the `any()` factory matcher):
  - `groupPatchOpOn_ensuresGroupThenAddsMember`: change to `doNothing().when(client).provisionGroupForMembership(any(), eq(group));` + `doNothing().when(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));`, then `order.verify(client).provisionGroupForMembership(any(), eq(group));` then `order.verify(client).patchGroupMembership(...)`. (Drop the `create` stub/verify entirely.)
  - `groupPatchOpOff_…` (the `group-patchOp=false` case): change `verify(client, never()).create(...)` to `verify(client, never()).provisionGroupForMembership(any(), any())`; it still verifies `patchGroupMembership` IS called.
  - `missingLocalGroup_isSkipped`: unchanged (still `never()` on `patchGroupMembership`; also assert `never()` on `provisionGroupForMembership`).
  Then `./gradlew test` — all green. (Do NOT try to spy a `private` method; that is the failure mode Step 3 avoids.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimClient.java \
        src/test/java/sh/libre/scim/core/EnsureGroupMembershipTest.java
git commit -m "fix(ldap): provision groups member-lessly in ensureGroupMembership (breaks re-import loop)"
```

---

## Chunk 2: Verification

### Task 3: Re-measurement spike (the mandatory verification gate)

**Files:**
- Temporary instrumentation in `src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java` + a throwaway IT (both reverted at the end — commit nothing).

Mirror the earlier measurement spike. Requires Docker.

- [ ] **Step 1: Instrument** `onImportUserFromLDAP` with the same INFO log used before: `LOGGER.infof("SPIKE onImportUserFromLDAP user=%s isCreate=%s thread=%s call=%d", user.getUsername(), isCreate, Thread.currentThread().getName(), COUNTER.incrementAndGet());` (add a static `AtomicLong COUNTER`).

- [ ] **Step 2: Throwaway spike IT** `SpikeReimportFixedIT` (model on `ScimLdapGroupMembershipIT`): `newRealmWithScimAndLdapGroups(...)` with both propagations + `group-patchOp=true`, stub the sink, run ONE `triggerFullSync`, settle a few seconds, then `keycloak.getLogs()`, extract `SPIKE onImportUserFromLDAP` lines and print: total count, per-username count, count on `scim-dispatch-*` threads (the re-imports). Also print `memberAddPatchCount()` and `/Groups` POSTs.

- [ ] **Step 3: Run + assert the loop is gone.** Run `./gradlew integrationTest --tests "sh.libre.scim.integration.SpikeReimportFixedIT" --rerun-tasks`. **PASS criteria:** `onImportUserFromLDAP` fires ≈ once per user (a small constant — NOT hundreds/thousands), with **zero (or near-zero) `scim-dispatch`-thread re-imports**, and member-add PATCHes ≈ member count. Compare to the pre-fix baseline (2,776 invocations / 1,388 PATCHes for 2 members).

- [ ] **Step 4: Record the numbers, then REVERT** the instrumentation + delete the spike IT. `git status` clean; commit nothing.

> **STOP-and-surface:** if the loop is NOT gone (still hundreds of invocations or many `scim-dispatch` re-imports), report the residual trigger (e.g. the `group-patchOp=false` `replace` path, or a `SCOPE_USER`/`UserAdapter` path) with its stack before proceeding — do not paper over it.

### Task 4: Confirm existing ITs green

**Files:**
- Possibly tighten: `src/integrationTest/java/sh/libre/scim/integration/ScimLdapGroupMembershipIT.java`

- [ ] **Step 1: Run the group/LDAP ITs** — `./gradlew integrationTest --tests "sh.libre.scim.integration.ScimLdapGroupMembershipIT" --tests "sh.libre.scim.integration.ScimGroupPropagationIT" --tests "sh.libre.scim.integration.ScimGroupReconcileIT"` (Docker). All pass. Run the membership IT 2-3× to confirm non-flaky now that the storm is gone.

- [ ] **Step 2: Revisit the bounded group-POST assertion.** `groupProvisionedOnceAcrossMultipleMembers` currently asserts `1 ≤ groupPosts ≤ 2` (the concurrent-create double-POST was *amplified* by the storm). With the storm gone, observe the actual count across the 2-3 runs. If it is reliably `1`, tighten back to `assertEquals(1, …)` and update the comment; if it can still be `2` (the check-then-act race persists independently of the storm — see the roadmap follow-up), keep the bound. Decide based on the observed runs, not assumption.

- [ ] **Step 3: Commit** any IT tweak (only if changed):
```bash
git add src/integrationTest/java/sh/libre/scim/integration/ScimLdapGroupMembershipIT.java
git commit -m "test(ldap): tighten group-provisioning assertion now that the re-import storm is fixed"
```

---

## Chunk 3: Documentation

### Task 5: Update the roadmap

**Files:**
- Modify: `docs/roadmap.md`

- [ ] **Step 1:** Update the **"Redundant per-sync membership re-assertions"** entry — it mis-framed this as benign chattiness; it was actually an unbounded re-import **loop** (`GroupAdapter.apply(GroupModel)` enumerating members on a federated group re-imported them). Mark it **fixed**: `ensureGroupMembership` now provisions groups member-lessly (`applyForProvisioning`), breaking the loop; verified by re-measurement (≈1 invocation/user vs 2,776). If a residual non-fanning re-assertion remains worth optimizing, note it; otherwise close it.

- [ ] **Step 2:** Update the **"Concurrent group provisioning can double-POST"** entry — note the runaway storm that amplified it is gone; whether the underlying check-then-act race still warrants an atomic-provisioning fix depends on Task 4's observation (reference it).

- [ ] **Step 3: Commit**
```bash
git add docs/roadmap.md
git commit -m "docs: federated re-import loop fixed via member-less group provisioning"
```

---

## Done criteria

- [ ] `./gradlew test` green (incl. `GroupProvisioningApplyTest`).
- [ ] Re-measurement shows the loop is gone (≈1 `onImportUserFromLDAP` per user, no `scim-dispatch` re-imports) — the verification gate.
- [ ] `ScimLdapGroupMembershipIT`, `ScimGroupPropagationIT`, `ScimGroupReconcileIT` green (Docker); membership IT non-flaky across runs.
- [ ] Admin `create`/`replace` behavior unchanged (full member enumeration preserved).
- [ ] Roadmap updated. No CHANGELOG.md edit.
```
