# Releasing

Runbook for cutting a release of this fork.

A single workflow (`.github/workflows/release.yml`) runs on every push to
`main` and does two things in one job:

1. **release-please** scans conventional commits since the last release
   and keeps an open release PR.
2. **OCI build + publish** builds a multi-arch image, pushes it to
   `ghcr.io/pelotech/keycloak-scim`, signs with cosign keyless, and
   attaches SPDX + CycloneDX SBOMs.

The image tag depends on what happened in the run:

- If release-please's PR was just merged (i.e. `release_created=true`),
  the image is tagged with the new SemVer — e.g. `1.0.0-rc.1`.
- Otherwise, the image is tagged `git-<short_sha>`. Every commit on
  `main` is fetchable by SHA, which is useful for testing a specific
  commit against a cluster without cutting a tag.

Operators consume the image via the K8s `image` volume type (Kubernetes
1.36+ — see the README's ImageVolume section).

## Conventional commits

release-please computes the next version from commit message prefixes:

| Prefix | Effect on version | Appears in CHANGELOG? |
| --- | --- | --- |
| `feat:` | minor bump (`X.Y.0` → `X.(Y+1).0`) | yes, "Features" |
| `fix:` | patch bump (`X.Y.Z` → `X.Y.(Z+1)`) | yes, "Bug fixes" |
| `feat!:` or footer `BREAKING CHANGE:` | major bump (`X.Y.Z` → `(X+1).0.0`) | yes |
| `perf:`, `refactor:`, `build:`, `test:`, `docs:` | no bump | yes |
| `chore:`, `ci:` | no bump | hidden |

If a commit doesn't match the convention, release-please ignores it
for version computation but it'll still appear in `git log`.

## Cutting a normal release

1. Land conventional commits on `main` as usual. Each push triggers the
   release workflow, which publishes an image tagged `git-<short_sha>`.
2. release-please keeps a single open release PR titled
   `chore(main): release X.Y.Z`. The proposed version reflects the
   cumulative effect of all unreleased conventional commits.
3. Review the PR diff. It should:
   - Bump `version =` in `build.gradle.kts`.
   - Update `.release-please-manifest.json`.
   - Update `CHANGELOG.md` with categorized entries.
4. Merge the PR. The same workflow run that the merge triggers will:
   - Push tag `vX.Y.Z` and create a GitHub Release (via release-please).
   - Publish `ghcr.io/pelotech/keycloak-scim:X.Y.Z` (multi-arch,
     cosign-signed, SBOM-attested).

No tag-push trigger, no inter-workflow chain, no PAT. The OCI build
runs as later steps in the same job that release-please ran in, so it
sees `release_created=true` and uses the SemVer for the image tag.

## Pre-release (dry run)

Two flavors, depending on how much of the pipeline you want to
exercise.

### Smoke-test the OCI build path only

For verifying the image build, registry push, signing, and SBOM
without involving release-please:

1. Trigger `release.yml` via `workflow_dispatch` from the GitHub
   Actions UI (or `gh workflow run release.yml -f version_override=test-build`).
2. On `workflow_dispatch`, release-please is skipped entirely. The
   workflow publishes `ghcr.io/pelotech/keycloak-scim:test-build`.
3. Verify the image (see "Verifying an artifact" below). When you're
   satisfied, optionally delete the `:test-build` tag from the
   registry to keep things tidy.

### Full pipeline through release-please (RC)

For exercising the entire release flow — release-please PR, merge,
tag, OCI build — without committing to a stable `1.0.0`:

1. Make a commit with a `Release-As:` footer:

   ```bash
   git commit --allow-empty -m "$(cat <<'EOF'
   chore: kick off release pipeline dry run

   Release-As: 1.0.0-rc.0
   EOF
   )"
   git push origin main
   ```

   The empty commit is fine; the footer is what release-please reads.

2. release-please opens a PR proposing version `1.0.0-rc.0` (overriding
   whatever it would have computed from accumulated commits).

3. Merge the PR. The same workflow run that the merge triggers
   publishes `ghcr.io/pelotech/keycloak-scim:1.0.0-rc.0` and pushes
   tag `v1.0.0-rc.0`.

4. Verify the artifact end-to-end (see below).

5. If problems surface, fix them on `main`, then either:
   - Cut another RC: `Release-As: 1.0.0-rc.1`, repeat.
   - Or skip directly to GA when ready: `Release-As: 1.0.0`.

## Promoting RC → GA

After an RC validates, ship the GA release with another `Release-As:`
override:

```bash
git commit --allow-empty -m "$(cat <<'EOF'
chore: promote to first stable release

Release-As: 1.0.0
EOF
)"
git push origin main
```

The override is needed because release-please's automatic computation
after an RC tends to land on something like `1.1.0-rc.0` (it treats
the previous release as the baseline and bumps from there). The
explicit footer keeps things deterministic.

After this PR merges, `ghcr.io/pelotech/keycloak-scim:1.0.0` is
published. There are no floating `:latest` or `:major.minor` tags — pin
to the explicit version (or, even better, to the manifest digest).

## Verifying an artifact

```bash
# Image exists, multi-arch
docker manifest inspect ghcr.io/pelotech/keycloak-scim:VERSION

# Cosign signature is valid (keyless, GitHub OIDC)
cosign verify \
  --certificate-identity-regexp "https://github.com/pelotech/keycloak-scim/.+" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  ghcr.io/pelotech/keycloak-scim:VERSION

# SBOM is attached
cosign download attestation \
  --predicate-type https://spdx.dev/Document \
  ghcr.io/pelotech/keycloak-scim:VERSION \
  | jq -r '.payload | @base64d | fromjson | .predicate'
```

For a deeper smoke test, mount the image into a real Keycloak pod via
the README's ImageVolume example and confirm the SCIM provider
appears under *Realm Settings → Provider Info* (or via the admin REST
`/admin/serverinfo` endpoint).

## Bootstrap state (for the curious)

This fork's release-please was bootstrapped at commit
`eec8ecd14971886f0d00f3dc688b587c3002f252` — the last commit from
upstream `mitodl/keycloak-scim` before this fork's work began. That's
configured as `bootstrap-sha` in `release-please-config.json`, which
makes the inaugural CHANGELOG entry contain only this fork's
contributions, not anything from upstream.

The first release is hardcoded to `1.0.0` via `initial-version` in
the same config, regardless of what the cumulative bootstrap commits
would otherwise compute.
