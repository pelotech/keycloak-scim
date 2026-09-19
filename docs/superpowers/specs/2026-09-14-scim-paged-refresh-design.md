# SCIM Sync — Paged Refresh with Per-Page Transactions — Design

**Status:** Approved (design phase)
**Date:** 2026-09-14

## Problem

`triggerFullSync` runs the entire refresh inside one JTA transaction.
`ScimStorageProviderFactory.sync` wraps both halves, users and groups, import and
refresh, in a single `runJobInTransaction`, and every SCIM round trip happens
inside it.

Observed in an environment of 4,552 users: the sync needs about 25 minutes
against a default 30-second transaction timeout. It completed only after the
timeout was raised to 1800 seconds.

Three consequences follow:

1. The transaction timeout is a global Keycloak setting. Raising it to 1800s
   gives every transaction in the instance a 30-minute leash, so anything that
   wedges holds its database connection and locks for half an hour instead of
   failing fast.
2. The headroom is thin. End to end the run averages about 3.0 users per second,
   so 1800s covers roughly 5,500 users. The environment is at 4,552, about 83
   percent of the budget.
3. A rollback discards everything. `refreshResources` increments
   `SynchronizationResult` inside the transaction, so the reported counters
   describe work that was thrown away, and every mapping row written during the
   run is lost.

Enumeration makes it worse. `refreshResources` opens with
`getResourceStream().toList()`, materializing every enabled user before the
first SCIM request goes out. In one failed run that prelude accounted for most
of 7.5 minutes. It runs inside the same transaction, so batching the writes
alone would not let the timeout be restored: enumeration on its own exceeds a
short timeout. Excluding that prelude, the push itself runs at roughly 4.3 users
per second; the 3.0 figure above is the end-to-end average and is the one that
matters for sizing the timeout.

Why enumeration is slow is worth recording. `UserAdapter.getResourceStream()`
calls `session.users().searchForUserStream(realm, ENABLED=true)`. In
`UserStorageManager` that search chains local storage with every enabled
federation provider that supports queries, so on a federated realm it also runs
a directory search, imports any entries not yet imported, and validates each
user against the directory. `ReconcilerRunner` carries a comment warning about
walking Keycloak's user list for this reason, and iterates the mapping table
instead.

## Goal

The user half of refresh runs as a sequence of short transactions, one per page,
so committed work survives a failure and no page holds a database connection for
longer than it needs.

The goal is not a lower global transaction timeout. A spike settled that, and
the next section records it: Keycloak opens a transaction of its own around the
whole sync, so the timeout still has to cover the entire run. Nor is the goal a
hard guarantee that no page exceeds its transaction, since no such bound is
derivable from the current HTTP configuration. What the design delivers is a
much smaller unit of loss when something does overrun.

**Non-goals:** batching `sync-import` (see Deferred); a resumable sync that skips
already-synced users on a re-run; parallel page dispatch; wiring SCIM `/Bulk`
into the refresh path.

## What the outer transaction does

`UserStorageSyncManager.syncAllUsers` calls `factory.sync` inside its own
`KeycloakModelUtils.runJobInTransaction`, on both the admin-triggered and the
scheduled path. That transaction stays open for the whole run, however short the
page transactions inside it are.

A spike on Keycloak 25.0.6 measured what happens when a run outlives the global
timeout: the timeout set to 30 seconds, the sync built from five 8-second
transactions, triggered once through the admin API and once through Keycloak's
periodic sync.

- Narayana's reaper cancels the outer transaction at the timeout even though it
  is suspended, idle, holds no locks and does no database work of its own
  (`ARJUNA012117`, logged 30.0 seconds into every run).
- Every inner transaction still commits. On the scheduled path exactly one
  transaction was reaped, and it was Keycloak's.
- The run continues to completion and fails only at the final commit, with
  `ARJUNA016102: The transaction is not active!`. The admin path returns HTTP
  400 to the caller; the scheduled path logs `Error occurred during FULL
  users-sync`.

So paging does not let the timeout come down. Under a short timeout a long sync
would push every user and keep every mapping row, then report itself as failed.
Durable data with an unusable result signal is worse to operate than a long
timeout, so the timeout stays above the length of a run.

What paging changes is the unit of loss: committed pages survive a failed run,
memory stays bounded, enumeration no longer walks the directory, and the token
is re-minted between pages rather than once for the run.

One related limit is worth recording, though it is not addressed here.
`syncAllUsers` takes its cluster lock with
`clusterProvider.executeIfNotExecuted(taskKey, max(30, fullSyncPeriod), ...)`.
That lock expires long before a multi-minute run finishes, so two syncs of the
same realm can overlap. It is filed separately.

