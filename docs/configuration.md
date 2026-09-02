# Configuration reference

Complete list of every config knob this plugin exposes — what each one
does, its type, default, and any non-obvious interaction.

The plugin contributes five things to a Keycloak realm:

1. A **SCIM provider component** (User Federation entry, providerId
   `scim`). One per remote SCIM endpoint. Most config lives here.
2. A **`scim-ldap-sync` LDAP mapper** attached to an LDAP User
   Federation provider. No config of its own; presence enables
   propagation of LDAP-imported users.
3. A **`scim` event listener** registered on the realm.
4. A **`/realms/{realm}/scim-reconcile/...`** REST endpoint. Query
   params, not stored config.
5. **User attributes** the plugin reads (opt-out) and writes
   (liveness-tracking).

Plus JVM-level **system properties** for build/runtime tuning.

## SCIM provider component

Add via *Admin Console → User Federation → Add → scim*, or via the
admin REST API (`POST /admin/realms/{realm}/components` with
`providerType=org.keycloak.storage.UserStorageProvider` and
`providerId=scim`). Many of these can be edited later; the
`onUpdate` hook reschedules timers as needed.

### Connection

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `endpoint` | string | *required* | Base URL of the SCIM 2.0 service. The endpoint's `/Users`, `/Groups`, `/ServiceProviderConfig`, `/Schemas`, `/ResourceTypes` should all be reachable from this base. Example: `https://identity.example.com/scim/v2`. |
| `content-type` | enum | `application/scim+json` | Content-Type header for outbound SCIM requests. Override to `application/json` only if the remote SCIM server doesn't accept the canonical `application/scim+json`. Options: `application/scim+json`, `application/json`. |

### Authentication

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `auth-mode` | enum | `NONE` | One of `NONE`, `BASIC_AUTH`, `BEARER`, `CLIENT_CREDENTIALS`. `NONE` is for local dev only — production deployments should always use `BEARER` or `CLIENT_CREDENTIALS`. |
| `auth-user` | string | — | Username for `BASIC_AUTH`. Ignored for other modes. |
| `auth-pass` | password | — | Password for `BASIC_AUTH`, or static token for `BEARER`. Stored encrypted by Keycloak's Vault Provider where configured. Ignored for `CLIENT_CREDENTIALS`. |
| `oauth-client-id` | string | — | OAuth client ID for the `CLIENT_CREDENTIALS` grant. Required when `auth-mode=CLIENT_CREDENTIALS`. Ignored for other modes. |
| `oauth-client-secret` | password | — | OAuth client secret for the `CLIENT_CREDENTIALS` grant. Required when `auth-mode=CLIENT_CREDENTIALS`. Stored encrypted by Keycloak's Vault Provider where configured. Ignored for other modes. |
| `oauth-token-endpoint` | string | — | Full URL of the OAuth 2.0 token endpoint. Required when `auth-mode=CLIENT_CREDENTIALS`. Must be an absolute `http` or `https` URL with a host (e.g. `https://keycloak.example.com/realms/main/protocol/openid-connect/token`). Validated at component save time. Ignored for other modes. |
| `oauth-scope` | string | — | Space-separated OAuth scopes to request. Optional. When non-blank, sent as the `scope` parameter on the token request; omitted otherwise. Ignored for modes other than `CLIENT_CREDENTIALS`. |

### Propagation toggles

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `propagation-user` | bool | `true` | When false, user create/update/delete events do not result in SCIM calls. Useful for groups-only deployments or for temporarily disabling user propagation during operator maintenance. |
| `propagation-group` | bool | `true` | Same toggle for group create/update/delete and group-membership changes. |
| `bulk-enabled` | bool | `false` | When true, federation-sync user **create** operations are coalesced into SCIM `/Bulk` requests instead of one `POST /Users` each. Requires the SCIM server to support `/Bulk`; set `scim.dispatch.bulkBatchSize` ≤ the server's advertised `maxOperations`. Only the LDAP-import create path is batched — replace/delete/membership stay per-op. Pays off most against slow/high-RTT sinks and can be slightly *slower* than per-op against a fast local sink (see `docs/performance.md`), hence default off. |

