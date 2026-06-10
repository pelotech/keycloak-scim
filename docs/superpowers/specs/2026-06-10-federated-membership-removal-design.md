# Federated Membership Removal — Design

## Problem

The LDAP-federated group-membership path is **additions-only**. When a
federated user is imported, `ScimLdapStorageMapper.onImportUserFromLDAP`
dispatches a `SCOPE_GROUP` worker that walks `user.getGroupsStream()` and
calls `ScimClient.ensureGroupMembership` for each group the user is
*currently* in — provisioning the group and adding the member via a
single-member delta PATCH. It never **removes** a membership.

So when a user is dropped from a group in LDAP, the change does not
propagate: the SCIM group still lists them. Like federated group
delete, this is **event-free** — an LDAP-driven membership change fires
no `GROUP_MEMBERSHIP` admin event, so the admin-event remove path
(`ScimEventListenerProvider` → `patchGroupMembership(..., isAdd=false)`,
which already works for admin-initiated removals) never runs.

This is the last functional gap in the federated membership feature.

## Goal

Propagate LDAP-driven membership removals to SCIM, reusing the existing
single-member REMOVE PATCH, **without** reintroducing the re-import loop
that was just fixed (any path that enumerates a federated group's members
via `getGroupMembersStream` re-imports them and re-fires the hook).

## Decisions

1. **Detection = per-user stored-set diff on import (not a reconciler,
   not a SCIM read-back).** On each import, diff the user's *current*
   Keycloak groups against the set we last propagated for them; the
   difference `stored − current` is the set of groups the user was
   removed from. The current set comes from `user.getGroupsStream()` —
   which reads the *user's own* group memberships, **not** any group's
   member list — so it cannot trigger the federated re-import loop. No
   `getGroupMembersStream`, no SCIM GET. A reconciler pass was rejected
   because determining a group's Keycloak members requires
   `getGroupMembersStream` (the loop hazard) plus a SCIM read per group;
   a per-import SCIM read-back was rejected as chatty.

2. **Additions stay as-is (re-assert every import); only removals are
   diff-driven.** Making *additions* delta-only would forfeit the
   "re-assert on every import" robustness the membership feature relies
   on — e.g. the lazy-import lag where an add *skips* because the user's
   SCIM mapping isn't committed yet (the case `ScimLdapGroupMembershipIT`
   resyncs around). A delta-only add would record such a skip as done
   and never retry. So the `getGroupsStream().forEach(ensureGroupMembership)`
   add path is unchanged; the stored set is used **only** to compute
   removals. Reducing the additions chattiness is a separate, already
   tracked roadmap item ("redundant per-sync re-assertions") and is out
   of scope here.

3. **Storage = a per-component multi-valued user attribute**
   (`scim-propagated-groups-<componentId>`), holding the group ids that
   component last propagated for the user. Per-component (not a single
   shared attribute) because the `SCOPE_GROUP` dispatch **fans out one
   worker per group-propagation component**, each in its own session: a
   single shared attribute would race — the first component to write it
   would set `stored = current`, masking the diff for every other
   component. Distinct per-component attributes are independent, so the
   workers don't race, and the design stays correct when an operator runs
   multiple SCIM providers. **Precondition:** the attribute key uses the
   SCIM provider's component id, reached from the worker's `ScimClient`
   via its `ComponentModel.getId()` — a persisted id, stable across syncs
   and restarts (a small package accessor exposes it). The attribute
   mirrors the existing `LAST_SEEN_ATTRIBUTE` pattern (a mapper-owned user
   attribute), stored multi-valued (`setAttribute(name, List)`) rather
   than as a delimited string.

