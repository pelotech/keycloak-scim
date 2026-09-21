# Configuration reference

This page lists every setting this plugin exposes. For each setting, it
gives the type, the default value, and any interaction with other
settings.

The plugin adds five things to a Keycloak realm:

1. A **SCIM provider component** (a User Federation entry, providerId
   `scim`). Add one per remote SCIM server. Most settings live here.
2. A **`scim-ldap-sync` LDAP mapper**, attached to an LDAP User
   Federation provider. It has no settings of its own. When attached,
   it turns on propagation for LDAP-imported users.
3. A **`scim` event listener**, registered on the realm.
4. A **`/realms/{realm}/scim-reconcile/...`** REST endpoint. It takes
   query parameters, not stored settings.
5. **User attributes** that the plugin reads (to let an operator opt a
   user out) and writes (to track when it last saw a user).

The plugin also reads JVM-level **system properties** to tune build and
runtime behaviour.

## SCIM provider component

Add this component through the Admin Console: *User Federation → Add →
scim*. You can also add it through the admin REST API: send
`POST /admin/realms/{realm}/components` with
`providerType=org.keycloak.storage.UserStorageProvider` and
`providerId=scim`. You can edit most settings later. When you do, the
`onUpdate` hook reschedules timers as needed.

### Connection

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `endpoint` | string | *required* | Base URL of the SCIM 2.0 server. From this base URL, the server must expose `/Users`, `/Groups`, `/ServiceProviderConfig`, `/Schemas`, and `/ResourceTypes`. Example: `https://identity.example.com/scim/v2`. |
| `content-type` | enum | `application/scim+json` | Content-Type header on outbound SCIM requests. Set it to `application/json` only if the SCIM server rejects the standard `application/scim+json`. Options: `application/scim+json`, `application/json`. |

### Authentication

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `auth-mode` | enum | `NONE` | One of `NONE`, `BASIC_AUTH`, `BEARER`, `CLIENT_CREDENTIALS`. Use `NONE` for local development only. In production, always use `BEARER` or `CLIENT_CREDENTIALS`. |
| `auth-user` | string | — | Username for `BASIC_AUTH`. Ignored for other modes. |
| `auth-pass` | password | — | Password for `BASIC_AUTH`, or a static token for `BEARER`. Keycloak's Vault Provider stores this value encrypted, where configured. Ignored for `CLIENT_CREDENTIALS`. |
| `oauth-client-id` | string | — | OAuth client ID for the `CLIENT_CREDENTIALS` grant. Required when `auth-mode=CLIENT_CREDENTIALS`. Ignored for other modes. |
| `oauth-client-secret` | password | — | OAuth client secret for the `CLIENT_CREDENTIALS` grant. Required when `auth-mode=CLIENT_CREDENTIALS`. Keycloak's Vault Provider stores this value encrypted, where configured. Ignored for other modes. |
| `oauth-token-endpoint` | string | — | Full URL of the OAuth 2.0 token endpoint. Required when `auth-mode=CLIENT_CREDENTIALS`. It must be an absolute `http` or `https` URL with a host, for example `https://keycloak.example.com/realms/main/protocol/openid-connect/token`. The plugin checks this value when you save the component. Ignored for other modes. |
| `oauth-scope` | string | — | Space-separated OAuth scopes to request. Optional. When set, the plugin sends it as the `scope` parameter on the token request. When blank, it omits the parameter. Ignored for modes other than `CLIENT_CREDENTIALS`. |

### Propagation toggles

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `propagation-user` | bool | `true` | When false, user create, update, and delete events do not trigger SCIM calls. Use this for groups-only deployments, or to pause user propagation during maintenance. |
| `propagation-group` | bool | `true` | The same toggle, for group create, update, and delete events, and for group-membership changes. |
| `bulk-enabled` | bool | `false` | When true, the plugin combines federation-sync user **create** operations into SCIM `/Bulk` requests, instead of sending one `POST /Users` per user. The SCIM server must support `/Bulk`. Set `scim.dispatch.bulkBatchSize` to at most the server's advertised `maxOperations`. Only the LDAP-import create path is batched. Replace, delete, and membership operations still go one at a time. Bulk mode helps most against a slow or high-latency SCIM server. Against a fast local server it can be slightly slower than sending one request at a time (see `docs/performance.md`). This is why the default is off. |

Both toggles apply to every path: admin REST events, LDAP-federation
imports (when the `scim-ldap-sync` mapper is attached), and sync.

### Deprovisioning mode

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `delete-mode` | enum | `delete` | How user deprovisioning reaches the SCIM server. Options: `delete`, `deactivate`. |

With `delete` (the default), the plugin sends `DELETE /Users/{id}` and
drops the local mapping. This is the original, unchanged behaviour. With
`deactivate`, the plugin never hard-deletes users. Every deprovisioning
path (admin user deletion, account self-deletion, and the LDAP-deletion
reconciler) instead marks the remote user inactive and keeps the local
mapping.

