# Roadmap

This is the post-1.0 backlog for the fork. 1.0.0 shipped on
2026-05-26, and the 1.0.x line is current, with 1.0.2 as the latest
release (2026-06-07). release-please now drives versioning from
conventional commits, so this document is forward-looking only. For
what has landed, see [`CHANGELOG.md`](../CHANGELOG.md). For the
release flow, see [`docs/releasing.md`](releasing.md).

Items are grouped by area. None of them block normal operation of the
1.0.x line. They are known gaps or refinements.

## Group propagation overhaul

The 1.0.0 plumbing for group membership changes is correct, but
inefficient at scale. Also, groups federated from LDAP do not
propagate at all. These are the same problem space. A coherent
solution touches both.

- **Incremental PATCH delta.** _Done._ `GROUP_MEMBERSHIP` events now
  dispatch `ScimClient.patchGroupMembership`. When
  `group-patchOp=true`, this sends a single-member ADD or REMOVE
  PATCH, instead of re-sending the full member list. So a user
  joining a 10k-member group produces a one-member request. REMOVE
  uses the RFC 7644 filter path `members[value eq "..."]`.
  `group-patchOp=false` deployments fall back to the existing full
  `replace`. Verified by `GroupMembershipPatchTest` (wire shape) and
  `ScimGroupPropagationIT` (end-to-end add and remove). A group
  **update** (rename or sync-refresh) on `group-patchOp=true` now
  also PATCHes only the group's own attributes (`displayName`,
  `externalId`). `toPatchBuilder` no longer re-asserts the member
  list, which used to mean a per-member external-ID lookup plus a
  whole-list re-send on every rename. Membership is now maintained by
  the delta PATCHes. Verified by
  `GroupMembershipPatchTest.groupUpdatePatchCarriesOnlyAttributesNotMembers`.
  (The `group-patchOp=false` full PUT still must send the whole
  resource. It cannot be a partial update.)
- **LDAP-federated group membership.** _Done._ Federated users'
  current group memberships now propagate to SCIM, through
  `ScimLdapStorageMapper.onImportUserFromLDAP` calling
  `ScimClient.ensureGroupMembership`. For each group the imported
  user belongs to, the mapper first ensures the SCIM group exists (an
  idempotent create, skipped when `group-patchOp=false` because the
  `replace` fallback already covers it), then adds the member with a
  single-member delta PATCH. Additions are delta-driven; see
  "membership removal" below, only newly added groups get a PATCH.
  This requires the SCIM provider component to enable both
  `propagation-user=true` and `propagation-group=true`. Membership
  resolution looks up the user's SCIM mapping under the same
  component ID, so a group-only component cannot resolve members.
  Verified by `EnsureGroupMembershipTest` (unit) and
  `ScimLdapGroupMembershipIT` (integration).
- **LDAP-federated membership removal.** _Done._ A user dropped from
  an LDAP group fires no `GROUP_MEMBERSHIP` event, so removal rides
  the same import hook. On each import, the `SCOPE_GROUP` worker
  compares the user's current groups against a per-component record
  of what it last propagated. It sends a single-member REMOVE PATCH
  for each group the user has left, and a single-member ADD for each
  newly joined group. **Both directions are delta-driven and
  success-tracked**, so a steady-state re-import, where a full sync
  re-fires the hook for every unchanged user, sends zero SCIM
  PATCHes. This removes the prior per-sync re-assertion (measured at
  +1 ADD per member per sync). A failed or skipped ADD or REMOVE is
  left unrecorded or kept, and retried on the next import, the
  lazy-import-lag self-heal. The bookkeeping is stored through
  `UserFederatedStorageProvider` (key
  `scim-propagated-groups-<componentId>`), **not** as a user
  attribute. The diff runs in the post-commit async worker, on a
  re-fetched federated user, whose attributes are read-only under
  `editMode=READ_ONLY` (the common config). Federated storage is the
  JPA-backed local store Keycloak keeps for federated users, and is
  writable there. The member-presence reconciler reaps the now-empty
  group. Verified by `ScimLdapStorageMapperTest` (diff, failed-removal
  retry, and idempotence units) and `ScimLdapGroupMembershipIT`
  (end-to-end remove and loop-safety, run under `READ_ONLY`
  federation).
- **Group rename and delete for federated groups.** _Done
  (delete-based)._ Federated group deletes and renames now propagate
  through a member-presence pass in the reconciler. A mapped group
  with zero members, or a local model that is gone, gets a SCIM
  DELETE. Rename propagates as delete-old-and-create-new: the renamed
  group provisions fresh with a new SCIM ID, and the old, now-
  memberless group is deleted on the next reconcile. Accepted
  limitations: rename produces a new SCIM ID, and SCIM briefly holds
  both the old and new group, for the window between the rename sync
  and the next reconcile pass. Group reconciliation uses the existing
  `reconciler-enabled` flag. No group-specific threshold is needed;
  the `reconciler-stale-threshold-seconds` setting and its
  `> fullSyncPeriod` check apply only to the user phase. A
  provisioned group that legitimately loses all its LDAP members is
  also deleted, and re-provisioned when it gains a member again.
  Verified by `ScimGroupReconcileIT` (delete, rename-as-recreate,
  live-group-not-deleted).