## What bounds a page, and what does not

A page cannot be given a hard time bound, and the design does not claim one.

`ScimClient` builds its HTTP client with `connectTimeout(30)`,
`requestTimeout(30)` and `socketTimeout(30)` seconds, and retries with
`maxAttempts(10)` over `IntervalFunction.ofExponentialBackoff()`. Three
properties of that stack defeat any derivation of a worst case per resource:

- `ScimAuthHeaders.sendWithAuthRefresh` wraps the retry. A 401 or 403 invalidates
  the token, re-mints, and runs the whole retry loop a second time.
- The re-mint calls `OAuthClientCredentialsTokenSource.HttpTokenMinter`, which
  builds its client with `HttpClients.createDefault()`. Apache HttpClient 4
  defaults to no connect or socket timeout, so a hung token endpoint blocks
  without limit, over `MINT_MAX_ATTEMPTS` of 3.
- `socketTimeout` is a per-read inactivity timeout, not a total. A server
  emitting a byte every 29 seconds holds one attempt open indefinitely.

So the honest statement of what this design buys is narrower than a bound: a
resource that misbehaves badly enough will still exceed the transaction timeout,
and that page will fail. What changes is the blast radius. Today that failure
discards the entire run; afterwards it costs one page, and every page already
committed survives.

Two adjustments follow anyway, because the current numbers are worse than they
need to be.

**The sync path uses a smaller retry budget with a capped interval.** Pages
construct their `ScimClient` with `maxAttempts(4)` over
`IntervalFunction.ofExponentialBackoff(Duration.ofMillis(500), 1.5,
Duration.ofSeconds(5))`. The cap matters as much as the count: the default
unbounded growth contributes about 37 seconds of sleeping over ten attempts, and
capping it at 5s holds the backoff contribution to roughly 2.4 seconds over
four. Four attempts still rides out a brief blip, which two would not.

**A sync page uses shorter HTTP timeouts.** The attempt count is not what
dominates a stuck resource; the per-attempt HTTP timeouts are. Three of them
apply to one attempt and they add up: the wait for a pooled connection
(`requestTimeout`), the TCP connect, and each read. At 30s each, four attempts
allow about 362 seconds for one resource, against a 45-second page.

A client built for a sync page uses 1s for the pool wait, 3s to connect and 10s
per read, over three attempts rather than four. The values differ because the
timeouts mean different things. The pool wait never touches the network and
should not block at all. A same-region connect takes under 50ms. Only the read
has to hold a real SCIM write, and 10s is about forty times the observed
per-user push of 0.23 seconds. One resource then costs about 43 seconds: three
attempts of 14 seconds plus 1.25 seconds of backoff. Every other caller keeps
30 seconds, because an interactive call has no page to fit inside.

This is a normal worst case, not a guarantee, and several paths escape it. The
read timeout applies per read, so a server that sends a byte every 9 seconds
holds an attempt open. The TLS handshake and the DNS lookup are covered by none
of the three values. The connect timeout applies per resolved address, so a
dual-stack endpoint can pay it twice. A 401 re-mints the token and runs the
whole retry loop again. The token minter has no timeouts at all, so a
`CLIENT_CREDENTIALS` deployment is not bounded by this change; that is filed
separately. A failed `replace` can fall back to a PATCH and then to a create,
each outside the retry loop.

A deployment whose endpoint answers writes more slowly than 10 seconds will see
those writes fail during a sync while they still succeed on interactive paths.
That is the first thing to check if failures rise after this change.

**Sizing guidance, not a guarantee.** Let `P` be the page wall-clock bound and `T`
the transaction timeout. The page step starts its clock before the fetch, so `P`
covers the fetch as well as the resources, and it checks the clock between
resources, so a page overruns `P` by at most one resource. `P` plus one resource
should sit inside `T` for the latencies a deployment actually sees.

| Symbol | Value | Source |
| --- | --- | --- |
| `P` | 45s | `sync-page-max-seconds`, default |
| `T` | 1800s | the transaction timeout in place today, which has to cover the whole run |

At the observed 4.3 pushes per second a 50-resource page finishes in about 12
seconds, so `P` is a ceiling for degraded latency rather than the normal case.
`T` is sized from the population, not from `P`: it must exceed the total run
length, and `P` only has to fit inside it, which it does with room to spare at
any timeout large enough for a sync to finish. An operator lowering `T` should
lower `P` to match.

The token minter having no timeouts is a latent hang on every path that mints a
token, not only this one. It is filed as its own fix rather than folded in here.

## Design

### Page runner

The work splits into two pieces with a narrow interface between them.