By default (`user-patchOp=false`), deactivation sends a `GET /Users/{id}`,
then a `PUT /Users/{id}` of the fetched resource with `active: false`. If
the GET already shows the user as inactive, the plugin sends no PUT. With
`user-patchOp=true`, it sends a single `PATCH` that replaces `active`. A
404 on any call means the resource is already gone from the SCIM server,
so the plugin treats the deprovision as complete.

The plugin records each deactivation locally, in the `DEACTIVATED_AT`
column on the mapping row. Because of this record:

- The reconciler skips already-deactivated users on later passes. It
  sends no repeated writes and no per-pass HTTP calls for retained users.
- `sync-refresh` also skips deactivated mappings. A local copy that still
  exists is not proof that the user is back.
- `sync-import` neither deletes nor re-imports inactive, unmatched remote
  users while this mode is active.

Reactivation targets the same remote resource. Say the mapping is retained
and the user reappears, for example the directory entry returns. The
plugin sends the update to the same `/Users/{id}`, with `active: true`. If
someone deletes and re-creates the Keycloak account itself, the plugin
sends the user as a fresh `POST /Users`. Identity continuity then depends
on the SCIM server matching the existing deactivated user by `userName`
and returning the same resource ID. Treat `externalId` as advisory here:
it is the Keycloak internal ID, and it changes in this case. When a create
response returns a resource ID that matches a retained deactivated
mapping, the plugin purges the stale mapping.

Switching back to `delete` re-enables deletes. Previously deactivated
mappings lose their skip protection and go through the reconciler's normal
absence checks, like any other mapping. In practice, this deletes them on
the SCIM server, because the conditions that caused deactivation still
hold.

Groups are not affected. SCIM groups have no `active` attribute, so group
deletions always go out as `DELETE /Groups/{id}`. User *disable* is also
not affected. It already propagates as an ordinary update with
`active: false`.

### Failure handling on interactive events

By default, the plugin is fail-open. When a SCIM call fails, the Keycloak
change still commits, and the plugin logs the failure. Some deployments
treat the SCIM server as authoritative: a Keycloak change should not stand
if the plugin cannot mirror it there. For these, `rollback-strategy` lets
an interactive event roll the Keycloak transaction back instead.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `rollback-strategy` | enum | `never` | What to do when a SCIM call fails on an interactive console or account event. `never` (default): log the failure and keep the Keycloak change. `always`: roll back the Keycloak operation on any SCIM failure. `critical-only`: roll back only on transient failures (server unreachable, 5xx, 429). The plugin logs permanent failures (4xx, bad mapping, malformed data) and keeps the change. Options: `never`, `always`, `critical-only`. |

This setting applies only to the synchronous, pre-commit event path:
console, admin-REST, and account-console user create, update, and delete,
handled by the `scim` event listener. It runs on the same transaction as
the Keycloak operation, so marking that transaction rollback-only undoes
the change. It does not apply to LDAP-federation imports, which propagate
after the import commits and leave nothing to roll back. It also does not
apply to batch sync, governed by `sync-on-error` below. Both of these stay
fail-open regardless of this setting.

Keycloak rejects a component that sets `rollback-strategy` to anything
other than `never` together with `bulk-enabled=true`, at save time. Bulk
creates run after commit, so they cannot take part in a rollback. Use a
non-bulk component instead.

Know these three things before you enable it:

- **Orphans with more than one SCIM server.** Rollback assumes one
  critical SCIM server. If a realm has several SCIM components and an
  event fans out to more than one, the listener sends to each in turn. If
  one call succeeds and a later one fails and rolls back, the Keycloak
  operation is undone. But the plugin cannot recall the write already sent
  to the first server, and that server's local mapping rolls back with the
  transaction. This leaves an orphan: a SCIM resource with no Keycloak
  mapping, that nothing cleans up. Point `rollback-strategy` at a
  single-server deployment only.
- **Admin REST still returns 201.** Keycloak builds the `201 Created`
  response before commit. So a rolled-back admin user-create still returns
  201 with a `Location` header, even though the user does not persist.
  Check whether the user exists. Do not trust the status code alone.
  Account and self-service flows do surface an error. The 201 issue is
  specific to the Admin REST path.
- **Latency when the SCIM server is down.** Rollback needs synchronous
  propagation. So an interactive create or update against a slow or
  unreachable SCIM server blocks first. It uses the whole retry
  budget, about 38 seconds at the current hardcoded 10-attempt
  backoff. Only then does it roll back and return. The `never`
  default avoids this delay, because propagation is asynchronous. A
  proxy in front of Keycloak may return a `504` first.

### Sync behavior