## Auth-mode follow-ups

The OAuth 2.0 `CLIENT_CREDENTIALS` mode, shipped in 1.0.0,
deliberately deferred the following. Each item was considered, and
deferred until a concrete identity provider needs it.

- **OIDC discovery** (`.well-known/openid-configuration`). Today the
  operator supplies the token endpoint URL directly.
- **`client_secret_post`** client authentication. Only
  `client_secret_basic` is supported today.
- **`private_key_jwt` or mTLS bearer** (RFC 8705).
- **`audience` request parameter.** Keycloak does not honor it on the
  client_credentials request body anyway. Configure audience through
  a token mapper on the client instead.
- **Proactive refresh ahead of expiry.** Lazy refresh, with a
  30-second skew, is in place today. Proactive refresh would use a
  thread for about 1 to 2% throughput at the expiry boundary.

## Reconciler refinements

- **Bloom-filter witness.** Designed in
  [`docs/ldap-federation-support.md`](ldap-federation-support.md), but
  not implemented. This is an extra safeguard against silent
  timestamp-write failures.
- **Phase 1 parallelization.** At 10k mappings, the sequential mapping
  walk plus `getUserById` takes about 10 seconds. This matters only
  at extreme reconciliation volumes. A typical case, deleting a few
  hundred stale users, does not come close to that.

## Resilience

- **SCIM-endpoint 5xx/429 retry.** _Done._ The SCIM SDK returns a 5xx
  response as a `ServerResponse` with `isSuccess()=false`, instead of
  throwing. So resilience4j's exception-based retry did not fire. Now
  `ScimClient`'s `RetryConfig` adds a `retryOnResult(...)` predicate
  (`isRetryableStatus`: 429 and any 5xx), covering create, replace,
  and delete. Verified by
  `ScimResilienceIT#serverErrorIsRetriedAndEventuallySucceeds` and
  `ScimClientRetryTest`.
- **Token-endpoint 5xx/429 retry.** _Done._ `HttpTokenMinter` mints
  tokens outside the SCIM retry path, and used to throw a bare
  `RuntimeException` on any failure. Now
  `OAuthClientCredentialsTokenSource` wraps the mint in a resilience4j
  `Retry` (`maxAttempts(3)`, exponential backoff). It retries
  transient failures: transport faults, 429, and any 5xx, through
  `isRetryableMintFailure`, which reuses
  `ScimClient.isRetryableStatus`. It never retries 4xx config errors.
  The smaller retry budget reflects that mints run under the
  per-component lock. Verified by
  `ScimOidcAuthIT#tokenEndpointTransientErrorIsRetried` and
  `OAuthClientCredentialsTokenSourceRetryTest`.

## SCIM protocol features

- **SCIM `/Bulk` batching.** Not implemented. Today every resource
  change produces one HTTP request. Bulk would let a full sync
  combine N requests into one.

## Performance / observability

- **Redundant per-sync membership re-assertions (federated re-import
  loop).** _Done/Fixed._ `GroupAdapter.apply(GroupModel)` used to list
  a federated group's members during membership provisioning, through
  `getGroupMembersStream`. On a federated `groupOfNames`, this
  re-imported every member, which re-fired `onImportUserFromLDAP`,
  which re-dispatched, causing unbounded recursion (measured: 2,776
  invocations and 1,388 member-add PATCHes for a 2-member group, per
  sync). The fix provisions groups without members:
  `GroupAdapter.applyForProvisioning` sets only the ID, `displayName`,
  and `scim-skip`. `ensureGroupMembership` now calls this instead of
  the member-enumerating `create`/`apply`. Re-measured: 2 invocations
  and about 1 PATCH per 2-member sync, with zero re-import recursion
  on `scim-dispatch` threads, on the default `group-patchOp=true`
  path. A separate *steady-state* per-sync re-assertion remained
  after the loop fix: additions re-asserted one ADD per member per
  group on every sync (measured at +1 ADD per member per sync, since
  Keycloak re-fires `onImportUserFromLDAP` for unchanged users). This
  is **also now eliminated**: additions are delta-driven against the
  federated-storage propagated-group set, so a no-change re-import
  sends zero PATCHes
  (`ScimLdapStorageMapperTest.noMembershipChangeEmitsNoScimCalls`,
  `ScimLdapGroupMembershipIT.unchangedResyncSendsNoRedundantMemberPatches`).
  **`group-patchOp=false` residual: handled.** Inspection confirmed
  that on that non-default path, both add and remove fall back to a
  full `replace` (`GroupAdapter.apply(GroupModel)` calling
  `getGroupMembersStream`), which re-imports the federated group's
  members, the same loop. A full member-list PUT *inherently* needs
  the member list. The member-less fix used for `group-patchOp=true`
  does not apply here, and deriving the list without
  `getGroupMembersStream` costs O(mapped users) per group. So
  federated group-membership propagation is **gated on
  `group-patchOp=true`**: the `SCOPE_GROUP` worker does nothing when
  `ScimClient.isGroupMembershipDeltaEnabled()` is false, so the loop
  cannot happen. The admin `GROUP_MEMBERSHIP`-event path shares the
  same `replace` fallback, so `patchGroupMembership` also skips it for
  a **federated** group when `group-patchOp=false` (detected through
  `StorageId.isLocalStorage(group.getId())`). Local groups still
  `replace` as before, since their members are already local and do
  not re-import. The rare cost is that with `group-patchOp=false`, a
  federated group's memberships do not propagate. This is documented
  in [`docs/ldap-federation-support.md`](ldap-federation-support.md).
  Verified by
  `ScimLdapStorageMapperTest.skipsEntirelyWhenGroupPatchOpDisabled` and
  `EnsureGroupMembershipTest.groupPatchOpOff_federatedGroup_skipsReplace`
  (local groups still replace, per
  `groupPatchOpOff_localGroup_stillReplaces`).
