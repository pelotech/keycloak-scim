# SCIM Delete Mode — Deactivate Instead of Delete — Design

**Status:** Approved (design phase)
**Date:** 2026-08-26

## Problem

Every user-deprovisioning path in the provider ends in `DELETE /Users/{id}`:
the admin `USER DELETE` event, the account-console `DELETE_ACCOUNT` event, and
the reconciler that detects users vanished from the directory
(`ScimEventListenerProvider`, `ReconcilerRunner`). `ScimClient.delete` sends the
DELETE and drops the local `SCIM_RESOURCE` mapping row.

Some downstream SCIM services must never hard-delete users: their users own
records (registrations, completions, audit history) that outlive access, so
deprovisioning must mean *deactivate* (`active: false`), with the same remote
identity recoverable if the user returns. A hard DELETE both destroys history
and severs the identity: with our mapping gone, a returning user re-enters as a
`POST /Users` create, and identity continuity then depends entirely on the
consumer's create handler resurrecting the account rather than minting a
duplicate.

The user-*disable* path is already deactivate-shaped (`active` maps from
`isEnabled()` in `UserAdapter.apply` and propagates as an ordinary replace).
The gap is user *removal*.

## Goal

An opt-in `delete-mode=deactivate` under which all three deprovisioning paths
mark the remote user `active: false` in place, preserve the local mapping so
reactivation targets the same remote resource, and stay idempotent across
reconciler passes without per-pass HTTP.