`PagedSyncRunner` owns what is common to any paged sync: the loop, carrying the
cursor from one page to the next, stop propagation and counter merging. It holds
no session-bound types; its only Keycloak type is `SynchronizationResult`, a
plain counter object. It treats the cursor as opaque.

A `PageStep` owns everything specific to one kind of page: the cursor's type and
meaning, fetching the page, the transaction it runs in, processing each
resource, and deciding whether the list is finished. `ScimClient` does not
manage session or transaction lifecycle; it continues to hold the session it was
constructed with.

```
interface PageStep<C> {
    C initialCursor();
    PageOutcome<C> run(C cursor, int size);
}

record PageOutcome<C>(C next, boolean progressed, boolean exhausted,
                      SynchronizationResult counters, StopReason stopReason) {}

PagedSyncRunner.run(step, pageSize, syncRes)
    cursor = step.initialCursor()
    loop:
        outcome = step.run(cursor, pageSize)
        syncRes.add(outcome.counters)
        stop if outcome.stopReason == POLICY
        stop if outcome.exhausted
        fail if not outcome.progressed         // a stuck step fails loudly instead of spinning
        cursor = outcome.next
```

`runJobInTransactionWithResult` lets a page report back.
`SynchronizationResult.add` merges counters, and only after the page commits, so
a rolled-back page cannot inflate the totals.

The runner infers nothing about position or completion. Those belong to the step,
because only the step knows how its source behaves.

### Refresh page step: keyset paging over local user rows

The refresh step does not page through `searchForUserStream`. It pages through
Keycloak's local user table directly, using the last username it examined as
the cursor, and loads each user through the SPI.

```
RefreshPageStep.initialCursor() = null          // before the first username

RefreshPageStep.run(after, size)
    return runJobInTransactionWithResult(factory, session -> {
        start = clock.now()                     // the page budget includes the fetch
        realm = session.realms().getRealm(realmId)
        session.getContext().setRealm(realm)    // adapters resolve the component from it
        ScimClient bound to this session, reduced retry budget

        rows = select u.id, u.username from UserEntity u
                where u.realmId = :realmId
                  and u.enabled = true
                  and u.serviceAccountClientLink is null
                  and (:after is null or u.username > :after)
                order by u.username
                limit size

        last = after
        stopReason = NONE
        for each (id, username) in rows {
            last = username
            user = session.users().getUserById(realm, id)   // null if the user is gone
            if (user != null) {
                if (refreshOne(user, ...) == POLICY) {        // shared with the group path
                    stopReason = POLICY
                    break
                }
            }
            if (clock.now() - start exceeds P) {
                stopReason = PAGE_BUDGET
                break
            }
        }

        return { next = last,
                 progressed = rows.isEmpty() or !Objects.equals(last, after),
                 exhausted  = stopReason == NONE and rows.size() < size,
                 counters, stopReason }
    })
```

**The cursor is the last username examined.** It moves past every row the step
looked at: users it pushed, users it skipped, and users whose load returned null.
Nothing that happens to a row changes the position of any other row, because the
next page asks for usernames greater than the cursor rather than for a numbered
position.

**The filter keeps enabled users and leaves out service accounts.** Restricting
to enabled users matches the current search. Leaving out service accounts does
not: `JpaUserProvider` adds `serviceAccountClientLink is null` only when the
caller passes `include_service_account`, and refresh passes only `enabled`, so
today's refresh pushes enabled service-account users. They are client
credentials rather than people, nothing else in the plugin propagates them, and
pushing them appears to be an accident of the search refresh happened to use.
The new query excludes them on purpose, and Rollout lists it as a behaviour
change.

**The cursor is consistent within a database.** Its value comes from the
database and is compared in the database, which applies one collation to both
the comparison and the ordering. A unique constraint on `USER_ENTITY` over
`(REALM_ID, USERNAME)` makes the ordering total, and on a case- or
accent-insensitive collation the index uses the same collation, so two usernames
that compare equal cannot coexist.

**Completion is decided by the step, and it can decide from page length.** The
query reads raw rows with no validation or provider chaining, so a page shorter
than `size` is the last one. A page that ran to completion and came back short
reports exhaustion; an empty page does too. A page cut short by the budget or a
policy stop never reports exhaustion, because rows after `last` were not read.

**Directory validation runs only on a cache miss.** `session.users()` is the
caching layer. On a miss, `getUserById` reaches `UserStorageManager`, which
validates a federated user against its directory and, if the entry is gone,
deletes the user and returns null; the step skips that user, and the deletion
does not disturb the cursor. On a hit, `UserCacheSession.validateCache` only
checks that the storage component still exists and returns the cached copy
without consulting the directory.

That differs from the current search, which validates every result. The
consequences are accepted:

- **Removing users is the reconciler's job, not refresh's.** A user whose
  directory entry is gone but who is still cached is pushed as an ordinary
  update rather than skipped. The reconciler still deprovisions them once the
  cache entry expires or their last-seen attribute goes stale. Refresh
  removing such users was a side effect of the search it used, not a
  responsibility it was designed to carry.
- **A cached user is pushed from its stored copy.** Attributes whose mapper reads
  the value from the directory on each load may be pushed as the value last
  stored locally rather than the current directory value, until the cache entry
  is refreshed.
- **Refresh makes fewer directory calls.** Cache hits skip the lookup the current
  search makes for every user.

**A non-empty page always makes progress.** The budget is checked after a row is
examined, so a page whose fetch used the whole budget still moves past one row.
The runner's no-progress failure never fires for refresh; it exists for a step
whose cursor can legitimately stand still, which import will be.

**A policy stop outranks the budget stop.** A resource can both fail transiently
and push the page past `P`. Checking the policy first keeps the run from
continuing against an endpoint that `sync-on-error` said to stop for.

### Why keyset paging over local rows, and not the SPI search

An earlier draft paged `searchForUserStream(realm, params, first, max)` by
offset. Four review passes found defects in that approach, and they share a
cause: `UserStorageManager` is not a cursor.

- It pages the raw local query first and runs `importValidation` afterwards.
  Validation deletes a user whose directory entry is gone, in a separate
  transaction that commits during the page, so later offsets shift and an offset
  cursor skips users. Pages that are not the last can also come back short or
  empty, so neither page length nor emptiness marks the end.
- When local storage returns fewer rows than requested, the remaining slots are
  filled by searching federation providers. With import enabled, directory
  entries not yet imported are imported and returned inside the page, which
  moves an offset cursor past local rows it never read.
- Telling an empty page that marks the end from one emptied by validation needs a
  count taken before and after the fetch. A count read inside the page's own
  transaction does not see the deletions on a database running REPEATABLE READ.
- The final page leaves slots unfilled, which triggers a directory search over
  the whole directory inside a page transaction, where the page budget cannot
  bound it.

Keyset paging over the user table avoids all four. Validation deletions do not
move a username cursor, federation providers are never consulted to list users,
page length reliably marks the end, and no count or isolation assumption is
needed.

### Trade-offs of querying the user table

**It depends on Keycloak's JPA schema.** The query names `UserEntity` and its
`realmId`, `username`, `enabled` and `serviceAccountClientLink` fields. The
plugin already compiles against `keycloak-model-jpa`, but until now it has only
queried its own tables. A rename in a future Keycloak release would break this
query at runtime. The integration suite runs against both Keycloak 25 and 26,
which is where such a change would surface.

**Only users with a local row are examined.** Users served by a federation
provider without import have no `USER_ENTITY` row, so the query never sees them,
and LDAP synchronization does not import them either. That case is already
outside what the plugin supports: `docs/ldap-federation-support.md` makes
`Import Users = ON` a hard requirement, because without it federated users never
get a local row and the `scim-ldap-sync` mapper never fires. The same
requirement covers any custom user storage provider that does not import.

**Refresh stops importing directory users as a side effect.** The current
unpaged refresh goes through the provider chain, so it imports directory entries
that were never imported and pushes them. Enumerating local rows does not. Those
users reach the SCIM service when LDAP synchronization imports them, which fires
the `scim-ldap-sync` mapper, provided that mapper is attached to the LDAP
component as the plugin's setup requires; that is already the path a new
directory user normally takes. The difference shows only in a realm where LDAP
synchronization is not scheduled and refresh has been relied on to pull users
in.

**A rename across the cursor can be missed or seen twice.** A user renamed from
before the cursor to after it is examined again; one renamed the other way is
skipped for this run. Re-examining is harmless, and a skipped user keeps the
state the remote already has until the next sync.

### Why the transaction boundary belongs to the step

The runner cannot own the transaction, because the two kinds of sync this is
meant to serve need different transaction shapes.

Refresh reads the page of ids and loads each user in the same transaction that
processes them. A `UserModel` holds its session and, for the JPA implementation,
an `EntityManager` and an entity with lazy collections, so it cannot be handed to
another session: lazy loads, writes and the adapter's own session calls all fail.

Import, when it is batched, will fetch outside the transaction. The page is a
list of SCIM resources, which are plain objects with no session attachment, and
fetching them is an HTTP round trip that would otherwise count against the
transaction for no benefit.

A runner that opened the transaction itself would have to branch on which kind
of source it was driving. Putting the boundary in the step keeps the runner free
of that decision.

### A ScimClient per page