- **Concurrent group provisioning double-POST.** _Fixed
  (cluster-safe)._ When several members of a not-yet-provisioned group
  were imported at the same time, each worker, in its own transaction,
  queried the mapping, found none, and POSTed `/Groups` before any of
  them saved. This was a check-then-act race. Against a non-deduping
  server, it creates duplicate SCIM groups and a duplicate mapping: a
  primary-key collision that rolls back the worker, or a
  `NonUniqueResultException` on a later add. This surfaced sharply
  once delta-driven additions removed the per-sync re-assertion that
  had *masked* the resulting non-convergence. **Fix:**
  `ScimClient.provisionGroupForMembership` does a lock-free pre-check.
  Then, only when the mapping is absent, it takes a **pessimistic
  database lock**: `SELECT ... FOR UPDATE` on a single seeded row, in
  a dedicated `SCIM_PROVISION_LOCK` table (`ScimProvisionLock`),
  through the worker's own `EntityManager`
  (`LockModeType.PESSIMISTIC_WRITE`). It then re-checks, and POSTs and
  saves the mapping in that same transaction. The lock is held until
  the transaction commits, so it serializes provisioners **across
  cluster nodes**. The next worker to acquire it sees the winner's
  committed mapping, and skips. This gives exactly one POST and one
  mapping, regardless of the server's dedup behavior. A single lock
  row, rather than one per group, means a worker holds at most one
  provisioning lock, so there is no lock-ordering deadlock. The cost
  is that concurrent *first-time* provisioning serializes. This is
  bounded: it lasts only until each distinct group is provisioned
  once, and steady-state or already-mapped groups never lock. A
  nested transaction was tried first and rejected: Keycloak's Quarkus
  runtime does not give a freshly nested session a JPA
  `EntityManagerFactory` from an async worker thread, which caused an
  NPE. Verified by
  `ScimLdapGroupMembershipIT.concurrentFirstProvisioningPostsGroupExactlyOnce`
  (exactly one `POST /Groups` under a non-deduping always-201 stub,
  which holds only if the database lock serialized the provisioners
  and the winner's commit-on-release made the mapping visible to the
  loser).
- **Perf-rig sibling container.** The Testcontainers, Keycloak, and
  WireMock setup routes SCIM traffic through an SSH tunnel
  (`host.testcontainers.internal`), adding about 25 to 30 ms per
  request to the `ScimClientMetrics` numbers in
  [`docs/performance.md`](performance.md). Moving WireMock to a
  sibling container on Keycloak's Docker network would make the
  published numbers reflect real network cost. This does not affect
  production.

## Test gaps (1.x scope)

Coverage that did not make the bar for 1.0.0, but is reasonable to
add:

- Persistence across Keycloak restart
- Concurrent admin operations against the same component
- LDAP-side auth failures
- TLS certificate validation paths
- Long usernames / special-character payloads

## Code quality

- **Split `ScimClient` further.** The auth and header concern was
  extracted into `ScimAuthHeaders` in 1.0.2 ([#25]). The rest still
  splits naturally along create, replace, delete, and
  retry/failure-handling. This does not block anything, but the file
  is still large.

  _Done in 1.0.2:_ adapter instantiation no longer uses reflection.
  `getAdapter(Class)` was replaced by the `AdapterFactory` functional
  interface, invoked through constructor references ([#25]).

## Documentation

- **`CONTRIBUTING.md`.** Code-style notes, branch-naming conventions,
  TDD expectations, and commit-message format.

[#25]: https://github.com/pelotech/keycloak-scim/issues/25
