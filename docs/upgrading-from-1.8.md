# Upgrading from 1.8

The releases after 1.8.0 change some defaults and some wire formats. Read
this page before you upgrade from 1.8.0 or earlier. Each section says what
changed, who it affects, and how to keep the 1.8.0 behavior.

## Before you upgrade

1. Make sure that you run Keycloak 26.0.8 or later. See
   [Keycloak version](#keycloak-version).
2. Find the settings that each SCIM provider component stores. Two
   defaults change after 1.8.0. A component that stores a value keeps it. A
   component that does not store a value gets the new default.

   ```sh
   kcadm.sh get components -r <realm> \
     -q type=org.keycloak.storage.UserStorageProvider \
     | jq -r '.[] | select(.providerId=="scim")
         | "\(.name): delete-mode=\((.config["delete-mode"] // ["(unset)"])[0])"
           + " group-patchOp=\((.config["group-patchOp"] // ["(unset)"])[0])"
           + " user-patchOp=\((.config["user-patchOp"] // ["(unset)"])[0])"'
   ```

   A value of `(unset)` means that the component gets the new
   default for that setting.
3. Read each section below for the changes that apply to your
   components and your SCIM server.

## After you upgrade

1. Start one manual sync of each SCIM provider component, with
   `sync-refresh=true`. This sends every user once with the new
   [effective SCIM roles](#scim-roles-include-composites-and-parent-groups).
   Until a sync or a role change sends a user, the SCIM server keeps the
   roles that 1.8.0 sent for that user.
2. Set a periodic sync of each SCIM provider component, once a day, with
   `sync-refresh=true`. It sends the changes that make no admin event, and
   the role changes that a node did not send before it stopped. See
   [Sync behavior](configuration.md#sync-behavior).

## Changes in behavior

### Keycloak version

**What changed.** The plugin requires Keycloak 26.0.8 or later. It no
longer supports Keycloak 25.

**To keep the 1.8.0 behavior.** If you run Keycloak 25, stay on plugin
version 1.8.0.

### `delete-mode` defaults to `deactivate`

**What changed.**

- A component with no stored `delete-mode` now deactivates users. The
  plugin sets `active: false` on the remote user and keeps the local
  mapping. It does not send `DELETE /Users/{id}`. Groups are still
  deleted.
- Only the exact value `delete` deletes users. The plugin reads any
  other value as `deactivate`, and logs a warning once for each
  component. In 1.8.0, the plugin read any value other than `deactivate`
  as `delete`.
- The admin console and the admin REST API now reject a `delete-mode`
  value other than `delete` or `deactivate`. A component that stores a
  bad value can no longer be saved until you correct the value. A realm
  import does not check the value.

**Who it affects.** Components that show `delete-mode=(unset)`, or a
value other than `delete` or `deactivate`, in the check above.

**What to check.**

- The SCIM server keeps deactivated users in its `/Users` list. If your
  server charges for each user, or if you must erase user data on
  request, use `delete`.
- The plugin keeps one mapping row for each deactivated user. It does
  not remove these rows.
- With `user-patchOp=false`, deactivation sends a GET, then a PUT of
  what the GET returned with `active: false`. If your server leaves
  stored attributes out of its GET response, the PUT can clear them.
  Such a server should use `user-patchOp=true`.

See [Deprovisioning mode](configuration.md#deprovisioning-mode).

**To keep the 1.8.0 behavior.** Set `delete-mode=delete`.

### `group-patchOp` defaults to `true`

**What changed.** A component with no stored `group-patchOp` now uses
PATCH for groups:

- A group update (a rename or a `sync-refresh`) sends a PATCH that
  replaces `displayName` and `externalId`. It does not send the member
  list.
- Each membership change sends one PATCH. An addition uses `op: add` on
  `members` with one member. A removal uses `op: remove` on the path
  `members[value eq "<id>"]`.
- Group membership from a federated (LDAP) provider is now sent. In
  1.8.0, a component with the default setting skipped it.

**Who it affects.** Components that show `group-patchOp=(unset)` in the
check above.

**What to check.** Your SCIM server must support PATCH on `/Groups`,
including the filtered `remove` path above.

**To keep the 1.8.0 behavior.** Set `group-patchOp=false`. With `false`,
the plugin does not send group membership from a federated provider.

### Single-valued extension attributes are sent as scalars

**What changed.** With `user-patchOp=true`, the plugin sends a
single-valued extension attribute as a scalar, for example
`"value": true` or `"value": "Eng"`. In 1.8.0, it sent a one-element
array, for example `"value": [true]`. A multivalued attribute is still
sent as one array.

**Who it affects.** Components with `user-patchOp=true` and at least
one row in `user-extension-mappings`.

**What to check.** Your SCIM server must accept a scalar value for a
single-valued attribute, as RFC 7643 defines it.

**To keep the 1.8.0 behavior.** There is no setting for this.

### A user named `admin` is included in `sync-refresh`

**What changed.** In 1.8.0, `sync-refresh` skipped any user whose
username is `admin`. Create and update events still sent that user. Now,
`sync-refresh` sends that user like any other user.

**Who it affects.** Realms that have a user named `admin` and a
component with `sync-refresh=true`.

**To keep the 1.8.0 behavior.** Set the user attribute `scim-skip` to
`"true"` on that user. This keeps the user off the SCIM server on every
path.

### SCIM roles include composites and parent groups

**What changed.** The SCIM `roles` attribute now holds every role marked
`scim="true"` that a user has:

- roles mapped to the user directly,
- roles of every group of the user, and of each parent group,
- roles inside a composite role that the user has.

Realm roles and client roles both count. A name appears once. In 1.8.0,
`roles` held only the user's direct mappings and the roles of the user's
direct groups.

**Who it affects.** Components that send users who get a marked role
through a composite role or a parent group. These users get more roles on
the SCIM server.

**What to check.** Look at which roles carry `scim="true"`. A marked role
inside a composite, or on a parent group, now reaches every user who has
it that way.

**To keep the 1.8.0 behavior.** There is no setting for this. Remove the
`scim` marker from a role that must not be sent this way.

### Role changes on groups and roles reach the server after a delay

**What changed.** In 1.8.0, a role granted to or removed from a group sent
a PUT for each member of the group, inside the admin's request. Other
changes to roles and groups sent nothing until the next sync.

Now these changes request one **role refresh** of each SCIM component,
about 60 seconds later:

- a role granted to or removed from a group,
- any change to a realm role or a client role, including its composites
  and its `scim` marker, and its deletion,
- a group moved to a new parent or to the top level,
- a group deleted,
- a client deleted.

A role refresh reads every mapped user of the component. It sends only
the users whose SCIM body changed. It never creates a user. A change to
one user's roles still sends that user at once.

**Who it affects.** Every component with `propagation-user=true`.

**What to check.** A role removed from a group reaches the SCIM server
about one minute later, not during the admin's request. The JVM property
`scim.roles.refreshDelaySeconds` sets the delay. See
[SCIM roles](configuration.md#scim-roles).

**To keep the 1.8.0 behavior.** There is no setting for this.

## Other changes to know about

These changes fix bugs. They have no setting, but they change what the
plugin sends.

- **User PATCH sends more attributes.** With `user-patchOp=true`, a user
  PATCH now replaces the same attributes as a user PUT: `externalId`,
  `userName`, `displayName`, `name.givenName`, `name.familyName`,
  `emails`, `active`, and the extension mappings. In 1.8.0, it did not
  send `externalId`, `name`, or `emails`. An attribute with no value is
  left out. `roles` is still sent only in a PUT. See
  [SCIM roles](configuration.md#scim-roles).
- **User PATCH takes `userName` from `username-source`.** In 1.8.0, a
  PATCH always used the Keycloak username, even with
  `username-source=email`.
- **PATCH sends `active` as a JSON boolean.** In 1.8.0, it sent the
  string `"true"` or `"false"`.
- **A 400 on an update no longer re-creates the resource.** In 1.8.0, a
  404 or a 400 on an update made the plugin send a POST. Now, only a
  404 does. A 400 means that the server rejected the request body.
- **A re-created resource keeps its new remote id.** In 1.8.0, after a 404
  on an update, the plugin created the resource again but kept the old
  remote id in its mapping. Each later update then got a 404 and created
  the resource again. On a server that rejects duplicate user names, each
  later update failed instead. Now the mapping takes the new id. This fix
  does not repair a mapping that already holds a stale id. To repair one,
  delete the resource on the SCIM server. The next update of the user or
  group then creates it again with a correct mapping.