Both toggles apply across all paths: admin-REST events,
LDAP-federation imports (when the `scim-ldap-sync` mapper is attached),
and sync operations.

### Deprovisioning mode

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `delete-mode` | enum | `delete` | How user deprovisioning reaches the SCIM service. Options: `delete`, `deactivate`. |

`delete` (the default) sends `DELETE /Users/{id}` and drops the local
mapping (the existing behavior, unchanged). `deactivate` never hard-deletes
users: every deprovisioning path (admin user deletion, account self-deletion,
and the LDAP-deletion reconciler) instead marks the remote user inactive in
place and keeps the local mapping.

On the wire (with `user-patchOp=false`, the default) this is a
`GET /Users/{id}` followed by a `PUT /Users/{id}` of the fetched resource with
`active: false`. If the GET already shows the user inactive, no PUT is sent.
With `user-patchOp=true` it is a single `PATCH` replacing `active`. A 404 on
any call means the resource is already gone downstream, and the deprovision is
treated as complete.

Deactivation is recorded locally (`DEACTIVATED_AT` on the mapping row), so the
reconciler skips already-deactivated users on later passes: no repeated
writes, no per-pass HTTP for retained users. `sync-refresh` also skips
deactivated mappings, since a lingering local copy is not evidence the user is
back, and `sync-import` neither deletes nor re-imports inactive unmatched
remote users while this mode is active.

Reactivation targets the same remote resource. If the user reappears (e.g. the
directory entry returns) while the mapping is retained, the update goes to the
same `/Users/{id}` with `active: true`. If the Keycloak account itself was
deleted and re-created, the user goes out as a fresh `POST /Users`; identity
continuity then depends on the SCIM service matching the existing deactivated
user by `userName` and returning the same resource id. Treat `externalId` as
advisory here: it is the Keycloak internal id and changes in this case. When a
create response returns a resource id matching a retained deactivated mapping,
the stale mapping is purged.

Switching back to `delete` re-enables deletes: previously deactivated mappings
lose their skip protection and go through the reconciler's normal absence
checks like any other mapping. In practice that deletes them remotely, since
the conditions that caused deactivation still hold.

Groups are unaffected: SCIM groups have no `active` attribute, so group
deletions always go out as `DELETE /Groups/{id}`. User *disable* is also
unaffected; it already propagates as an ordinary update with `active: false`.

### Failure handling on interactive events

By default the plugin is fail-open: when a SCIM call fails, the Keycloak
change still commits and the failure is logged. When the SCIM endpoint is
authoritative (a Keycloak change shouldn't stand if it can't be mirrored),
`rollback-strategy` lets an interactive event roll the Keycloak transaction
back instead.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `rollback-strategy` | enum | `never` | What to do when a SCIM call fails on an interactive console/account event. `never` (default): log and keep the Keycloak change. `always`: roll back the Keycloak operation on any SCIM failure. `critical-only`: roll back only on transient failures (endpoint unreachable, 5xx, 429); permanent failures (4xx, bad mapping, malformed data) are logged and kept. Options: `never`, `always`, `critical-only`. |

This applies only to the synchronous, pre-commit event path: console,
admin-REST, and account-console user create/update/delete handled by the
`scim` event listener. It runs on the same transaction as the Keycloak
operation, so marking that transaction rollback-only undoes the change. It
does not apply to LDAP-federation imports (which propagate after the import
commits, leaving nothing to roll back) or to batch sync (governed by
`sync-on-error`, below); both stay fail-open regardless.

A component with `rollback-strategy` other than `never` and
`bulk-enabled=true` is rejected at save time: bulk creates are deferred past
commit and can't take part in a rollback. Use a non-bulk component.

