# Effective SCIM roles

**Status:** approved design
**Date:** 2026-11-05

## Problem

The plugin fills the SCIM `roles` attribute of a user from Keycloak roles
that carry the attribute `scim="true"`. Today `UserAdapter.apply` collects
these roles only from two places:

- the user's direct role mappings, and
- the role mappings of the user's direct groups.

It does not expand composite roles, and it does not read the roles of parent
groups. A marked role that a user gets only in one of these ways never
reaches the SCIM server.

Event handling has a second problem. Many admin changes can change the roles
of many users at once:

- a role granted to or removed from a group,
- a change to the composites of a role, or to its `scim` attribute,
- a group moved to a new parent,
- a role, a group, or a client deleted.

Today only one of these sends anything. When a realm role is granted to or
removed from a group, `ScimEventListenerProvider` sends a user PUT for every
member of that group. It does this one user after another, inside the
admin's request, through `ScimDispatcher.run`. It finds the members with
`getGroupMembersStream`, which for a federated (LDAP) group can import users
from LDAP. The work and the length of the admin request have no limit.

## Decision

1. The plugin sends a user's **effective** marked roles: the roles that
   `RoleUtils.getDeepUserRoleMappings(user)` returns, filtered to
   `scim="true"`. This includes composites, parent groups, and client roles.
2. A change that can affect many users does not send anything in the admin
   request. After the change commits, it schedules one **role refresh** of
   each affected component, a short time later.
3. A role refresh reads every mapped user of the component and sends only
   the users whose SCIM body changed. To know what changed, the plugin
   stores, on each mapping row, a hash of the body that the last refresh
   sent. An event that sends a user clears that user's hash.
4. A periodic or manual sync ignores the hash and sends every user, as in
   1.8.0. Operators run it once a day as a safety net.

A change to one user still sends that user at once, as today.

## Non-goals

- Changes that make no admin event are not detected at once:
  - changes that come from LDAP, through LDAP group or role mappers,
  - roles that an identity provider mapper grants at login,
  - a client deleted through dynamic client registration, which makes a
    user event, not an admin event,
  - realm imports and partial imports,
  - direct database edits.

  They reach the server at the user's next write or at the next periodic
  sync, as today.
- `roles` is still not sent in a user PATCH. With `user-patchOp=true`, SCIM
  roles do not reach the server, as documented today.