The plugin implements `ImportSynchronization`, so this component appears
under *User Federation → Periodic Sync*. These settings control what
happens during a manual or scheduled sync of *this SCIM provider
component*. That is, they control what happens when the plugin syncs
state with the SCIM server. They do not control LDAP-federation sync.
The LDAP component controls that.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `sync-import` | bool | `false` | When true, the plugin fetches users and groups from the SCIM server during sync and acts on each one, per `sync-import-action`. |
| `sync-import-action` | enum | `CREATE_LOCAL` | What to do when a remote SCIM user or group has no local Keycloak counterpart. Options: `NOTHING` (log only), `CREATE_LOCAL` (add to Keycloak), `DELETE_REMOTE` (remove from SCIM). Choose `DELETE_REMOTE` only for one-way deployments where Keycloak is the source of truth. |
| `sync-refresh` | bool | `false` | When true, the plugin pushes local users and groups out to the SCIM server during sync, covering anything the event listener missed. Combine with `sync-import=false` for a pure outbound sync. |
| `sync-on-error` | enum | `auto` | How the plugin handles a per-record failure in the sync loop. `auto` (default): the plugin skips the record and continues on a bad mapping, malformed data, a non-throttling 4xx response, or a 429 throttling response. It stops the run on an unreachable server or a 5xx response, since every remaining record would fail the same way. `continue`: always skip the failed record and keep going. `stop`: abort on the first failure of any kind. Options: `auto`, `continue`, `stop`. Against a SCIM server that throttles persistently, `auto` retries and skips one record after another, instead of aborting early. This can make a sync take much longer to finish, instead of failing fast. |
| `sync-page-size` | int (string) | `50` | Number of users the plugin reads and pushes per `sync-refresh` transaction. This also sets how many throttled users in a row stop the run. Must be a whole number greater than zero. |
| `sync-page-max-seconds` | int (string) | `45` | Wall-clock limit for one `sync-refresh` page, checked between users. If a page runs past this limit, the plugin commits the work it already did, and the next page continues. Must be a whole number greater than zero. |

`sync-on-error` governs both the import half and the refresh half of a
sync. It is independent of `rollback-strategy`, which covers interactive
events only. A sync never rolls back records it already applied. It only
skips or stops.

Both `sync-import` and `sync-refresh` are off by default. If you trigger a
sync on this component without turning on at least one of them, the sync
does nothing and returns an empty result. The plugin logs a line to say
so, because an empty result alone looks the same as a sync that ran and
found no work.

The plugin rejects a bad `sync-page-size` or `sync-page-max-seconds` when
you save the component, through the admin console or the REST API. A
realm imported from a file skips this check, so a stored value can still
be unusable. At sync time, the plugin falls back to the default for an
unusable stored value, and logs a warning naming the component and the
bad value.

#### How sync-refresh pages through users

`sync-refresh` commits one page of users per transaction, instead of pushing
every user in a realm in a single transaction. A failure costs only the page
it happened in. Pages already committed keep their SCIM mappings.

The plugin reads users from Keycloak's local user table, in username
order, one page at a time. Compared to earlier versions, this changes
what refresh does:

- **Service accounts are not refreshed.** Earlier versions pushed enabled
  service-account users. The plugin leaves them out now. Their existing
  SCIM records stop receiving updates, but the plugin does not delete
  them.
- **Refresh only examines users with a local row.** LDAP federation must run
  with `Import Users = ON`, which the plugin already requires (see
  [LDAP federation support](ldap-federation-support.md)). Refresh no longer
  imports directory users it has never seen. LDAP synchronization does that,
  and the `scim-ldap-sync` mapper pushes them from there.
- **Removing users is the reconciler's job.** The plugin skips a user
  whose directory entry is gone, when Keycloak's user cache does not have
  them. If the cache still has the user, the plugin pushes them as an
  ordinary update, and the reconciler deprovisions them later. In that
  case, the plugin may push attribute values stored locally, rather than
  current directory values.
- **Skipped users no longer count as updated.** Earlier versions counted
  a user excluded by `scim-skip` or `propagation-role` in the sync
  result's `updated` total. The plugin no longer counts them. What the
  plugin actually propagates does not change.
- **A refresh that stops early now reports a failure.** Earlier versions
  did not flag this in the result. The sync result now records a failure.
  The log names the cursor where the run stopped, and the reason.
- **Groups still refresh in one transaction.** Keycloak has no paged way to
  list every group. The plugin logs a warning when a realm has more than 500
  groups. Refreshing that many groups in one transaction may exceed the
  transaction timeout.

**Transaction timeout.** Paging does not let you lower it. Keycloak wraps the
whole sync in a transaction of its own. It cancels that transaction at the
global timeout, even though the transaction sits idle while the pages run
underneath it. A sync that outlasts the timeout still pushes every user and
keeps every mapping the pages committed, but Keycloak still reports the sync
as failed. Keep the timeout above the time a full sync takes. Paging is not a
way to shorten it.

What paging changes is the cost of a failure. Pages that already committed
keep their SCIM mappings, so a re-run repeats work instead of starting from
nothing.

**Retry and timeouts during a sync.** Every part of a sync retries a failed
call up to 3 times, not the 10 attempts an interactive event gets. Backoff
starts at 500 ms and grows to a 5 second cap.

