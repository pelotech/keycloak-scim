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
syncs for ever. A holder that cannot prove its lease is still its own stops
on its own.

The lease is per component, not per realm. Two SCIM providers in one realm
push to different servers and share no mapping rows, so they may sync at
the same time. Collisions happen within one component.

The reconciler is out of scope. It is a separate scheduled task with the
same overlap risk and deserves its own change.

## Constants

| Name | Value | Meaning |
| --- | --- | --- |
| heartbeat interval | 30 s | How often the holder renews |
| stale threshold | 120 s | Age of `RENEWED_AT` past which any node may take the lease |
| self-fence threshold | 90 s | Age of the holder's own last successful renewal past which it stops itself |
| progress window | 10 min | Renewal stops when the run has reported no progress for this long |

These are constants, not settings. There is no deployment-specific reason
to change them, and a wrong value on one node breaks every node. The
self-fence threshold sits below the stale threshold so a holder stops
before another node can take over, with the clock skew margin described
under Clocks.

## Storage

A new table `SCIM_SYNC_LEASE`:

| Column | Type | Meaning |
| --- | --- | --- |
| `COMPONENT_ID` | `VARCHAR(36)`, primary key | The SCIM provider component |
| `HOLDER` | `VARCHAR(36)`, nullable | Token of the run that holds the lease, or null |
| `ACQUIRED_AT` | `BIGINT`, nullable | Epoch milliseconds when the current holder took it |
| `RENEWED_AT` | `BIGINT`, nullable | Epoch milliseconds of the last successful renewal |

A foreign key from `COMPONENT_ID` to `COMPONENT(ID)` with `ON DELETE
CASCADE`, as `SCIM_RESOURCE` has, so a deleted component leaves no orphan
row. One changeset, `author="keycloak-scim" id="scim-sync-lease-1.0"`, with
`createTable`, `addPrimaryKey` and `addForeignKeyConstraint`, and no data.
Component ids are not known at changelog time, so the row cannot be
seeded the way `SCIM_PROVISION_LOCK` is. Keycloak applies the changelog at
startup, so an existing deployment needs nothing else.

The entity `ScimSyncLease` lives beside `ScimProvisionLock` in
`sh.libre.scim.jpa` and is registered in the same entity provider.

## Holder token

The holder is a token generated per run, not per node. A per-node identity
fails when two runs of one component overlap on the same node: the second
takes the stale lease, both carry the same identity, the first run's
heartbeat renews the second run's lease, and the first run's release
clears it. A token per run makes every guard in this design exact. The
refuse log names the token; a node name is not needed.

## Acquisition

`ScimSync.run` acquires after its existing early returns, which open no
transaction when both toggles are off or no propagation is enabled, and
before any stage. Acquisition is two short transactions.

**Ensure the row.** Insert a row for the component with a null holder. A
primary-key violation means another node inserted it first; catch it and go
on. This transaction exists because a `PESSIMISTIC_WRITE` on a missing row
locks nothing, so two nodes would both insert and one would fail at commit,
after it had already decided to take the lease.

**Lock and decide.** Load the row with `PESSIMISTIC_WRITE`, as the group
provisioning lock does. The row lock makes the decision atomic across
nodes. Then apply the decision function:

    decide(holder, renewedAt, now) -> TAKE | REFUSE

- `TAKE` when `holder` is null, or when `now - renewedAt` exceeds the stale
  threshold.
- `REFUSE` otherwise. A live lease is refused whoever holds it, including a
  run on this node.

On `TAKE`, set `HOLDER` to the run's token and both timestamps to `now`. On
`REFUSE`, log a warning that names the holder token and the age of its last
renewal, and return a `SynchronizationResult` with `ignored` set. Keycloak
renders that as "Synchronization ignored as it's already in progress",
which is what its own lock reports for the first 30 seconds. On both the
admin and the scheduled path Keycloak passes an ignored result through
without updating the last sync time, so a refused scheduled sync runs again
at its next period.