Each page constructs a `ScimClient` bound to its session and closes it, as
`ScimDispatcher.runAsync` and `ReconcilerRunner` already do. That means a fresh
connection pool per page, so keep-alive is lost at page boundaries: about one
extra TCP and TLS handshake per page.

The 43ms figure in `KeepAliveConfigManipulator` is a localhost measurement and
covers setup plus teardown. Against a remote sink a pessimistic 200ms is fairer,
which over 91 pages is roughly 18s across a 25-minute run. The conclusion holds
at either number: this does not justify refactoring `ScimClient` to share its
HTTP layer across sessions.

Per-page clients also change how tracing reads. The `scim.sync.refresh` span is
opened inside `refreshResources`, which the user path no longer calls. The
factory opens the run-level span around `PagedSyncRunner.run`, since the span
takes the resource type and endpoint URL from the component, which the runner
does not hold. Each page nests under it.

### Entry point

`ScimClient.sync` is retired. The import and refresh halves are selected in the
factory, which is where the session factory needed for per-page transactions
lives. Both existing gates are preserved, `sync-import` and `sync-refresh` stay
independent booleans rather than alternatives, and import still precedes refresh
within each resource type.

```
sync(sessionFactory, realmId, model):
    result = new SynchronizationResult()
    if propagation-user:
        if sync-import:  runJobInTransaction(... importResources(UserAdapter) ...)
        if sync-refresh: PagedSyncRunner.run(RefreshPageStep for users, ...)
    if propagation-group:
        if sync-import:  runJobInTransaction(... importResources(GroupAdapter) ...)
        if sync-refresh: runJobInTransaction(... refreshResources(GroupAdapter) ...)
    return result
```

The log that fires when both `sync-import` and `sync-refresh` are disabled moves
from `ScimClient.sync` to the factory alongside this branch.

This bypasses `ScimDispatcher.runOne`, which does two jobs. Losing the
`rollback-strategy` handling is correct rather than an oversight:
`rollback-strategy` is documented as covering interactive console and account
events only, and as never affecting federation imports or batch sync.

Losing its exception containment is not. `runOne` catches
`ScimPropagationException` and `Exception`, logs, and returns, which is why a
`RuntimeException` thrown out of `importResources` on a bad list response
currently leaves `sync()` returning a result rather than propagating into
Keycloak's sync manager. The factory keeps that behaviour by wrapping each half
in the same catch-log-and-continue, recording the failure on the
`SynchronizationResult`.

### Page steps and the adapters

Paged enumeration lives in `RefreshPageStep`, not on `Adapter`. `Adapter` gains
nothing. The step's query is specific to users and to the local table, and
adding a paged method to the abstract `Adapter` would force a meaningless
`GroupAdapter` implementation for a path that stays unpaged.

`getResourceStream()` therefore stays. The group half still uses it.

The per-resource body of `refreshResources` (the skip checks, the create or
replace decision, the counter increments, and the `SyncErrorPolicy` handling)
is extracted into one shared method used by both the paged user path and the
unpaged group path, returning a stop signal to its caller. The logic exists
once. The page step learns about a policy stop from that return value rather
than by catching anything, and reports it to the runner in the page outcome.

### A counting bug fixed during the extraction

`refreshResources` calls `syncRes.increaseUpdated()` after `create()` or
`replace()` has already returned early on `adapter.skip`, so users excluded by
`scim-skip` or `propagation-role` are reported as updated despite no request
being sent. The extracted method evaluates the skip before the create-or-replace
decision and counts only work that was attempted.

This is a live bug rather than one this design introduces, but the extraction is
the right moment to fix it and it would otherwise be copied into new code.
Operators should expect the reported `updated` figure to fall on realms that use
either exclusion, with no change in what is actually propagated.

### Groups keep one transaction

There is no paged enumeration of all groups. `GroupProvider` offers
`getGroupsStream(realm, Stream<String> ids, first, max)` and
`getTopLevelGroupsStream(realm, first, max)`, neither of which pages the full
tree without walking it. Group counts sit orders of magnitude below user counts,
so the group half keeps its single transaction. When
`getGroupsCount(realm, false)` exceeds 500 the factory logs a warning naming the
count, so a realm with pathological group counts reports the risk instead of
timing out without explanation.

### Configuration

Two component properties, typed `STRING_TYPE` with a typed getter, matching
`reconciler-interval-seconds`. Keycloak 25's `ProviderConfigProperty` has no
integer type.

| Name | Default | Description |
| --- | --- | --- |
| `sync-page-size` | `50` | Users examined per transaction. |
| `sync-page-max-seconds` | `45` | Wall-clock ceiling for one page, checked between resources. A page that exceeds it commits what it has done and the next page resumes after it. |