4. **The diff runs in the `SCOPE_GROUP` worker, against the committed
   view; removals are tracked, additions are re-asserted.** The worker
   already re-fetches the user (`getUserById`) to read committed group
   state. There it computes `current` (the user's current group ids),
   reads `stored` (the per-component attribute), and reconciles
   asymmetrically:
   - **Removals (diff-driven):** `removed = stored − current` →
     `patchGroupMembership(isAdd=false)` per group.
   - **Additions (full re-assert, unchanged):** `ensureGroupMembership`
     for **every** group in `current` (not a diff — see Decision 2).

   It then records what it believes SCIM now reflects. Because additions
   re-assert the whole of `current` every import, a failed add self-heals
   on the next import; removals do **not** re-assert, so a removal that
   fails must be retried explicitly. The new stored set is therefore
   `current ∪ {groups whose REMOVE did not apply}` — a failed removal
   stays in `stored` so the next import re-detects and retries it; a
   removal that applied (or had no SCIM mapping to remove) is dropped.
   This requires the removal call to signal whether it was applied (see
   Architecture). Two independent properties of the write: it is keyed per
   component, which is what makes it **race-free** under the worker fan-out
   (Decision 3); and when the new set is empty the attribute is **removed**
   rather than written empty, which is what makes the round-trip
   **reliable** (Keycloak's empty-list write is not a dependable
   round-trip). The attribute is written once per worker.

5. **Empty-group cleanup is delegated, not handled here.** Removing the
   last member leaves the SCIM group memberless; the group-delete
   reconciler (member-presence pass, #32) already reaps memberless mapped
   groups on its next run. This design emits only the member REMOVE.

## Architecture

### Where the diff lives

The orchestration stays in the mapper's `SCOPE_GROUP` worker (it already
holds the re-fetched `UserModel`, the `KeycloakSession`, and the
component-bound `ScimClient`). Sketch:

```
dispatcher.runAsync(SCOPE_GROUP, (client, workerSession) -> {
    var u = workerSession.users().getUserById(realm, userId);
    if (u == null) return;
    var current = u.getGroupsStream().map(GroupModel::getId).collect(toSet());

    var attr    = PROPAGATED_GROUPS_PREFIX + client.getComponentId();
    var stored  = u.getAttributeStream(attr).collect(toSet());

    // removals (new): groups we propagated but the user has left.
    // keep any that did NOT apply, so the next import retries them.
    var kept = new HashSet<String>();
    stored.stream().filter(g -> !current.contains(g)).forEach(g -> {
        boolean applied = client.patchGroupMembership(GroupAdapter::new, g, userId, false);
        if (!applied) kept.add(g);
    });

    // additions (unchanged): full re-assert of current memberships
    current.forEach(g -> client.ensureGroupMembership(GroupAdapter::new, g, userId));

    // record what SCIM now reflects = current ∪ failed-removals
    var next = new HashSet<>(current); next.addAll(kept);
    if (next.isEmpty()) u.removeAttribute(attr);
    else u.setAttribute(attr, List.copyOf(next));
});
```

The exact factoring (helper method vs. inline; the `getComponentId`
accessor) is an implementation detail for the plan. To signal
applied-or-not, the method's signature changes from `void` to `boolean`:

```
boolean patchGroupMembership(factory, groupId, userId, boolean isAdd)
```

The returned value is **only consulted on the REMOVE path** (`isAdd=false`),
where it means: **true** when the PATCH succeeded **or** there is no SCIM
mapping to remove (the existing `NoResultException` skip — nothing to do, so
a never-propagated group in `stored` is correctly dropped, not retried
forever); **false** only on a genuine failure (the `!response.isSuccess()`
branch, after resilience4j's 429/5xx retries are exhausted). The ADD path
(`isAdd=true`) and the `group-patchOp=false` `replace` fallback simply return
`true`; their callers — the admin `GROUP_MEMBERSHIP` event listener and the
`ensureGroupMembership` add path — ignore the return entirely, so their
behavior is unchanged apart from the now-`boolean` (for them, meaningless)
signature. The REMOVE filter path (`members[value eq "..."]`) itself is
unchanged.

### What does not change

- `ensureGroupMembership` and the member-less provisioning path
  (`provisionGroupForMembership`) — unchanged.
- `patchGroupMembership`'s ADD path and REMOVE filter wire shape —
  unchanged. The only edit to the method is the `void → boolean` return
  (consulted on REMOVE only; see Architecture); ADD/event callers are
  behaviorally unchanged.
- The admin `GROUP_MEMBERSHIP` event path — unchanged (still the primary,
  immediate path for admin-initiated add/remove).
- The `SCOPE_USER` dispatch and the `LAST_SEEN_ATTRIBUTE` stamp —
  unchanged.

### Interactions (all benign)

- **Admin events:** admin add/remove still propagate immediately via the
  event listener. The stored set (updated only on import) lags an
  admin-driven change until the next import, then **self-heals** — the
  next diff re-emits the matching add or remove, an idempotent no-op on a
  SCIM server that already reflects it. No conflict.
- **Initial deploy (attribute absent):** `stored = {}` ⇒ `removed = {}`,
  so **no spurious removals**; the attribute is simply populated on the
  first import. No migration.
- **REMOVE failure (transient or hard):** resilience4j (429/5xx) covers
  transient faults inside the call. A removal that still fails returns
  `false`, so the group is **kept in `stored`** and re-attempted on the
  next import — the departed user does not silently linger in the SCIM
  group. The cost is that a *permanently* failing REMOVE (e.g. a server
  that 4xx-es the filter) is retried every import; that is a server/config
  fault that should surface to an operator, and the repeated warn log is
  the signal.
- **Concurrent imports of the same user (same component):** two
  `SCOPE_GROUP` workers for one user in one component would race on the
  attribute, but the race is benign — both read the **same committed**
  group membership, so they compute identical `current`/`removed` sets and
  converge to the same written value. Last-writer-wins is safe. (Distinct
  components never share an attribute; per Decision 3.)

## Testing

- **Unit:** the removal diff — given a stored set and a current set,
  `stored − current` yields exactly the groups to REMOVE, and an
  unchanged membership yields none. (Pure set-diff assertion on the
  worker's logic, mirroring the existing membership unit tests.)
- **Integration (`ScimLdapGroupMembershipIT` or a sibling):** a federated
  user in an LDAP group syncs (member present in SCIM); remove the user
  from the LDAP `groupOfNames`; sync again; assert a single-member
  **REMOVE** PATCH fires for that user/group and the stored attribute no
  longer lists the group.
- **Guard — idempotence:** a sync with **no** membership change emits
  **no** REMOVE PATCH (the stored set already equals current) — protects
  against re-removing on every sync.
- **Guard — failed-removal retry:** when the REMOVE returns `false`, the
  group stays in the stored attribute and the next import re-emits the
  REMOVE (unit-level, with a stubbed failing `patchGroupMembership`).
- **Guard — loop safety:** the diff path must not enumerate a group's
  members. The central hazard is that someone "optimizes" the diff toward
  `getGroupMembersStream` and reintroduces the re-import loop. Assert the
  import/diff path triggers no member-set materialization / re-import
  (e.g. the existing re-import-loop measurement stays flat with removals
  active), not merely that the set math is correct.

## Non-goals

- Reducing the additions re-assertion chattiness (separate roadmap item).
- A reconciler-based membership pass (rejected — `getGroupMembersStream`
  loop hazard + per-group SCIM reads).
- Deleting the now-empty group (handled by the member-presence reconciler,
  #32).
- The `group-patchOp=false` path: there `ensureGroupMembership` defers to
  the full `replace`, which already re-sends the complete member list, so
  removals already propagate via replace; the stored-set diff targets the
  default `group-patchOp=true` delta path.
- **Cleaning up orphaned `scim-propagated-groups-<componentId>`
  attributes.** Deleting and recreating a SCIM provider component leaves
  the old-id attribute orphaned on every previously-synced user; nothing
  reaps it. Accepted as minor debt — the attribute is small and inert, and
  component re-creation is rare. Not handled here.