Three things to know before enabling it:

- **Multi-endpoint orphans.** Rollback assumes a single critical SCIM
  endpoint. If a realm has several SCIM components and an event fans out to
  more than one, the listener dispatches to each in turn. If one succeeds and
  a later one fails and rolls back, the Keycloak operation is undone, but the
  write already sent to the first endpoint can't be recalled and its local
  mapping rolls back with the transaction. That leaves an orphan (a SCIM
  resource with no Keycloak mapping) that isn't cleaned up. Point
  `rollback-strategy` at a single-endpoint deployment.
- **Admin REST still returns 201.** Keycloak builds the `201 Created`
  response before commit, so a rolled-back admin user-create returns 201 with
  a `Location` header while the user doesn't persist. Check whether the user
  exists rather than trusting the status. Account/self-service flows do
  surface an error; the 201 is specific to the Admin REST path.
- **Latency on a down endpoint.** Rollback needs synchronous propagation, so
  an interactive create/update against a slow or unreachable endpoint blocks
  for the whole retry budget (~38s at the current hardcoded 10-attempt
  backoff) before it rolls back and returns. The `never` default avoids this,
  since propagation is async. A proxy in front of Keycloak may return a `504`
  first.

### Sync behavior

The plugin implements `ImportSynchronization`, so this component
appears under *User Federation → Periodic Sync*. These knobs control
what happens during a manual or scheduled sync of *this SCIM provider
component itself* (i.e., asking the plugin to sync state with the
remote SCIM server). They do NOT control LDAP-federation sync — that's
controlled by the LDAP component.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `sync-import` | bool | `false` | When true, fetch users/groups from the remote SCIM server during sync and act on each per `sync-import-action`. |
| `sync-import-action` | enum | `CREATE_LOCAL` | What to do when a remote SCIM user/group has no local Keycloak counterpart. Options: `NOTHING` (log only), `CREATE_LOCAL` (add to Keycloak), `DELETE_REMOTE` (remove from SCIM). Choose `DELETE_REMOTE` only for one-way Keycloak-as-source-of-truth deployments. |
| `sync-refresh` | bool | `false` | When true, push local users/groups out to the SCIM server during sync (covering anything the event listener missed). Combine with `sync-import=false` for a pure outbound sync. |
| `sync-on-error` | enum | `auto` | How a per-record failure in the sync loop is handled. `auto` (default): a permanent failure (bad mapping, malformed data, 4xx) skips that record and the run continues; a transient failure (endpoint unreachable, 5xx, 429) stops the run, since every remaining record would fail the same way. `continue`: always skip the failed record and keep going. `stop`: abort on the first failure of any kind. Options: `auto`, `continue`, `stop`. |

`sync-on-error` governs both the import and refresh halves of a sync. It is
independent of `rollback-strategy` (which covers interactive events only): a
sync never rolls back already-applied records, it only skips or stops.

Both `sync-import` and `sync-refresh` are off by default, so triggering a sync
on this component without enabling at least one of them does nothing and
returns an empty result. The plugin logs a line saying so, since an empty
result on its own looks the same as a sync that ran and found no work.

### PATCH vs PUT preferences

When updating an existing SCIM resource, the plugin can issue either
PUT (full replace) or PATCH (selective). PUT is the default; some
SCIM servers (notably Databricks) require PATCH for groups.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `user-patchOp` | bool | `false` | When true, user updates use PATCH instead of PUT. |
| `group-patchOp` | bool | `false` | When true, group updates use PATCH instead of PUT. When false (default), the plugin still falls back to PATCH automatically on a 405 Method Not Allowed response from PUT — so most operators don't need to flip this. |

### User identity mapping

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `username-source` | enum | `username` | Which Keycloak attribute populates the SCIM `userName` field. Options: `username`, `email`. Use `email` if the remote SCIM server expects email-style identifiers. Falls back to the user's `username` if `email`-source is selected but the user has no email set. |

