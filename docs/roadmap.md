# Roadmap

Post-1.0 backlog for the fork. 1.0.0 shipped on 2026-05-26 and the
1.0.x line is current (latest 1.0.2, 2026-06-07); release-please now
drives versioning from conventional commits, so this doc is
forward-looking only — for what landed, see
[`CHANGELOG.md`](../CHANGELOG.md); for the release flow, see
[`docs/releasing.md`](releasing.md).

Items are grouped by area. None of them block normal operation of
the 1.0.x line — they're known gaps or refinements.

## Group propagation overhaul

The 1.0.0 plumbing for group membership changes is correct but
inefficient at scale, and federated-from-LDAP groups don't propagate
at all. These two are the same problem space — a coherent solution
touches both.

- **Incremental PATCH delta.** _Done._ `GROUP_MEMBERSHIP` events now
  dispatch `ScimClient.patchGroupMembership`, which (when
  `group-patchOp=true`) sends a single-member ADD/REMOVE PATCH instead
  of re-sending the full member list — a user joining a 10k-member group
  produces a one-member request. REMOVE uses the RFC 7644 filter path
  `members[value eq "..."]`. `group-patchOp=false` deployments fall back
  to the existing full `replace`. Verified by `GroupMembershipPatchTest`
  (wire shape) and `ScimGroupPropagationIT` (end-to-end add/remove).
  Note: this covers membership *changes*; a full group `replace` (name
  edits, sync-refresh) still sends the whole list via `toPatchBuilder`.
- **LDAP-federated group membership.** _Done._ Federated users'
  current group memberships now propagate to SCIM via
  `ScimLdapStorageMapper.onImportUserFromLDAP` →
  `ScimClient.ensureGroupMembership`: for each group the imported
  user belongs to, the mapper first ensures the SCIM group exists
  (idempotent create; skipped when `group-patchOp=false` because the
  `replace` fallback already covers it), then adds the member via a
  single-member delta PATCH. Additions are delta-driven (see
  "membership removal" below — only newly-added groups PATCH).
  Requires the SCIM provider component
  to enable both `propagation-user=true` and `propagation-group=true`
  — membership resolution looks up the user's SCIM mapping under the
  same component id, so a group-only component cannot resolve
  members. Verified by `EnsureGroupMembershipTest` (unit) and
  `ScimLdapGroupMembershipIT` (integration).
- **LDAP-federated membership removal.** _Done._ A user dropped from
  an LDAP group fires no `GROUP_MEMBERSHIP` event, so removal rides the
  same import hook: on each import the `SCOPE_GROUP` worker diffs the
  user's current groups against a per-component record of what it last
  propagated and sends a single-member REMOVE PATCH for each group the
  user has left and a single-member ADD for each newly-joined group.
  **Both directions are delta-driven and success-tracked**, so a
  steady-state re-import (a full sync re-fires the hook for every
  unchanged user) sends zero SCIM PATCHes — eliminating the prior
  per-sync re-assertion (measured +1 ADD/member/sync). A failed/skipped
  ADD or REMOVE is left unrecorded/retained and retried on the next
  import (the lazy-import-lag self-heal). The bookkeeping is stored via
  `UserFederatedStorageProvider` (key `scim-propagated-groups-<componentId>`),
  **not** as a user attribute: the diff runs in the post-commit async
  worker on a re-fetched federated user, whose attributes are read-only
  under `editMode=READ_ONLY` (the common config) — federated storage is
  the JPA-backed local store Keycloak keeps for federated users and is
  writable there. The now-empty group is reaped by the member-presence
  reconciler. Verified by `ScimLdapStorageMapperTest` (diff / failed-
  removal retry / idempotence units) and `ScimLdapGroupMembershipIT`
  (end-to-end remove + loop-safety, run under `READ_ONLY` federation).
- **Group rename and delete for federated groups.** _Done
  (delete-based)._ Federated group deletes and renames now propagate
  via a member-presence pass in the reconciler: a mapped group with
  zero members (or a gone local model) receives a SCIM DELETE.
  Rename propagates as delete-old + create-new — the renamed group
  provisions fresh with a new SCIM id; the old, now-memberless group
  is deleted on the next reconcile. Accepted limitations: rename
  yields a new SCIM id, and SCIM transiently holds both the old and
  new group for the window between the rename sync and the next
  reconcile pass. Group reconciliation rides the existing
  `reconciler-enabled` flag; no group-specific threshold is needed
  (the `reconciler-stale-threshold-seconds` and its
  `> fullSyncPeriod` validation apply to the user phase only). A
  provisioned group that legitimately loses all its LDAP members is
  also deleted (re-provisioned when it regains a member). Verified by
  `ScimGroupReconcileIT` (delete, rename-as-recreate,
  live-group-not-deleted).

