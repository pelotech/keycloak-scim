# Sync lease: one run per component at a time

**Status:** approved design
**Date:** 2026-09-25

## Problem

Keycloak locks a component before a sync starts, through its cluster
provider. That lock expires after `max(30, fullSyncPeriod)` seconds. It does
not grow with the run. A paged `sync-refresh` of a large realm runs for
minutes, so a second sync of the same component can start while the first
is still running, from the scheduler or from an admin.

When that happens today, the loser of a mapping-row race marks its page
transaction rollback only and stops. The winner keeps every page it
committed. So the failure is safe, but it is real, it wastes a run, and it
gets more likely as a realm grows. The operator documentation names the
symptom. This design removes the cause.

## Decision

A run takes a lease on its component before any stage starts, renews it
with a heartbeat for the whole run, and releases it at the end. A second
run that finds a live lease refuses at once. A lease whose heartbeat has
stopped goes stale after a fixed interval, so a crashed node cannot block
syncs for ever.

The lease is per component, not per realm. Two SCIM providers in one realm
push to different servers and share no mapping rows, so they may sync at
the same time. Collisions happen within one component.

The reconciler is out of scope. It is a separate scheduled task with the
same overlap risk and deserves its own change.

## Storage

A new table `SCIM_SYNC_LEASE`:

| Column | Type | Meaning |
| --- | --- | --- |
| `COMPONENT_ID` | varchar, primary key | The SCIM provider component |
| `HOLDER` | varchar, nullable | Identity of the node that holds the lease, or null |
| `ACQUIRED_AT` | bigint | Epoch milliseconds when the current holder took it |
| `RENEWED_AT` | bigint | Epoch milliseconds of the last heartbeat |

It is added in the same changelog as `SCIM_PROVISION_LOCK`, as a new
changeset. The entity `ScimSyncLease` lives beside `ScimProvisionLock` in
`sh.libre.scim.jpa` and is registered in the same entity provider.

A node's identity is a UUID generated once per JVM. It only has to differ
between nodes and between restarts of one node.

## Acquisition

`ScimSync.run` acquires in one short transaction, before any stage:

1. Load the component's row with `PESSIMISTIC_WRITE`, as the group
   provisioning lock does. If no row exists, insert one. The row lock makes
   the decision atomic across nodes.
2. Decide from the row: **take** it when the holder is null, or when
   `RENEWED_AT` is older than the stale threshold; **refuse** otherwise.
3. On take, set `HOLDER` to this node, and both timestamps to now.
4. On refuse, log a warning that names the holder and the age of the last
   heartbeat, and return a `SynchronizationResult` with `ignored` set. Keycloak
   renders that status as "Synchronization ignored as it's already in
   progress", which is what its own lock reports for the first 30 seconds.

The decision in step 2 is a pure function of the row and the clock, so it is
unit-tested without a database.

If acquisition throws, for example because the database is unreachable,
the run does not proceed. It counts one failure, logs the cause, and
returns. A run without a lease would recreate the overlap this design
removes.

## Heartbeat

A single daemon scheduler thread renews the lease every 30 seconds for the
whole run, in its own short transaction:

    UPDATE SCIM_SYNC_LEASE SET RENEWED_AT = :now
     WHERE COMPONENT_ID = :component AND HOLDER = :me

The stale threshold is 120 seconds, four missed heartbeats. Both values are
constants. They are not settings, because there is no deployment-specific
reason to change them, and a wrong value on one node breaks every node.

An update that affects zero rows means another node judged this lease
stale and took it. That only happens when this node's heartbeat fell silent
for over two minutes. The run must not go on as if it owned the component:

- The heartbeat sets a lost flag.
- `RefreshPageStep` checks the flag between users and ends the run with a
  new stop reason, `LEASE_LOST`. The runner handles it like the other
  run-ending reasons and keeps what is committed.
- Import and group refresh run as one transaction each and cannot stop in
  the middle. They finish their stage. `ScimSync` checks the flag before
  each stage and skips the rest once it is set.

A heartbeat that throws, because the database is briefly unreachable, is
not a lost lease. It logs and tries again 30 seconds later. Only an update
that succeeds and matches no row means loss.

## Release

When the run ends by any path, `ScimSync.run` stops the heartbeat and
clears the holder:

    UPDATE SCIM_SYNC_LEASE SET HOLDER = NULL
     WHERE COMPONENT_ID = :component AND HOLDER = :me

The `HOLDER = :me` guard means a node never clears a lease that another
node took from it.

If release fails, the heartbeat has already stopped, so the lease goes
stale after 120 seconds and the next run takes it. No manual override is
needed, and none is provided.

## Components

- `sh.libre.scim.jpa.ScimSyncLease`: the entity.
- `sh.libre.scim.core.SyncLease`: acquire, renew, release, the heartbeat,
  and the static decision function. One instance per run. It owns the
  scheduler thread and stops it on release.
- `sh.libre.scim.core.ScimSync.run`: acquires first, releases in a
  `finally`, checks the lost flag between stages, and passes it to the page
  step.
- `sh.libre.scim.core.StopReason.LEASE_LOST`, and one more case in the
  runner's exhaustive switch.

## Alternatives rejected

- **Keycloak's cluster lock with a longer timeout.** Its interface takes a
  fixed expiry with no renewal, so a run length has to be guessed up front.
  That is the failure this design removes.
- **Renew at each page commit, with a long stale threshold.** No new
  thread, but import and group refresh have no page boundaries, so the
  threshold must exceed the longest of those, and nothing bounds them. A
  crashed node would also block syncs for the whole threshold.
- **Wait instead of refuse.** An admin-triggered sync could block for
  minutes, and scheduled syncs could queue behind a long run.
- **A manual override to clear a lease.** Not needed once a stale lease
  expires in two minutes on its own.

## Behaviour change

A sync that starts while another run of the same component is in progress
returns at once with the "already in progress" status and pushes nothing.
Before this change it ran, collided, and stopped part way. A scheduled
sync that is refused runs at its next period.

## Testing

Unit tests, no database:

- The decision function over every combination: no row, a live holder, a
  stale holder, and this node as holder.
- Renewal that matches a row and renewal that matches none.
- The heartbeat setting the lost flag on a matched-nothing update and not
  on a thrown one.
- The page step ending on the lost flag, and the runner ending on
  `LEASE_LOST`.

Integration tests:

- Two syncs of one component started together against a slow endpoint. The
  second returns the ignored status. The first completes with every user
  pushed. A third sync, started afterwards, runs normally.
- A stale lease is taken. Start a sync, stop its heartbeat through a
  package-private test hook, wait past the threshold, and start another. It
  runs.

## Documentation

`docs/configuration.md` replaces the paragraph that names the overlap
symptom with what now happens: a second sync is refused with the "already
in progress" status, and a crashed node's lease expires within two minutes.
The design record for the paged refresh moves this item from follow-ups to
a reference to this document.