**Non-goals:** group deactivation (RFC 7643 Groups have no `active` attribute;
group deletions still go out as `DELETE /Groups/{id}`, and the consumer has
confirmed absorbing org-lifecycle semantics on its side); changing the disable
path (already correct); changing the default (`delete-mode=delete` preserves
today's behavior exactly).

## Design

### Config

New component property `delete-mode`: `LIST_TYPE`, options `delete` |
`deactivate`, default `delete`. Unprefixed kebab-case, matching `auth-mode` /
`rollback-strategy` / `bulk-enabled`. Applies to Users only. Documented in
`docs/configuration.md`.

### Schema

New nullable `DEACTIVATED_AT` timestamp column on `SCIM_RESOURCE`, added by a
new changeset in `META-INF/scim-resource-changelog.xml`. It is a plain column,
not part of the composite PK; the Case-B analysis below depends on that, since
two rows there share an `EXTERNAL_ID` and differ only in KC id. `NULL` means a
live mapping; non-null means we deactivated the remote resource at that
instant. A timestamp rather than a boolean: same cost, and it also answers
"when did this user lose access".

### Choke point: `ScimClient.delete`

The mode gate lives inside `delete()`, so all three emitters inherit it with no
caller changes. When `delete-mode=deactivate`, the adapter type is `User`, and
a mapping exists:

- `user-patchOp=true`: send `PATCH /Users/{externalId}` replacing `active` with
  `false`. One round trip, no GET.
- `user-patchOp=false` (default): `GET /Users/{externalId}`; if the resource is
  already `active: false`, skip the write; otherwise set `active: false` and
  `PUT` the resource back.

On success, set `DEACTIVATED_AT` and keep the mapping row. On transport or
5xx failure: classified exception per the existing taxonomy
(`ScimPropagationException` hierarchy), `DEACTIVATED_AT` not set, so the
next reconciler pass retries. Groups, and Users under `delete-mode=delete`,
take the existing DELETE path untouched.

A 404 on the GET or PUT means the remote resource is already gone; the goal
state ("no access") holds. Mark `DEACTIVATED_AT`, log at INFO, do not error.
The consumer has confirmed 404-as-already-deprovisioned matches its handler
semantics. (This does not reuse the replace path's 404-then-re-create logic;
re-creating a user in order to deactivate them would be backwards.)

### Reconciler

Phase 1 (`ReconcilerRunner`) additionally skips mappings whose
`DEACTIVATED_AT` is set. That is a local column check, so a pass costs zero
HTTP regardless of how many retained deactivated users accumulate. Group
reconciliation is unchanged. Log lines and `ReconcileResult` state which mode
acted.

The skip applies only under `delete-mode=deactivate`. If the mode is later
flipped back to `delete`, flagged mappings are treated like any other orphan
and deleted: the operator asked for deletes. A DELETE for a remote resource
the consumer already removed 404s into a no-op.

### Fourth emitter: `sync-import`

`importResources` (`sync-import=true`) is a fourth deprovisioning-adjacent
path that bypasses `ScimClient.delete`: it enumerates the consumer's `/Users`,
and an unmatched remote resource is either imported locally
(`sync-import-action=CREATE_LOCAL`) or deleted remotely (`DELETE_REMOTE`, a
direct `scimRequestBuilder.delete` rather than `delete()`). Deactivated users
remain in the consumer's `/Users` list, so without scoping, `DELETE_REMOTE`
would hard-delete our own tombstones (violating the never-hard-delete
invariant) and `CREATE_LOCAL` would resurrect deprovisioned users as local
Keycloak accounts. Under `delete-mode=deactivate`, `importResources` therefore
skips unmatched remote resources whose `active` is `false`: they are
tombstones by contract, expected to be locally absent. One conditional, scoped
to deactivate mode; `delete` mode's import behavior is unchanged. The
dangling-mapping cleanup inside `importResources` likewise skips
`DEACTIVATED_AT`-flagged rows, since those are retained on purpose.

### Reactivation

Two cases, split by whether the Keycloak user id survived.

**Case A — local user lingers (same KC id).** The reconciler's core scenario
(the LDAP-deletion gap, Keycloak #35235, leaves the imported local user
behind). When the directory entry returns, Keycloak re-links to the existing
local user by username. The KC id is unchanged, so the preserved mapping is found.
`sendCreate` currently short-circuits whenever a mapping exists; change it to
fall through to `replace` when the existing mapping has `DEACTIVATED_AT`
set. The replace pushes `active: true` (from `isEnabled()`) to the same
remote id. Any successful replace clears `DEACTIVATED_AT`. The short-circuit
for live (unflagged) mappings stays exactly as-is.

**Case B — local user was deleted (new KC id).** Re-creation mints a
fresh KC id; `SCIM_RESOURCE` is keyed by KC id, so the preserved mapping is
not found and the user goes out as `POST /Users`. Identity continuity here is
the consumer's confirmed contract: its create handler resurrects an existing
deactivated user by `userName` and returns the same resource id rather than
minting a duplicate.

**Bulk lane.** With `bulk-enabled=true`, LDAP-import creates route through
`ScimClient.bulkCreateUsers`, which has its own already-mapped skip and
persists mappings via `adapter.saveMapping()`, bypassing both `sendCreate`
and `handleCreateResponse`. Both behaviors extend there. The bulk lane's skip
check treats a `DEACTIVATED_AT`-flagged mapping as "needs reactivation" and
routes that user to an individual `replace` instead of silently skipping;
Case A's trigger, LDAP re-import, is exactly the path the bulk lane serves.
The bulk mapping-save runs the same tombstone purge as the Case-B cleanup
below.

**Case-B cleanup.** After a successful POST stores the new mapping
(`handleCreateResponse`, and the bulk lane's mapping-save), purge any mapping
rows for the same `(realm, component, type, external_id)` that have
`DEACTIVATED_AT` set. The old row shares an `EXTERNAL_ID` with the new live
row (external id is part of the composite PK, so the duplicate insert is
legal). Without cleanup, a later switch back to `delete-mode=delete` would
let the reconciler DELETE a remote id that a live mapping still points to.
Tombstones whose external id never returns stay retained, as intended, and
are harmless under either mode.

### Consumer contract (stated, not assumed)

Confirmed with the consuming service's team:

1. **Resource `id` is the consumer's own stable identifier** and survives
   Keycloak account re-creation. Case-B cleanup depends on this: if the
   consumer echoed the Keycloak uuid as its resource id, a resurrected
   user's create response would never match the tombstone and the cleanup
   would not fire.
2. **`externalId` is the Keycloak user id and is advisory in both
   directions.** It changes on Case-B resurrection; `userName` is the durable
   key.
3. **`POST /Users` resurrection by `userName`** (return the existing
   deactivated user, reactivated, rather than a duplicate) is the consumer's
   gating work item before `delete-mode=deactivate` is enabled against its
   current implementation. The consumer signals readiness.
4. **Group `DELETE`s continue** and the consumer absorbs org-lifecycle
   handling on receipt.

### Rollout

Default stays `delete`; enabling `deactivate` is a per-component realm-config
decision, gated on item 3 above for any consumer whose create path does not
yet resurrect.

### `sync-refresh` respects tombstones

`sync-refresh` replaces every locally-enumerable mapped user on sync. A user
the reconciler deactivated but whose local account lingers (Case A, before the
directory entry returns) would be re-pushed `active: true` by a refresh,
clearing the flag, and the next reconcile pass would deactivate again: a
sustained oscillation, with HTTP on every cycle. (`delete` mode has no
equivalent: the mapping is gone, so refresh's replace no-ops on
mapping-not-found and the system converges.)

Refresh therefore skips `DEACTIVATED_AT`-flagged mappings. The rule:
reactivation requires evidence the user is back, not evidence a stale copy
still exists. The import/create fall-through (Case A, bulk lane) reactivates
on directory-driven re-appearance; an admin acting directly on the user (an
`UPDATE` event replace, e.g. re-enabling them) reactivates on explicit intent;
`sync-refresh`, which merely re-pushes lingering local state, does not. So:
refresh checks the flag and skips; every *other* successful replace clears the
flag, as already specified.

## Failure handling

Reuses the existing typed-exception taxonomy end to end. Deactivation failures
on the event paths are logged per the component's failure-handling settings;
on the reconciler path the unflagged mapping is the retry signal, and the next
pass re-attempts. No new retry machinery.

## Testing

Unit: the mode gate (delete vs deactivate vs group), both verb branches
(PATCH / GET+PUT, including GET-shows-inactive skip), `DEACTIVATED_AT`
set/clear transitions, Case-B purge query, reconciler skip of flagged
mappings, bulk-lane flagged-mapping routing to replace, `sync-import`
tombstone skip (both actions), refresh skip of flagged mappings.

Integration (WireMock, existing harness), with `delete-mode=deactivate`:
admin-delete a user → observe GET+PUT `active: false`, no DELETE, mapping
retained; run a second reconcile pass → zero SCIM traffic for that user;
user reappears (Case A) → replace `active: true` to the same external id,
flag cleared; Case-B create returning a tombstoned external id → old row
purged.

Same branch, separate concern: an integration test proving a `scim="true"`
realm role assigned through the LDAP role mapper lands in the pushed
`User.roles` and survives re-sync: the consumer's directory-driven-roles
feasibility question, currently uncovered (`role-ldap-mapper` appears nowhere
in the suite).

## Alternatives considered

- **GET-check instead of a column** (no schema change; each reconciler pass
  GETs the remote to learn it is already inactive): per-pass HTTP grows
  unboundedly with retained deactivated users. Rejected for unbounded
  worst-case; the column is a routine Liquibase changeset.
- **Blind re-deactivation each pass** (no schema change, no GET): repeated
  writes forever, noisy on both sides. Rejected.
- **Marker role as deactivation state**: the KC user no longer exists on every
  path this feature covers, so a realm role has no subject to attach to; a
  marker pushed into `User.roles` moves the state remotely (collapsing into
  the GET-check option) and pollutes a roles vocabulary consumers treat as a
  frozen allowlist. Rejected.
- **PATCH-only wire behavior**: single round trip but requires server PATCH
  support on Users, unconfirmed for the initial consumer (`user-patchOp=false`
  posture). Instead the write verb honors the existing `user-patchOp` switch.
