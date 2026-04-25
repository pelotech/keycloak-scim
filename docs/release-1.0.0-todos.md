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
      contributor changes. (commit ce7d167)
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
- [ ] **W-K8s-matrix** — Add a Keycloak version matrix to the CI
      workflow: 25.0.6 (current minimum) and the latest 26.x. We
      claim binary compatibility but have only tested against 25.0.6.

## Documentation

- [ ] **B-README** — Rewrite the README. Currently it's mostly
      the upstream mitodl prose with our ImageVolume section grafted
      in. Should describe:
      - What this fork is and why (long-lived; not a PR back to mitodl)
      - What's in scope vs upstream (LDAP federation, reconciler,
        perf work, OCI image)
      - Compatibility (Keycloak 25.x+, 26.x)
      - Quick-start using the OCI image (already exists; keep)
      - Pointers to `docs/` for everything else
- [ ] **B-Config-reference** — Single operator-facing reference for
      every SCIM provider component config knob. Today there are
      ~15: endpoint, content-type, auth-mode, auth-user, auth-pass,
      propagation-user, propagation-group, sync-import,
      sync-import-action, sync-refresh, group-patchOp, user-patchOp,
      username-source, group-filter, reconciler-enabled,
      reconciler-interval-seconds, reconciler-stale-threshold-seconds.
      Each documented in helpText but not collected anywhere.
- [ ] **B-License-footer** — README footer says `License AGPL`. The
      `LICENSE` file is Apache 2.0. Pre-existing inconsistency from
      upstream; cheap to fix.
- [ ] **W-Migration-guide** — One page on migrating from upstream
      `mitodl/keycloak-scim`. DB schema is compatible; config is
      mostly compatible. Help adopters who are switching.
- [ ] **D-Contributing** — `CONTRIBUTING.md`, code-style notes,
      branch-naming conventions. Defer.

## Functional gaps already pinned

- [ ] **W-Group-PATCH-delta** — `group-patchOp=true` switches the
      group update path from PUT to PATCH, but the body still
      contains the full member list (just expressed as a REPLACE
      operation on `members`). For a 10k-member group, every
      membership change re-sends 10k members. Real fix: send
      incremental ADD/REMOVE patches based on the delta. The
      mapping table can compute the delta cheaply.
- [ ] **W-HTTP-43ms** — A SCIM HTTP request to localhost takes ~43 ms
      in our perf measurements. That's an order of magnitude higher
      than typical localhost HTTP (~1–5 ms). Investigate whether
      Apache HttpClient inside the Captain Goldfish SCIM SDK is
      establishing a new TCP connection per request despite our
      ScimClient caching. If it's a small config fix on the SDK
      side, throughput cascades upward.
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

- [ ] **W-Multi-provider** — Realm with two SCIM providers
      configured; verify dispatcher fans out and both endpoints
      receive the user.
- [ ] **W-Realm-isolation** — Two realms each with their own SCIM
      provider; verify mappings/state don't bleed between them.
- [ ] **W-Runtime-config-change** — Toggle `reconciler-enabled` on
      a live component, change `reconciler-interval-seconds`,
      verify `onUpdate` reschedules cleanly. Today the `onUpdate`
      hook exists but isn't exercised end-to-end.
- [ ] **W-scim-skip-attribute** — `scim-skip=true` user-attribute
      opt-out has been in the code since the inkules port. Not
      tested end-to-end.
- [ ] **W-group-filter** — `group-filter` regex is unit-tested at
      the witness level but no integration test confirms only
      matched groups sync.
- [ ] **D-Other** — Persistence-across-restart, concurrent admin
      ops, LDAP auth failures, certificate validation, long
      usernames / special characters. 1.x scope.

## Code quality / cleanup

- [ ] **W-Diag-sweep** — Verify no leftover `[diag]` instrumentation
      logs anywhere in the codebase. Cleanup pass after the perf
      work; cheap.
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

- LDAP-group-membership-from-LDAP propagation
- Bloom-filter-witness extension to the reconciler
- SCIM `/Bulk` batching
- The 5xx-not-retried gap in the resilience policy