Component properties rather than JVM properties, because page sizing depends on
the latency of that component's SCIM endpoint and separate components can point
at different endpoints. `scim.dispatch.bulkBatchSize` is a JVM property because
it sizes a shared thread pool, which is global to the JVM. This is not.

Both are validated at component save, alongside `ReconcilerConfigValidator`:
each must parse as an integer greater than zero. The validator is the only
defence against a bad page size, and both bad values misbehave. A size of zero
reads no rows, so the first page reports the list exhausted and refresh silently
does nothing. A negative size is rejected by JPA's `setMaxResults` with an
exception on every run.

The reduced sync retry budget is a constant rather than a property. Exposing it
would invite settings that quietly undo the sizing guidance above.

### Failure handling

`sync-on-error` keeps its meaning through `SyncErrorPolicy.shouldStopRun`. What
changes is scope: a stop ends the remaining pages, and everything already
committed survives. An aborted run now costs one page rather than the whole
sync.

**`AUTO` stops treating 429 as a reason to stop.** `isTransient()` is true for
429, so `AUTO` currently aborts a run on a single throttled response. That reads
throttling as an endpoint being down, which it is not: 429 means slow down, and
the retry already backs off. `ScimPropagationException` gains `isThrottled()`,
returning false by default and overridden in
`InvalidResponseFromScimEndpointException` as `httpStatus() == 429`. `AUTO`
becomes `isTransient() && !isThrottled()`. `CONTINUE` and `STOP` are unchanged,
so an operator who wants the old behaviour sets `stop`.

This pairs with the smaller retry budget. Four attempts ride out a blip; a
sustained throttle then skips the resource and the run carries on, rather than
discarding the remaining pages.

**A long streak of throttles stops the run.** If `AUTO` never stops on 429, an
endpoint that throttles every request makes the run walk the whole population.
Each user costs its four attempts and its backoff, and almost nothing is
pushed. The step counts throttled failures in a row. One page of them in a row
means the endpoint is not available to this run, so the run stops with the
reason recorded. Any push, skip, or failure of another kind resets the count.
The count carries across pages, because the step lives for the whole run.

A stop raised mid-page commits what that page has done. Those records
succeeded, and discarding them would reproduce the original problem at smaller
scale.

A failure of the page transaction itself propagates and aborts the run, logging
the cursor it stopped at. Pages are not retried automatically, since a poison
page would loop.

### An orphan window this narrows

HTTP is not transactional. When the enclosing transaction rolls back today, the
mapping rows vanish but the SCIM resources already POSTed remain on the remote.
The next run finds no mapping, calls `create`, and POSTs them again. Deployments
have been shielded from duplicates only by consuming services that deduplicate
on `userName`. Per-page commits narrow that window from the whole run to a
single page.

### What this does not fix

A re-run is not cheaper. Refresh is unconditional, so a restarted sync re-pushes
every user. Skipping unchanged users needs change detection, which is a separate
feature.

Throughput is unchanged. Refresh issues one HTTP request per user.
`bulkCreateUsers` exists but is reached only from the LDAP-import path through
`ScimBulkLane`. Wiring SCIM `/Bulk` into refresh would address the observed rate
and is tracked separately.

## Rollout

Several behaviours change the moment the new version is deployed, and none of
them depends on a configuration change:

- The sync-path retry budget drops from 10 attempts to 4 with a capped interval.
- A sync page uses shorter HTTP timeouts, 1s to wait for a pooled connection,
  3s to connect and 10s per read, over three attempts. Other paths keep 30s and
  ten attempts.
- `AUTO` no longer stops a run on 429, but one page of consecutive 429s stops
  the run.
- Refresh commits per page rather than per run, which also narrows the orphan
  window.
- Refresh no longer imports directory users that were never imported.
- Refresh no longer pushes service-account users. Their existing records on the
  SCIM service stop receiving updates; they are not deleted.
- Refresh no longer removes users whose directory entry is gone when those users
  are in the user cache. The reconciler handles them.
- The reported `updated` count falls on realms using `scim-skip` or
  `propagation-role`, from the counting fix.

Before deploying, confirm the documented prerequisites on each realm: LDAP
federation runs with `Import Users = ON`, the `scim-ldap-sync` mapper is attached,
and LDAP synchronization is scheduled if refresh has been relied on to pull users
in. Where directory removals matter, confirm the reconciler is enabled.

1. Deploy. Sync behaviour changes as above. The 1800s timeout stays as it is.
2. Run a sync and confirm from the logs that it completes in pages, with the
   users examined consistent with the realm's count of enabled users excluding
   service accounts. This is where a throttling endpoint would first show up
   under the new retry budget. Compare the enumeration time with the previous
   run: the directory walk that dominated it should be gone.
