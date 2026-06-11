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

2. **Both additions and removals are diff-driven, with success-tracking.**
   The stored set records the groups *successfully* propagated for the
   user; each import sends a single-member ADD only for `current − stored`
   and a single-member REMOVE only for `stored − current`. A steady-state
   re-import (the common case — a full sync re-fires this hook for every
   unchanged user) therefore sends **zero** SCIM PATCHes, eliminating the
   per-sync re-assertion chattiness.

   The robustness that an earlier revision preserved by re-asserting every
   import — the lazy-import lag where an add *skips* because the user's
   SCIM mapping isn't committed yet — is instead preserved by
   **success-tracking**: `ensureGroupMembership` returns whether the add
   actually propagated, and a skipped/failed add is *not* recorded in the
   stored set, so the next import re-attempts it. This requires the ADD
   path's "no SCIM mapping" outcome to mean *not-applied* (retry), the
   mirror of the REMOVE path where "no mapping" means *nothing to remove*
   (done) — see Architecture. (Initial revision re-asserted all additions
   every import and scoped this reduction to a separate roadmap item; it
   was folded in once the federated-storage bookkeeping made it cheap and
   the measurement confirmed the re-assertion was a real recurring cost.)

3. **Storage = a per-component multi-valued attribute in Keycloak's
   federated-user storage** (`UserFederatedStorageProvider`), keyed
   `scim-propagated-groups-<componentId>`, holding the group ids that
   component last propagated for the user. Per-component (not a single
   shared key) because the `SCOPE_GROUP` dispatch **fans out one worker
   per group-propagation component**, each in its own session: a single
   shared key would race — the first component to write it would set
   `stored = current`, masking the diff for every other component.
   Distinct per-component keys are independent, so the workers don't race,
   and the design stays correct when an operator runs multiple SCIM
   providers. **Precondition:** the key uses the SCIM provider's component
   id, reached from the worker's `ScimClient` via its
   `ComponentModel.getId()` — a persisted id, stable across syncs and
   restarts (a small public accessor exposes it).

   **Why federated storage and not a plain user attribute (load-bearing,
   verified by spike):** the obvious choice — `user.setAttribute` like the
   sibling `LAST_SEEN_ATTRIBUTE` — does **not** work here. `LAST_SEEN` is
   written synchronously on the import-thread `UserModel`, inside the
   import transaction, where writes succeed. The membership diff instead
   runs in the **post-commit async worker** on a re-fetched user, and
   under the common `editMode=READ_ONLY` LDAP federation that proxy is
   read-only: `setAttribute`/`removeAttribute` throw
   `ReadOnlyException: Federated storage is not writable`. A spike
   confirmed two hard constraints that force the split: (a) the
   import-thread `user.getGroupsStream()` is **empty** at hook time
   (group memberships aren't materialized until later in the import
   pipeline), so the diff *cannot* run there — only the re-fetched worker
   user sees the real memberships; (b) the worker *can* persist via
   `UserStorageUtil.userFederatedStorage(session)` (equivalently
   `session.getProvider(UserFederatedStorageProvider.class)`), the
   JPA-backed local store Keycloak keeps for federated users — it is
   **not** gated by the LDAP read-only edit mode, and round-trips across
   syncs (`getAttributes`/`setAttribute`/`removeAttribute` keyed by
   `(realm, userId)`). So the diff and the bookkeeping both live in the
   worker, against federated storage — never the read-only user proxy.

