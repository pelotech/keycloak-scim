# Missing user mapping recovery

Missing-mapping provisioning is an optional policy for each SCIM destination.
It defaults to **off**; existing configurations keep the missing-mapping error.

## Why a mapping can be missing

The plugin stores a mapping between a Keycloak user and the remote SCIM
resource ID for each destination. A user can exist in Keycloak without that
mapping whenever an update arrives before successful provisioning has
established it, or after the mapping has been lost. This can happen during
ordinary operation as well as a migration:

- **Users predate SCIM provisioning.** When migrating from non-SCIM
  provisioning to SCIM, existing Keycloak users do not automatically have
  mappings. Some may also have existing downstream accounts that need
  reconciliation rather than creation.
- **Initial provisioning fails.** Under the default `rollback-strategy=never`,
  Keycloak retains the user even when the SCIM create fails. A rejected request,
  exhausted retries, or an invalid response can leave the user without a
  mapping. The remote user may already exist if creation succeeded but the
  response was lost or the local mapping could not be persisted.
- **A destination is added or enabled later.** Users that already exist can
  have no mapping for that destination until a refresh or an eligible user
  event provisions them. A mapping for another destination does not apply.
- **A user becomes eligible later.** Removing `scim-skip` or granting the
  configured propagation role can make a previously excluded user eligible.
  If that user was never provisioned to the destination, there is no mapping
  for the next update to use.
- **A disabled user was skipped by refresh.** Full refresh excludes disabled
  users. A user skipped during initial refresh can therefore still be unmapped
  when later enabled. An eligible edit while the user remains disabled can
  also encounter this condition.
- **Mapping data is lost or restored inconsistently.** The Keycloak mapping
  store and remote SCIM resources can become inconsistent after data loss or
  an incomplete restore. The remote user may still exist even though the
  plugin no longer knows its ID.

By default, an UPDATE requires an existing mapping and fails before sending
any request when it is absent. Repeated edits, including enabling the user,
cannot repair that condition. Opting in to recovery lets an eligible update
attempt provisioning instead; recovering an existing remote identity remains
subject to the receiver's behavior and the limits below.

## Enable recovery for a destination

In the Admin Console, open *User Federation → your SCIM provider* and enable
**Provision unmapped users on update**. The component configuration key is
`user-provision-on-update` (boolean, default `false`). For admin REST or realm
configuration, its entry in the component's `config` object is:

```json
{
  "user-provision-on-update": ["true"]
}
```

Set this separately for each destination. With the setting absent or `false`,
an eligible unmapped user update raises `InconsistentScimMappingException`
under the configured failure policy and sends no provisioning request.
Enable it only after reviewing the receiver's uniqueness, repeated-create,
and account-provisioning side effects. It does not verify that an existing
remote account belongs to the incoming identity.

The setting only controls provisioning during an update with a missing local
mapping. Mapped updates (including disabled-user updates), ordinary creates,
full-refresh provisioning, and recreation after a mapped resource returns
404 retain their existing behavior. No mapping data migration is needed.

## Recovery behavior and limits

When recovery is enabled and an eligible user update has no mapping for its
destination, the plugin attempts `POST /Users` through the normal provisioning
path. A successful response with a nonblank remote ID establishes the mapping; later updates
use that ID with the configured PUT/PATCH behavior. Provider settings,
`scim-skip`, and propagation-role exclusions still apply.

This applies to enabled and disabled users. Disabled-user events send
`active: false` without enabling the account. Full refresh continues to
exclude disabled users; role refresh continues to skip unmapped users.
Group mapping errors and existing tombstone/reactivation behavior are unchanged.

A missing local mapping does not prove that the remote user is absent.
Repeated POST is not a standard SCIM upsert: recovery of an existing remote
identity requires the receiver's documented repeated-provisioning contract.
A uniqueness conflict remains a visible failure, without automatic adoption
by username/email or conflict retries. Failures follow the configured
`rollback-strategy`.

Recovery uses existing create retries and concurrency behavior; it does not
guarantee exactly-once creation. If a create succeeds remotely but its response
is lost, or the local transaction fails, another attempt can conflict or create
a duplicate depending on the receiver. A local rollback cannot undo remote
creation. Check the destination's records and plugin logs before manually
reconciling an unresolved identity. Recovery logs include realm, component,
and user IDs. On older versions, `sync-refresh=true` can attempt provisioning
of eligible enabled users, but cannot universally recover lost mappings for
remote identities that already exist.