### Group filtering

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `propagation-role` | string | — | Realm role name. When set, only users who have this role are propagated — applied the same way on both events and sync. Leave empty (default) to propagate all users. If the named role doesn't exist in the realm, no users are propagated (fail-closed), with a warning logged. Gates users only; to exclude an individual user or group regardless, use the `scim-skip` attribute. |

### User extension attributes

Maps Keycloak user attributes to SCIM extension-schema attributes and
pushes them outbound on create, update, refresh, and `/Bulk` sync.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `user-extension-mappings` | multivalued string | — | One mapping per row; each row has the grammar `<keycloakAttr> = <scimSchemaUrn>:<attr> [; type=<t>] [; multi]`. See below. |

**Row grammar.**

```
<keycloakAttr> = <scimSchemaUrn>:<attr> [; type=<t>] [; multi]
```

- `<keycloakAttr>` — the name of the Keycloak user attribute to read.
- `<scimSchemaUrn>:<attr>` — the target SCIM extension schema URN and
  attribute name, separated by the last `:`.  Both the IETF Enterprise
  User extension
  (`urn:ietf:params:scim:schemas:extension:enterprise:2.0:User`) and
  arbitrary custom URN schemas are supported.
- `type=<t>` (optional) — coerces the string attribute value before
  serialising.  Supported types: `string` (default), `boolean`,
  `integer`, `decimal`, `dateTime`, `reference`.  Enterprise User
  fields must be `string` (the default; no `type=` needed).
- `multi` (optional) — reads all attribute values via
  `UserModel.getAttributes()` and emits a JSON array.  Not allowed on
  Enterprise User fields.

Modifiers (`type=…`, `multi`) may appear in either order, separated by
`;`.  Blank rows are ignored.  A malformed row is rejected at component
save time with a `ComponentValidationException`; if a bad row somehow
reaches runtime it is skipped (logged as WARN) and the whole mapping
table is treated as empty.

**Constraints specific to Enterprise User fields.**

| Enterprise User field | Description |
| --- | --- |
| `employeeNumber` | Employee number (string) |
| `costCenter` | Cost centre (string) |
| `organization` | Organisation name (string) |
| `division` | Division name (string) |
| `department` | Department name (string) |

No other Enterprise User fields are mapped through this knob (the SCIM
SDK exposes these five; the rest of RFC 7643 §4.3 is not surfaced
here).

**Examples.**

```
# Enterprise User extension (field must be type=string; no 'multi')
kcDept = urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department

# Custom schema — boolean attribute
kcActive = urn:example:custom:2.0:User:active ; type=boolean

# Custom schema — multivalued string attribute
kcLabels = urn:example:custom:2.0:User:labels ; multi
```

Values are pushed outbound on every user create, update, refresh, and
bulk-create operation.  The property is absent from the component by
default (no mappings).