4. **The diff runs in the `SCOPE_GROUP` worker, against the committed
   view; both directions are tracked.** The worker already re-fetches the
   user (`getUserById`) to read committed group state. There it computes
   `current` (the user's current group ids), reads `stored` (the
   per-component propagated set), and reconciles both directions as deltas:
   - **Removals:** `removed = stored − current` →
     `patchGroupMembership(isAdd=false)` per group.
   - **Additions:** `added = current − stored` → `ensureGroupMembership`
     per group (groups already in `stored` are skipped — no PATCH).

   It then records what it believes SCIM now reflects. The new stored set
   is `(current ∩ stored) ∪ {adds that applied} ∪ {removes that did not
   apply}` — i.e. already-propagated current groups, plus newly-added
   successes, plus failed removals (which are still in SCIM, to retry). A
   failed/skipped add is simply not added (so the next import re-attempts
   it); a failed remove is retained (so the next import re-attempts it); a
   removal that applied, or had no SCIM mapping to remove, is dropped. This
   requires *both* calls to signal whether they applied (see Architecture).
   Two independent properties of the write: it is keyed per
   component, which is what makes it **race-free** under the worker fan-out
   (Decision 3); and when the new set is empty the federated-storage key is
   **removed** (`removeAttribute`) rather than written as an empty list,
   so the next read sees a clean absence rather than a possibly-ambiguous
   empty value. The key is written once per worker.

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
    var realm = workerSession.getContext().getRealm();
    var u = workerSession.users().getUserById(realm, userId);
    if (u == null) return;
    var current = u.getGroupsStream().map(GroupModel::getId).collect(toSet()); // worker user: real

    // bookkeeping in federated storage (READ_ONLY-safe), NOT the user proxy
    var fed    = workerSession.getProvider(UserFederatedStorageProvider.class);
    var attr   = PROPAGATED_GROUPS_PREFIX + client.getComponentId();
    var sl     = fed.getAttributes(realm, userId).get(attr);
    var stored = sl == null ? new HashSet<String>() : new HashSet<>(sl);

    // removals: groups we propagated but the user has left.
    // keep any that did NOT apply, so the next import retries them.
    var kept = new HashSet<String>();
    stored.stream().filter(g -> !current.contains(g)).forEach(g -> {
        if (!client.patchGroupMembership(GroupAdapter::new, g, userId, false)) kept.add(g);
    });

    // additions: only groups not already propagated (delta), success-tracked.
    var addedOk = new HashSet<String>();
    current.stream().filter(g -> !stored.contains(g)).forEach(g -> {
        if (client.ensureGroupMembership(GroupAdapter::new, g, userId)) addedOk.add(g);
    });

    // record SCIM state = already-propagated current ∪ new successes ∪ failed removals
    var next = new HashSet<>(addedOk);
    current.stream().filter(stored::contains).forEach(next::add);
    next.addAll(kept);
    if (next.isEmpty()) fed.removeAttribute(realm, userId, attr);
    else fed.setAttribute(realm, userId, attr, new ArrayList<>(next));
});
```

The exact factoring (helper method vs. inline; the `getComponentId`
accessor) is an implementation detail for the plan. To signal applied-or-not,
`patchGroupMembership` changes from `void` to `boolean`, and
`ensureGroupMembership` likewise returns whether the add propagated.

The boolean is now **direction-aware on the "no SCIM mapping"
(`NoResultException`) case** — the mirror that makes delta additions safe:
- **REMOVE** with no mapping → **true** (nothing to remove; drop the group
  from `stored`, don't retry a phantom forever).
- **ADD** with no mapping → **false** (the add did *not* propagate — e.g. the
  user's SCIM mapping isn't committed yet, the lazy-import lag — so leave it
  unrecorded and retry next import).

Implemented as `return !isAdd;` in the `NoResultException` branch. Otherwise
**true** on a successful PATCH (and on the `group-patchOp=false` `replace`
fallback), **false** only on `!response.isSuccess()` after retries.
`ensureGroupMembership` returns `false` when the local group is missing, else
the add's result. The admin `GROUP_MEMBERSHIP` event listener still ignores
the return, so its behavior is unchanged. The REMOVE filter path
(`members[value eq "..."]`) itself is unchanged.

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

- **Unit:** the diff — `stored − current` yields exactly the groups to
  REMOVE, `current − stored` exactly the groups to ADD, and an unchanged
  membership yields neither. (Set-diff assertions on the worker's logic.)
- **Integration (`ScimLdapGroupMembershipIT`):** a federated user in an
  LDAP group syncs (member present in SCIM); remove the user from the LDAP
  `groupOfNames`; sync again; assert a single-member **REMOVE** PATCH
  fires for that user/group.
- **Guard — no per-sync re-assertion:** a sync with **no** membership
  change emits **no** SCIM PATCH at all (neither ADD nor REMOVE — both
  diffs are empty); the integration-level mirror asserts an unchanged
  full-sync adds zero member PATCHes. This is the Follow-up A guard.
- **Guard — add success-tracking:** a skipped/failed ADD is **not**
  recorded, so the next import re-attempts it (the lazy-import-lag
  self-heal); a successful ADD is recorded and not re-sent.
- **Guard — failed-removal retry:** when the REMOVE returns `false`, the
  group stays in the stored set and the next import re-emits the REMOVE.
- **Guard — loop safety:** the diff path must not enumerate a group's
  members. The central hazard is that someone "optimizes" the diff toward
  `getGroupMembersStream` and reintroduces the re-import loop. Assert the
  import/diff path triggers no member-set materialization / re-import
  (e.g. the existing re-import-loop measurement stays flat with removals
  active), not merely that the set math is correct.

## Non-goals

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
