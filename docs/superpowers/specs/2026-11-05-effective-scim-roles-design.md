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
- a role or a group deleted.

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
2. The plugin stores a hash of the SCIM user body it last sent, on the
   mapping row.
3. A change that can affect many users does not send anything in the admin
   request. It schedules one `sync-refresh` of the component, a short time
   later. That refresh sends a PUT only for users whose body hash changed.
4. A periodic or manual sync ignores the hash and sends every user, as in
   1.x. Operators run it once a day as a safety net.

A change to one user still sends a PUT at once.

## Non-goals

- Changes that make no admin event are not detected at once. These include
  changes that come from LDAP through LDAP group or role mappers, realm
  imports and partial imports, and direct database edits. They reach the
  server at the user's next write or at the next periodic sync, as today.
- `roles` is still not sent in a user PATCH. The body hash would make that
  possible later, but it is a separate change.
- The plugin does not compute which users a change affects. A triggered
  refresh reads every user of the component. See
  [Alternatives considered](#alternatives-considered).

## Design

### Effective roles

`UserAdapter.apply(UserModel)` computes roles as follows:

```java
RoleUtils.getDeepUserRoleMappings(user).stream()
    .filter(r -> "true".equals(r.getFirstAttribute("scim")))
    .map(RoleModel::getName)
    .sorted()
```

The result replaces the two loops that read direct role mappings and direct
groups. Sorting makes the body, and so the hash, independent of the order in
which Keycloak returns roles.

### Body hash

A new nullable column, `BODY_HASH`, on the SCIM mapping table holds a
SHA-256 of the SCIM user body that the plugin last sent with success. A
Liquibase change in `scim-resource-changelog.xml` adds it.

- The hash input is the JSON of `toSCIM(false)`, with object keys in a fixed
  order. It covers every attribute the plugin sends, not only `roles`.
- Every successful user create and user PUT writes the hash.
- A failed request does not write it, so the old hash stays.
- A user PATCH does not write it. With `user-patchOp=true`, the next
  hash-checked refresh sends a PUT for users the PATCH changed. This costs
  one extra PUT per changed user, and keeps the hash meaning simple: "the
  body of the last full write".
- A missing hash never matches. So the first hash-checked refresh after the
  upgrade sends every user once.

Groups do not get a hash. Group bodies do not carry roles.

### Two kinds of refresh

`sync-refresh` gets one new input, `skipUnchanged`:

| Started by | `skipUnchanged` | Behavior |
| --- | --- | --- |
| Keycloak periodic sync, or a manual sync (`ImportSynchronization.sync`) | `false` | Sends every user, as in 1.x. It also undoes changes made directly on the SCIM server. |
| A role-structure event (this design) | `true` | Computes each user's body. Sends a PUT only when the hash differs from the stored one, or is missing. |

A refresh with `skipUnchanged=true` runs only the user refresh stage. It does
not run `sync-import` or the group refresh, because a role change does not
change them. It runs when the component has `propagation-user=true`, even if
`sync-refresh` is `false`. The `sync-refresh` setting controls the periodic
and manual syncs only.

Both kinds use the existing paging (`sync-page-size`,
`sync-page-max-seconds`), the existing commit after each page, and the
existing lease. So only one sync of a component runs at a time, across the
cluster.

### Events

| Change | Admin event | What happens |
| --- | --- | --- |
| A role granted to or removed from a user | `REALM_ROLE_MAPPING` or `CLIENT_ROLE_MAPPING`, path `users/{id}/role-mappings/...` | Send that user now, with the hash check. |
| A user added to or removed from a group | `GROUP_MEMBERSHIP` | The existing group membership PATCH, then send that user now, with the hash check. This replaces `groupConfersScimRole`. |
| A role granted to or removed from a group | `REALM_ROLE_MAPPING` or `CLIENT_ROLE_MAPPING`, path `groups/{id}/role-mappings/...` | Schedule a refresh. The current fan-out to group members is removed. |
| Any change to a realm role or a client role | `REALM_ROLE` or `CLIENT_ROLE` | Schedule a refresh. |
| A subgroup created, or a group moved | `GROUP` `CREATE` | The existing group create, then schedule a refresh. |
| A group deleted | `GROUP` `DELETE` | The existing group delete, then schedule a refresh. |
| A group renamed | `GROUP` `UPDATE` | The existing group update only. |

"Send that user now, with the hash check" means: apply the user, compute the
body hash, and send a PUT only if it differs from the stored hash. Events
that change other user attributes (`USER` `UPDATE`) keep sending a PUT
without the hash check, as today.

Every role event schedules a refresh, even one that cannot change any user's
roles, such as a new empty role. Sorting those out would mean reading the
change itself. Role edits are rare, and a refresh that finds no change sends
no PUT.

### Scheduling a refresh

Each node has one `RoleRefreshScheduler`. It holds, for each component id:

- whether a refresh is scheduled, and
- whether a new request came while a refresh was running ("run again").

On a request for component C:

1. If a refresh of C is already scheduled, do nothing. The scheduled refresh
   will see the change.
2. If a refresh of C is running on this node, set "run again" and return.
3. Otherwise, schedule a refresh of C after `scim.roles.refreshDelaySeconds`
   (default 60) on a single-thread scheduled executor that the plugin owns.

When the refresh runs:

- If the lease is held by another run, on this node or another, schedule the
  refresh again after the same delay.
- When it ends, if "run again" is set, clear it and schedule one more
  refresh.

Several edits in a row become one refresh. A request is never dropped while
the node is alive.

The state is in memory on each node. If a node stops before its refresh
runs, that change waits for the next periodic sync. This is why the docs ask
operators to run a periodic sync with `sync-refresh=true` once a day.

The executor stops when the plugin factory closes. A refresh that is running
at that time stops like any sync that loses its lease.

### Errors

A hash-checked refresh handles errors like any `sync-refresh`, through
`sync-on-error`. A user whose PUT fails keeps the old hash. The next
hash-checked refresh, or the next periodic sync, sends that user again.

A triggered refresh does not retry on its own after a failure. A failed run
does not schedule another, so a server that is down does not cause a loop.

## Configuration and docs

- New JVM system property `scim.roles.refreshDelaySeconds`, default `60`.
  Tests set it to `1`.
- `docs/configuration.md`:
  - The *SCIM roles* section describes effective roles and the new event
    behavior.
  - The *Sync behavior* section asks operators to run a periodic sync with
    `sync-refresh=true` once a day.
  - The JVM properties table lists the new property.
- `docs/upgrading-to-2.0.md`:
  - Effective roles change what the plugin sends.
  - Start one manual sync after the upgrade, so that every user gets the
    composite and parent-group roles that 1.x never sent.

## Testing

### Unit

- Effective roles include roles from composites and from parent groups, and
  leave out roles without `scim="true"`.
- The body hash does not depend on the order of roles or of object keys.
- A refresh with `skipUnchanged=true` sends no PUT when the hash matches, and
  sends one when the hash differs or is missing.
- A refresh with `skipUnchanged=false` sends a PUT whatever the hash.
- A successful create or PUT writes the hash. A failed one does not.
- Each structure event schedules one refresh. A second request before it runs
  schedules nothing more. A request during a run sets "run again", which
  schedules one more refresh.
- A refresh that finds the lease held schedules itself again.

### Integration (WireMock)

1. Grant a group a marked role. Its members, including members of its
   subgroups, get a PUT with the role. The admin request sends no PUT.
2. Remove a role from a composite. The users who held it through the
   composite get a PUT without it.
3. Make 10 role edits in a row. They produce one refresh.
4. Start a manual sync. It sends every user.
5. Grant a marked role to a user directly. That user gets a PUT at once.

### Measurement

Run one refresh with `skipUnchanged=true` over 10,000 mapped users with no
change. The expected result is about 6 seconds and no PUTs. This estimate
comes from the measured adapter cost in `docs/performance.md` (about 0.6 ms
per user). Record the result in `docs/performance.md`.

## Alternatives considered

- **A counter table and a background pass.** A persistent counter per
  component, a timer, a quiet period, an upper wait limit, and back-off
  rules. This survives a node crash without a periodic sync, but it has many
  more parts. The daily periodic sync covers the same losses.
- **A periodic hash-checked refresh only, for example every 5 minutes.** No
  new event handling. It costs about 6 seconds of work every 5 minutes for
  10,000 users, even when nothing changes.
- **Compute the affected users from each event.** It reads fewer users, but
  it needs a separate path for each event type. It cannot find the users of
  a deleted role or group, and listing the members of an LDAP group can
  import users. It would still need the full refresh as a fallback.