Only the `sync-refresh` pages that push users use shorter HTTP timeouts:

- 1 second to wait for a pooled connection.
- 3 seconds to connect.
- 10 seconds per read.

Import and group refresh keep the normal 30-second timeouts. Import reads a
whole remote list in one response, which can take longer than a single push.

A deployment whose SCIM server takes more than 10 seconds to answer one
write will see failures during a refresh. It will not see those failures
on other paths. Check this first if failures rise after an upgrade.

**Throttling (HTTP 429).** A single 429 no longer stops a run under
`sync-on-error=auto`. The retry already backs off, and one throttled
response does not mean the SCIM server is down. But `sync-page-size`
throttled users in a row does stop the run. This stops the run from
walking the whole population against a SCIM server that throttles every
request, for no result. The streak carries across pages, and resets on
any push, skip, or failure of another kind.

**Rollback strategy does not apply to a sync.** `rollback-strategy` (above)
already covers interactive console and account events only. `sync-refresh`
and `sync-import` no longer run through the dispatcher those events use. The
code now matches what this document already said: a sync never rolls back.
It only skips or stops, governed by `sync-on-error`.

**Two syncs of the same realm can overlap.** Keycloak locks a component
before a sync starts. That lock's expiry does not grow with the run's
length, so it can expire long before a long run ends. A second scheduled or
admin-triggered sync can then start on the same component while the first is
still running. A run that stops because a page cannot commit, with no
throttle streak and no `sync-on-error` stop logged, is the likely sign of
this.

**What to check on the first run after upgrading.** A few behaviors change
without any configuration change:

- Service accounts stop being refreshed. Check whether any deployment relied
  on that.
- Refresh stops importing directory users it has never seen. On any realm
  that relied on refresh to pull new users in, confirm LDAP synchronization
  is scheduled and the `scim-ldap-sync` mapper is attached.
- The sync result's `updated` count may fall on realms using `scim-skip` or
  `propagation-role`. What is actually propagated does not change.
- A refresh that stops before finishing the realm's users now reports a
  failure, with the reason in the log. Check the log, not only the result
  counters.

### PATCH vs PUT preferences

To update an existing SCIM resource, the plugin can issue either PUT
(full replace) or PATCH (selective). PUT is the default. Some SCIM
servers (notably Databricks) require PATCH for groups.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `user-patchOp` | bool | `false` | When true, user updates use PATCH instead of PUT. |
| `group-patchOp` | bool | `false` | When true, group updates use PATCH instead of PUT. When false (default), the plugin still falls back to PATCH automatically on a 405 Method Not Allowed response from PUT. Most operators do not need to turn this on. |

### User identity mapping

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `username-source` | enum | `username` | Which Keycloak attribute fills the SCIM `userName` field. Options: `username`, `email`. Use `email` if the SCIM server expects email-style identifiers. Falls back to the user's `username` if you select `email` but the user has no email set. |

### Group filtering

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `propagation-role` | string | — | Realm role name. When set, the plugin propagates only users who have this role, the same way on both events and sync. Leave empty (default) to propagate all users. If the named role does not exist in the realm, the plugin propagates no users (fail-closed), and logs a warning. This setting gates users only. To exclude an individual user or group regardless, use the `scim-skip` attribute. |

### User extension attributes

This setting maps Keycloak user attributes to SCIM extension-schema
attributes. The plugin pushes them outbound on create, update, refresh,
and `/Bulk` sync.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `user-extension-mappings` | multivalued string | — | One mapping per row. Each row uses the grammar `<keycloakAttr> = <scimSchemaUrn>:<attr> [; type=<t>] [; multi]`. See below. |

**Row grammar.**

```
<keycloakAttr> = <scimSchemaUrn>:<attr> [; type=<t>] [; multi]
```

- `<keycloakAttr>`: the name of the Keycloak user attribute to read.
- `<scimSchemaUrn>:<attr>`: the target SCIM extension schema URN and
  attribute name, separated by the last `:`. The plugin supports both
  the IETF Enterprise User extension
  (`urn:ietf:params:scim:schemas:extension:enterprise:2.0:User`) and
  custom URN schemas.
- `type=<t>` (optional): converts the string attribute value before
  serialising it. Supported types: `string` (default), `boolean`,
  `integer`, `decimal`, `dateTime`, `reference`. Enterprise User fields
  must use `string` (the default; do not set `type=`).
- `multi` (optional): reads all attribute values through
  `UserModel.getAttributes()` and emits a JSON array. Not allowed on
  Enterprise User fields.

The `type=…` and `multi` modifiers may appear in either order, separated
by `;`. Blank rows are ignored. The plugin rejects a malformed row at
component save time, with a `ComponentValidationException`. If a bad row
somehow reaches runtime, the plugin skips it, logs a WARN, and treats
the whole mapping table as empty.

**Constraints specific to Enterprise User fields.**

