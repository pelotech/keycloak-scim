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
   multiple SCIM providers. The attribute is keyed by the SCIM component
   id available to the worker's `ScimClient`. It mirrors the existing
   `LAST_SEEN_ATTRIBUTE` pattern (a mapper-owned user attribute), stored
   multi-valued (`setAttribute(name, List)`) rather than as a delimited
   string.

4. **The diff runs in the `SCOPE_GROUP` worker, against the committed
   view.** The worker already re-fetches the user (`getUserById`) to read
   committed group state. There it computes `current` (group ids),
   reads `stored` (the per-component attribute), applies removals
   (`removed = stored − current` → `patchGroupMembership(isAdd=false)`),
   runs the unchanged add path, then writes the attribute to `current`.
   Writing once per worker (one per component, keyed per component) keeps
   it race-free.

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

    // removals (new): groups we propagated but the user has left
    stored.stream().filter(g -> !current.contains(g))
          .forEach(g -> client.patchGroupMembership(GroupAdapter::new, g, userId, false));

    // additions (unchanged): re-assert current memberships
    current.forEach(g -> client.ensureGroupMembership(GroupAdapter::new, g, userId));

    // record what we propagated this import
    u.setAttribute(attr, List.copyOf(current));
});
```

The exact factoring (helper method vs. inline; whether `getComponentId`
already exists on `ScimClient` or needs a small accessor) is an
implementation detail for the plan. `patchGroupMembership(..., isAdd=false)`
and the REMOVE filter path (`members[value eq "..."]`) are unchanged and
already covered by tests; a removal whose group has no SCIM mapping is a
no-op (the existing not-found handling), so a never-propagated group in
`stored` does no harm.

### What does not change

- `ensureGroupMembership` and the member-less provisioning path
  (`provisionGroupForMembership`) — unchanged.
- `patchGroupMembership` (both ADD and the REMOVE filter path) — unchanged.
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
- **Transient REMOVE failure:** the SCIM client's existing resilience4j
  retry (429/5xx) covers transient faults. A removal that ultimately
  fails is not retried on a later import (the user is already absent from
  `current`, so it is not re-detected) — acceptable, and consistent with
  how a hard failure surfaces in logs for operator attention.

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
- **Guard:** a sync with **no** membership change emits **no** REMOVE
  PATCH (the stored set already equals current) — protects against
  re-removing on every sync.

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
