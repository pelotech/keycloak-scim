# Migrating from `mitodl/keycloak-scim`

This is a one-page guide for switching from upstream
`mitodl/keycloak-scim` to this fork. The database schema and the SCIM
provider component settings are binary-compatible. A switchover is
mostly a matter of replacing the JAR (or image) and verifying
behavior. This fork adds new features. It does not change anything
you already rely on.

## What's compatible

- **Database schema.** The `SCIM_RESOURCE` table, provided through
  `JpaEntityProvider` and a Liquibase changelog, is unchanged.
  Existing local-to-remote SCIM ID mappings carry over without
  migration.
- **SCIM provider component settings.** Every setting upstream
  exposes has the same name and behavior here: `endpoint`,
  `auth-mode`, `auth-user`, `auth-pass`, `propagation-user`,
  `propagation-group`, `sync-import`, `sync-import-action`,
  `sync-refresh`, `content-type`. Existing components keep working
  unchanged.
- **Event listener id.** The `scim` event listener registered with
  Keycloak keeps the same id. A realm that already lists it in
  `eventsListeners` needs no reconfiguration.
- **JPA Entity Provider id.** `scim-resource`, used internally for ID
  mapping persistence, is the same.
- **Java / Keycloak versions.** Both forks target Keycloak 25.x, on
  Java 21. This fork's CI matrix runs the full integration suite
  against Keycloak 25.0.6 and 26.6.2 on every push. It verifies 26.x
  compatibility instead of merely claiming it.

## What's added

These are net-new in this fork. Each is opt-in or backward-compatible
default-off, so nothing changes for an existing deployment unless you
choose to use them.

- **`scim-ldap-sync` LDAP mapper.** Closes a gap in upstream, where
  LDAP-federated users never reached outbound SCIM. Attach it to
  each LDAP federation provider whose users should propagate.
  Without it, behavior matches upstream. See
  [`docs/ldap-federation-support.md`](ldap-federation-support.md).
- **Reconciler** for LDAP-deletion propagation. Opt in with
  `reconciler-enabled=true` on the SCIM provider component. It is off
  by default. See [`docs/configuration.md`](configuration.md) for its
  settings.
- **`scim-reconcile/*` REST endpoint**, for operator-driven
  reconciliation passes and metrics inspection.
- **OCI image** for Kubernetes ImageVolume mounting
  (`ghcr.io/pelotech/keycloak-scim`).

## New settings (all default-off / backward-compatible)

| Setting | Default | Effect if unchanged |
| --- | --- | --- |
| `user-patchOp` | false | Identical to upstream (PUT for user updates). |
| `group-patchOp` | false | Identical to upstream (PUT for group updates, with automatic 405→PATCH fallback). |
| `username-source` | `username` | Identical to upstream (uses Keycloak username for SCIM userName). |
| `reconciler-enabled` | false | No reconciler activity. |
| `reconciler-interval-seconds` | 86400 | Ignored when reconciler is disabled. |
| `reconciler-stale-threshold-seconds` | 172800 | Ignored when reconciler is disabled. |

## Behavioral differences worth knowing

These differences from upstream matter in production. None of them is
a setting you can toggle:

- **Async SCIM dispatch on the LDAP-import path.** Per-user SCIM HTTP
  calls no longer block the LDAP federation sync thread. With 8
  worker threads, full-sync throughput is about 8 times higher: 186
  users per second, against 22 without async dispatch, measured at
  1,000 users. See
  [`docs/performance.md`](performance.md).

  The trade-off: SCIM operations run *after* the caller's Keycloak
  transaction commits. If your workflow depends on the SCIM POST
  finishing before the user-import REST call returns, expect a small
  delay, since workers fire on the next scheduler tick. Most
  deployments never notice this.

- **Retry policy widened.** Upstream retried only on
  `ProcessingException`, a JAX-RS-layer exception. This fork also
  retries on `IORuntimeException`, the SCIM SDK's own network-error
  wrapper. Without it, the retry policy was effectively dead code for
  most real-world network failures. The plugin also retries a 429 or
  any 5xx response from the SCIM server. See
  [`docs/configuration.md`](configuration.md) for the full retry
  policy.

- **Admin-DELETE event handling fixed.** In upstream,
  `ScimEventListenerProvider#onEvent` for `OperationType.DELETE`
  called `getUser(userId)` after the admin commit, then called
  `isEmailVerified()` on the resulting null and swallowed the NPE. As
  a result, admin user deletes never propagated to SCIM. This fork
  uses `event.getUserId()` directly instead. If you worked around
  this upstream by avoiding admin DELETEs, you no longer need that
  workaround.

- **Group / role mapper null-return fix.** Upstream's mapper returned
  `null` from `getGroupMembers` and `getRoleMembers`. This threw a
  `NullPointerException` in Keycloak's
  `LDAPStorageProvider.getGroupMembersStream`, on any realm with the
  mapper attached, whenever a group operation ran. This fork returns
  `List.of()` instead. This is why group operations may have failed
  on realms with the SCIM mapper attached.

## Switchover procedure

1. **Take a backup** of the `SCIM_RESOURCE` table and your realm
   export. This is a standard precaution. The fork does not migrate
   schemas.
2. **Stop Keycloak**, or take the affected pod out of rotation.
3. **Replace the provider JAR.** If you mount it as a file, replace
   `/opt/keycloak/providers/keycloak-scim-*-all.jar` with the new
   one. If you build the Keycloak image, point `ADD`/`COPY` at the
   new artifact. If you are moving to the OCI image approach, follow
   the Kubernetes ImageVolume example in the [README](../README.md).
4. **Start Keycloak.** No schema migration runs. Existing
   configuration is read as-is.
5. **Verify.** Log into the admin console and check that your SCIM
   provider component is still present, with all its settings.
   Create an admin user and confirm the SCIM server received the
   `POST`. The
   [`/scim-reconcile/metrics`](configuration.md#scim-reconcile-rest-endpoint)
   endpoint also gives an at-a-glance check that propagation is
   working.
6. **Optional.** If you run LDAP User Federation and want to fix the
   federation-import gap, attach the `scim-ldap-sync` mapper to each
   LDAP provider. After that, federated user imports propagate to
   SCIM: on lazy import at first lookup, on periodic sync, and on an
   explicit `triggerFullSync`.
7. **Optional.** If you have hit the upstream LDAP-deletion gap
   (Keycloak issue #35235), enable the reconciler on the SCIM
   provider component with `reconciler-enabled=true`. The defaults
   are reasonable: a 24-hour interval and a 48-hour stale threshold.

## Rollback

If something goes wrong, rollback is symmetric. Replace the JAR with
the upstream one, restart, and restore from backup if any data moved
unexpectedly. The fork makes no one-way schema changes.