## Auth-mode follow-ups

The OAuth 2.0 `CLIENT_CREDENTIALS` mode shipped in 1.0.0 deliberately
deferred the following. Each was considered and deferred until there's
a concrete IdP that requires it.

- **OIDC discovery** (`.well-known/openid-configuration`) — operator
  supplies the token endpoint URL directly today.
- **`client_secret_post`** client authentication — only
  `client_secret_basic` is supported.
- **`private_key_jwt` / mTLS bearer** (RFC 8705).
- **`audience` request parameter** — Keycloak doesn't honor it on the
  client_credentials request body anyway; configure via a token mapper
  on the client.
- **Proactive refresh-ahead-of-expiry** — lazy refresh with 30s skew is
  in place; proactive would burn a thread for ~1–2% throughput at the
  expiry boundary.

## Reconciler refinements

- **Bloom-filter witness.** Designed in
  [`docs/ldap-federation-support.md`](ldap-federation-support.md) but
  not implemented. Belt-and-suspenders against silent
  timestamp-write failures.
- **Phase 1 parallelization.** At 10k mappings the sequential mapping
  walk + `getUserById` takes ~10s. Only matters at extreme
  reconciliation volumes; typical "delete a few hundred stale users"
  doesn't approach that.

## Resilience

- **SCIM-endpoint 5xx/429 retry.** _Done._ The SCIM SDK returns 5xx as
  `ServerResponse` with `isSuccess()=false` rather than throwing, so
  resilience4j's exception-based retry didn't fire. Now `ScimClient`'s
  `RetryConfig` adds a `retryOnResult(...)` predicate
  (`isRetryableStatus`: 429 + any 5xx) covering create/replace/delete.
  Verified by
  `ScimResilienceIT#serverErrorIsRetriedAndEventuallySucceeds` and
  `ScimClientRetryTest`.
- **Token-endpoint 5xx/429 retry.** _Done._ `HttpTokenMinter` mints
  tokens outside the SCIM retry path and threw a bare `RuntimeException`
  on any failure. Now `OAuthClientCredentialsTokenSource` wraps the mint
  in a resilience4j `Retry` (`maxAttempts(3)`, exponential backoff) that
  retries transient failures — transport faults + 429 + any 5xx, via
  `isRetryableMintFailure` reusing `ScimClient.isRetryableStatus` — and
  never 4xx config errors. The smaller budget reflects that mints run
  under the per-component lock. Verified by
  `ScimOidcAuthIT#tokenEndpointTransientErrorIsRetried` and
  `OAuthClientCredentialsTokenSourceRetryTest`.

## SCIM protocol features

- **SCIM `/Bulk` batching.** Not implemented. Today every resource
  change produces an individual HTTP request. Bulk would let a full
  sync collapse N requests into one.

## Performance / observability

- **Redundant per-sync membership re-assertions (federated re-import
  loop).** _Done/Fixed._ `GroupAdapter.apply(GroupModel)` enumerated
  a federated group's members during membership provisioning via
  `getGroupMembersStream`; on a federated `groupOfNames` this
  re-imported every member, re-firing `onImportUserFromLDAP` →
  re-dispatch → unbounded recursion (measured: 2,776 invocations /
  1,388 member-add PATCHes for a 2-member group per sync). Fixed by
  provisioning groups member-lessly: `GroupAdapter.applyForProvisioning`
  sets id + displayName + `scim-skip` only, and `ensureGroupMembership`
  now calls it instead of the member-enumerating `create`/`apply`.
  Re-measured: 2 invocations / ~1 PATCH per 2-member sync, zero
  re-import recursion on `scim-dispatch` threads (on the default
  `group-patchOp=true` path). The separate *steady-state* per-sync
  re-assertion that remained after the loop fix — additions re-asserting
  one ADD per member per group on every sync (measured +1 ADD/member/sync,
  since Keycloak re-fires `onImportUserFromLDAP` for unchanged users) — is
  **also now eliminated**: additions are delta-driven against the
  federated-storage propagated-group set, so a no-change re-import sends
  zero PATCHes (`ScimLdapStorageMapperTest.noMembershipChangeEmitsNoScimCalls`,
  `ScimLdapGroupMembershipIT.unchangedResyncSendsNoRedundantMemberPatches`).
  **`group-patchOp=false` residual — handled.** Confirmed by inspection
  that on that non-default path both add and remove fall back to a full
  `replace` (`GroupAdapter.apply(GroupModel)` → `getGroupMembersStream`),
  which re-imports the federated group's members — the same loop. Because
  a full member-list PUT *inherently* needs the member list (the
  member-less fix used for `group-patchOp=true` does not apply, and
  deriving the list without `getGroupMembersStream` is O(mapped users) per
  group), federated group-membership propagation is **gated on
  `group-patchOp=true`**: the `SCOPE_GROUP` worker no-ops when
  `ScimClient.isGroupMembershipDeltaEnabled()` is false, so the loop
  cannot occur. The (rare) cost is that on `group-patchOp=false` a
  federated user's group memberships are not propagated — documented in
  [`docs/ldap-federation-support.md`](ldap-federation-support.md). Verified
  by `ScimLdapStorageMapperTest.skipsEntirelyWhenGroupPatchOpDisabled`.
