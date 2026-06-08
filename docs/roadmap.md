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

- **Incremental PATCH delta.** `group-patchOp=true` switches the group
  update path from PUT to PATCH, but the body still contains the full
  member list (just expressed as a REPLACE operation on `members`).
  For a 10k-member group, every membership change re-sends all 10k
  members. Real fix: send incremental ADD/REMOVE patches based on the
  delta. The mapping table can compute the delta cheaply.
- **LDAP-federated group membership.** No `onImportGroupFromLDAP`
  analogue in the LDAP mapper. Groups federated from LDAP don't
  propagate to SCIM. Architectural addition.

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
- **Token-endpoint 5xx retry** — symmetric with the existing SCIM 5xx
  no-retry gap (see "Resilience" below).

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

- **5xx-not-retried gap.** Pinned by
  `ScimResilienceIT#serverErrorIsNotRetriedGap`. The SCIM SDK returns
  5xx as `ServerResponse` with `isSuccess()=false` rather than throwing,
  so resilience4j's exception-based retry doesn't fire. Widening
  requires `retryOnResult(...)` and should cover both SCIM-endpoint and
  token-endpoint 5xx together.

## SCIM protocol features

- **SCIM `/Bulk` batching.** Not implemented. Today every resource
  change produces an individual HTTP request. Bulk would let a full
  sync collapse N requests into one.

## Performance / observability

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
