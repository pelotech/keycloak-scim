# Adding LDAP Federation Import Support to mitodl/keycloak-scim

This document summarizes what it takes to make
[mitodl/keycloak-scim](https://github.com/mitodl/keycloak-scim) send
outbound SCIM events for users that Keycloak imports through its LDAP
User Federation. This way, a downstream SCIM server sees
third-party-managed users. It does not need to poll LDAP or Keycloak
directly.

## Status (2026-04-23)

**Shipped**

- `sh.libre.scim.ldap.ScimLdapStorageMapper` and `ScimLdapStorageMapperFactory`
  (id `scim-ldap-sync`), with `META-INF/services` registration.
- `keycloak-ldap-federation` as a `compileOnly` dependency, through the
  Gradle version catalog. It targets Keycloak 25.0.6 as the minimum
  version. One artifact is binary-compatible with 26.x.
- Shared-service routing. The mapper delegates to the existing
  `ScimDispatcher`. So the event-listener path and the
  federation-import path send to the same configured SCIM providers.
- Fail-open failure handling. `ScimDispatcher.runOne` catches SCIM
  errors. They do not stop the Keycloak-side LDAP import.
- Idempotency. `ScimClient.create` stops early when a `ScimResource`
  mapping already exists locally. So lazy imports and repeated syncs
  do not create duplicate SCIM resources.
- Test harness. JUnit 5 and Mockito unit tests, plus
  Testcontainers-driven integration tests (Keycloak, osixia/openldap,
  and an embedded WireMock SCIM server). This covers scenario 1 (lazy
  import sends POST), scenario 2 (modify in LDAP, then
  `triggerChangedUsersSync`, sends PUT through the `isCreate=false`
  path), scenario 3 (full sync sends one POST per user, with no
  duplicates), and scenario 5 (fail-open on a SCIM server failure). It
  also covers the admin-REST event-listener path end to end: admin
  create sends POST, admin update sends PUT, admin delete sends
  DELETE, and `username-source=email` sends the email as the SCIM
  `userName`.
- Fixed a pre-existing NPE (null pointer exception) in
  `ScimEventListenerProvider`'s DELETE handler. It called
  `getUser(userId)` after commit, when the user was already deleted,
  then read `user.isEmailVerified()`. It now uses `event.getUserId()`
  directly. The mapping table is the source of truth for whether a
  user was ever propagated. The `emailVerified` check at delete time
  was not needed.

**Deferred / open**
- Scenario 4 (deletion reconciliation): **a scoped solution has
  shipped.** The baseline behavior, with no reconciler running, is
  still fixed by the test `ldapSyncAloneDoesNotPropagateDeletion`.
  Removing a user from LDAP, then running `triggerFullSync`, does not
  propagate to SCIM. This is because upstream Keycloak issue
  [#35235](https://github.com/keycloak/keycloak/issues/35235) leaves
  the local `UserModel` in place, and no admin `USER/DELETE` event
  fires. The plugin now ships a reconciler (`ReconcilerRunner` and
  `POST /realms/{realm}/scim-reconcile/{componentId}`). It sends a
  SCIM DELETE for mapping rows whose local user is gone, or whose
  `ldap-federation-last-seen` attribute is older than a configurable
  threshold. The test `reconcilerDeletesScimResourcesForMissingLdapUsers`
  verifies this end to end.
- Role-gating. An opt-in filter that limits SCIM propagation to a
  configurable subset of users.

## Problem

mitodl's `ScimEventListenerProvider` (in `src/main/java/sh/libre/scim/event/`) listens
only to Keycloak's `EventListenerProvider` SPI. That SPI fires for:

- end-user actions (registration, profile self-update, email verify, delete-account)
- admin console and admin REST API actions (user and group CRUD, role mappings)

It does **not** fire for any path where Keycloak's LDAP User
Federation imports users from an LDAP backend. This includes lazy
on-demand lookup, the periodic sync job, and an explicit
`/user-storage/{id}/sync` call. This is a limitation in Keycloak core,
documented in
[*Keycloak: Event Listener SPI for LDAP / User Federation Sync*](https://medium.com/@ivancheahkf/keycloak-event-listener-spi-for-ldap-user-federation-sync-62fa17c573bc).
mitodl's source confirms this: no `LDAPStorageMapper` subclass exists
anywhere in the repository.

Consequence: in any deployment where users originate in LDAP (for
example FreeIPA) and flow into Keycloak only through federation,
mitodl sends nothing. No SCIM POST reaches the SCIM server for the
initial import. No SCIM PATCH reaches it for later LDAP-driven
updates. Users produce outbound SCIM traffic only after an admin
touches them in the Keycloak admin console. For third-party-managed
deployments, this is rare or never.

## The hook that *does* cover federation imports

`org.keycloak.storage.ldap.mappers.LDAPStorageMapper#onImportUserFromLDAP(LDAPObject, UserModel, RealmModel, boolean isCreate)`.

This method fires every time Keycloak turns an LDAP entry into a user,
across all three federation trigger paths:

| Trigger | Fires `onImportUserFromLDAP`? |
| --- | --- |
| Lazy on-demand lookup (e.g., `GET /users?username=foo` triggers a federation fetch) | yes |
| Periodic sync (`triggerChangedUsersSync` scheduled) | yes |
| Explicit `POST /user-storage/{id}/sync` | yes |

The `isCreate` argument tells a first-time import from an update.

## Required changes to mitodl

### 1. New package: `sh.libre.scim.ldap` (or `sh.libre.scim.storage.ldap`)

Two new classes:

#### `ScimLdapStorageMapperFactory implements LDAPStorageMapperFactory<ScimLdapStorageMapper>`

- Registers the mapper, so it appears in the Keycloak admin UI as a
  mapper you can assign on each LDAP Storage Provider.
- `getId()` → a stable string like `"scim-ldap-sync"`.
- `create(session, model)` → returns a new `ScimLdapStorageMapper`.
- Declare configurable properties on the factory if you want
  per-provider settings, for example "only propagate users with role
  X" or "dry-run mode". Otherwise, leave the config empty and inherit
  the event-listener-level config.

#### `ScimLdapStorageMapper implements LDAPStorageMapper`

Only `onImportUserFromLDAP` does real work. The other methods can do
nothing. See the Medium article for the skeleton:

```java
@Override
public void onImportUserFromLDAP(LDAPObject ldapUser, UserModel user, RealmModel realm, boolean isCreate) {
    // Reuse mitodl's existing ScimClient / ScimResource machinery.
    // Resolve the configured SCIM service providers for this realm via the
    // same UserStorageProviderFactory mechanism the event listener uses.
    // For each provider:
    //   if (isCreate) scimClient.create(user);
    //   else          scimClient.replace(user);
    // Persist mitodl's ScimResource row (ssoId ↔ remote SCIM id) via the
    // existing JpaEntityProvider so subsequent updates hit the right endpoint.
}

@Override public void close() {}
@Override public void onRegisterUserToLDAP(LDAPObject ldapUser, UserModel localUser, RealmModel realm) {}
@Override public LDAPStorageProvider getLdapProvider() { return null; }
@Override public boolean onAuthenticationFailure(LDAPObject ldapUser, UserModel user, AuthenticationException ex, RealmModel realm) { return false; }
@Override public void beforeLDAPQuery(LDAPQuery query) {}
@Override public UserModel proxy(LDAPObject ldapUser, UserModel delegate, RealmModel realm) { return delegate; }
@Override public List<UserModel> getRoleMembers(RealmModel realm, RoleModel role, int firstResult, int maxResults) { return null; }
@Override public List<UserModel> getGroupMembers(RealmModel realm, GroupModel group, int firstResult, int maxResults) { return null; }
@Override public SynchronizationResult syncDataFromFederationProviderToKeycloak(RealmModel realm) { return new SynchronizationResult(); }
@Override public SynchronizationResult syncDataFromKeycloakToFederationProvider(RealmModel realm) { return new SynchronizationResult(); }
```

The body of `onImportUserFromLDAP` should call mitodl's existing SCIM
outbound code. This avoids duplicating the HTTP client, auth, retry,
and mapping logic. Today that code lives under `sh/libre/scim/core/`,
and `ScimEventListenerProvider` calls it. Extract a shared method or
service, and call it from both entry points.

### 2. Maven dependency

Add to `pom.xml`:

```xml
<dependency>
  <groupId>org.keycloak</groupId>
  <artifactId>keycloak-ldap-federation</artifactId>
  <version>${keycloak.version}</version>
  <scope>provided</scope>
</dependency>
```

### 3. Deployment descriptor (Keycloak on WildFly only)

If you deploy the plugin to a legacy WildFly-based Keycloak, add this
to `jboss-deployment-structure.xml`:

```xml
<module name="org.keycloak.keycloak-ldap-federation" />
```

Quarkus-based Keycloak, the default since Keycloak 17, does not need this.

### 4. Service registration

Create `src/main/resources/META-INF/services/org.keycloak.storage.ldap.mappers.LDAPStorageMapperFactory`.
It should contain the fully qualified class name:

```
sh.libre.scim.ldap.ScimLdapStorageMapperFactory
```

### 5. Realm-level configuration (operator step, not code)

After deployment, an operator must attach the new mapper to each LDAP
Storage Provider in each realm, through the Keycloak admin UI: *User
Federation → (LDAP provider) → Mappers → Create → SCIM LDAP Sync*. The
mapper applies per provider, per realm. Document this in the plugin's
README.

## Architecture notes

### Routing to the correct SCIM client

mitodl represents each configured SCIM server as a
`UserStorageProviderFactory` instance. It reuses Keycloak's
UserStorageProvider SPI as a container for configuration UI, not as a
real user-storage mechanism. The new LDAP mapper needs access to the
same list. There are two options:

- **Shared service.** Extract a `ScimPropagation` helper from
  `ScimEventListenerProvider`. It takes
  `(KeycloakSession, RealmModel, UserModel, Op)` and sends to every
  configured SCIM provider. Both the event listener and the LDAP
  mapper call this helper.
- **Queue-based.** Write a `PendingScimOp` row through the existing
  `JpaEntityProvider` (`ScimResource` schema), and let a background
  worker drain the queue. This separates the import path from HTTP
  latency. It is heavier, but it removes the "event listener is not
  cancelable" problem that mitodl's README already notes.

The shared-service option is smaller, and ships faster. The queue
option is the better long-term shape if federation sync batches are
large, and blocking SCIM calls cause sync timeouts on the Keycloak
side.

### Create vs update

`isCreate == true` sends a SCIM POST. `isCreate == false` sends a SCIM
PATCH, or a PUT, depending on how mitodl's core already handles
updates. Reuse the mapping that mitodl's event listener uses for the
`UPDATE` admin event. The data shape is the same.

### Deletion

`LDAPStorageMapper` has **no** `onDelete` or `onRemove` hook. When an
LDAP entry disappears and the Keycloak periodic sync detects it,
Keycloak removes the local `UserModel`. That removal is an admin
event, so in principle mitodl's existing `ScimEventListenerProvider`
catches it through the admin `USER DELETE` event. **Check this
directly during the investigation.** Removals from
`LDAPStorageProvider.removeNonExistentUsers()` may go through a
different code path that skips the admin event system. If they do,
the mapper needs added logic to reconcile deletions. Either compare
the SCIM server's resource list against the current Keycloak user set
on a timer, or hook `syncDataFromFederationProviderToKeycloak` and
compare before and after.

### Prerequisites on the Keycloak side

- The LDAP Storage Provider **must** have `Import Users = ON`. If it
  is off, federated users never get a `UserModel` row, and
  `onImportUserFromLDAP` never fires. Document this as a hard
  requirement.
- The configured LDAP sync schedule sets update latency. Lazy import
  covers the first authentication. For changes made in LDAP between
  logins, periodic sync is the only trigger. Consider recommending a
  short `Changed Users Sync Period` (default 24h) for third-party
  deployments.

### Idempotency

`onImportUserFromLDAP` can fire more than once for the same user, for
example on lazy import and then again on periodic sync. The outbound
SCIM operation must be idempotent. mitodl's existing `ScimResource`
table stores the local-to-remote ID mapping. The propagation helper
should use this to choose POST or PATCH, regardless of the `isCreate`
flag. Trust the local database over the hook argument.

### Failure handling

mitodl's README already notes that its event listener is "not
cancelable." If the SCIM server fails, there is no clean way to roll
back the Keycloak state. The LDAP mapper has the same limit: Keycloak
calls `onImportUserFromLDAP` inside its LDAP sync transaction, and
throwing an error aborts the import of that user. Decide between:

- **Fail-closed.** Throw on SCIM errors. The user is not imported into
  Keycloak, so authentication fails until the SCIM server is
  reachable. This matches a "complete record or nothing" approach.
- **Fail-open with queueing.** Catch the error, queue a pending SCIM
  operation, and let a retry worker drain it. The user is imported
  into Keycloak right away. The SCIM server catches up shortly after.

Fail-open is almost certainly the right choice for third-party
deployments. You do not want a short SCIM-server outage to block every
federated user from authenticating into Keycloak. It pairs with the
queue-based architecture option above.

## Testing

Set up a `docker-compose.yml` with Keycloak, a FreeIPA or OpenLDAP
container, and a lightweight SCIM server (for example, a small
Express app that logs every request). Configure Keycloak's LDAP User
Federation against the FreeIPA or OpenLDAP container, with
`Import Users = ON` and the new mapper attached. Then test:

1. Create a user directly in LDAP. Trigger lazy import through the
   Keycloak admin API (`GET /users?username=foo`). Check that the
   SCIM server received a POST.
2. Modify the user in LDAP. Run `triggerChangedUsersSync`. Check that
   the SCIM server received a PATCH through the `isCreate=false` path.
3. Run `triggerFullSync` with a large group of users. Check that each
   imported user produces exactly one SCIM POST or PATCH, with no
   duplicates.
4. Delete the user in LDAP. Run `triggerChangedUsersSync`. Check one
   of two outcomes: a SCIM DELETE fires through the admin-event path
   mitodl already handles, or the gap is documented and a
   deletion-reconciliation plan follows.
5. Stop the SCIM server mid-sync. Check that the chosen failure
   handling, fail-closed or fail-open, behaves as designed.

## Open questions

- **Keycloak version compatibility.** `LDAPStorageMapper`'s signature
  has changed across Keycloak major versions. The current build
  targets Keycloak 25.0.6 as the minimum, and is binary-compatible
  with 26.x. Future major versions will need checking. The test
  harness's Testcontainers setup gives a fast way to do that.
- **Role-gating.** Add an opt-in filter, so the mapper propagates only
  users with a configured realm role. Decide whether to build this
  into the mapper now, or as a follow-up.

## Reconciler design (v1 shipped; extensions planned)

This closes the scenario-4 gap. LDAP-federated users who disappear
from LDAP stay in Keycloak, because of upstream bug
[#35235](https://github.com/keycloak/keycloak/issues/35235). So they
also stay on the external SCIM server. The reconciler periodically
sends SCIM DELETEs for mappings whose federation-backed user has not
been seen from LDAP within a configured window.

### Principles

- **Scope is outbound SCIM only.** The plugin does not delete the
  local `UserModel`. That is Keycloak's responsibility, tracked in
  #35235. When upstream fixes it, admin `USER/DELETE` events fire, and
  the existing event-listener path propagates the delete. The
  reconciler needs no change for that.
- **Opt-in, off by default.** Operators on affected Keycloak versions
  enable it directly. On versions where upstream has fixed the bug,
  leaving it off is fine. Leaving it on is also fine, since it is
  idempotent.
- **Uses only public SPIs.** No Hibernate integrators, and no private
  subclassing. It uses `TimerProvider` for scheduling, `session.users()`
  and `UserModel.getFederationLink()` for data access, and the
  existing `ScimDispatcher` and `ScimClient.delete` for the outbound
  call.
- **Must be idempotent with the event-listener path.** Both can fire
  for the same delete. The net effect must be one SCIM DELETE and one
  cleared mapping. `ScimClient.delete` already handles the "mapping
  missing" case with `NoResultException`, so concurrent races are
  safe.

### Liveness signal: `ldap-federation-last-seen`

Every call to `ScimLdapStorageMapper.onImportUserFromLDAP` sets the
`ldap-federation-last-seen` user attribute to the current timestamp.
This covers lazy imports, periodic sync, and explicit
`triggerFullSync` or `triggerChangedUsersSync` triggers, the same
three paths the primary mapper hook covers. The attribute name is
federation-type-specific, not SCIM-specific, so future federation
integrations can reuse the same signal.

Storage: Keycloak's existing `USER_ATTRIBUTE` table stores user
attributes. The plugin needs no schema migration.

### Decision rule (pluggable witnesses)

The reconciler checks each federation-linked user against one or more
witnesses, each an independent piece of evidence. **It deletes only
when every active witness agrees the user is absent.** Today there is
one witness. The design leaves room for more, without a restructure.

Active witnesses at v1:

- **Timestamp witness.** `ldap-federation-last-seen` is older than
  `reconciler-stale-threshold-hours`. This is the final word on
  "stale."

Before checking witnesses, the reconciler applies two preconditions
to each mapping row:

1. `session.users().getUserById(realm, mappingId)`: if this returns
   `null`, the local user is gone entirely (dropped by federation,
   deleted by an admin, or similar). The reconciler deletes the SCIM
   resource without checking witnesses.
2. Otherwise, check `user.getFederationLink()`. If this is `null`,
   the user is local-only, and out of scope for the LDAP reconciler.
   The reconciler skips it.

The timestamp witness applies only to users that still exist locally,
still have a federation link, and have not been seen recently.

Planned witnesses (future, not v1):

- **Bloom-filter witness.** During sync, every `onImportUserFromLDAP`
  call adds the username to an in-progress Bloom filter, scoped to
  the LDAP federation component. When the reconciler checks a
  candidate, and the filter is fresh (its `built_at` is within 2 times
  the federation's sync period), and `filter.mightContain(username)`
  is `false`, the witness votes "absent." If the filter is stale, the
  witness abstains, and the decision falls back to timestamps alone.
  This degrades gracefully. It catches a narrow bug: a silent
  timestamp-write failure that the threshold buffer does not catch.
  The cost is modest, about 1 to 2 bytes per user, and one `put` per
  user per sync.

Why this shape: it gives a clear extension point if false positives
show up in production, without adding complexity to the v1
implementation. Adding the filter later means adding a witness
implementation and including it in the AND check, not restructuring
the reconciler.

### Config (shipped on the SCIM provider component)

- `reconciler-enabled` (bool, default `false`)
- `reconciler-interval-seconds` (string, default `"86400"`, 24h)
- `reconciler-stale-threshold-seconds` (string, default `"172800"`, 48h)

Values are in seconds, to match Keycloak's own federation-sync config
convention, for example `fullSyncPeriod`. The endpoint also accepts a
`thresholdHours` query parameter, for operator-forced passes.

Config validation at component save time (shipped, in
`ReconcilerConfigValidator`):

- When `reconciler-enabled=false`, no validation runs. The operator
  has not opted in.
- Both `reconciler-interval-seconds` and
  `reconciler-stale-threshold-seconds` must be positive.
- `reconciler-stale-threshold-seconds` must be strictly greater than
  `reconciler-interval-seconds`.
- For every LDAP federation in the realm with `fullSyncPeriod > 0`,
  `reconciler-stale-threshold-seconds` must be strictly greater than
  that federation's `fullSyncPeriod`. Otherwise, the reconciler would
  delete users the federation simply has not had time to see again.
  Federations with periodic sync disabled (`fullSyncPeriod <= 0`) skip
  this check. Operators who disable periodic sync accept that the
  reconciler works from lazy-import-only liveness data.

A violation throws `ComponentValidationException` at save time. So
the admin console or REST API surfaces the error, and the bad config
never reaches the timer.

### Manual trigger (shipped)

A convenience endpoint through `RealmResourceProviderFactory`.
`POST /realms/{realm}/scim-reconcile/{componentId}` forces an
immediate reconciliation pass for the given SCIM provider, without
waiting for the timer. The optional `thresholdHours` query parameter
overrides the default for a single call, useful for operator-forced
cleanups. It returns `{"deleted": N}`. The caller needs a bearer token
issued by that realm, for a user who holds the realm's `manage-users`
admin role. See
[the endpoint reference](configuration.md#caller-authentication).

### Scheduled trigger (shipped)

`ScimStorageProviderFactory` schedules a `TimerProvider` task per SCIM
component, when `reconciler-enabled=true`. Entry points:
- `onCreate` fires when a component is added to a realm through the
  admin console or REST. Operators who configure a new SCIM provider
  get their timer scheduled without a restart.
- `onUpdate` reschedules the timer on config changes: interval,
  threshold, or the enabled flag.
- `preRemove` cancels the timer when the component is deleted.
- `postInit` scans at boot time, so existing components get their
  timers back after a Keycloak restart.

The scheduled task runs in its own `KeycloakSession`, through
`KeycloakModelUtils.runJobInTransaction`. `ScimClient.delete` is
idempotent: a SCIM DELETE on a missing mapping does nothing. So
clustered deployments, where every Keycloak node schedules its own
timer, are safe, just a bit wasteful. A setting to deduplicate
cluster-wide is a possible follow-up.

### If upstream Keycloak ever fixes #35235

The pattern this reconciler uses, timestamp-based liveness plus a
threshold, maps directly to the `TODO` in Keycloak's own
`LDAPStorageProviderFactory.sync`: "*Remove all existing keycloak
users, which have federation links, but are not in LDAP. Perhaps
don't check users, which were just added or updated during this
sync?*" The parenthetical is exactly the threshold logic.

If Keycloak eventually adopts this pattern, for example a
`FEDERATION_LAST_SEEN_AT` column on `USER_ENTITY` plus a scheduled
task that deletes local users past a threshold, the plugin-side
reconciler turns itself off. Keycloak's task fires admin `USER/DELETE`
events, the existing event listener catches them, mapping rows clear
through the `delete()` path, and nothing remains for the reconciler to
find on its next run. No version check is needed. This works on every
supported Keycloak release.

## Group-membership propagation (shipped)

When `onImportUserFromLDAP` fires, `ScimLdapStorageMapper` also
propagates the imported user's current group memberships to SCIM,
through `ScimClient.ensureGroupMembership`. For each group the user
belongs to at import time, the call sequence is:

1. **Ensure the group exists in SCIM.** `ensureGroupMembership` sends
   an idempotent group create. It stops early when a local mapping
   already exists. When the SCIM provider component has
   `group-patchOp=false`, this step is skipped, because the `replace`
   path used during a normal group sync already covers it.
2. **Add the member with a delta PATCH.** The plugin sends a
   single-member ADD PATCH (RFC 7644) for the user. This is the same
   delta-PATCH mechanism the `GROUP_MEMBERSHIP` event-listener path
   uses.

Additions are delta-driven, see below. Only a group the user has
newly joined produces an ADD PATCH. So a steady-state re-import sends
nothing. **Removals** go through the same hook, for a user dropped
from an LDAP group.

### Membership add/remove diff

LDAP-driven membership changes fire no `GROUP_MEMBERSHIP` event. So
they ride the import hook instead of an event. On each import, the
`SCOPE_GROUP` worker compares the user's *current* groups against a
per-component record of the groups it last propagated for that user.
It reconciles both directions as a delta: a single-member **ADD**
PATCH for each newly joined group (`current − stored`), and a
single-member **REMOVE** PATCH for each group the user has left
(`stored − current`). Groups already propagated are skipped. So an
unchanged user, re-imported on every full sync, produces **zero**
PATCHes: no per-sync re-assertion. The record is **success-tracked**.
A skipped or failed ADD is left unrecorded, and a failed REMOVE is
kept in the record, so either is retried on the next import. This is
the lazy-import-lag self-heal. A REMOVE that keeps failing after
retries stays in the record and is retried on the next import, so a
departed user does not quietly linger in the SCIM group. The
group-reconciler (member-presence) reaps the now-memberless group.

The per-user record is stored in Keycloak's **federated-user storage**
(`UserFederatedStorageProvider`, key `scim-propagated-groups-<componentId>`),
not as a user attribute. This matters under `editMode=READ_ONLY`
federation (the common case): the diff runs in the post-commit async
worker on a re-fetched federated user, whose LDAP-backed attributes are
read-only there, whereas federated storage is the writable JPA-backed
local store Keycloak keeps for federated users.

### Operator requirement

The SCIM provider component must have **both** `propagation-user=true`
and `propagation-group=true` set. Membership resolution looks up the
user's SCIM resource ID under the same component. So a component
configured for group propagation alone cannot resolve member IDs, and
silently skips group-membership propagation. A single component that
covers both is the supported configuration.

It must also have **`group-patchOp=true`**, the default. The
federated membership add/remove path relies on the single-member
delta PATCH. With `group-patchOp=false`, membership would instead go
through a full-group `replace` (a PUT of the whole member list). This
lists the federated group's members and re-imports them, an unbounded
re-import loop. To avoid that, federated group-membership propagation
is **skipped entirely when `group-patchOp=false`**: the `SCOPE_GROUP`
worker does nothing. Use the default `group-patchOp=true` for
federated group membership.

### Lazy-import convergence

On a one-shot lazy import, where a single user login triggers a
federation fetch, the group-membership task may run before the user's
SCIM mapping is saved, and skip. This is expected. A skipped ADD is
not recorded in the propagated-group set, so the next sync tries it
again through success-tracking. The state converges without operator
action. The accepted lag is one sync interval.

## Group reconciliation (member-presence)

The reconciler's group phase deletes the SCIM resource for any mapped
federated group that currently has zero members, or whose local
`GroupModel` is gone.

### Member-presence rule

When `reconciler-enabled=true`, the reconciler checks each
`Group`-type mapping after the user phase:

- **Local model gone.** SCIM DELETE. This is a backstop for orphans,
  and handles groups silently removed through admin events or future
  Keycloak fixes.
- **Present, zero members.** SCIM DELETE. When an LDAP group is
  renamed or deleted, Keycloak keeps the local `GroupModel`, but
  drains it to zero members on the next full sync. Zero members is a
  reliable, race-free signal that LDAP no longer backs the group.
- **Present, one or more members.** Keep. A live LDAP group always has
  its member edges present in Keycloak between syncs, so the
  reconciler never wrongly deletes a stable group.

This needs no group-attribute write, and has no timing dependency. The
reconciler reads member presence at classification time.

### Rename = delete-old + create-new

On a rename, Keycloak creates the new group, with a new `GroupModel`
ID, and orphans the old one. The new group provisions to SCIM fresh,
with a new SCIM ID, through the membership import path. After the
next full sync drains the old group's members to zero, the reconciler
deletes its SCIM resource. Between that sync and the next reconcile
pass, SCIM briefly holds both the old and new group. The reconcile
interval bounds how long the duplicate lasts.

### No group-specific config

Group reconciliation uses the existing `reconciler-enabled` flag.
There is no separate enable flag or threshold for groups. The
`reconciler-stale-threshold-seconds` setting, and its
`> fullSyncPeriod` check, apply only to the user phase, which uses
timestamp staleness. The group phase does not use them.

### Known consequence: empty groups

A provisioned group that legitimately loses all its LDAP members, for
example a team is offboarded but the LDAP group entry stays, looks the
same as a renamed-away or deleted group under a member-count signal.
The next reconcile deletes it. It provisions again automatically when
the group gains a member.

### Verified by

`ScimGroupReconcileIT`: delete-propagates, rename-as-recreate, and
live-group-not-deleted scenarios.

## References

- [mitodl/keycloak-scim README](https://github.com/mitodl/keycloak-scim/blob/main/README.md)
- [Medium: Keycloak Event Listener SPI for LDAP / User Federation Sync](https://medium.com/@ivancheahkf/keycloak-event-listener-spi-for-ldap-user-federation-sync-62fa17c573bc):
  the tutorial that walks through `LDAPStorageMapper` end to end,
  including the
  `org.keycloak.storage.ldap.mappers.LDAPStorageMapperFactory` META-INF
  registration file, and the admin-UI attachment step.
- [Keycloak forum: Event SPI and Users added from other sources](https://forum.keycloak.org/t/event-spi-and-users-added-from-other-sources-identity-provider-federated-provider/9433):
  the original discussion that shows EventListenerProvider does not
  fire for federation-origin users.
