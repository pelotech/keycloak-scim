# 1.0.0 Release TODOs

Working checklist for what stands between today and a credible 1.0.0
tag. Items are grouped by category and weighted:

- **B** — blocks 1.0.0
- **W** — would include if it lands cheaply
- **D** — defer to 1.x

Update by checking boxes as items land. The corresponding commit
should reference this file (e.g. "release(todos): mark `B-CI` done").

## Release engineering

- [x] **B-CI** — Add a GitHub Actions workflow that runs
      `./gradlew test integrationTest` on every push to a non-`main`
      branch and on every PR. Today the only workflows are
      `release.yml` (tag-triggered) and `release-please.yml` (main
      pushes); nothing actually validates the test suite on
      contributor changes. ✅ `.github/workflows/ci.yml` added,
      runs across a Keycloak version matrix (25.0.6 + 26.x) — which
      also closes **W-K8s-matrix** below.

- [x] **W-K8s-matrix** — Folded into B-CI. Workflow's strategy
      matrix runs the full test suite on Keycloak 25.0.6 and 26.4.0.
- [ ] **B-Release-dry-run** — Cut a real `Release-As: 1.0.0-rc.0`
      end-to-end, per `docs/releasing.md`:
      - Land an empty commit with the `Release-As` footer
      - Verify release-please opens its PR
      - Merge it; verify the v1.0.0-rc.0 tag appears
      - Verify `release.yml` fires; image lands at
        `ghcr.io/pelotech/keycloak-scim:1.0.0-rc.0`
      - `cosign verify` works
      - SBOM downloadable
      - Mount via ImageVolume in a K8s 1.36+ cluster + smoke-test
        Keycloak loads the providers
      Bugs in the workflows are common; we've never run them.
- [ ] **B-Token** — Set the `RELEASE_PLEASE_TOKEN` repo secret (PAT
      or GitHub App with `contents: write` + `pull-requests: write`).
      Without it, the v* tag release-please pushes won't trigger
      `release.yml` — see the runbook's "If the OCI image didn't
      auto-publish" section. The dry-run above also exercises the
      fallback if this is the chosen posture.

## Documentation

- [x] **B-README** — Rewrite the README. ✅ Replaced the upstream
      prose with a fork-focused README:
      - One-paragraph "what is this" plus the differentiator list
        relative to upstream (LDAP federation, reconciler, perf,
        OCI image, test coverage)
      - Compatibility table (Keycloak 25.x/26.x, Java 21, K8s 1.36+)
      - Quick-start with K8s ImageVolume as the primary path; bare
        JAR + docker-compose as the dev path
      - Three-step Keycloak setup (event listener, SCIM provider,
        LDAP mapper) pointing to `docs/configuration.md`
      - Pointer list for every other doc
      - Status section pointing back at this todo file
      - Apache-2.0 license footer
- [x] **B-Config-reference** — Single operator-facing reference for
      every config knob. ✅ `docs/configuration.md`: covers the SCIM
      provider component (~17 properties grouped by Connection /
      Authentication / Propagation toggles / Sync behavior / PATCH vs
      PUT / User identity mapping / Group filtering / Reconciler), the
      `scim-ldap-sync` LDAP mapper, the `scim` event listener, the
      `/scim-reconcile/*` endpoint, the user attributes the plugin
      reads + writes (`scim-skip`, `ldap-federation-last-seen`), and
      JVM-level system properties (`scim.dispatch.threads`,
      `keycloak.image`). Includes a "what's NOT configurable" section
      so operators know what they're agreeing to.
- [x] **B-License-footer** — README footer said `License AGPL`. The
      `LICENSE` file is Apache 2.0. Pre-existing inconsistency from
      upstream. ✅ Footer corrected to Apache-2.0.