| Enterprise User field | Description |
| --- | --- |
| `employeeNumber` | Employee number (string) |
| `costCenter` | Cost centre (string) |
| `organization` | Organisation name (string) |
| `division` | Division name (string) |
| `department` | Department name (string) |

This setting maps only these five Enterprise User fields. The SCIM SDK
exposes only these five, so the plugin does not surface the rest of
RFC 7643 §4.3.

**Examples.**

```
# Enterprise User extension (field must be type=string; no 'multi')
kcDept = urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department

# Custom schema: boolean attribute
kcActive = urn:example:custom:2.0:User:active ; type=boolean

# Custom schema: multivalued string attribute
kcLabels = urn:example:custom:2.0:User:labels ; multi
```

The plugin pushes values outbound on every user create, update, refresh,
and bulk-create operation. By default, the component has no mappings.

**Prerequisite: the source attribute must exist on the user.** A
mapping only *forwards* a Keycloak user attribute. It does not create
one. On Keycloak 25+, the realm's declarative **User Profile** drops any
attribute it does not recognise. So each `<keycloakAttr>` you map must
be permitted. Do one of the following:

- Declare it in the realm User Profile.
- Set the realm's **unmanaged attribute policy** to allow it, for
  example `ENABLED`.

Otherwise the left-hand attribute reads back empty, and the
plugin sends nothing. Populate the values themselves through your
LDAP/federation attribute mappers, the admin API, or user-profile
inputs. See
[Headless / automated provisioning](#headless--automated-provisioning)
for how to do both steps through realm configuration.

### Reconciler

The reconciler is an opt-in periodic task that propagates LDAP
deletions to SCIM. It works around upstream Keycloak issue
[#35235](https://github.com/keycloak/keycloak/issues/35235). See
`docs/ldap-federation-support.md` for the design, and
`docs/performance.md` for scale numbers.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `reconciler-enabled` | bool | `false` | Master switch. Off by default. Most deployments running unaffected Keycloak versions do not need it. |
| `reconciler-interval-seconds` | int (string) | `86400` (24h) | How often the reconciler task fires. Set in seconds, to match Keycloak's federation-sync convention (`fullSyncPeriod`). |
| `reconciler-stale-threshold-seconds` | int (string) | `172800` (48h) | The plugin treats users whose `ldap-federation-last-seen` attribute is older than this value as absent. |

**Validation rules**, enforced at component save time when
`reconciler-enabled=true`:

- Both interval and threshold must be positive integers.
- Threshold must be strictly greater than interval.
- For every LDAP federation in the realm with a positive
  `fullSyncPeriod`, threshold must be strictly greater than that
  federation's sync period. Otherwise the reconciler would delete
  users that the federation simply had not had time to re-observe.

A bad combination throws `ComponentValidationException` at save time.

### OAuth 2.0 client_credentials

When `auth-mode=CLIENT_CREDENTIALS`, the plugin mints a bearer token
from the configured token endpoint and sends it as
`Authorization: Bearer <token>` on every outbound SCIM request.

**Setup steps:**

1. Set `auth-mode` to `CLIENT_CREDENTIALS`.
2. Set `oauth-client-id` to the client ID registered on the
   authorization server.
3. Set `oauth-client-secret` to the matching client secret. If your
   Keycloak deployment has a Vault Provider configured, it stores this
   value encrypted, the same as `auth-pass`.
4. Set `oauth-token-endpoint` to the full token endpoint URL, for
   example
   `https://keycloak.example.com/realms/main/protocol/openid-connect/token`.
   The plugin checks this value at component save time: it must be an
   absolute `http` or `https` URL with a host. Saving fails with a
   `ComponentValidationException` if the URL is blank, relative, or
   has no host.
5. Optionally, set `oauth-scope` to a space-separated list of scopes
   to request. When blank (the default), the plugin omits the `scope`
   parameter from the token request.

**Token request format.** The plugin sends a `POST` to the token
endpoint with:

- `Authorization: Basic <base64(URLEncode(clientId):URLEncode(clientSecret))>`,
  using RFC 6749 §2.3.1 `client_secret_basic` client authentication.
- Body `grant_type=client_credentials` (plus `scope=…` when set).

**Token cache.** The plugin caches tokens in a JVM-wide map, keyed by
SCIM component ID. So all concurrent SCIM requests for a given
component share a single bearer header. It uses the cached entry until
`expires_in − 30s` has elapsed. At that point, the next request
triggers a fresh token fetch: a lazy refresh with a 30-second skew. If
an operator edits any component field, or deletes the component, the
plugin invalidates the cached token for that component immediately.

**On-401/403 retry.** If the SCIM server returns 401 or 403, the
plugin invalidates the cached token and fetches a fresh one from the
token endpoint. It then retries the SCIM operation exactly once. This
handles short-lived token revocations or clock-skew edge cases,
without manual intervention.

**What is not supported for CLIENT_CREDENTIALS.** Each omission below
is deliberate. The team considered each one and deferred it until
there is a concrete need:

- **No OIDC discovery.** You must supply the token endpoint URL
  directly. The plugin does not fetch or follow
  `.well-known/openid-configuration`.
- **No `client_secret_post`.** The plugin supports only
  `client_secret_basic` (RFC 6749 §2.3.1).
- **No `private_key_jwt` or mTLS bearer** (RFC 8705).
- **No `audience` request parameter.** Keycloak does not honour
  `audience` in the client_credentials request body. Configure
  audience restrictions through a token mapper on the Keycloak client
  instead.
- **No proactive refresh ahead of expiry.** Refresh is lazy: the
  plugin fetches a new token only when the cached one has expired, or
  when it receives a 401/403. This matches the SCIM-5xx no-retry gap
  below.
- **No retry on token-endpoint 5xx.** A failed token fetch surfaces
  as an error on the calling SCIM operation, matching the no-retry
  policy for SCIM-server errors.

## Headless / automated provisioning

You can configure everything above without the Admin Console. The SCIM
provider is an ordinary **User Storage Provider component**
(`providerType=org.keycloak.storage.UserStorageProvider`,
`providerId=scim`). Its settings form a map of `key → list-of-strings`.

> **The one gotcha:** `user-extension-mappings`, and any other
> multivalued setting, is a **list**. Put **one mapping row per array
> element**. The plugin reads a single newline-joined string as one
> malformed row. It rejects malformed rows when you save the component
> (`ComponentValidationException` → **HTTP 400**), so a bad mapping
> fails your pipeline fast, instead of shipping silently.

### Realm import JSON

Put the settings under the realm's `components`, keyed by provider
type:

```json
{
  "realm": "myrealm",
  "components": {
    "org.keycloak.storage.UserStorageProvider": [
      {
        "name": "scim",
        "providerId": "scim",
        "config": {
          "endpoint": ["https://identity.example.com/scim/v2"],
          "auth-mode": ["BEARER"],
          "auth-pass": ["${SCIM_TOKEN}"],
          "propagation-user": ["true"],
          "user-extension-mappings": [
            "employeeId = urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:employeeNumber",
            "dept = urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department",
            "active = urn:example:custom:2.0:User:active ; type=boolean",
            "roles = urn:example:custom:2.0:User:roles ; multi"
          ]
        }
      }
    ]
  }
}
```

Import it with `kc.sh start --import-realm` (file in
`/opt/keycloak/data/import/`), with `kc.sh import --file …`, or with
`POST /admin/realms/{realm}/partialImport`.

### kcadm CLI

A multivalued setting takes a JSON array literal:

```bash
kcadm.sh create components -r myrealm \
  -s name=scim -s providerId=scim \
  -s providerType=org.keycloak.storage.UserStorageProvider \
  -s 'config.endpoint=["https://identity.example.com/scim/v2"]' \
  -s 'config.auth-mode=["BEARER"]' \
  -s 'config.auth-pass=["'"$SCIM_TOKEN"'"]' \
  -s 'config.user-extension-mappings=["dept = urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department","active = urn:example:custom:2.0:User:active ; type=boolean","roles = urn:example:custom:2.0:User:roles ; multi"]'
```

To change mappings on an existing provider: run
`kcadm.sh get components/<id>`, edit the array, then run `kcadm.sh
update components/<id> -s 'config.user-extension-mappings=[…]'`.

### Admin REST API

```http
POST /admin/realms/{realm}/components
Content-Type: application/json

{
  "name": "scim",
  "providerId": "scim",
  "providerType": "org.keycloak.storage.UserStorageProvider",
  "config": {
    "endpoint": ["https://identity.example.com/scim/v2"],
    "auth-mode": ["BEARER"],
    "auth-pass": ["…token…"],
    "user-extension-mappings": [
      "dept = urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department",
      "active = urn:example:custom:2.0:User:active ; type=boolean"
    ]
  }
}
```

To update: `GET` the component, change `config.user-extension-mappings`,
then send `PUT /admin/realms/{realm}/components/{id}`.

### The other two pieces, headlessly

- **Event listener** (admin-REST / self-service propagation): add
  `scim` to the realm's `eventsListeners`. In realm JSON, set
  `"eventsListeners": ["jboss-logging", "scim"]`. With kcadm, run
  `kcadm.sh update events/config -r myrealm -s
  'eventsListeners=["jboss-logging","scim"]'`.
- **scim-ldap-sync mapper** (LDAP-import propagation): add a
  `ComponentRepresentation` with
  `providerType=org.keycloak.storage.ldap.mappers.LDAPStorageMapper`,
  `providerId=scim-ldap-sync`, and `parentId` set to your LDAP
  provider's component ID. It has no settings of its own.

### Source-attribute prerequisite (Keycloak 25+)

For extension-attribute mappings, and for the `scim-skip` opt-out, the
realm User Profile must allow the underlying Keycloak user attribute.
Otherwise the plugin silently drops it. Headlessly, either declare each
attribute in the User Profile, or enable unmanaged attributes. To do
this, send `PUT /admin/realms/{realm}/users/profile` with an `UPConfig`
body containing `"unmanagedAttributePolicy": "ENABLED"` (other values:
`ADMIN_VIEW`, `ADMIN_EDIT`).

## scim-ldap-sync LDAP mapper

Attach this mapper to an LDAP User Federation provider through *User
Federation → (LDAP provider) → Mappers → Add → scim-ldap-sync*. **It
has no settings of its own.** Its presence is the configuration: when
attached, LDAP-imported users propagate to every SCIM provider
component in the realm that has `propagation-user=true`.

This mapper also stamps the `ldap-federation-last-seen` attribute on
every imported user. The reconciler uses this attribute.

Every LDAP federation provider whose users should propagate to SCIM
needs this mapper. Without it, only admin-REST and self-service events
propagate, through the event listener.

## scim event listener

Enable this listener through *Admin Console → Realm Settings → Events
→ Config → Event Listeners*, or through the realm configuration, by
including `scim` in `eventsListeners`.

**It has no settings of its own.** When enabled, the listener catches
admin-REST and self-service user, group, and membership events, and
sends them to every configured SCIM provider component.

User propagation does not depend on email verification. A user create
or update propagates to SCIM whether or not the email is confirmed.
Earlier versions gated the event path on `isEmailVerified()`. That made
event propagation inconsistent with sync, and could miss users whose
verification state was not yet visible when the event fired. To
restrict *which* users propagate, use the `propagation-role` component
setting. It applies the same way on both the event and sync paths.

## /scim-reconcile/* REST endpoint

This realm-scoped endpoint is mounted at
`/realms/{realm}/scim-reconcile/...` by
`ScimReconcileResourceProviderFactory`.

| Method | Path | Query | Description |
| --- | --- | --- | --- |
| `POST` | `/{componentId}` | `thresholdHours` (optional, default 48) | Forces a reconciliation pass for the SCIM provider component with the given ID. Returns `200 {"deleted": N, "groupsDeleted": D, "userDeleteMode": "delete"\|"deactivate"}`. `deleted` counts user deprovision operations: SCIM DELETE calls, or in-place `active: false` deactivations when the component has `delete-mode=deactivate` (`userDeleteMode` names which of the two happened). `groupsDeleted` counts federated groups with zero local members that the group phase deleted. The `thresholdHours` query parameter overrides `reconciler-stale-threshold-seconds` for this one call. Use it for operator-driven cleanups after a known LDAP cleanup. |
| `GET` | `/metrics` | — | Returns a plain-text summary of `ScimClient.create` per-phase timing counters (applyModel, query, http send, applyResponse, saveMapping). Useful for live diagnostics. Counters accumulate across the JVM lifetime. |
| `POST` | `/metrics/reset` | — | Zeros the metrics counters. The performance test harness uses this between scenarios. |

### Caller authentication

Keycloak does not authenticate `RealmResourceProvider` routes, so the
provider checks the caller itself. All three routes above apply the
same rule.

The caller sends an `Authorization: Bearer <access token>` header. The
token must be issued by the realm named in the request path. Keycloak
verifies a bearer token against the realm of the current request. So a
token minted by the `master` realm does not authenticate a request to
`/realms/other/scim-reconcile/...`. To call the endpoint for realm `X`,
use a user or a client service account that lives in realm `X`.

The authenticated user must hold the realm's `manage-users` admin role.
In an ordinary realm, that role lives on the `realm-management` client.
The `master` realm has no `realm-management` client. There, the same
role lives on the `master-realm` client, and the `admin` realm role
includes it as a composite. Composite and group-inherited roles both
count, so `realm-admin` in an ordinary realm and `admin` in `master`
also pass.

Responses:

- `401`, with a JSON error body, when the `Authorization` header is
  missing, or the token does not verify against this realm.
- `403`, when the token verifies but its user lacks `manage-users`.

A typical operator setup uses a confidential client in the target
realm, with service accounts enabled, and grants its service-account
user `realm-management` `manage-users`. Fetch a token from that realm's
token endpoint with the `client_credentials` grant.

## User attributes the plugin uses

| Attribute | Set by | Read by | Purpose |
| --- | --- | --- | --- |
| `scim-skip` | operator (manual) | `UserAdapter.apply`, `GroupAdapter.apply` | Set this to `"true"` on a user or group to opt it out of SCIM propagation. The mapper still fires for it, but propagation stops there. Useful for service accounts, internal admin users, or to exclude one member of a `propagation-role`-eligible group. |
| `ldap-federation-last-seen` | `ScimLdapStorageMapper.onImportUserFromLDAP` | `StaleAttributeWitness` (the reconciler) | ISO-8601 timestamp of the last time Keycloak's LDAP federation observed this user. The reconciler treats a user as absent when this attribute is older than `reconciler-stale-threshold-seconds`, and propagates deprovisioning (SCIM DELETE, or deactivation under `delete-mode=deactivate`). |

## JVM system properties

The plugin reads these settings from `System.getProperty(...)` at
runtime. Set them with `-D…` JVM flags on the Keycloak process.

| Property | Default | Where used | Description |
| --- | --- | --- | --- |
| `scim.dispatch.threads` | `8` | `ScimDispatcher` | Size of the worker pool that processes async SCIM operations (LDAP-import propagation and reconciler-batch deletes). This pool is JVM-global. It also sizes the `/Bulk` lane's worker pool when `bulk-enabled` is on. So with bulk enabled, the total dispatch thread budget is roughly double: one pool per lane. Higher values increase parallel throughput against the SCIM server, at the cost of more concurrent connections. Most SCIM servers tolerate 8 to 16 threads. Raise this only if you have headroom on both sides. |
| `scim.dispatch.queueCapacity` | `256` | `ScimDispatcher` / bulk lane | Bounded queue depth between producers and the worker pool. Each lane has its own queue of this size. When the queue is full, the producer **blocks** (back-pressure) instead of buffering without limit. This is what bounds the dispatch memory footprint to roughly this many tasks, regardless of sync size. Raising it loosens the memory bound, for more burst headroom. |
| `scim.dispatch.blockWarnMs` | `10000` | `ScimDispatcher` / bulk lane | How long a producer may stay blocked on a full queue before the plugin logs a back-pressure WARN and bumps a counter. A rising count signals a slow or wedged SCIM server that is throttling syncs. |
| `scim.dispatch.bulkBatchSize` | `20` | bulk lane | Maximum number of SCIM operations the plugin combines into one `/Bulk` request (K). Must be at most the SCIM server's advertised `maxOperations`. An oversize batch draws a whole-request `413` or `400`, and that batch's creates are lost for that round. Set K conservatively. The plugin does not auto-discover this limit. Only relevant when a component has `bulk-enabled=true`. |
| `scim.tls.insecureHostnameVerification` | `false` | `ScimClient` | When `true`, disables TLS hostname verification on outbound SCIM requests. The plugin then accepts any certificate the SCIM server presents, regardless of CN/SAN. Use this only as an escape hatch for development environments, internal CAs with CN drift, or explicitly-trusted self-signed setups. **Leave `false` in production.** With verification off, an attacker can position itself between the plugin and the SCIM server. It can then present a valid certificate for any domain, impersonate the SCIM server, and harvest bearer tokens. |
| `keycloak.image` | `quay.io/keycloak/keycloak:25.0.6` | integration tests | Overrides the Keycloak container image the test harness uses. The CI matrix uses this to verify both 25.x and 26.x. Not relevant to production. |

## What's NOT configurable (by design)

- **Retry policy.** `ScimClient` retries `ProcessingException` and
  `IORuntimeException` (network-level errors), plus 429 and 5xx
  responses. Calls outside a sync make up to 10 attempts, with
  exponential backoff that starts at 500 ms and has no cap. This
  covers events, the LDAP mapper, the bulk lane, and the reconciler.
  Every part of a sync makes up to 3 attempts instead. This covers
  import, `sync-refresh` pages, and group refresh. Its backoff also
  starts at 500 ms, but is capped at 5 s. The smaller budget keeps one
  failing resource from holding a sync transaction open for minutes.
  This value is hardcoded. A tunable setting here would invite
  per-deployment drift, without a clear benefit.
- **HTTP timeouts.** Interactive calls, import, and group refresh use 30 s
  for connect, request, and socket, in `ScimClient.genScimClientConfig`. A
  `sync-refresh` page uses shorter timeouts instead. See "How sync-refresh
  pages through users" above for the values.
- **`/Bulk` scope.** SCIM `/Bulk` batching is available only for the
  federation-sync user **create** path (opt-in through the
  `bulk-enabled` component flag, see above). Replace, delete, and
  group-membership operations still send one HTTP request each.
  Extending `/Bulk` to them is a deferred follow-up, pending the
  performance data in `docs/performance.md`.
- **Async dispatch on/off.** The LDAP-import path is always async,
  since the v0.x performance work. The plugin keeps the synchronous
  `ScimDispatcher.run` method for the reconciler endpoint, which needs
  to return a count synchronously.
- **OAuth token-endpoint retry on 5xx.** When `auth-mode=CLIENT_CREDENTIALS`,
  a failed token fetch (a network error, or 5xx from the authorization
  server) surfaces immediately as an error on the calling SCIM
  operation. There is no retry loop for the token request itself.
  This matches the SCIM-server no-retry gap above.
- **OAuth proactive refresh.** Token refresh is lazy. The plugin
  replaces the cached token only when `expires_in − 30s` has elapsed,
  or when it receives a 401/403 from the SCIM server. There is no
  background thread that refreshes ahead of expiry.