- The plugin does not compute which users a change affects. A role refresh
  reads every mapped user of the component. See
  [Alternatives considered](#alternatives-considered).
- An existing bug is out of scope: for a subgroup create, the event path is
  `groups/{parentId}/children`, so the existing group create handles the
  parent, not the new subgroup. It is not tracked yet.

## Design

### Effective roles

`UserAdapter.apply(UserModel)` computes roles as follows:

```java
RoleUtils.getDeepUserRoleMappings(user).stream()
    .filter(r -> "true".equals(r.getFirstAttribute("scim")))
    .map(RoleModel::getName)
    .distinct()
    .sorted()
```

The result replaces the two loops that read direct role mappings and direct
groups. `distinct()` keeps one entry when a realm role and a client role, or
roles of two clients, have the same name. This matches today's `HashSet`.
Sorting makes the body independent of the order in which Keycloak returns
roles.

`ScimEventListenerProvider.groupConfersScimRole(group)` changes the same
way. It returns true when the group, or any parent group, has a role mapping
whose composite expansion (`RoleUtils.expandCompositeRoles`) includes a
marked role.

### Body hash

A new nullable column, `BODY_HASH`, on the SCIM mapping table holds a
SHA-256 in hex. A Liquibase change in `scim-resource-changelog.xml` adds it.

**Input.** The hash input is the JSON of `toSCIM(false)`, with these rules:

- The `id` and `meta` attributes are removed. `id` is the remote id, which
  is not part of what the plugin decides to send.
- Object keys are sorted at every level. A small helper writes the sorted
  form; Jackson's `ObjectNode` keeps insertion order, so the helper does not
  rely on a mapper setting.
- Array order is kept. `roles` is already sorted. Multivalued extension
  attributes keep the order that Keycloak stores.

**Who writes it.** Only a refresh writes a hash, of either kind:

- After a successful user replace in a refresh, the refresh writes the hash
  of the body it computed for that user. The write is a compare-and-set:
  it changes the row only if the stored value is still the one the refresh
  read before it sent. If an event cleared the hash in between, the write
  changes nothing.
- The write runs in its own short transaction, right after the request. So
  the refresh page does not hold a lock on the mapping row while it pushes
  other users. An event that deletes or deactivates the same user waits at
  most for that short transaction.
- A failed request does not write the hash, so the old hash stays.
- A replace that got a 404 and re-created the user does not write the hash.
  That path changes the mapping row's external id, which is part of the
  primary key, inside the page transaction. A second transaction on the
  same row would wait on that lock. The next role refresh sends the user
  once more.

**Who clears it.** An event path that sends an existing user replaces that
user's hash with a fresh marker, `cleared-` followed by a random UUID, in
the event's own transaction, after a successful send. The marker is set on
the managed mapping rows, so a later flush in the same transaction cannot
put the old value back. The marker is never a hex hash, so it never
matches. These paths are `ScimClient.replace` (user events and the group
membership path) and the reactivation branch of `createApplied`. A new
mapping row starts with a null hash, so creates and bulk creates need no
clear.

Clearing is required. Without it, an event can send a body the hash does
not describe, and a later change can bring the user back to the body the
hash does describe. Example:

1. A role refresh sends roles `{a}` and stores the hash of that body.
2. An admin grants a composite that contains `b`. The event sends
   `{a, b}`.
3. An admin removes `b` from the composite. The role refresh computes
   `{a}`. Without the clear, this matches the stored hash, and the server
   keeps `b` until the daily sync.

A cleared hash never matches, so clearing can only cause one extra request.
The marker is fresh on every clear, and the refresh's write is a
compare-and-set, so a race cannot leave a hash that describes a body the
server does not have. Example: a refresh sends body A, an event sends body
B and clears the hash, and then the refresh tries to store the hash of A.
The stored value is no longer the one the refresh read, so its write
changes nothing. The marker cannot be null for this reason: a refresh that
read null would still match a clear to null.

The refresh page does not lock mapping rows, because it writes hashes in
their own short transactions. So the event's clear waits at most for one of
those, and a refresh's hash write waits at most for the event's
transaction. One case can wait with no time limit: a page that re-created
one user holds that row, while an admin transaction holds another user's
row and needs the first. The database cannot see this cycle, because the
page thread waits in Java. It needs two rare events at once, so it is
accepted. A database lock timeout ends it.

The clear runs in the admin transaction. If that transaction rolls back
after a successful send, the server keeps the event's body and the old hash
stays. `rollback-strategy` rolls back only after a later failed send in the
same request, so this is rare. The daily periodic sync corrects it.

No path other than a refresh reads the hash.

**With `user-patchOp=true`.** The refresh's replace sends a PATCH, as today.
The hash still describes the body the refresh computed, so the next role
refresh finds it equal and sends nothing. `roles` is not in the PATCH, so a
role change does not reach the server in this mode. That is the documented
limit, and it does not cause a loop.

**A missing hash never matches.** The first role refresh after the upgrade
sends every mapped user once.

Groups do not get a hash. Group bodies do not carry roles.

### Two kinds of refresh

`sync-refresh` of users gets one new input, `roleRefresh`:

| Started by | `roleRefresh` | Behavior |
| --- | --- | --- |
| Keycloak periodic sync, or a manual sync (`ImportSynchronization.sync`) | `false` | As in 1.8.0. Sends every user, and creates users with no mapping. Writes the hash after each successful replace. |
| The role refresh scheduler (this design) | `true` | For each user that has a mapping: compute the body hash. Send a replace only when the hash differs from the stored one, or is missing. Skip users with no mapping. Never create a user. |

A role refresh runs only the user refresh stage. It does not run
`sync-import` or the group refresh, because a role change does not affect
them. It runs whenever the component has `enabled=true` and
`propagation-user=true`, even if `sync-refresh` is `false`. Because it never
creates users, it never sends a user that the operator has not sent before.

Both kinds use the existing paging (`sync-page-size`,
`sync-page-max-seconds`), the existing commit after each page, the existing
skips (deactivated mappings, `scim-skip`, `propagation-role`, disabled
users), and the existing lease. So only one sync of a component runs at a
time, across the cluster.

Disabled users are skipped, as `sync-refresh` already does. The old
group fan-out sent disabled members too. A disabled user is inactive on the
server, so a stale role on that user grants nothing.

### Events

| Change | Admin event | What happens |
| --- | --- | --- |
| A role granted to or removed from a user | `REALM_ROLE_MAPPING` or `CLIENT_ROLE_MAPPING`, path `users/{id}/role-mappings/...` | Replace that user now, as today for realm roles. |
| A user added to or removed from a group | `GROUP_MEMBERSHIP` | As today: the group membership PATCH, then a user replace if `groupConfersScimRole` is true. That check now covers parent groups and composites. |
| A role granted to or removed from a group | `REALM_ROLE_MAPPING` or `CLIENT_ROLE_MAPPING`, path `groups/{id}/role-mappings/...` | Request a role refresh. The current fan-out to group members is removed. |
| Any change to a realm role or a client role | `REALM_ROLE` or `CLIENT_ROLE` | Request a role refresh. |
| A group moved to a new parent | `GROUP` `UPDATE`, path `groups/{newParentId}/children` | Request a role refresh only. Do not run a group update: the path names the new parent, not the moved group, and a move does not change the group's SCIM body. Today this event replaces the parent group by mistake. |
| A group moved to the top level | `GROUP` `UPDATE`, path `groups` | Request a role refresh only. The path has no group id. |
| A group renamed, or its attributes changed | `GROUP` `UPDATE`, path `groups/{id}` | The existing group update only. Roles are sent by name, and group names and attributes are not in the user body, so this cannot change any user's roles. |
| A group deleted | `GROUP` `DELETE` | The existing group delete, then request a role refresh. |
| A client deleted | `CLIENT` `DELETE` | Request a role refresh. Deleting a client removes its roles from users and sends no role event. |

`ScimEventListenerProvider` drops an event when its resource type has no
pattern (`patterns.get` returns null). So:

- The `GROUP` pattern matches exactly `groups`, `groups/{id}`, and
  `groups/{id}/children`, anchored at both ends. It tells the three
  `UPDATE` paths apart. Any other path, such as `default-groups/{id}`, is
  ignored.
- `CLIENT_ROLE_MAPPING`, `REALM_ROLE`, `CLIENT_ROLE`, and `CLIENT` have no
  pattern today. Each one needs a pattern, or a branch before the pattern
  check. For `REALM_ROLE`, `CLIENT_ROLE`, and `CLIENT`, the path does not
  matter: every event of these types requests a role refresh (for `CLIENT`,
  only `DELETE`).

Every role event requests a refresh, even one that cannot change any user's
roles, such as a new empty role. Sorting those out would mean reading the
change itself. Role edits are rare, and a role refresh that finds no change
sends no request.

**One request per component.** A "request a role refresh" call asks for one
refresh of every component in the realm that has `enabled=true` and
`propagation-user=true`.

**After commit.** Admin events fire before the admin's transaction commits.
So the request is registered with `enlistAfterCompletion`, as
`ScimDispatcher.runAsync` does, and it is made only if the transaction
commits. A refresh therefore never starts before the change it serves is
visible.

### Role refresh scheduler

`RoleRefreshScheduler` is one object per node. `ScimEventListenerProviderFactory`
creates it in `postInit`, which receives the `KeycloakSessionFactory` that a
run needs, and shuts it down in `close`. It owns a single-thread scheduled
executor. One thread serves every component on the node, so a long refresh
of one component delays the others and their retries. This is on purpose:
it keeps the load on the database and on the SCIM servers bounded, and a
realm has few SCIM components.

For each component id, it keeps one state, changed atomically
(`ConcurrentHashMap.compute`):

| State | Meaning |
| --- | --- |
| `IDLE` | Nothing scheduled or running. |
| `SCHEDULED` | A refresh will start after the delay. It has not read anything yet. |
| `RUNNING` | A refresh is running and may have read some users already. |
| `RUNNING_AGAIN` | A refresh is running, and a newer request came during it. |

**A request** for component C:

| From state | To state | Action |
| --- | --- | --- |
| `IDLE` | `SCHEDULED` | Schedule a run after `scim.roles.refreshDelaySeconds` (default 60). |
| `SCHEDULED` | `SCHEDULED` | Nothing. The run has not read anything yet, so it will see the change. |
| `RUNNING` | `RUNNING_AGAIN` | Nothing now. |
| `RUNNING_AGAIN` | `RUNNING_AGAIN` | Nothing now. |

**A run** for component C:

1. Change the state from `SCHEDULED` to `RUNNING` in one atomic step, before
   it reads anything.
2. Read the component again from its realm. If the realm or the component
   no longer exists, or the component has `enabled=false` or
   `propagation-user=false`, change the state to `IDLE` and stop. A missing
   `enabled` value counts as `true`, as in `ScimDispatcher`.
3. Run the role refresh through `ScimSync`.
4. When it ends, change the state:

| Run result | State was `RUNNING` | State was `RUNNING_AGAIN` |
| --- | --- | --- |
| Finished, with or without per-user failures | `IDLE` | `SCHEDULED`, and schedule a run after the delay. |
| Refused: the lease is held by another run, the lease could not be taken because of an error, or the component could not be read because of an error | `SCHEDULED`, and schedule a run after the delay. | `SCHEDULED`, and schedule a run after the delay. |
| Stopped early: any run that did not complete (`outcome.completed()` is false), including stop-on-error, a throttle streak, a failed page transaction, a lost lease, or shutdown | `IDLE` | `SCHEDULED`, and schedule a run after the delay. On shutdown, nothing is scheduled. |

So a newer request is never dropped while the node runs. A refused run tries
again after the delay. While the database or another sync blocks it, this
costs one lease attempt per delay. A run that stopped on an error does not
retry on its own, so a server that is down does not cause a loop. The users
it did not send keep their old hash, so the next role refresh or periodic
sync sends them.

`ScimSync` reports the result to the scheduler as one of `FINISHED`,
`REFUSED`, or `STOPPED`. The scheduler adds `SKIPPED` for a component that
is gone or turned off, which always goes to `IDLE`. Today a refused run returns
`SynchronizationResult.ignored()`. The new result type keeps that for the
`ImportSynchronization.sync` caller.

**State is in memory on each node.** If a node stops before its refresh
runs, that change waits for the next periodic sync. This is why the docs ask
operators to run a periodic sync with `sync-refresh=true` once a day.

**Shutdown.** `close` sets a shutdown flag and stops the executor. The
running refresh checks the flag through the same supplier it uses to check
the lease (`lost`), between users. So it stops at the next user, like a sync
that loses its lease. Requests after shutdown are ignored.

### Interaction with the periodic sync

A role refresh holds the component's lease while it runs. If the daily
periodic sync starts during that time, the lease refuses it, and Keycloak
tries again only at the next period. A role refresh of 10,000 users is
expected to take seconds, so this is rare. When it happens, the next daily
sync runs as normal. The docs state this.

## Configuration and docs

- New JVM system property `scim.roles.refreshDelaySeconds`, default `60`.
  Tests set it to `1`.
- `docs/configuration.md`:
  - The *SCIM roles* section describes effective roles, the events that
    request a role refresh, and the delay.
  - The *Sync behavior* section asks operators to run a periodic sync with
    `sync-refresh=true` once a day, and says that a role refresh can make
    that day's periodic sync be refused.
  - The JVM properties table lists the new property.
- `docs/upgrading-from-1.8.md`:
  - Effective roles change what the plugin sends.
  - Start one manual sync after the upgrade, so that every user gets the
    composite and parent-group roles that 1.8.0 never sent.

## Testing

### Unit

- Effective roles include roles from composites and from parent groups,
  leave out roles without `scim="true"`, and list a shared name once.
- `groupConfersScimRole` is true when only a parent group, or only a
  composite, gives the marked role.
- The body hash does not depend on key order, and ignores `id` and `meta`.
- A role refresh sends no request when the hash matches, and sends one when
  it differs or is missing. It skips users with no mapping.
- A periodic refresh sends every user, and creates users with no mapping.
- A refresh writes the hash after a successful replace, and does not write
  it after a failed one.
- An event replace, and a reactivation through `createApplied`, clear the
  hash after a successful send, and do not clear it after a failed one.
- The scheduler state table: each transition in the two tables above,
  including a request during `RUNNING`, and a refused run.
- A run for a component that was disabled during the delay stops and goes
  to `IDLE`.

### Integration (WireMock)

1. Grant a group a marked role. Its members, including members of its
   subgroups, get a replace with the role. The admin request sends no user
   request.
2. Let a role refresh store a user's hash. Grant the user a composite
   through an event, then remove a role from that composite. The user gets
   a replace without the role. This is the sequence that needs the clear.
3. Make 10 role edits in a row, with the delay set to 5 seconds. They
   produce one role refresh. Count refreshes through the scheduler, not
   through request timing.
4. Move a group under a parent that has a marked role, and then move it to
   the top level. Its members get the role, and then lose it.
5. Start a manual sync. It sends every user.
6. Grant a marked role to a user directly. That user gets a replace at
   once.

### Measurement

Run one role refresh over N mapped users with no change. Record the
duration, and prove from the SCIM server's request log that it sends no
request. Also time a manual sync, which now writes a hash for each user.

Measured so far, at 1,000 and 5,000 users: see `docs/performance.md`,
"Role refresh". A run at 10,000 users is blocked today: a manual sync
aborts after about 6,300 updates with `NoHttpResponseException`, on the
code before this feature too. That abort is not tracked yet.

## Alternatives considered

- **A counter table and a background pass.** A persistent counter per
  component, a timer, a quiet period, an upper wait limit, and back-off
  rules. This survives a node crash without a periodic sync, but it has many
  more parts. The daily periodic sync covers the same losses.
- **A periodic hash-checked refresh only, for example every 5 minutes.** No
  new event handling. It costs the measured refresh time every 5 minutes,
  even when nothing changes.
- **Compute the affected users from each event.** It reads fewer users, but
  it needs a separate path for each event type. It cannot find the users of
  a deleted role or group, and listing the members of an LDAP group can
  import users. It would still need the full refresh as a fallback.
- **Write the hash on every send path.** Event creates and replaces would
  write the hash of what they sent, so fewer users would be sent twice. But
  an event and a refresh can then race to write the hash, and the last
  writer wins even if its body is older. Clearing on the event path avoids
  that, because a null hash is never wrong.

  One race stays, and it also exists in 1.8.0. A refresh can compute a user's
  body, an event can then send a newer body, and the refresh can then send
  its older body. The stored hash then differs from the current body, so the
  next role refresh fixes the user. A change to one user does not request a
  role refresh, so until the next role event, the daily periodic sync fixes
  it. This is accepted.