The decision function is pure, so it is unit-tested without a database.

If either transaction throws, for example because the database is
unreachable, the run does not proceed. It counts one failure, logs the
cause, and returns. A run without a lease would recreate the overlap this
design removes.

## Heartbeat

The heartbeat is the run's own single-thread daemon
`ScheduledExecutorService`. It is not Keycloak's `TimerProvider`: that is
one `java.util.Timer` thread, and a scheduled sync runs on that same
thread, so a heartbeat scheduled there would never fire during a scheduled
sync.

The heartbeat starts after the acquisition transaction commits. Its first
tick is 30 seconds later, and every tick runs in its own short transaction
opened through `KeycloakModelUtils.runJobInTransaction`, the way the
dispatcher's worker threads open theirs:

    UPDATE SCIM_SYNC_LEASE SET RENEWED_AT = :now
     WHERE COMPONENT_ID = :component AND HOLDER = :token

The tick catches `Throwable`. A tick that throws does not cancel the
schedule, and it is not a lost lease. The next tick tries again.

Three outcomes matter:

- **Renewed**, one row matched. Record the time as the last successful
  renewal.
- **Matched nothing.** Another node judged this lease stale and took it.
  Set the lost flag.
- **Threw.** Log at warning level. Do nothing else.

Renewal is gated on progress. The run reports progress when a page handles
a user, and at the start and end of each stage. When the last progress is
older than the progress window, the tick stops renewing and logs why. A run
that is hung in a call that never returns then loses its lease within the
stale threshold, and another node can sync. Keycloak's own lock used to
free a hung component when its timeout passed; this rule keeps that
property. A single non-paged stage that runs longer than the progress
window without a stage boundary lets the lease lapse; the warning logged
above 500 groups bounds that exposure.

## Self-fence

A holder must not rely on seeing a matched-nothing update, because a
holder that cannot reach the database never sees one. Two paths make that
window unbounded otherwise: a run thread that holds the pool's last
connection while every heartbeat times out on acquisition, and a
database that is away for longer than the stale threshold while the run
thread keeps a connection it already had.

So the run thread also treats the lease as lost when the last successful
renewal is older than the self-fence threshold, whether or not the lost
flag is set. `SyncLease.lost()` answers true in either case. With this rule
the overlap after a takeover is bounded in every scenario by one heartbeat
interval plus one in-flight user.

## Lost lease

`SyncLease.lost()` is checked in two places:

- `RefreshPageStep.processRows` checks it after each user, beside the
  rollback-only check, through one more `BooleanSupplier`. It ends the run
  with a new stop reason, `LEASE_LOST`. The page in flight commits: its
  pushes happened, and discarding their mappings would orphan them. The
  runner's exhaustive switch treats `LEASE_LOST` as run-ending, logs the
  cursor at error level, and keeps what is committed. `refreshUsers`
  already counts any incomplete run as one failure.
- `ScimSync.run` checks it before each stage and skips the rest once it is
  set. Import and group refresh run as one transaction each and cannot
  stop in the middle; they finish their stage.

After a takeover the old holder can still push for up to one heartbeat
interval plus one in-flight user, which with retries can be minutes. A
collision inside that window falls back to today's mapping-row race
handling, which is safe.

## Release

When the run ends by any path, `ScimSync.run` stops the heartbeat, waits
for a tick in flight to finish so it cannot log a false loss after the run,
and clears the holder:

    UPDATE SCIM_SYNC_LEASE SET HOLDER = NULL
     WHERE COMPONENT_ID = :component AND HOLDER = :token

The `HOLDER = :token` guard means a run never clears a lease that another
run took from it.

If release fails, the heartbeat has already stopped, so the lease goes
stale after 120 seconds and the next run takes it. If the JVM shuts down
mid-run, the daemon thread dies with it and the same applies. A run hung
in a call that never returns stops renewing after the progress window. So
no manual override is needed, and none is provided.

