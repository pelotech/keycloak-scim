# Federated-Import Re-import Loop Fix — Design

## Problem

The federated group-membership propagation path contains a **runaway
re-import feedback loop**, not merely "chattiness." Measured: one
`triggerFullSync` of a 2-member group produced **2,776
`onImportUserFromLDAP` invocations** and **1,388 member-add PATCHes**,
bounded only by the test's settle window (it does not converge).

**Mechanism (confirmed by a stack-trace spike):**

1. `ScimLdapStorageMapper.onImportUserFromLDAP` dispatches an async
   `SCOPE_GROUP` worker that iterates `user.getGroupsStream()` and calls
   `ScimClient.ensureGroupMembership(GroupAdapter::new, groupId, userId)`
   per group.
2. `ensureGroupMembership` calls `ScimClient.create(GroupAdapter::new,
   group)` to provision the group. `create` calls
   `GroupAdapter.apply(GroupModel)`.
3. `GroupAdapter.apply(GroupModel)` **eagerly enumerates the group's full
   member set**: `session.users().getGroupMembersStream(realm, group)`.
4. For an LDAP-federated `groupOfNames` with `importEnabled=true`,
   enumerating members forces `LDAPStorageProvider` to **import every
   member DN not already local** (`loadUsersByDNs` → `importUserFromLDAP`),
   which **re-fires `onImportUserFromLDAP` (`isCreate=true`)** per member,
   which dispatches another `SCOPE_GROUP` worker → which enumerates the
   members again → unbounded fan-out across the 8-worker pool.

Captured stack (load-bearing frames):
`ensureGroupMembership → create → GroupAdapter.apply(GroupModel) →
getGroupMembersStream → LDAPStorageProvider.importUserFromLDAP →
onImportUserFromLDAP`. The worker's `getUserById` re-fetch is **not** a
re-import trigger (an earlier hypothesis; disproven — no `getUserById`
frame appears in any re-import stack).

This is a real production bug under the feature's required `Import
Users=ON`: every federated sync/import spins an unbounded re-import +
SCIM + DB storm per user. It also explains the earlier CI flakiness and
`StaleStateException` storms.

## Goal

Break the loop at its source: provision the group for membership
**without enumerating its members**, which the delta-PATCH membership flow
does not need. Verify by re-measurement that invocations return to ~1 per
user per sync (no recursion).

## Decisions

1. **Dedicated member-less provisioning path (not a flag on the generic
   `create`).** `ensureGroupMembership` provisions the group via a path
   that sets the group's `id` + `displayName` (+ `scim-skip`) only and
   does **not** call `getGroupMembersStream`. The generic
   `create`/`replace` used by the admin `GROUP` event path are left
   untouched — they legitimately enumerate members for full-group
   create/PUT. This isolates the change away from the admin paths.

2. **Members aren't needed for membership propagation.** The flow is:
   provision the group (id + displayName), then add exactly one member via
   the single-member delta PATCH (`patchGroupMembership`). The group's full
   member list was never required here — sending it was both the loop
   trigger and wasted payload. Omitting it is correct, not a regression.

3. **`group-patchOp=false` path is unaffected by the trigger but still
   reviewed.** When `group-patchOp=false`, `ensureGroupMembership` already
   skips the provisioning `create` and lets `patchGroupMembership`'s full
   `replace` fallback run. `replace` → `GroupAdapter.apply(GroupModel)`
   also enumerates members — so the loop can occur on the non-patchOp path
   too. Scope decision: this design targets the default `group-patchOp=true`
   path (the loop's confirmed locus). The non-patchOp path's enumeration is
   noted as a residual risk to confirm during the post-fix re-measurement;
   if it also loops, it is handled the same way (provision without
   enumeration / avoid the full replace for mere provisioning) — but only
   if measurement shows it.

4. **Verify by re-measurement (mandatory).** After the fix, instrument and
   re-run a single full sync; confirm `onImportUserFromLDAP` fires ~once
   per user (no `scim-dispatch`-thread re-imports) and member-add PATCHes ≈
   member count, not thousands. Also confirm no *secondary* re-import
   trigger remains via the `SCOPE_USER` path. This is a light check, not a
   symmetric risk: `UserAdapter.apply` reads only the importing user's
   *own* `getGroupsStream()` for role mappings — it does **not** materialize
   those groups' member sets, so it cannot fan out across members the way
   the group path does. Measurement just confirms it stays quiet.

## Architecture

### The member-less provisioning

`GroupAdapter` gains a way to populate the group from a `GroupModel`
**without** the member enumeration: it must still set `id`, `displayName`,
**and the `scim-skip` flag** (`group.getFirstAttribute("scim-skip")`) — it
omits *only* the `getGroupMembersStream` block. Dropping the `scim-skip`
read would silently break `create`'s skip short-circuit for skip-flagged
groups, so the contract is: **everything `apply(GroupModel)` does except
member enumeration.** The existing `apply(GroupModel)` (with enumeration)
remains for `create`/`replace`.

`ensureGroupMembership` provisions the group through a dedicated path that
reuses the existing create semantics — idempotent short-circuit on an
existing local mapping, `scim-skip` honored, retry + span, POST via
`toSCIM(false)` (now an empty member list), `handleCreateResponse` →
`saveMapping` — but builds the adapter member-lessly. Whether this reuses
`create`'s send/persist internals via a small extraction or is a focused
group-specific method is an implementation detail for the plan; the
contract is: **provision the group with no member enumeration, idempotently.**

### What does not change

- The admin `GROUP` event path (`create`/`replace` with full member
  enumeration) — unchanged.
- `patchGroupMembership` and the single-member delta PATCH — unchanged
  (never enumerated members).
- The `SCOPE_USER` dispatch and the worker `getUserById` re-fetch —
  unchanged (not the trigger).

### Resulting behavior

`onImportUserFromLDAP` fires once per genuine import; its `SCOPE_GROUP`
worker provisions each group with a member-less POST (first time only,
idempotent thereafter) and adds the importing user via one delta PATCH. No
member enumeration ⇒ no federated re-import ⇒ no recursion. The group ends
up populated by the per-member delta PATCHes as each member imports.

## Testing

- **Unit:** the member-less provisioning produces a group SCIM payload
  with id + displayName and **no members** (wire-shape assertion), and is
  idempotent (short-circuits on an existing mapping).
- **Re-measurement (the key verification):** an instrumented single-sync
  run shows `onImportUserFromLDAP` ≈ one invocation per user (no
  `scim-dispatch`-thread re-imports) and member-add PATCHes ≈ member count
  — replacing the prior 2,776 / 1,388 storm.
- **Existing ITs stay green:** `ScimLdapGroupMembershipIT` (membership
  still provisions the group + adds members; the bounded group-POST
  assertion still holds) and the admin `ScimGroupPropagationIT` (full-group
  create/replace still includes members).

## Non-goals

- Changing the admin `create`/`replace` member enumeration.
- The worker `getUserById` re-fetch (not the trigger).
- A general dispatch-dedup / seen-set (the loop is removed at its source;
  no seen-set is needed). If the post-fix re-measurement reveals residual
  amplification (e.g. the `group-patchOp=false` replace path or a
  `SCOPE_USER` path), address that specifically then — do not pre-build a
  speculative dedup.
