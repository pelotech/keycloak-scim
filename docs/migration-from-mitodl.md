# Migrating from `mitodl/keycloak-scim`

If you're already running upstream `mitodl/keycloak-scim` and want
to switch to this fork, this is a one-page guide. The short version:
the database schema and the SCIM provider component config are
binary-compatible, so a switchover is mostly a matter of replacing
the JAR (or image) and verifying behavior. The fork adds new
features but doesn't change anything you were already relying on.

## What's compatible

- **Database schema.** The `SCIM_RESOURCE` table (provided via
  `JpaEntityProvider` + Liquibase changelog) is unchanged. Your
  existing local-to-remote SCIM ID mappings carry over without
  migration.
- **SCIM provider component config.** Every config knob upstream
  exposes — `endpoint`, `auth-mode`, `auth-user`, `auth-pass`,
  `propagation-user`, `propagation-group`, `sync-import`,
  `sync-import-action`, `sync-refresh`, `content-type` — has the
  same name and semantics here. Existing components keep working
  unchanged.
- **Event listener id.** The `scim` event listener registered with
  Keycloak has the same id; realms that already have it in
  `eventsListeners` don't need reconfiguration.
- **JPA Entity Provider id.** `scim-resource` (used internally for
  ID mapping persistence) is the same.
- **Java / Keycloak versions.** Both forks target Keycloak 25.x,
  Java 21. We additionally claim 26.x compatibility (CI matrix
  verifies).

## What's added

These are net-new in this fork. Each is opt-in or backward-compatible
default-off, so nothing changes for an existing deployment unless you
choose to use them.

- **`scim-ldap-sync` LDAP mapper.** Closes upstream's gap where
  LDAP-federated users were silent to outbound SCIM. Attach it to
  each LDAP federation provider whose users should propagate.
  Without attaching it, behavior is identical to upstream. See
  [`docs/ldap-federation-support.md`](ldap-federation-support.md).
- **Reconciler** for LDAP-deletion propagation. Opt-in via
  `reconciler-enabled=true` on the SCIM provider component (default
  off). See [`docs/configuration.md`](configuration.md) for config
  knobs.
- **`scim-reconcile/*` REST endpoint** for operator-driven
  reconciliation passes and metrics inspection.
- **OCI image** for K8s ImageVolume mounting (`ghcr.io/pelotech/keycloak-scim`).

## New config knobs (all default-off / backward-compatible)

| Knob | Default | Effect if unchanged |
| --- | --- | --- |
| `user-patchOp` | false | Identical to upstream (PUT for user updates). |
| `group-patchOp` | false | Identical to upstream (PUT for group updates, with automatic 405→PATCH fallback). |
| `username-source` | `username` | Identical to upstream (uses Keycloak username for SCIM userName). |
| `group-filter` | empty | Identical to upstream (no filter; all groups propagated). |
| `reconciler-enabled` | false | No reconciler activity. |
| `reconciler-interval-seconds` | 86400 | Ignored when reconciler is disabled. |
| `reconciler-stale-threshold-seconds` | 172800 | Ignored when reconciler is disabled. |

## Behavioral differences worth knowing

A few production-relevant differences from upstream that aren't
config-toggleable:

- **Async SCIM dispatch on the LDAP-import path.** Per-user SCIM
  HTTP no longer blocks the LDAP federation sync thread. With 8
  worker threads, full-sync throughput is roughly 8–10× higher
  (~245 users/sec vs ~22 users/sec). Documented in
  [`docs/performance.md`](performance.md).

  The trade-off: SCIM operations execute *after* the caller's
  Keycloak transaction commits. If your operations rely on the
  SCIM POST having completed before the user-import REST call
  returns, you'll see a small delay (workers fire on the next
  scheduler tick). For most deployments this is invisible.

- **Retry policy widened.** Upstream retried only on
  `ProcessingException` (JAX-RS layer). We additionally retry on
  `IORuntimeException` (the SCIM SDK's own network-error wrapper).
  Without this, the retry policy was effectively dead code for
  most network failures. 5xx responses still don't trigger retry
  by design — they're not network failures.

- **Admin-DELETE event handling fixed.** Upstream's
  `ScimEventListenerProvider#onEvent` for `OperationType.DELETE`
  called `getUser(userId)` after the admin commit and dereferenced
  the resulting null on `isEmailVerified()`, swallowing the NPE.
  Net result: admin user deletes never propagated to SCIM. We use
  `event.getUserId()` directly. If you've worked around this
  upstream by avoiding admin DELETEs, that workaround is no longer
  needed.

- **Group / role mapper null-return fix.** Upstream's mapper
  returned `null` from `getGroupMembers` and `getRoleMembers`,
  which NPE's Keycloak's `LDAPStorageProvider.getGroupMembersStream`
  on any realm with the mapper attached and a group operation
  performed. We return `List.of()`. If you had any group operations
  failing on realms with the SCIM mapper attached, this is why.

## Switchover procedure

1. **Take a backup** of the SCIM_RESOURCE table and your realm
   export. Standard precaution; the fork doesn't migrate schemas.
2. **Stop Keycloak** (or take the affected pod out of rotation).
3. **Replace the provider JAR.** If you mount via files,
   replace `/opt/keycloak/providers/keycloak-scim-*-all.jar` with
   the new one. If you build the Keycloak image, point the
   ADD/COPY at the new artifact. If you're moving to the OCI
   image approach, follow the K8s ImageVolume example in
   the [README](../README.md).
4. **Start Keycloak.** No schema migration runs; existing config
   is read as-is.
5. **Verify**: log into the admin console, check that your SCIM
   provider component is still present with all its config, do a
   small admin user create + check the SCIM sink received the
   POST as expected. The
   [`/scim-reconcile/metrics`](configuration.md#scim-reconcile-rest-endpoint)
   endpoint is also useful for at-a-glance "is this thing working"
   confirmation.
6. **Optional** — if you're running LDAP User Federation and want
   to fix the federation-import gap: attach the `scim-ldap-sync`
   mapper to each LDAP provider. After that, federated user
   imports propagate to SCIM (lazy import on first lookup,
   periodic sync, explicit triggerFullSync).
7. **Optional** — if you've been bitten by the upstream
   LDAP-deletion gap (Keycloak issue #35235), enable the
   reconciler on the SCIM provider component:
   `reconciler-enabled=true`. Defaults are sane (24h interval,
   48h stale threshold).

## Rollback

If something goes wrong, rollback is symmetric: replace the JAR
with the upstream one, restart, restore from backup if any data
moved unexpectedly. The fork doesn't make any one-way schema
changes.