**Prerequisite — the source attribute must exist on the user.** A
mapping only *forwards* a Keycloak user attribute; it does not create
one.  On Keycloak 25+, the realm's declarative **User Profile** drops
any attribute it doesn't recognise, so each `<keycloakAttr>` you map
must be permitted — either declared in the realm User Profile, or the
realm's **unmanaged attribute policy** set to allow it (e.g. `ENABLED`).
Otherwise the left-hand attribute reads back empty and nothing is sent.
Populate the values themselves via your LDAP/federation attribute
mappers, the admin API, or user-profile inputs.  See
[Headless / automated provisioning](#headless--automated-provisioning)
for the realm-config form of both steps.

### Reconciler

The reconciler is an opt-in periodic task that propagates LDAP
deletions to SCIM, working around upstream Keycloak issue
[#35235](https://github.com/keycloak/keycloak/issues/35235). See
`docs/ldap-federation-support.md` for the design, and
`docs/performance.md` for scale numbers.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `reconciler-enabled` | bool | `false` | Master switch. Off by default — most deployments running unaffected Keycloak versions don't need it. |
| `reconciler-interval-seconds` | int (string) | `86400` (24h) | How often the reconciler task fires. Configured in seconds to match Keycloak's federation-sync convention (`fullSyncPeriod`). |
| `reconciler-stale-threshold-seconds` | int (string) | `172800` (48h) | Users whose `ldap-federation-last-seen` attribute is older than this are considered absent. |

**Validation rules** enforced at component save time (when
`reconciler-enabled=true`):

- Both interval and threshold must be positive integers.
- Threshold must be strictly greater than interval.
- For every LDAP federation in the realm with positive
  `fullSyncPeriod`, threshold must be strictly greater than that
  federation's sync period. Otherwise the reconciler would delete
  users the federation simply hadn't had time to re-observe.

A bad combination throws `ComponentValidationException` at save time.

### OAuth 2.0 client_credentials

When `auth-mode=CLIENT_CREDENTIALS`, the plugin mints a bearer token
from the configured token endpoint and sends it as
`Authorization: Bearer <token>` on every outbound SCIM request.

**Setup steps:**

1. Set `auth-mode` to `CLIENT_CREDENTIALS`.
2. Set `oauth-client-id` to the client ID registered on the
   authorization server.
3. Set `oauth-client-secret` to the corresponding client secret.
   If your Keycloak deployment has a Vault Provider configured,
   the value is stored encrypted (same behavior as `auth-pass`).
4. Set `oauth-token-endpoint` to the full token endpoint URL (e.g.
   `https://keycloak.example.com/realms/main/protocol/openid-connect/token`).
   The value is validated at component save time: it must be an
   absolute `http` or `https` URL with a host. Saving fails with a
   `ComponentValidationException` if the URL is blank, relative, or
   has no host.
5. Optionally set `oauth-scope` to a space-separated list of scopes
   to request. When blank (the default), the `scope` parameter is
   omitted from the token request.

**Token request format.** The plugin issues a `POST` to the token
endpoint with:

- `Authorization: Basic <base64(URLEncode(clientId):URLEncode(clientSecret))>`
  — RFC 6749 §2.3.1 `client_secret_basic` client authentication.
- Body `grant_type=client_credentials` (plus `scope=…` when set).

**Token cache.** Tokens are cached in a JVM-wide map keyed by SCIM
component ID, so all concurrent SCIM requests for a given component
share a single bearer header. The cached entry is used until
`expires_in − 30s` has elapsed, at which point the next request
triggers a fresh token fetch (lazy refresh with a 30-second skew).
If any component field is edited by an operator, or the component is
deleted, the cached token for that component is invalidated
immediately.

**On-401/403 retry.** If the SCIM endpoint returns 401 or 403, the
plugin invalidates the cached token, fetches a fresh one from the
token endpoint, and retries the SCIM operation exactly once. This
handles short-lived token revocations or clock-skew edge cases
without manual intervention.

**What's NOT supported for CLIENT_CREDENTIALS** (deliberate
omissions; each was considered and deferred until there is a
concrete need):

- **No OIDC discovery.** The token endpoint URL must be supplied
  directly; the plugin does not fetch or follow
  `.well-known/openid-configuration`.
- **No `client_secret_post`.** Only `client_secret_basic` (RFC 6749
  §2.3.1) is supported.
- **No `private_key_jwt` or mTLS bearer** (RFC 8705).
- **No `audience` request parameter.** Keycloak does not honor
  `audience` in the client_credentials request body. Configure
  audience restrictions via a token mapper on the Keycloak client
  instead.
- **No proactive refresh-ahead-of-expiry.** Refresh is lazy: a new
  token is fetched only when the cached one has expired (or a
  401/403 is received). This is symmetric with the existing SCIM-5xx
  no-retry gap below.
- **No retry on token-endpoint 5xx.** A failed token fetch surfaces
  as an error on the calling SCIM operation. Symmetric with the
  existing no-retry policy for SCIM-server errors.

## Headless / automated provisioning

Everything above is configurable without the Admin Console. The SCIM
provider is an ordinary **User Storage Provider component**
(`providerType=org.keycloak.storage.UserStorageProvider`,
`providerId=scim`); its config is a map of `key → list-of-strings`.

> **The one gotcha:** `user-extension-mappings` (and any other
> multivalued knob) is a **list** — put **one mapping row per array
> element**. A single newline-joined string is read as one malformed
> row. Malformed rows are rejected when the component is saved
> (`ComponentValidationException` → **HTTP 400**), so a bad mapping
> fails your pipeline fast rather than shipping silently.

### Realm import JSON

Under the realm's `components`, keyed by provider type:

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

Import with `kc.sh start --import-realm` (file in
`/opt/keycloak/data/import/`), `kc.sh import --file …`, or
`POST /admin/realms/{realm}/partialImport`.

### kcadm CLI

Multivalued config takes a JSON array literal:

```bash
kcadm.sh create components -r myrealm \
  -s name=scim -s providerId=scim \
  -s providerType=org.keycloak.storage.UserStorageProvider \
  -s 'config.endpoint=["https://identity.example.com/scim/v2"]' \
  -s 'config.auth-mode=["BEARER"]' \
  -s 'config.auth-pass=["'"$SCIM_TOKEN"'"]' \
  -s 'config.user-extension-mappings=["dept = urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department","active = urn:example:custom:2.0:User:active ; type=boolean","roles = urn:example:custom:2.0:User:roles ; multi"]'
```

Change mappings on an existing provider: `kcadm.sh get components/<id>`,
edit the array, then `kcadm.sh update components/<id> -s
'config.user-extension-mappings=[…]'`.

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

Update = `GET` the component, mutate `config.user-extension-mappings`,
`PUT /admin/realms/{realm}/components/{id}`.

### The other two pieces, headlessly

- **Event listener** (admin-REST / self-service propagation): add
  `scim` to the realm's `eventsListeners` — realm JSON
  `"eventsListeners": ["jboss-logging", "scim"]`, or `kcadm.sh update
  events/config -r myrealm -s 'eventsListeners=["jboss-logging","scim"]'`.
- **scim-ldap-sync mapper** (LDAP-import propagation): add a
  `ComponentRepresentation` with
  `providerType=org.keycloak.storage.ldap.mappers.LDAPStorageMapper`,
  `providerId=scim-ldap-sync`, and `parentId` = your LDAP provider's
  component id (no config of its own).

### Source-attribute prerequisite (Keycloak 25+)

For extension-attribute mappings (and the `scim-skip` opt-out) the
underlying Keycloak user attribute must be allowed by the realm User
Profile, or it is silently dropped. Headlessly, either declare each
attribute in the User Profile, or enable unmanaged attributes —
`PUT /admin/realms/{realm}/users/profile` with an `UPConfig` body
containing `"unmanagedAttributePolicy": "ENABLED"` (other values:
`ADMIN_VIEW`, `ADMIN_EDIT`).

## scim-ldap-sync LDAP mapper

Attached to an LDAP User Federation provider via *User Federation →
(LDAP provider) → Mappers → Add → scim-ldap-sync*. **No config
properties of its own.** Presence is the configuration: when attached,
LDAP-imported users propagate to every SCIM provider component in the
realm with `propagation-user=true`.

This mapper also stamps the `ldap-federation-last-seen` attribute on
every imported user (used by the reconciler).

The mapper is required on every LDAP federation provider whose users
should propagate to SCIM. Without it, only admin-REST and self-service
events propagate (via the event listener).

## scim event listener

Enable via *Admin Console → Realm Settings → Events → Config →
Event Listeners*, or via realm config (`eventsListeners` includes
`scim`).

**No config properties of its own.** When enabled, the listener
catches admin-REST and self-service user/group/membership events and
fans out to every configured SCIM provider component.

User propagation does not depend on email verification: a user
create/update propagates to SCIM whether or not the email is
confirmed. (Earlier versions gated the event path on
`isEmailVerified()`, which made event propagation inconsistent with
sync and could miss users whose verification state wasn't yet visible
when the event fired.) To restrict *which* users propagate, use the
`propagation-role` component setting — it applies the same way on both
the event and sync paths.

## /scim-reconcile/* REST endpoint

Realm-scoped endpoint mounted at
`/realms/{realm}/scim-reconcile/...` by `ScimReconcileResourceProviderFactory`.

| Method | Path | Query | Description |
| --- | --- | --- | --- |
| `POST` | `/{componentId}` | `thresholdHours` (optional, default 48) | Forces a reconciliation pass for the SCIM provider component with the given id. Returns `200 {"deleted": N, "groupsDeleted": D, "userDeleteMode": "delete"\|"deactivate"}`. `deleted` counts user deprovision operations issued: SCIM DELETE calls, or in-place `active: false` deactivations when the component has `delete-mode=deactivate` (`userDeleteMode` reports which of the two happened). `groupsDeleted` counts federated groups with zero local members deleted by the group phase. The `thresholdHours` query param overrides `reconciler-stale-threshold-seconds` for this single call, useful for operator-driven cleanups after a known LDAP cleanup. |
| `GET` | `/metrics` | — | Returns a plain-text summary of `ScimClient.create` per-phase timing counters (applyModel, query, http send, applyResponse, saveMapping). Useful for live diagnostics; counters accumulate across the JVM lifetime. |
| `POST` | `/metrics/reset` | — | Zeros the metrics counters. Used by the perf harness between scenarios. |

### Caller authentication

Keycloak does not authenticate `RealmResourceProvider` routes, so the
provider checks the caller itself. All three routes above apply the
same rule.

The caller sends an `Authorization: Bearer <access token>` header. The
token must be issued by the realm named in the request path. Keycloak
verifies a bearer token against the realm of the current request, so a
token minted by the `master` realm does not authenticate a request to
`/realms/other/scim-reconcile/...`. To call the endpoint for realm `X`,
use a user or a client service account that lives in realm `X`.

The authenticated user must hold the realm's `manage-users` admin role.
In an ordinary realm that role lives on the `realm-management` client.
The `master` realm has no `realm-management` client; there the same role
lives on the `master-realm` client, and the `admin` realm role includes
it as a composite. Composite and group-inherited roles both count, so
`realm-admin` in an ordinary realm and `admin` in `master` also pass.

Responses: `401` with a JSON error body when the `Authorization` header
is missing or the token does not verify against this realm, `403` when
the token verifies but its user lacks `manage-users`.

A typical operator setup is a confidential client in the target realm
with service accounts enabled, whose service-account user is granted
`realm-management` `manage-users`. Fetch a token from that realm's
token endpoint with the `client_credentials` grant.

## User attributes the plugin uses

| Attribute | Set by | Read by | Purpose |
| --- | --- | --- | --- |
| `scim-skip` | operator (manual) | `UserAdapter.apply`, `GroupAdapter.apply` | Set to `"true"` on a user (or group) to opt them out of SCIM propagation. The mapper still fires for them, but propagation short-circuits. Useful for service accounts, internal admin users, or excluding an individual member of a `propagation-role`-eligible group. |
| `ldap-federation-last-seen` | `ScimLdapStorageMapper.onImportUserFromLDAP` | `StaleAttributeWitness` (the reconciler) | ISO-8601 timestamp of the last time Keycloak's LDAP federation observed this user. The reconciler treats users whose attribute is older than `reconciler-stale-threshold-seconds` as absent and propagates deprovisioning (SCIM DELETE, or deactivation under `delete-mode=deactivate`). |

## JVM system properties

Tunables read from `System.getProperty(...)` at runtime. Set via
`-D…` JVM flags on the Keycloak process.

| Property | Default | Where used | Description |
| --- | --- | --- | --- |
| `scim.dispatch.threads` | `8` | `ScimDispatcher` | Size of the worker pool that processes async SCIM operations (LDAP-import propagation and reconciler-batch deletes). Pool is JVM-global. Also sizes the `/Bulk` lane's worker pool when `bulk-enabled` is on — so with bulk enabled the total dispatch thread budget is roughly double (one pool per lane). Higher values increase parallel throughput against the SCIM sink at the cost of more concurrent connections. Most SCIM servers tolerate 8–16; raise only if you have headroom on both sides. |
| `scim.dispatch.queueCapacity` | `256` | `ScimDispatcher` / bulk lane | Bounded queue depth between producers and the worker pool (each lane has its own queue of this size). When full, the producer **blocks** (back-pressure) rather than buffering unboundedly — this is what bounds the dispatch memory footprint to ~capacity tasks regardless of sync size. Raising it loosens the memory bound for more burst headroom. |
| `scim.dispatch.blockWarnMs` | `10000` | `ScimDispatcher` / bulk lane | How long a producer may stay blocked on a full queue before each back-pressure WARN log (and counter bump). A rising count signals a slow/wedged SCIM sink throttling syncs. |
| `scim.dispatch.bulkBatchSize` | `20` | bulk lane | Max SCIM operations coalesced into one `/Bulk` request (K). Must be ≤ the SCIM server's advertised `maxOperations` — an oversize batch draws a whole-request `413`/`400` and that batch's creates are lost this round (the operator sets K conservatively; there is no auto-discovery). Only relevant when a component has `bulk-enabled=true`. |
| `scim.tls.insecureHostnameVerification` | `false` | `ScimClient` | When `true`, disables TLS hostname verification on outbound SCIM requests — any cert presented by the SCIM endpoint will be accepted regardless of CN/SAN. Escape hatch for dev environments, internal CAs with CN drift, or explicitly-trusted self-signed setups. **Leave `false` in production**: with verification off, a MITM presenting a valid cert for any domain can impersonate the SCIM endpoint and harvest bearer tokens. |
| `keycloak.image` | `quay.io/keycloak/keycloak:25.0.6` | integration tests | Override the Keycloak container image used by the test harness. Used by the CI matrix to verify both 25.x and 26.x. Production-irrelevant. |

## What's NOT configurable (by design)

- **Retry policy.** `ScimClient` retries on `ProcessingException` and
  `IORuntimeException` (network-level errors), max 10 attempts with
  exponential backoff starting at 500 ms. Hardcoded — tunable knobs
  for this would invite per-deployment drift without a clear win.
- **HTTP timeouts.** Connect / request / socket all 30 s. Hardcoded
  in `ScimClient.genScimClientConfig`.
- **`/Bulk` scope.** SCIM `/Bulk` batching is available for the
  federation-sync user **create** path only (opt-in via the
  `bulk-enabled` component flag; see above). Replace, delete, and
  group-membership operations still issue one HTTP request each —
  extending `/Bulk` to them is a deferred follow-up pending the
  characterization data in `docs/performance.md`.
- **Async dispatch on/off.** Always async on the LDAP-import path
  (since v0.x perf work). The synchronous `ScimDispatcher.run` is
  preserved for the reconciler endpoint's "return a count
  synchronously" semantics.
- **OAuth token-endpoint retry on 5xx.** When `auth-mode=CLIENT_CREDENTIALS`,
  a failed token fetch (network error or 5xx from the authorization
  server) surfaces immediately as an error on the calling SCIM
  operation — there is no retry loop for the token request itself.
  Symmetric with the SCIM-server no-retry gap above.
- **OAuth proactive refresh.** Token refresh is lazy: the cached
  token is replaced only when `expires_in − 30s` has elapsed or a
  401/403 is received from the SCIM endpoint. There is no
  background thread that refreshes ahead of expiry.
