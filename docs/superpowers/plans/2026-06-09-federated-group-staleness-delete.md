# Federated Group Delete via Staleness — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the SCIM resource for any LDAP-federated group that has gone stale (no longer re-stamped by federation import), so deletes propagate and renames resolve as delete-old + create-new.

**Architecture:** Reuse the existing user-reconciler staleness machinery for groups. Stamp the group's `ldap-federation-last-seen` attribute on every federation import (in the LDAP mapper's group dispatch). The reconciler's group phase deletes a mapped group whose local model is gone (orphan) or whose `last-seen` is older than the threshold. Remove the dead in-place-rename machinery built under the superseded orphan-based design.

**Tech Stack:** Java 21, Keycloak SPI, Captain Goldfish SCIM SDK, JUnit 5 + Mockito (unit), Testcontainers + OpenLDAP + WireMock (integration).

**Spec:** `docs/superpowers/specs/2026-06-09-federated-group-staleness-delete-design.md`

---

> ## ⚠️ REVISION — pivot to member-presence (Tasks 1–5 below are SUPERSEDED)
>
> Tasks 1–5 below (timestamp staleness: stamp a group `last-seen`, delete when
> older than a threshold) were implemented and committed, then a defect was
> found: the per-member group-attribute stamp races across the async dispatch
> workers (concurrent `GROUP_ATTRIBUTE` writes → `StaleStateException`,
> poisoning the membership dispatch and the reconcile transaction). A spike
> established a race-free replacement: **a federated group no longer backed by
> LDAP is drained to zero members locally** (confirmed for rename and delete).
>
> The work pivots to **member-presence**: delete a mapped group with zero
> members (or a gone local model). No group-attribute write, no threshold, no
> timing dependency. See the revised spec.
>
> **Starting point:** Tasks 1–5 below are ALL committed as the
> timestamp-staleness implementation (isStale extracted, `classifyGroup`
> staleness-based, the group stamp present in `ScimLdapStorageMapper`,
> `ScimGroupReconcileIT` with aging + 500-retry). The **Adjustment Tasks (AT)**
> below revert/adjust that committed code into the member-presence design —
> execute the ATs; do NOT re-do Tasks 1–5.
>
> - **AT-1 — Revert the group `last-seen` stamp.** In `ScimLdapStorageMapper`'s
>   `SCOPE_GROUP` dispatch, remove the `group.setSingleAttribute(LAST_SEEN_ATTRIBUTE,
>   …)` line **and the now-stale 5-line `// Liveness: …` comment above it**,
>   collapsing the block lambda back to the single-statement form
>   `group -> client.ensureGroupMembership(GroupAdapter::new, group.getId(), userId)`.
>   This removes the concurrency defect.
> - **AT-2 — `classifyGroup` → member-presence.** In `ReconcilerRunner`: change
>   `classifyGroup` to `static GroupAction classifyGroup(GroupModel group,
>   boolean hasMembers)` → `group == null` → DELETE; `!hasMembers` → DELETE;
>   else KEEP. In `reconcileGroups()`, compute `hasMembers` per group via
>   `session.users().getGroupMembersStream(realm, group).findAny().isPresent()`
>   and pass it in; drop the `Instant now` / `staleThreshold` usage in the group
>   path (the user phase keeps them). **Replace the entire `GroupActionTest`
>   file contents** with the three member-presence cases (null → DELETE,
>   no-members → DELETE, has-members → KEEP) calling the new two-arg
>   `classifyGroup(group, hasMembers)`; the old three-arg staleness cases are
>   DELETED, not kept (they won't compile against the new signature). TDD:
>   rewrite the test first, watch it fail, then implement.
> - **AT-3 — Remove the now-unused `isStale`.** After AT-2, `StaleAttributeWitness.isStale`
>   has no caller (the user witness's `evaluate` was never routed through it).
>   Remove the `isStale` static and its `StaleAttributeWitnessTest` cases (keep
>   the pre-existing `evaluate`/vote tests). Confirm `grep -rn "isStale" src/`
>   is empty afterward.
> - **AT-4 — Rewrite `ScimGroupReconcileIT` for member-presence.** Remove ALL
>   aging machinery (`setGroupAttribute`/`ageGroupLastSeen`/`awaitLastSeenQuiescent`)
>   and the `postReconcileOk` 500-retry (no group-attribute write ⇒ no
>   `StaleStateException`; use a plain `postReconcile`). Three scenarios:
>   (1) **delete** — provision, `deleteLdapEntry`, full sync (drains members to
>   0), reconcile → assert SCIM `DELETE` for the group's SCIM id; (2) **rename**
>   — provision `engineers`, rename cn→`engineers-team`, full sync (old drained,
>   new provisioned), reconcile → assert DELETE for OLD `engineers` id, new
>   `engineers-team` POSTed and NOT deleted; (3) **live** — provision, keep
>   members, reconcile → assert NO `DELETE /Groups/.*`. Keep the
>   distinct-SCIM-id-per-group stub (`stubScimGroupCreateReturning`) from the
>   prior IT so the two groups' DELETEs are distinguishable. Deterministic
>   sequencing: provision → await member-add PATCHes → (rename + sync + await new
>   group POST) → reconcile (no aging step). Run 2–3× to confirm non-flaky.
> - **AT-5 — Docs (was Task 6).** Document member-presence (group with no members
>   → deleted), rename = delete-old + create-new, the transient duplicate
>   bounded by the reconcile interval, and that groups need NO threshold (the
>   `> fullSyncPeriod` validation is user-only). Drop any timestamp/last-seen
>   group language.
>
> Everything below this banner is the superseded timestamp plan, retained for
> history.

---

**Context — current committed state (this branch):** The orphan-based C work is committed. `ReconcilerRunner` has `enum GroupAction { DELETE, RENAME, NOOP }`, `classifyGroup(GroupModel)` (drift-based), `reconcileGroups()` returning `GroupCounts(deleted, renamed)`, `dispatchGroupOp(..., boolean isDelete)`, and `ReconcileResult(usersDeleted, groupsDeleted, groupsRenamed)`. `ScimClient` has `reconcileGroupName`; `GroupAdapter` has `toDisplayNamePatchBuilder` + `SYNCED_NAME_ATTRIBUTE`. Unit tests `ReconcileGroupNameTest`, `GroupDisplayNamePatchTest`, `GroupActionTest`. IT `ScimGroupReconcileIT` has gap-pinning scenarios. This plan reshapes all of that.

---

## Chunk 1: Reshape the reconciler to staleness

### Task 1: Extract a shared `isStale` helper

**Files:**
- Modify: `src/main/java/sh/libre/scim/reconcile/StaleAttributeWitness.java`
- Test: `src/test/java/sh/libre/scim/reconcile/StaleAttributeWitnessTest.java` (create if absent; else add cases)

Extract the parse-and-compare core so the group phase can reuse it without going through the `UserModel`-typed witness interface.

- [ ] **Step 1: Write the failing test**

```java
package sh.libre.scim.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StaleAttributeWitnessTest {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");
    private static final Duration THRESHOLD = Duration.ofHours(48);

    @Test
    void freshTimestamp_isNotStale() {
        String seen = NOW.minus(Duration.ofHours(1)).toString();
        assertThat(StaleAttributeWitness.isStale(seen, THRESHOLD, NOW)).isFalse();
    }

    @Test
    void oldTimestamp_isStale() {
        String seen = NOW.minus(Duration.ofHours(72)).toString();
        assertThat(StaleAttributeWitness.isStale(seen, THRESHOLD, NOW)).isTrue();
    }

    @Test
    void absent_isNotStale() {
        assertThat(StaleAttributeWitness.isStale(null, THRESHOLD, NOW)).isFalse();
    }

    @Test
    void unparseable_isNotStale() {
        assertThat(StaleAttributeWitness.isStale("not-a-timestamp", THRESHOLD, NOW)).isFalse();
    }
}
```

- [ ] **Step 2: Run, watch it fail** — `./gradlew test --tests "sh.libre.scim.reconcile.StaleAttributeWitnessTest"` (isStale missing).

- [ ] **Step 3: Implement** — add the static to `StaleAttributeWitness` and refactor `evaluate` to use it:

```java
    /**
     * Returns true when {@code raw} is a parseable ISO-8601 instant older than
     * {@code threshold} before {@code now}. Absent or unparseable values are
     * NOT stale (the abstain-safe default — never delete on missing/corrupt data).
     */
    public static boolean isStale(String raw, Duration threshold, Instant now) {
        if (raw == null) {
            return false;
        }
        try {
            return Instant.parse(raw).isBefore(now.minus(threshold));
        } catch (Exception e) {
            LOGGER.warnf("unparseable liveness attribute %s; treating as not-stale", raw);
            return false;
        }
    }
```
**Leave `evaluate(UserModel)` UNCHANGED** — do not refactor it to call `isStale`. The witness distinguishes three votes (PRESENT / ABSENT / **ABSTAIN**) and must keep returning ABSTAIN for both absent *and* unparseable attributes; `isStale` is a two-valued boolean (unparseable → `false`), so routing `evaluate` through it would silently change unparseable from ABSTAIN to PRESENT. Just ADD the `isStale` static alongside the existing `evaluate`. The small duplication of the parse/compare is deliberate — correctness of the witness's abstain semantics over DRY. (Add a one-line comment on `isStale` noting it intentionally mirrors `evaluate`'s comparison but collapses absent/unparseable to "not stale" for the group path.)

- [ ] **Step 4: Run** — the new test passes AND the existing user-reconciler tests still pass: `./gradlew test`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/reconcile/StaleAttributeWitness.java \
        src/test/java/sh/libre/scim/reconcile/StaleAttributeWitnessTest.java
git commit -m "refactor(reconcile): extract reusable isStale from StaleAttributeWitness"
```

### Task 2: Reshape the group phase to delete-or-keep (staleness)

**Files:**
- Modify: `src/main/java/sh/libre/scim/reconcile/ReconcilerRunner.java`
- Modify: `src/main/java/sh/libre/scim/reconcile/ScimReconcileResourceProvider.java`
- Modify: `src/main/java/sh/libre/scim/reconcile/ReconcilerScheduler.java`
- Test: `src/test/java/sh/libre/scim/reconcile/GroupActionTest.java` (rewrite)

- [ ] **Step 1: Rewrite the classifier test** — replace `GroupActionTest` with staleness cases:

```java
package sh.libre.scim.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;

import sh.libre.scim.ldap.ScimLdapStorageMapper;
import sh.libre.scim.reconcile.ReconcilerRunner.GroupAction;

class GroupActionTest {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");
    private static final Duration THRESHOLD = Duration.ofHours(48);

    private GroupModel groupWithLastSeen(String lastSeen) {
        var g = mock(GroupModel.class);
        when(g.getFirstAttribute(ScimLdapStorageMapper.LAST_SEEN_ATTRIBUTE)).thenReturn(lastSeen);
        return g;
    }

    @Test
    void nullGroup_isDelete() { // orphan backstop
        assertThat(ReconcilerRunner.classifyGroup(null, THRESHOLD, NOW)).isEqualTo(GroupAction.DELETE);
    }

    @Test
    void staleLastSeen_isDelete() {
        var g = groupWithLastSeen(NOW.minus(Duration.ofHours(72)).toString());
        assertThat(ReconcilerRunner.classifyGroup(g, THRESHOLD, NOW)).isEqualTo(GroupAction.DELETE);
    }

    @Test
    void freshLastSeen_isKeep() {
        var g = groupWithLastSeen(NOW.minus(Duration.ofHours(1)).toString());
        assertThat(ReconcilerRunner.classifyGroup(g, THRESHOLD, NOW)).isEqualTo(GroupAction.KEEP);
    }

    @Test
    void absentLastSeen_isKeep() { // never-stamped (e.g. local group) — never delete on missing data
        var g = groupWithLastSeen(null);
        assertThat(ReconcilerRunner.classifyGroup(g, THRESHOLD, NOW)).isEqualTo(GroupAction.KEEP);
    }
}
```

- [ ] **Step 2: Run, watch it fail** — `./gradlew test --tests "sh.libre.scim.reconcile.GroupActionTest"`.

- [ ] **Step 3: Reshape `ReconcilerRunner`:**
  - `enum GroupAction { DELETE, KEEP }` (drop `RENAME`, `NOOP`).
  - Replace `classifyGroup`:
    ```java
    /**
     * Classifies a group mapping for the staleness reconciler. A null group
     * (local model gone) is an orphan → DELETE (forward-compatible backstop).
     * A present group whose ldap-federation-last-seen is stale → DELETE. Fresh
     * or absent (never stamped, e.g. a local group) → KEEP. No federation
     * filter: a never-stamped group has an absent attribute and is kept.
     */
    static GroupAction classifyGroup(GroupModel group, Duration threshold, Instant now) {
        if (group == null) {
            return GroupAction.DELETE;
        }
        String lastSeen = group.getFirstAttribute(ScimLdapStorageMapper.LAST_SEEN_ATTRIBUTE);
        return StaleAttributeWitness.isStale(lastSeen, threshold, now)
            ? GroupAction.DELETE : GroupAction.KEEP;
    }
    ```
  - `reconcileGroups()`: keep the Phase-1 scan but only a `toDelete` bucket (drop `toRename`); compute `Instant now = Instant.now();` once and pass `staleThreshold, now` into `classifyGroup`. Return an `int` (deleted count) — drop the `GroupCounts` record. Phase-2 dispatches deletes only.
  - `dispatchGroupOp(...)`: drop the `boolean isDelete` param and the rename branch; the worker always calls `workerClient.delete(GroupAdapter::new, groupId)`.
  - `run()`: `int groupsDeleted = reconcileGroups(); return new ReconcileResult(usersDeleted, groupsDeleted);`
  - `ReconcileResult`: drop `groupsRenamed` → `record ReconcileResult(int usersDeleted, int groupsDeleted) {}`.
  - Imports: keep `GroupModel`, `GroupAdapter`, add `java.time.Duration`/`java.time.Instant` if missing; `ScimLdapStorageMapper` is already imported (the user phase uses `LAST_SEEN_ATTRIBUTE`).

- [ ] **Step 4: Update the two `ReconcileResult` consumers (drop `groupsRenamed`):**
  - `ScimReconcileResourceProvider`: JSON becomes `"{\"deleted\":" + result.usersDeleted() + ",\"groupsDeleted\":" + result.groupsDeleted() + "}"` (remove the `groupsRenamed` key). Update the class Javadoc.
  - `ReconcilerScheduler`: log line drops `groups renamed=%d` → e.g. `"Reconciler %s: users deleted=%d; groups deleted=%d"`.

- [ ] **Step 5: Run** — `./gradlew compileJava test` (GroupActionTest + full suite green; no references to the removed `RENAME`/`groupsRenamed` remain).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/sh/libre/scim/reconcile/ src/test/java/sh/libre/scim/reconcile/GroupActionTest.java
git commit -m "feat(reconcile): group phase deletes stale federated groups"
```

### Task 3: Remove the dead in-place-rename machinery

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (remove `reconcileGroupName`)
- Modify: `src/main/java/sh/libre/scim/core/GroupAdapter.java` (remove `toDisplayNamePatchBuilder` + `SYNCED_NAME_ATTRIBUTE`)
- Delete: `src/test/java/sh/libre/scim/core/ReconcileGroupNameTest.java`
- Delete: `src/test/java/sh/libre/scim/core/GroupDisplayNamePatchTest.java`

After Task 2, nothing references these. Pure removal.

- [ ] **Step 1: Confirm no remaining references** — `grep -rn "reconcileGroupName\|toDisplayNamePatchBuilder\|SYNCED_NAME_ATTRIBUTE" src/`. Expect only the definitions + the two test files (which are deleted).

- [ ] **Step 2: Remove** the `reconcileGroupName` method from `ScimClient`, the `toDisplayNamePatchBuilder` method and `SYNCED_NAME_ATTRIBUTE` constant from `GroupAdapter`, and `git rm` the two test files.

- [ ] **Step 3: Run** — `./gradlew test` (full suite green; nothing references the removed symbols).

- [ ] **Step 4: Commit**

```bash
git rm src/test/java/sh/libre/scim/core/ReconcileGroupNameTest.java \
       src/test/java/sh/libre/scim/core/GroupDisplayNamePatchTest.java
git add src/main/java/sh/libre/scim/core/ScimClient.java src/main/java/sh/libre/scim/core/GroupAdapter.java
git commit -m "refactor(group): remove dead in-place-rename machinery (superseded by staleness)"
```

---

## Chunk 2: Liveness stamping

### Task 4: Stamp group `last-seen` on federation import

**Files:**
- Modify: `src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java`

The mapper's `onImportUserFromLDAP` already has a `SCOPE_GROUP` async dispatch that iterates the imported user's groups and calls `ensureGroupMembership` per group. Stamp each group's `last-seen` there — in the `ldap` package, which already owns `LAST_SEEN_ATTRIBUTE` (avoids a `core→ldap` package cycle). The stamp is unconditional and precedes the SCIM call, so it records "group seen in LDAP this sync" regardless of whether the membership PATCH succeeds or the lazy-import race skips it.

- [ ] **Step 1: Implement.** In the `SCOPE_GROUP` `runAsync` lambda, change the `forEach` to stamp before ensuring membership:

```java
            u.getGroupsStream().forEach(group -> {
                // Liveness: record that this group was seen in LDAP this sync,
                // before any SCIM call. The reconciler's group phase deletes
                // groups whose last-seen has gone stale (renamed-away / deleted
                // in LDAP). Stamped unconditionally so a skipped membership PATCH
                // (lazy-import race) does not look like the group went stale.
                group.setSingleAttribute(LAST_SEEN_ATTRIBUTE, Instant.now().toString());
                client.ensureGroupMembership(GroupAdapter::new, group.getId(), userId);
            });
```
> The only change is converting the existing single-statement lambda
> `group -> client.ensureGroupMembership(GroupAdapter::new, group.getId(), userId)`
> into a block lambda and adding the `setSingleAttribute` line before the
> (unchanged) `ensureGroupMembership` call. `LAST_SEEN_ATTRIBUTE` and
> `java.time.Instant` are already imported in this file (the user stamp at the
> top of `onImportUserFromLDAP` uses both).

- [ ] **Step 2: Verify compile** — `./gradlew compileJava`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java
git commit -m "feat(ldap): stamp group last-seen on federation import for staleness reconcile"
```

---

## Chunk 3: Integration coverage + docs

### Task 5: Rewrite the integration scenarios for staleness

**Files:**
- Rewrite: `src/integrationTest/java/sh/libre/scim/integration/ScimGroupReconcileIT.java`

Replace the prior gap-pinning scenarios with three positive staleness scenarios. Reuse: the deterministic-provisioning pattern from `ScimLdapGroupMembershipIT` (await group/user POSTs, re-sync); `findGroupByName(RealmResource, String)` (added by the spike); `postReconcile(realm, componentId, thresholdHours)`; `deleteLdapEntry` / `renameLdapEntry` (the latter added in the prior IT work — verify it exists; else `modifyLdapAttribute` / delete+recreate). Stubs: `stubScimUserCreateOk`, `stubScimGroupCreateOk`, `stubScimGroupPatchOk`, `stubScimGroupDeleteOk`, `stubScimUserDeleteOk` (verify exact names via `grep "void stubScim"`).

**Deterministic staleness strategy (critical — do NOT use `thresholdHours=0`):** `threshold=0` marks *everything* stale (including freshly-stamped users and the new group), which caused churn/flakiness in earlier work. Instead, **set the target group's `last-seen` to an aged timestamp via the admin API** (e.g. 100 hours ago) AFTER the final sync that would otherwise re-stamp it, then reconcile with a normal threshold (`thresholdHours=48`). This precisely ages exactly the group under test while leaving freshly-imported users and the new group fresh. Add a small helper to set a group attribute, e.g. `setGroupAttribute(RealmResource, String groupId, String attr, String value)` using `groups().group(id).update(rep)` with the attribute on the `GroupRepresentation`.

**CRITICAL ORDERING — the group stamp is now ASYNC.** Group `last-seen` is stamped inside the `SCOPE_GROUP` `runAsync` worker (Task 4), so a late-landing stamp from the provisioning sync can overwrite the aged value you set, defeating the test. Before calling `setGroupAttribute(...aged...)`, you MUST first wait for the provisioning's async group dispatch to **quiesce**. The stamp runs in the same lambda *before* the member-add PATCH, so observing the expected member-add PATCH count (`awaitMemberAddPatchCount(...)`, the deterministic-provisioning helper) proves the stamp already landed. Sequence every aging scenario as: provision → `awaitMemberAddPatchCount` (stamps landed) → (rename + sync + await new group, if applicable) → **set aged `last-seen`** → `postReconcile`. Do NOT trigger any further sync between setting the aged value and reconciling (reconcile is HTTP-driven and does not re-stamp).

**Carry over the existing IT scaffolding** when rewriting the file — preserve the private helpers/constants the prior version defined that are still useful: `renameLdapEntry`, `createLdapGroup`, `triggerFullSync` (the retrying full-sync wrapper), any `ENGINEERS_DN` constant, and the realm/threshold config setup. Only the scenarios and the new `setGroupAttribute` helper are new.

Use `await().atMost(30, SECONDS)` for CI headroom. Configure `propagation-user=true`, `propagation-group=true`, `group-patchOp=true`, `reconciler-enabled=true` with the threshold/interval config the existing reconciler ITs use; reconcile is driven via `postReconcile` (the prior IT found the background scheduler adds storm with no coverage — keep it out of the way as that test did).

- [ ] **Step 1: Scenario `staleGroupIsDeleted`** — provision `engineers` (deterministic). Capture its SCIM external id (from the POST /Groups WireMock journal, or known mapping). Set engineers' `last-seen` to ~100h ago via the helper. `postReconcile(thresholdHours=48)`. Assert a SCIM `DELETE` to `/Groups/<engineers externalId>` fired, and (sanity) no `DELETE /Users` fired (users are fresh under a 48h threshold).

- [ ] **Step 2: Scenario `renameDeletesOldGroupAndProvisionsNew`** — provision `engineers`; rename the LDAP group `cn` to `engineers-team`; full sync (await the new `engineers-team` POST /Groups → it provisioned fresh, new id). Set the OLD `engineers` group's `last-seen` to ~100h ago (it has no members now, so the sync didn't re-stamp it; set explicitly for determinism). `postReconcile(thresholdHours=48)`. Assert: SCIM `DELETE` for the old `engineers` external id fired; `engineers-team` was POSTed and NOT deleted.

- [ ] **Step 3: Scenario `freshGroupIsNotDeleted`** (regression guard for "stable group not wrongly deleted") — provision `engineers`, re-sync so its `last-seen` is fresh (do NOT age it). `postReconcile(thresholdHours=48)`. Assert NO SCIM `DELETE` to `/Groups/.*` fired. This is the direct guard for the concern that a stable group is not deleted.

- [ ] **Step 4: Run + commit** — `./gradlew integrationTest --tests "sh.libre.scim.integration.ScimGroupReconcileIT"`, 2-3× with `--rerun-tasks` to confirm non-flaky (Docker).

```bash
git add src/integrationTest/
git commit -m "test(reconcile): cover federated group staleness delete + rename-as-recreate + liveness guard"
```

### Task 6: Documentation

**Files:**
- Modify: `docs/roadmap.md`
- Modify: `docs/ldap-federation-support.md`

- [ ] **Step 1: `docs/roadmap.md`** — update the "Group rename and delete for federated groups" item to `_Done (delete-based)._`: federated group deletes (and renames, as delete-old + create-new) now propagate via a staleness pass in the reconciler (group `last-seen` stamped on import; stale → SCIM DELETE). Note the accepted limitations: rename yields a new SCIM id + a transient duplicate until the stale threshold elapses; relies on periodic full sync within the threshold (the existing `> fullSyncPeriod` validation). Reference `ScimGroupReconcileIT`.

- [ ] **Step 2: `docs/ldap-federation-support.md`** — document the group staleness reconciliation: the `ldap-federation-last-seen` group stamp, the delete-on-stale rule (orphan backstop + staleness), reuse of the existing `reconciler-enabled` + threshold + `> fullSyncPeriod` validation, that rename = delete-old + create-new (new id, transient duplicate), and the no-slack-margin caveat (a delayed/failed full sync with a marginal threshold can wrongly delete; set the threshold comfortably above the full-sync period). Remove/correct any text from the earlier orphan-based or `group-patchOp=false`-rename notes that no longer applies.

- [ ] **Step 3: Commit**

```bash
git add docs/roadmap.md docs/ldap-federation-support.md
git commit -m "docs(reconcile): document federated group staleness delete + rename-as-recreate"
```

---

## Done criteria

- [ ] `./gradlew test` passes (incl. `StaleAttributeWitnessTest`, `GroupActionTest`).
- [ ] `./gradlew integrationTest --tests "sh.libre.scim.integration.ScimGroupReconcileIT"` passes (Docker), non-flaky across runs.
- [ ] No references remain to `reconcileGroupName` / `toDisplayNamePatchBuilder` / `SYNCED_NAME_ATTRIBUTE` / `groupsRenamed` / `GroupAction.RENAME`.
- [ ] Existing user-reconciler ITs still green (response keeps `deleted`; `groupsRenamed` key removal asserted nowhere).
- [ ] Docs updated. No CHANGELOG.md edit.
```