## Clocks

`RENEWED_AT` is written by one node and compared with `now` on another.
The tolerance for skew is the stale threshold minus the heartbeat
interval, about 90 seconds. A node ahead by more than that judges a live
holder stale and takes over; the holder then self-fences at its next
check, so the result is a wasted run, not a corrupted one. A node behind
delays a takeover by the skew, which is harmless. Nodes disciplined by NTP
are within a second. `SyncLease` takes an injected `Clock`, as
`RefreshPageStep` does, so the unit tests control time.

## Components

- `sh.libre.scim.jpa.ScimSyncLease`: the entity.
- `sh.libre.scim.core.SyncLease`: ensure, acquire, renew, release, the
  heartbeat, the progress and self-fence rules, and the static decision
  function. One instance per run, holding the run's token. It owns the
  scheduler thread and stops it on release. It exposes `lost()` and
  `progressed()`.
- `sh.libre.scim.core.ScimSync.run`: acquires after the early returns,
  releases in a `finally`, reports progress at stage boundaries, checks
  `lost()` between stages, and passes `lost()` and `progressed()` to the
  page step.
- `sh.libre.scim.core.StopReason.LEASE_LOST`, and one more case in the
  runner's exhaustive switch.
- A system property, `scim.sync.lease.heartbeat`, default `true`. Set to
  `false` it disables the heartbeat. It exists so an integration test can
  make a lease go stale without killing a container. It is documented as a
  test aid, not an operator setting.

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
- **A per-node holder identity.** See Holder token.
- **A manual override to clear a lease.** Not needed once a stale lease
  expires on its own and a hung run stops renewing.
- **The database clock instead of node clocks.** Removes skew entirely,
  but the skew tolerance is already 90 seconds, and node clocks keep the
  decision function pure and testable.

## Behaviour changes

- A sync that starts while another run of the same component is in
  progress returns at once with the "already in progress" status and pushes
  nothing. Before this change it ran, collided, and stopped part way. A
  refused scheduled sync runs at its next period.
- A rolling upgrade is a mixed cluster. Nodes on the old version take no
  lease and can still overlap with anything. Upgrade every node.

## Testing

Unit tests, no database:

- The decision function: no holder, a live holder, a stale holder, this
  run as holder, and the boundary at exactly the threshold.
- Renewal that matches a row and renewal that matches none.
- The heartbeat tick: sets the lost flag on matched-nothing, does not on a
  throw, survives a throw and runs again, and stops renewing after the
  progress window.
- Self-fence: `lost()` is true when the last success is older than the
  threshold, with no flag set.
- Release that matches none after a takeover.
- The page step ending on `lost()`, the runner ending on `LEASE_LOST`, and
  `ScimSync.run` on refusal returning an ignored result and opening no
  stage transaction.

Integration tests:

- **Refusal comes from the lease, not from Keycloak's lock.** The test
  realm sets no `fullSyncPeriod`, so Keycloak's own lock expires at 30
  seconds; a second sync started at once would be refused by Keycloak with
  or without this change. So: 30 users behind a 2 second endpoint, one sync
  started, a second sync started after 40 seconds while the first is still
  running. The second returns the ignored status. The first completes with
  every user pushed. A third sync, started after the first ends, runs.
- **A stale lease is taken.** A dedicated container with
  `-Dscim.sync.lease.heartbeat=false`, following the `JAVA_OPTS_APPEND`
  precedent of the timeout test. Start a sync that outlasts the stale
  threshold, wait past the threshold, start another. It runs. This test
  needs more than 120 seconds of wall time and says so.

## Documentation

`docs/configuration.md` replaces the paragraph that names the overlap
symptom with what now happens: a second sync is refused with the "already
in progress" status, a crashed node's lease expires within two minutes, and
a hung run stops renewing after ten minutes without progress. It adds the
rolling-upgrade note. The design record for the paged refresh moves this
item from follow-ups to a reference to this document.