- **Concurrent group provisioning double-POST.** _Fixed (cluster-safe)._
  When several members of a not-yet-provisioned group were imported
  concurrently, each worker (its own transaction) queried the mapping,
  found none, and POSTed `/Groups` before any saved — a check-then-act
  race that, against a non-deduping server, creates duplicate SCIM groups
  and a duplicate mapping (a PK collision that rolls back the worker, or a
  `NonUniqueResultException` on a later add). Surfaced sharply once
  delta-driven additions removed the per-sync re-assertion that had
  *masked* the resulting non-convergence. **Fix:**
  `ScimClient.provisionGroupForMembership` does a lock-free pre-check, then
  (only when the mapping is absent) takes a **pessimistic DB lock** —
  `SELECT ... FOR UPDATE` on a single seeded row in a dedicated
  `SCIM_PROVISION_LOCK` table (`ScimProvisionLock`), via the worker's own
  `EntityManager` (`LockModeType.PESSIMISTIC_WRITE`) — re-checks, then POSTs
  and saves the mapping in that same transaction. The lock is held until
  the transaction commits, so it serializes provisioners **across cluster
  nodes** and the next worker to acquire it sees the winner's committed
  mapping and skips — exactly one POST, one mapping, regardless of server
  dedup behavior. A single lock row (rather than per-group/striped) means a
  worker holds at most one provisioning lock, so there is no lock-ordering
  deadlock; the cost is that concurrent *first-time* provisioning
  serializes (bounded — only until each distinct group is provisioned once;
  steady state and already-mapped groups never lock). A nested
  transaction was tried first and rejected — Keycloak's Quarkus runtime
  does not give a freshly-nested session a JPA `EntityManagerFactory` from
  an async worker thread (NPE). Verified by
  `ScimLdapGroupMembershipIT.concurrentFirstProvisioningPostsGroupExactlyOnce`
  (exactly one `POST /Groups` under a non-deduping always-201 stub — which
  holds only if the DB lock serialized the provisioners and the winner's
  commit-on-release made the mapping visible to the loser).
- **Perf-rig sibling container.** The Testcontainers + Keycloak +
  WireMock setup routes SCIM traffic through an SSH tunnel
  (`host.testcontainers.internal`), adding ~25–30 ms per request to
  the `ScimClientMetrics` numbers in
  [`docs/performance.md`](performance.md). Moving WireMock to a sibling
  container on Keycloak's Docker network would make the published
  numbers reflect real network cost. Doesn't affect production.

## Test gaps (1.x scope)

Coverage that didn't make 1.0.0's bar but is reasonable to add:

- Persistence across Keycloak restart
- Concurrent admin operations against the same component
- LDAP-side auth failures
- TLS certificate validation paths
- Long usernames / special-character payloads

## Code quality

- **Split `ScimClient` further.** The auth/header concern was extracted
  into `ScimAuthHeaders` in 1.0.2 ([#25]); the remaining bulk still
  splits naturally along create/replace/delete + retry/failure-handling.
  Not blocking, but the file is still large.

  _Done in 1.0.2:_ adapter instantiation no longer uses reflection —
  `getAdapter(Class)` was replaced by the `AdapterFactory` functional
  interface invoked via constructor references ([#25]).

## Documentation

- **`CONTRIBUTING.md`** — code-style notes, branch-naming conventions,
  TDD expectations, commit-message format.

[#25]: https://github.com/pelotech/keycloak-scim/issues/25