- [x] **W-Migration-guide** — ✅ `docs/migration-from-mitodl.md`:
      what's compatible (DB schema, config, event-listener id,
      JPA-entity id, Keycloak/Java versions), what's added (mapper,
      reconciler, endpoint, OCI image), new config knobs (all
      default-off / backward-compat), behavioral differences worth
      knowing (async dispatch, widened retry, admin-DELETE fix,
      mapper null-return fix), and a step-by-step switchover +
      rollback procedure.
- [ ] **D-Contributing** — `CONTRIBUTING.md`, code-style notes,
      branch-naming conventions. Defer.

## Functional gaps already pinned

- [ ] **D-Group-PATCH-delta** — `group-patchOp=true` switches the
      group update path from PUT to PATCH, but the body still
      contains the full member list (just expressed as a REPLACE
      operation on `members`). For a 10k-member group, every
      membership change re-sends 10k members. Real fix: send
      incremental ADD/REMOVE patches based on the delta. The
      mapping table can compute the delta cheaply. Deferred to
      post-1.0.0 so it can be tackled holistically alongside
      **D-LDAP-group-membership** — the two are the same
      problem space (group propagation correctness + scale) and
      a coherent solution touches both.
- [x] **W-HTTP-43ms** — Investigated. ✅ Root cause: the SCIM SDK
      hardcodes
      `clientBuilder.setConnectionReuseStrategy((r,c) -> false)` in
      `ScimHttpClient.getHttpClient()`, forcing a new TCP connection
      per request. Workaround: register a `ConfigManipulator` (the
      SDK invokes it *after* the hardcoded no-reuse line, so we can
      flip it back). Implementation in
      `KeepAliveConfigManipulator`: restores
      `DefaultConnectionReuseStrategy`, applies
      `DefaultConnectionKeepAliveStrategy`, raises the
      `maxPerRoute` connection pool from Apache's default of 2 to
      32 (configurable via `scim.http.maxConnPerRoute`). Effect:
      per-request HTTP cost drops 21–37% (43 → 24–34 ms,
      depending on path). Wall-clock at 10k users is within
      run-to-run noise though, because we're already saturating
      the worker pool at 8 threads — to capitalize, operators
      raise `scim.dispatch.threads`. Documented in
      `docs/performance.md`.
- [x] **W-HTTP-30ms** — The residual ~25–30 ms localhost cost after
      the keepalive override turned out to be the test rig, not the
      SDK or our code. ✅ `HttpLayerBenchmark` (in `src/perfTest/`)
      strips containers + tunnel away and POSTs directly to an
      in-process WireMock on loopback. Three paths, 1000 sequential
      POSTs each: JDK `HttpClient` 0.47 ms, Apache HttpClient + our
      keepalive config 0.22 ms, full SCIM SDK
      `ScimRequestBuilder.create` 0.17 ms — all within measurement
      noise of each other. The HTTP stack sustains ~5000+ req/sec
      on a single connection. The 30 ms reported by
      `ScimClientMetrics` in the integration / perf rig is the
      Testcontainers SSH tunnel hop
      (`host.testcontainers.internal:<port>`): every container→host
      packet routes through an SSHD container, adding ~25–30 ms per
      request. Production deployments don't have this hop.
      Recalibration: with realistic SCIM-sink RTT, the per-worker
      ceiling is `1 / RTT`, not the test rig's `1 / 34ms`. Documented
      in `docs/performance.md`.
- [ ] **D-Perf-rig-sibling-container** — Move WireMock to a sibling
      container on Keycloak's Docker network so perf numbers reflect
      real network cost rather than tunnel overhead. Doesn't affect
      production behavior — only `ScimClientMetrics` numbers in
      `docs/performance.md`. 1.x.
- [ ] **D-LDAP-group-membership** — No `onImportGroupFromLDAP`
      analogue in our LDAP mapper. Groups federated from LDAP don't
      propagate to SCIM. Architectural addition; document as
      known-not-supported in 1.0.0 release notes.
- [ ] **D-Bloom-filter-witness** — Belt-and-suspenders for silent
      timestamp-write failures. Designed in
      `docs/ldap-federation-support.md` but not implemented. 1.x.
