# Federated Group Rename/Delete Propagation — Design

> **SUPERSEDED** by `2026-06-09-federated-group-staleness-delete-design.md`.
> Integration testing disproved this design's core assumption — Keycloak
> does not reconcile stale federated groups in place (an LDAP rename
> materializes a new group and orphans the old one; a delete leaves the old
> one), so orphan-detection and in-place name-drift have no real trigger.
> The successor pivots to a staleness mechanism. Retained for history.

## Problem

LDAP-federated groups propagate their *membership* to SCIM (a federated
user's group memberships are pushed on import via
`ScimClient.ensureGroupMembership`), but the group **resource lifecycle**
does not:

- **Rename.** When an LDAP group's `cn` changes, Keycloak's
  `group-ldap-mapper` updates the local `GroupModel` name on sync, but no
  `GROUP` admin event fires (federation-origin changes never do), so the
  SCIM group's `displayName` goes stale.
- **Delete.** When an LDAP group is removed, no `GROUP DELETE` admin event
  fires, so the SCIM group lingers.

The root cause is the same federation gap that motivated the user-import
mapper: there is **no `onImportGroupFromLDAP` hook** in the
`LDAPStorageMapper` SPI, and admin events do not fire for LDAP-driven
group changes. So there is no event-driven signal for a federated group
rename or delete — detection must be periodic.

## Goal

Propagate federated group renames and deletes to SCIM via a periodic
**group reconciliation pass**, reusing the existing reconciler
infrastructure. Renames push a targeted `displayName` update; deletes
issue a SCIM `DELETE` for orphaned group mappings.

## Decisions

1. **One group reconciler for both rename and delete.** Delete detection
   fundamentally requires a periodic pass (no event to react to), so
   rename folds into the same pass rather than piggybacking on the
   member-import path. This reuses the existing reconciler
   (scheduling, per-component mapping iteration, parallel SCIM ops) and
   **adds nothing to the per-import chattiness** that a separate
   efficiency follow-up will address.

2. **Delete = orphaned-mapping detection, spike-gated.** The reconciler
   issues SCIM `DELETE` for any group mapping whose local Keycloak group
   is gone (`getGroupById == null`) — symmetric with the user
   reconciler's orphan precondition. This is correct only if Keycloak
   removes the local `GroupModel` when its LDAP source group disappears,
   which is an empirical unknown (parallels the user `#35235` gap). A
   spike confirms it. If Keycloak does **not** remove the group, delete
   is deferred (documented gap) and rename ships alone — exactly how the
   user-deletion story deferred to a workaround for `#35235`. A
   staleness-witness approach is explicitly rejected for v1: it would add
   per-import stamping, a config threshold, and a false-positive risk for
   empty/memberless LDAP groups (no members ⇒ never stamped ⇒ wrongly
   deleted).

3. **Rename detection via a stored `scim-synced-name` group attribute.**
   The group mapping does not store the name, so the reconciler compares
   `group.getName()` against a `scim-synced-name` attribute it maintains.
   On drift (or attribute absent) it pushes a `displayName` PATCH and
   updates the attribute; otherwise it does nothing. This is a pure local
   comparison — a network call fires only on an actual rename. The
   attribute lives in `GROUP_ATTRIBUTE`, untouched by the LDAP sync
   (it is not an LDAP-mapped attribute), mirroring how the user
   reconciler's `ldap-federation-last-seen` lives in `USER_ATTRIBUTE`.
   The reconciler **solely owns** this attribute; the import path is not
   modified.

## Architecture

### Placement

Add a group phase to the existing `ReconcilerRunner` rather than a
parallel runner, so operators keep one timer, one `reconciler-enabled`
flag, and one `POST /realms/{realm}/scim-reconcile/{componentId}`
endpoint that reconciles users then groups. The group phase is simpler
than the user phase — no staleness witnesses, since delete is orphan-only
(decision 2).

**Integration with the existing user phase.** The group phase runs
**after** the user phase completes, in the same `KeycloakSession` /
transaction. It mirrors the user phase's two-step shape: a sequential
Phase-1 scan over the component's `Group`-type mappings (queried via the
existing `findByComponentAndType` named query with `type="Group"`) that
classifies each mapping as *delete* (orphaned), *rename* (name drift or
absent attribute), or *no-op*; then a Phase-2 dispatch of the collected
delete and rename operations on the shared worker pool (the same
`CompletableFuture`/`allOf().join()` pattern the user delete phase uses).

**Return shape change (blocker for the implementer).** `ReconcilerRunner.run()`
currently returns an `int` (users deleted). It must change to return a
small result struct carrying `usersDeleted`, `groupsDeleted`, and
`groupsRenamed`. Every caller updates: the manual endpoint
(`ScimReconcileResourceProvider`) serializes the struct to
`{"deleted": N, "groupsRenamed": R, "groupsDeleted": D}` (keeping
`deleted` as the existing user-deletion key for backward compatibility),
and the scheduled-task caller logs the struct. Preserve the existing
`deleted` JSON key so existing operator tooling does not break.