3. Compare the run's duration with the transaction timeout and record the
   headroom. Enumeration no longer dominates the run, so the duration should
   fall, but the timeout still has to exceed it.
4. Check the first run after deployment for two new failure modes. Writes that
   time out at 10 seconds show the endpoint is slower than the new sync-page
   read timeout; raise it or investigate the endpoint. A run that stops with the
   throttle-streak reason shows the endpoint rate-limited a whole page in a
   row.

The timeout cannot be lowered, for the reason measured under "What the outer
transaction does". Should a run exceed it anyway, the sync is reported as failed
while the pages it committed are kept, so a re-run repeats work rather than
losing it. The remaining operational risk of a long global timeout, that any
wedged transaction holds its connection and locks for half an hour, is unchanged
by this design.

## Deferred: sync-import

`importResources` keeps its single transaction. It also appears to read only the
first page of the remote list: the call is
`scimRequestBuilder.list(listUrl, resourceClass).get().sendRequest()`, with no
`startIndex` or `count` and no paging loop. `importResources` is generic, so
this applies to `/Groups` as well as `/Users`.

Those are one unit of work rather than two. Correct paging is what makes import
long-running, so pagination cannot land without batching. Fixing pagination is
also a behavior change with real reach: import currently processes at most one
page, and after a fix it processes the entire remote population. Under
`sync-import-action=CREATE_LOCAL` that turns a handful of local accounts into
the full remote population, and `DELETE_REMOTE` is worse. That warrants its own
design and rollout rather than riding along on a timeout fix.

**Batching import later leaves the runner unchanged.** Because the step chooses
its own cursor type and reports the next cursor, whether it made progress, and
whether the list is exhausted, `PagedSyncRunner` needs no change when an
`ImportPageStep` is added. Import's cursor would be a SCIM `startIndex` rather
than a username, since a remote list offers nothing else to page by. The import
step carries its own complications, and each is contained in it:

- **Fetching without a session.** `ScimClient` takes a session in its constructor
  and uses the same instance to fetch and process. An import step needs a way to
  issue the list request before the transaction opens: a session-free fetch
  path, a second client per page at the cost of one more handshake per page, or
  a change to `ScimClient`. That is the largest piece of work in import batching,
  and it sits in the step.
- **Short pages from the server.** RFC 7644 lets a server return fewer than
  `count` on a page that is not the last, and many servers cap `count`. The step
  reports exhaustion from `ListResponse`'s `totalResults` instead of page length,
  so a short page does not end the run.
- **A total that moves.** Under `DELETE_REMOTE` the remote list shrinks as the run
  deletes, so the step compares against the most recent page's `totalResults`
  rather than the first.
- **Deletions shift the list.** Each deletion moves the rest of the remote list
  toward the start, so the step advances its index past only what it examined and
  did not delete; otherwise the next page skips resources it never saw. A page
  where everything was deleted legitimately leaves the index where it was, so the
  step reports progress when it advanced or deleted anything. A server slow to
  reflect its own deletions could return the same page again; the step should
  detect a repeated page and report no progress, which the runner turns into a
  failure rather than a spin.
- **Index base.** SCIM's `startIndex` is 1-based, and the step owns that
  convention.

Because import reads one page today it is short and finishes well inside the
timeout, though the remote server chooses the page size and
`sync-import-action=CREATE_LOCAL` adds a local write per unmatched resource, so
that is guidance rather than a measured figure. It is the fixed version, reading
the whole remote population, that will need batching before its transaction
length becomes a concern.

## Testing

Unit, through package-private static seams, as used for `isRetryableStatus`,
`shouldDeactivate` and `skipAlreadyDeactivated`:

- `PagedSyncRunner` against a fake `PageStep` returning scripted outcomes. The
  runner holds no session-bound types, so it is tested without a session or a
  container. Cases: a policy stop, an exhausted outcome, a no-progress outcome
  failing rather than looping, counters merged from every page including the
  last, and each page receiving the cursor the previous outcome returned.
- `RefreshPageStep`'s page outcome as a truth table: an empty page reports
  exhausted; a completed short page reports exhausted with the cursor at its last
  username; a completed full page does not; a budget-cut or policy-stopped page
  never reports exhausted, even when short; a page whose users are all skipped or
  all removed by validation still advances the cursor; a null load is skipped
  without a push or a counter.
- The at-least-one-row invariant: a page already over its wall-clock budget still
  moves the cursor past one row.
- Stop propagation out of a page outcome, by stop reason.
- Counter merge.
- The wall-clock predicate with an injected `Clock`, following
  `OAuthClientCredentialsTokenSource`.

Integration:

- Multi-page correctness: seed more users than one page, sync, assert each user
  is pushed once and the loop terminates.