- [ ] **D-Reconciler-Phase1-parallel** — At 10k mappings the
      reconciler's Phase 1 (sequential mapping walk + getUserById)
      takes ~10s. Only matters at extreme reconciliation volumes;
      typical "delete a few hundred stale users" doesn't approach
      that.

## Testing gaps to close before 1.0.0

- [x] **W-Multi-provider** — ✅ `multipleScimProvidersAllReceiveTheUser`
      in `ScimMultiTenancyIT`: two SCIM providers in one realm with
      distinct WireMock URL paths; verifies admin user create
      produces a POST to BOTH endpoints.
- [x] **W-Realm-isolation** — ✅ `realmsAreIsolated` in
      `ScimMultiTenancyIT`: two realms each with their own SCIM
      provider; verifies a user-create in realm-1 produces no POST
      at realm-2's endpoint.
- [x] **W-Runtime-config-change** — ✅
      `runtimeReconcilerConfigChangeReschedules` in
      `ScimMultiTenancyIT`: starts with reconciler disabled,
      flips it on at runtime via component update, verifies the
      `onUpdate` hook reschedules and the timer fires.
- [x] **W-scim-skip-attribute** — ✅
      `scimSkipAttributeOptsUserOutOfPropagation` in
      `ScimPropagationFromLdapIT`: creates an opted-out user with
      `scim-skip=true` and a reference user without; verifies the
      reference user gets a POST and the opted-out one does not.
      Required `enableUnmanagedUserAttributes` helper because
      Keycloak 25's declarative user profile rejects unknown
      attributes via admin REST by default.
- [x] **W-group-filter** — ✅
      `groupFilterScopesSyncRefreshToMatchingGroupMembers` in
      `ScimGroupPropagationIT`: configures `sync-refresh=true` and
      `group-filter='admins'`, creates two users in two groups
      (admins, developers), triggers SCIM provider sync, verifies
      sync's refresh PUTs target the matching-group member but
      not the non-matching one.
- [ ] **D-Other** — Persistence-across-restart, concurrent admin
      ops, LDAP auth failures, certificate validation, long
      usernames / special characters. 1.x scope.

## Code quality / cleanup

- [x] **W-Diag-sweep** — ✅ `grep -rEn '\[diag\]|XXX|FIXME|TODO.*remove'`
      across `src/` returned nothing. The only `System.out.println`
      calls are the intentional `[perf]`-prefixed ones in the
      perfTest harness (PerfReport summary + per-scenario
      stdout headlines). Confirmed clean.
- [ ] **D-ScimClient-split** — `ScimClient` has accumulated mass
      (~500 lines). Naturally splits along create/replace/delete +
      retry/failure-handling. Defer.
- [ ] **D-Adapter-reflection** — `Adapter`'s reflection-based
      `getAdapter(Class)` is awkward. Cleaner pattern exists but
      not urgent.

## Definition-of-done for 1.0.0

The minimal set that lets us cut 1.0.0 with a straight face:

- All **B**-tagged items checked off
- All non-deferred **W**-tagged tests added (multi-provider, realm
  isolation, runtime config, scim-skip, group-filter)
- A successful `1.0.0-rc.0` dry-run, validated end-to-end
- Then a `Release-As: 1.0.0` commit, merge, OCI image published

Estimate: ~3–5 focused days for the **B** list, ~2–3 more for the
must-include **W** list.

## Out-of-scope for 1.0.0 (release-notes material)

These are real gaps but documented as such in the design docs and
release notes; not blocking 1.0.0:

- Group propagation overhaul (incremental PATCH delta +
  `onImportGroupFromLDAP` for LDAP-federated groups). Tackled as
  one cohesive effort post-1.0.0.
- Bloom-filter-witness extension to the reconciler
- SCIM `/Bulk` batching
- The 5xx-not-retried gap in the resilience policy