**No federation-origin filter on the group phase.** The user phase skips
*present* local-only users (`getFederationLink() == null`) because its
staleness witness could otherwise delete a present local user. The group
phase has no such risk: it deletes only on **orphan** (the local group is
*gone*), which is the correct outcome for any group whose local model has
been removed — federated or local. A present local group is never a
delete candidate, and its name-drift check is a harmless idempotent
backstop (local groups stay event-driven; the reconciler simply also
cleans up a local group whose admin `GROUP DELETE` event was missed). So
the group phase deliberately does **not** consult any federation link.

### Group phase, per `Group`-type mapping under the component

1. Fetch the local `GroupModel` by the mapping's Keycloak id.
2. **Gone** (`getGroupById == null`) → orphaned → SCIM `DELETE` + clear
   mapping (reuse `ScimClient.delete(GroupAdapter::new, groupId)`).
3. **Present** → compare `group.getName()` to `scim-synced-name`; on
   drift or absent attribute → push a `displayName` PATCH and set the
   attribute.

First time the reconciler sees a group without `scim-synced-name`, it
treats it as "needs sync" → one **unconditional** idempotent
`displayName` PATCH, sets the attribute, then stays quiet. This
unconditional first-sight PATCH is not merely a cost — it is the
mechanism that closes the window for any rename that landed between SCIM
provision and the first reconciler sighting (the SCIM resource may carry
the pre-rename name; the first-sight PATCH overwrites it with the current
name). **Implementer note:** do not add a guard that skips the PATCH when
the name "looks unchanged" on first sight — there is no stored baseline
to compare against yet, so skipping would reintroduce the stale-name gap.
The trade-off is one PATCH per pre-existing group on the first pass after
deploy; accepted.

### Rename mechanics

- New `GroupAdapter.toDisplayNamePatchBuilder(scimRequestBuilder, url)` —
  a single `REPLACE displayName` operation, no member re-send (parallel
  to the existing `toMembershipPatchBuilder`).
- New `ScimClient.reconcileGroupName(factory, groupId)` — resolves the
  group mapping; when `group-patchOp=true`, sends the displayName PATCH;
  when `group-patchOp=false`, falls back to the existing full
  `replace(group)` (re-sends members but updates the name), mirroring the
  membership fallback. On success, writes `scim-synced-name =
  group.getName()`. A missing mapping is an `infof` skip, mirroring
  existing methods.
  - **Implementer note on the fallback:** `replace` is not a pure
    displayName call — in `group-patchOp=false` mode it re-sends the full
    member list and its own recovery branches still apply (405 → PATCH,
    404/400 → re-create). This is acceptable (non-patchOp deployments
    already pay full-group costs for membership changes), but do not
    expect a minimal request in that mode. The `scim-synced-name` write
    should happen only after the fallback reports success.

### Delete mechanics

Reuse `ScimClient.delete(GroupAdapter::new, groupId)` for orphaned group
mappings. Already idempotent with the admin-event `GROUP DELETE` path
(missing mapping → `NoResultException` → skip), so the two never
double-delete.

### Idempotency and convergence

Both operations are safe to repeat. A name that already matches → no
PATCH (local compare). An already-deleted group → mapping cleared, next
pass skips it.

## Config and scheduling

Rides the existing `reconciler-enabled` flag, interval, and manual
endpoint — no new config key and no new threshold (orphan delete and
name-drift need none). The manual endpoint's `{"deleted": N}` response
extends to include group counts, e.g.
`{"deleted": N, "groupsRenamed": R, "groupsDeleted": D}`.

Operators opt into federation reconciliation as a unit (users + groups)
via the existing flag. A separate `reconciler-groups-enabled` sub-flag is
a possible future refinement if operators want renames without user
deletion, but is out of scope for v1.

## Testing

### Spike (gates delete)

An integration probe confirming: (a) whether Keycloak removes the local
`GroupModel` when its LDAP source group is deleted and synced — if not,
delete is deferred and only rename ships; and (b) that a custom
`scim-synced-name` group attribute survives an LDAP sync. Same
stop-and-surface discipline as the membership-materialization spike: if
(a) is false, surface to the human before proceeding.

### Unit (fast, no Docker)

- `toDisplayNamePatchBuilder` wire shape: a single `REPLACE displayName`
  op, no members.
- Group-decision logic: drift → rename; orphan → delete;
  attribute-absent → one-time sync; name-matches → no-op.

### Integration (Testcontainers + OpenLDAP + WireMock)

- Rename an LDAP group → full sync → reconcile → assert a `displayName`
  PATCH fires and no member list is re-sent.
- Re-reconcile with no change → assert no further PATCH (attribute
  short-circuit).
- Delete an LDAP group → sync → reconcile → assert SCIM `DELETE` (spike
  permitting; otherwise assert the documented deferral and skip).

## Non-goals (deliberately deferred)

- **Staleness-based delete.** Orphan-only for v1 (decision 2).
- **Groups never provisioned to SCIM.** A federated group with zero
  imported members never reaches SCIM (groups are provisioned only when a
  member is imported via `ensureGroupMembership`). Pre-existing behavior,
  unchanged.
- **Group hierarchy / path (`parent`) changes.** Only `displayName` and
  existence are reconciled.
- **Per-feature enable flag** (`reconciler-groups-enabled`). Group
  reconciliation rides the existing `reconciler-enabled` flag.
