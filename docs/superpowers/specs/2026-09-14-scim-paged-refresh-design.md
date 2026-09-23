# Paged sync-refresh: design record

**Status:** implemented
**Date:** 2026-09

This records why the paged refresh is shaped the way it is. The operator
documentation in `docs/configuration.md` says what it does. The two should
not repeat each other.

## Problem

`sync-refresh` pushed every user inside one transaction. Three things
followed:

1. The transaction timeout is a global Keycloak setting. A realm large
   enough to need a long sync forces a long timeout on every transaction
   in the instance.
2. A failure discarded the whole run, including every SCIM mapping row it
   wrote. The reported counters described work that was thrown away.
3. Enumeration ran inside the same transaction and used the user search
   SPI. On a federated realm that walks the directory, imports entries it
   has not seen, and validates each user against the directory.

## What was measured before anything was built

A throwaway test ran a sync built from short transactions against a
global timeout shorter than the run, on Keycloak 25.0.6, through both
the admin API and the scheduled sync.

- Keycloak wraps the whole sync in a transaction of its own.
- Narayana's reaper cancels that transaction at the timeout, even though
  it is suspended, idle, and holds no locks.
- Every inner transaction still commits.
- The run continues to completion and fails only at the final commit,
  with `ARJUNA016102: The transaction is not active!`. The admin path
  returns HTTP 400. The scheduled path logs an error.

So paging cannot let an operator lower the timeout. A run longer than the
timeout still pushes every user and keeps every mapping, but Keycloak
reports the sync as failed. The design accepts this. What paging changes
is the unit of loss.

## Decisions

**Page by keyset over the local user table.** Pages are read from
`UserEntity` directly, ordered by username, with the last username as the
cursor. The realm and username pair is unique, so the order is total and
no row is missed or repeated at a page boundary. Disabled users and
service accounts are excluded in the query. Offset paging over the search
SPI was rejected: validation can delete a user mid-list and shift every
offset, federation providers fill short pages from the directory, telling
a finished list from an emptied window needs counts that depend on
isolation level, and the final page walks the whole directory.

**One transaction per page, and a generic runner.** The page step opens
its own transaction, builds its own client, and returns an outcome with
the next cursor, whether it made progress, whether the source is
exhausted, its counters, and a stop reason. The runner knows nothing
about Keycloak or SCIM, so an import step with a different cursor type
can use it later without changes.

**Cached users are pushed without directory validation.** A user in
Keycloak's user cache is pushed as an ordinary update even if the
directory entry is gone. The reconciler owns removals. This is accepted
because the alternative, validating every user against the directory,
is what made enumeration slow.

**A smaller retry budget and shorter timeouts on the sync path.** Every
part of a sync retries 3 times. A refresh page also uses shorter HTTP
timeouts: 1 second to get a pooled connection, 3 seconds to connect, and
10 seconds per read. Every other caller keeps 30 seconds and 10 attempts,
because an interactive call has no page to fit inside. The attempt count
is not what bounds a stuck user; the per-attempt timeouts are, and the
three of them add up. The figures are a normal worst case, not a
guarantee. The read timeout applies per read, the TLS handshake and DNS
lookup are not covered, the connect timeout applies per resolved address,
a 401 repeats the whole retry loop, the token minter has no timeouts at
all, and a failed replace can fall back to a PATCH and then a create
outside the loop.

**A 429 does not stop a run; one page of them in a row does.** Under
`sync-on-error=auto`, a throttled response means the SCIM server is
alive, so the run continues. Without a limit, a server that throttles
every request would make the run walk the whole population and push
almost nothing. The page step counts throttled users in a row and stops
the run when the count reaches the page size. Any push, skip, or other
failure resets it. The count carries across pages, because the step lives
for the whole run. This stop applies even under `sync-on-error=continue`,
because it is a guard against a useless run, not an error policy.

**Counters describe only what was kept.** A user counts as updated only
when a push happened. A skipped user counts as nothing. A push that did
nothing and raised nothing counts as failed. A page or stage whose
transaction rolled back keeps its failure count and drops the rest. A
refresh that stops early counts a failure and logs the reason.

**A raw runtime error on one user does not end the page.** It is
counted, logged with the user id, and skipped, so a fault in building one
payload cannot roll back a page and orphan the users already pushed. The
exception is a persistence fault, which marks the transaction rollback
only. The step checks for that after every user and ends the page at
once, because pushing more users into a doomed transaction is the orphan
case multiplied by the page size.

**Import and group refresh keep one transaction each.** Keycloak has no
paged enumeration of all groups. A warning is logged above 500 groups.
Import reads one page from the SCIM server today; batching it belongs
with fixing its pagination, which is a behaviour change of its own.

**Two settings, validated at save and read with a fallback.**
`sync-page-size` (default 50) is the number of users in a page and also
the throttle threshold. `sync-page-max-seconds` (default 45) is a
wall-clock limit checked between users. Both reject a value that is not
a positive whole number. A realm import can bypass validation, so the
reader falls back to the default and logs a warning.

## Alternatives rejected

- **Raise the transaction timeout.** Global, leaves little headroom, and
  does nothing about work lost on rollback. The raised timeout stays, but
  it is no longer the only protection.
- **Snapshot every user id, then batch the writes.** Holds every id in
  memory and goes stale over a long run.
- **Enumerate from the mapping table.** Cannot serve a first-time
  backfill, because it only sees users already mapped.
- **`runJobInTransactionWithTimeout`.** One transaction still wraps the
  run, and the outer transaction belongs to Keycloak anyway.
- **Parallel page dispatch.** Page boundaries cannot be computed ahead of
  time, and the requirement is completing reliably, not faster.
- **Run the refresh in the background, or from the plugin's own
  scheduler.** Either avoids Keycloak's outer transaction, at the cost of
  the sync result no longer reporting the refresh.

## Behaviour changes

- Service accounts are no longer refreshed, and refresh no longer imports
  directory users it has never seen.
- Skipped users no longer count as updated.
- A 429 no longer stops a run; a full page of them does.
- Every part of a sync retries 3 times; refresh pages use short timeouts.
- `rollback-strategy` no longer affects a sync.
- A refresh that stops early reports a failure with the reason.

## Follow-ups

- **Overlapping syncs.** Keycloak's cluster lock for a sync expires on a
  fixed timer that does not grow with the run, so two syncs of one realm
  can overlap. The loser marks its page transaction rollback only and
  stops; the winner keeps every committed page. Preventing it needs a
  lease the plugin owns, renewed at each page commit.
- **Import paging.** The runner is ready for an import step. Fixing
  import's pagination is a behaviour change that needs its own rollout.
- **Token minter timeouts.** The minter's HTTP client has none. It is
  the largest remaining escape from the page bound.
- **One Keycloak container per test suite.** Container startup is most of
  the integration suite's time, and almost every class needs Keycloak.