- All-skipped page: seed a page's worth of users excluded by `propagation-role`
  ahead of an eligible user, assert the sync reaches the eligible user and
  terminates.
- A directory entry removed mid-population, with the user not cached: seed more
  users than one page, evict the removed user from the user cache, remove its
  directory entry, sync, and assert every remaining user is pushed and the
  removed user is not.
- A directory entry removed while the user is cached: assert refresh still
  completes and pushes the user as an update, pinning the accepted behaviour, and
  that the reconciler deprovisions the user afterwards.
- Disabled users and service-account users are not examined. The service-account
  half pins a behaviour change, not parity.
- The first page, with a null cursor, and a subsequent page run against the
  database the integration suite uses. The unit tests cannot exercise the query's
  null-parameter handling or collation.
- A directory entry that was never imported is not pushed by refresh, and is
  pushed once LDAP synchronization imports it. This pins the behaviour change
  called out in Rollout.
- A run longer than the transaction timeout: seed a population that cannot
  finish inside a low timeout, and assert that every page commits and the pushed
  users keep their mappings even though Keycloak reports the sync as failed.
  This pins the behaviour the spike measured, and it fails against the current
  code, which keeps nothing.
- Stop preserves progress: the endpoint returns 200 for the first page and 503
  after it; assert the run aborts and the first page's mappings survive.
- Counter accuracy: `SynchronizationResult` reflects only committed work, and
  skipped resources are not reported as updated. The second half covers the
  counting bug fixed above, which the all-skipped-page population would
  otherwise trip.

The short-timeout test needs its own container. `IntegrationTestBase` holds a
`static final KeycloakContainer` started once in `@BeforeAll`, so the timeout
cannot be varied per test class from there. `PerfTestBase` suggests Keycloak
honours `JAVA_OPTS`-style environment variables, though it asserts nothing about
it, and it lives in the `perfTest` source set rather than `integrationTest`. If
a dedicated container proves impractical, the stop-preserves-progress test
covers the substance, since both turn on committed pages outliving a failed
run.

## Alternatives considered

- **Snapshot ids first, then batch the writes.** Mirrors `ReconcilerRunner`'s
  two-phase shape. Reading ids from the user table is cheap under this design, so
  cost is not the objection. Rejected because the snapshot holds every id in
  memory, which grows without bound with the realm, and because the work list
  goes stale over a long run while the keyset cursor reads current rows on every
  page.
- **Enumerate from the mapping table instead of Keycloak's user list.** Avoids
  the federation walk, as the reconciler does. Rejected because it only sees
  already-mapped users, so it cannot serve a first-time backfill.
- **Raise the transaction timeout.** The current mitigation. Rejected as a
  complete fix because it is global, it leaves roughly 20 percent headroom
  before the same failure returns, and it does nothing about work discarded on
  rollback. The raised timeout stays under this design, which addresses the
  second and third objections only.
- **Keep the retry budget at 10 on the sync path.** Rejected because it makes a
  page unboundable: one resource can hold a transaction for over five minutes,
  so no page size or wall-clock bound restores a sane timeout.
- **Offset paging over `searchForUserStream`.** Uses only the SPI, with no
  dependency on Keycloak's JPA schema. Rejected for the four defects described
  under "Why keyset paging over local rows": validation deletions shift offsets,
  federation providers fill short pages mid-list, telling a finished list from an
  emptied window needs counts that depend on isolation level, and the final page
  walks the whole directory inside a transaction.
- **The runner owns the transaction.** Simpler for refresh alone, and it was the
  shape of an earlier draft. Rejected because refresh must fetch inside the
  processing transaction and import should fetch outside it, so a runner holding
  the boundary would have to be restructured, or would have to branch on its
  source, when import is batched.
- **`KeycloakModelUtils.runJobInTransactionWithTimeout`.** Exists in 25.0.6 and
  would let the sync set its own transaction timeout without touching the global
  setting, answering the "the timeout is global" half of the problem directly.
  Rejected as a solution because it does nothing about the other half: one
  transaction still wraps the whole run, so a failure still discards every
  mapping row written. The spike adds a second objection: the outer transaction
  belongs to Keycloak and takes the global timeout, so a per-job timeout on the
  plugin's own transactions cannot extend it. It would be a narrower workaround,
  not a fix.
- **Parallel page dispatch over the existing worker pool.** Rejected for now. It
  adds concurrent load on the SCIM endpoint and more failure modes, and the
  requirement is completing reliably rather than completing faster. It would not
  be a small addition either: each page starts from the last username the
  previous page examined, and a page can stop short, so page boundaries cannot be
  computed ahead of time and pages cannot simply be dispatched in parallel.
