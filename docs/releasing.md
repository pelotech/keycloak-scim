# Releasing

Runbook for cutting a release of this fork. The pipeline has three stages
that run in order:

1. **release-please** scans conventional commits since the last release,
   keeps an open release PR, and on merge pushes a `v*` tag.
2. **release.yml** fires on the `v*` tag, builds a multi-arch OCI image,
   pushes to `ghcr.io/pelotech/keycloak-scim`, signs with cosign, and
   attaches SPDX + CycloneDX SBOM attestations.
3. Operators consume the image via the K8s `image` volume type
   (Kubernetes 1.36+ — see the README's ImageVolume section).

## Prerequisites

### Token (one-time setup)

release-please opens its release PRs and pushes tags using whichever
token is configured. The default `GITHUB_TOKEN` works for the PR + tag
itself, but **tags it pushes do not trigger downstream workflows**
(GitHub's anti-recursion rule). That means the OCI image build will
*not* fire automatically when the release PR merges unless a
non-default token is used.

Two paths:

- **Recommended: a GitHub App.** Install a small App on the repo with
  `contents: write` + `pull-requests: write`. Generate an installation
  token and store it as the repo secret `RELEASE_PLEASE_TOKEN`. The
  release-please workflow picks it up automatically (see the
  `secrets.RELEASE_PLEASE_TOKEN || secrets.GITHUB_TOKEN` fallback in
  `.github/workflows/release-please.yml`).
- **Fallback: a Personal Access Token (classic or fine-grained) with
  the same permissions.** Same secret name. Uses the maintainer's
  identity for the tag push, which is fine for small teams but binds
  release authority to one human's PAT lifecycle.

Without either secret, the OCI workflow won't auto-fire. You can still
publish the image by manually triggering `release.yml` via
`workflow_dispatch` after merging a release PR — see "If the OCI image
didn't auto-publish" below.

### Conventional commits

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

1. Land conventional commits on `main` as usual.
2. release-please-action runs on every push to `main` and keeps a
   single open release PR titled `chore(main): release X.Y.Z`. The
   proposed version reflects the cumulative effect of all unreleased
   conventional commits.
3. Review the PR diff. It should:
   - Bump `version =` in `build.gradle.kts`.
   - Update `.release-please-manifest.json`.
   - Update `CHANGELOG.md` with categorized entries.
4. Merge the PR. release-please pushes tag `vX.Y.Z` and creates a
   GitHub Release.
5. `release.yml` fires on the tag and publishes
   `ghcr.io/pelotech/keycloak-scim:X.Y.Z` plus floating tags `X.Y` and
   `latest`. Multi-arch (linux/amd64, linux/arm64), cosign-signed,
   SBOM-attested.

## Pre-release (dry run)

Two flavors, depending on how much of the pipeline you want to
exercise.

### Smoke-test the OCI build path only

For verifying the image build, registry push, signing, and SBOM
without involving release-please:

1. Trigger `release.yml` via `workflow_dispatch` from the GitHub
   Actions UI (or `gh workflow run release.yml -f
   version_override=test-build`).
2. The workflow publishes `ghcr.io/pelotech/keycloak-scim:test-build`
   without touching `:latest` or `:X.Y`. The version label inside the
   image is `test-build`.
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

3. Merge the PR. Tag `v1.0.0-rc.0` is pushed; `release.yml` fires.

4. Because `1.0.0-rc.0` contains a `-` (SemVer prerelease marker), the
   workflow publishes only the explicit
   `ghcr.io/pelotech/keycloak-scim:1.0.0-rc.0` tag — `:latest` and
   `:1.0` are untouched. Stable-release consumers are isolated from the
   RC.

5. Verify the artifact end-to-end (see below).

6. If problems surface, fix them on `main`, then either:
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

After this PR merges, `:1.0.0`, `:1.0`, and `:latest` are all
published.

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

## If the OCI image didn't auto-publish

This is the expected state when no `RELEASE_PLEASE_TOKEN` is
configured. After merging a release-please PR:

```bash
gh workflow run release.yml -f version_override=X.Y.Z
```

Or trigger from the GitHub Actions UI. The workflow uses the version
override directly — no tag is involved, so be precise about the
intended version. The published image content is identical to what an
auto-fired run would produce (the source tree at the merge commit is
the same).

Long-term fix: configure `RELEASE_PLEASE_TOKEN` per the Prerequisites
section.

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
