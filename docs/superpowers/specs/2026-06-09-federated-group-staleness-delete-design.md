# Federated Group Delete via Member-Presence — Design

> **Supersedes** `2026-06-09-federated-group-rename-delete-design.md` (the
> orphan-based design).
>
> **Mechanism note:** an earlier revision of this spec used a *timestamp
> staleness* signal (stamp a group `last-seen` attribute on import; delete
> when older than a threshold). Implementation testing found that the
> per-member group-attribute write races across the async dispatch workers
> (concurrent `GROUP_ATTRIBUTE` writes → Hibernate `StaleStateException`,
> poisoning the membership dispatch and the reconcile transaction). A spike
> then established a simpler, race-free signal: **a federated group that is
> no longer backed by LDAP is drained to zero members locally** (verified for
> both rename and delete). This spec uses that **member-presence** signal — no
> group-attribute write, no threshold, no timing dependency.

## Problem

LDAP-federated group lifecycle changes do not propagate to SCIM:

- **Delete.** When an LDAP group is removed, Keycloak keeps the local
  `GroupModel` (the group `#35235`-analogue), and no `GROUP DELETE` admin
  event fires. The SCIM group lingers forever.
- **Rename.** Keycloak handles an LDAP `cn` change as "materialize a new
  group, orphan the old one" (verified via `modrdn` and delete+recreate).
  The new group provisions to SCIM fresh (correct name, new id) through the
  membership path; the old SCIM group is never cleaned up.

Both reduce to: **a federated group no longer backed by LDAP keeps an
orphaned SCIM resource.** There is no event; detection must be periodic.

## Key spike finding (mechanism foundation)

When an LDAP group is renamed or deleted and Keycloak re-syncs, the old
`GroupModel` persists locally **but is drained to zero members**:

- Rename `engineers` → `engineers-team`: old `engineers` keeps its id,
  **member count 0**; the new `engineers-team` holds the members.
- Delete `engineers`: old `engineers` keeps its id, **member count 0**.

Membership edges are removed exactly (no stragglers). So "has zero members"
is a reliable, race-free signal that a federated group is dead.

## Goal

Have the reconciler delete the SCIM resource for any mapped federated group
that currently has **no members** (or whose local model is gone). Rename is
handled implicitly: the renamed group provisions fresh while the old,
now-memberless group is deleted.

## Decisions

1. **Member-presence, not timestamp staleness.** A mapped group is a delete
   candidate when it has zero current members (or its local `GroupModel` is
   gone — a forward-compatible orphan backstop). No group-attribute write,
   no threshold, no clock — which removes the concurrency defect and the
   per-import write entirely.

2. **Rename = delete-old + create-new.** On current Keycloak a rename yields
   a new SCIM group (new id) plus the old one, which is deleted on the next
   reconcile pass after the sync drains its members. For the window between
   that sync and the next reconcile, SCIM holds both. Accepted; bounded by
   the reconcile interval (not a long stale threshold).

3. **No in-place rename machinery.** Rename is delete+create, so there is no
   `displayName` to push in place. The `toDisplayNamePatchBuilder` /
   `reconcileGroupName` / `scim-synced-name` machinery is removed.

4. **No group config; reuse only the enable flag.** Group reconciliation
   rides the existing `reconciler-enabled` flag. It needs **no threshold** and
   no new config. (The `reconciler-stale-threshold-seconds` and its
   `> fullSyncPeriod` validation remain for the **user** phase, which still
   uses timestamp staleness — groups are unaffected by them.)

5. **Empty groups — explicit known consequence.** A federated group only gets
   a SCIM mapping when a member is imported (`ensureGroupMembership`); a
   never-populated group is never provisioned and never reconciled. However, a
   group that *was* provisioned (had members) and then **legitimately becomes
   empty in LDAP** (e.g. a team is offboarded but the group still exists) will
   reach zero members after a sync and be **deleted on the next reconcile** —
   it is indistinguishable from a renamed-away/deleted group under a
   member-count signal. This is accepted: it matches what the superseded
   timestamp design would also have done (an unstamped, memberless group goes
   stale and is deleted), the SCIM group resource carries little value with no
   members, and it is re-provisioned automatically when the group regains a
   member. Documented so it is not mistaken for a bug.

## Architecture

### Reconciler group phase

Per `Group`-type mapping under the component (queried via
`findByComponentAndType` with `type="Group"`):
- `getGroupById == null` → **DELETE** (orphan backstop; forward-compatible
  for missed admin deletes or a future Keycloak that prunes).
- present + **zero members** (`session.users().getGroupMembersStream(realm,
  group)` empty) → **DELETE**.
- present + **has ≥1 member** → **KEEP**.
- Delete via the existing `delete(GroupAdapter::new, groupId)` (idempotent
  with the admin-event delete path).

Classification is split for testability: a pure `classifyGroup(GroupModel
group, boolean hasMembers)` (null → DELETE, `!hasMembers` → DELETE, else
KEEP), with `reconcileGroups()` computing `hasMembers` from the session.
Phase structure mirrors the user phase: sequential Phase-1 classification,
parallel Phase-2 delete dispatch on the shared worker pool. Runs after the
user phase, in the same session, gated by `reconciler-enabled`.

### Liveness guarantee (why a stable group is not wrongly deleted)

A live LDAP group always has its member edges in Keycloak — they are
materialized on import and persist between syncs (Keycloak does not drop
them). So a live group's member count is always ≥1 at reconcile time,
**with no timing dependency**: there is no "must full-sync within a
threshold" constraint (unlike the user phase). The only way a mapped group
reaches zero members is a sync draining it because it was renamed / deleted /
emptied in LDAP — exactly the cases we want to delete. This removes the
no-slack-margin caveat the timestamp variant carried.

### No group-attribute write

The reconciler reads member presence; nothing on the import path stamps a
group attribute. (The earlier timestamp variant's per-member
`group.setSingleAttribute(LAST_SEEN_ATTRIBUTE, …)` in the LDAP mapper's group
dispatch is removed — it was the source of the concurrent-write defect.)

### Result struct

`ReconcileResult` carries `usersDeleted` and `groupsDeleted`. The endpoint
preserves the existing `deleted` key (= `usersDeleted`) and adds
`groupsDeleted`.

## Testing

### Unit (fast, no Docker)

- `classifyGroup(group, hasMembers)`: null → DELETE; present + `false` →
  DELETE; present + `true` → KEEP.

### Integration (Testcontainers + OpenLDAP + WireMock)

No aging machinery and no 500-retry workaround are needed (no group-attribute
write ⇒ no `StaleStateException`). Reuse the deterministic-provisioning
pattern (await group/user POSTs, re-sync) and `postReconcile`.

1. **Delete propagates** — provision a federated group; delete the LDAP
   group; full sync (drains members to 0); reconcile → SCIM `DELETE` for the
   group.
2. **Rename = delete-old + create-new** — provision `engineers`; rename in
   LDAP; full sync → new group provisions (new id), old `engineers` drained
   to 0 members; reconcile → old `engineers` SCIM resource deleted, the new
   group kept.
3. **Live group is not deleted** — a group that still has its members is NOT
   deleted by reconcile. Direct regression guard for "stable group must not
   be wrongly deleted."

## Non-goals (deliberately deferred)

- **In-place rename propagation** (id-stable displayName update). Not
  possible on current Keycloak — rename is delete+create.
- **Eliminating the transient duplicate.** Inherent; bounded by the reconcile
  interval.
- **Timestamp-based group liveness.** Rejected: the per-member group-attribute
  write races across async workers; member-presence is the race-free signal.
- **A group-specific enable flag.** Group reconciliation rides the existing
  `reconciler-enabled`.
