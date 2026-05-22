# keycloak-scim

A Keycloak provider that propagates user and group lifecycle events
out to one or more remote [SCIM 2.0](http://www.simplecloud.info)
service providers
([RFC 7643](https://datatracker.ietf.org/doc/html/rfc7643),
[RFC 7644](https://datatracker.ietf.org/doc/html/rfc7644)).
Keycloak stays the source of truth for identity; downstream
applications get user create / update / delete and group membership
changes via SCIM, with no need to give them direct access to your
LDAP / Keycloak.

This is a long-lived [pelotech](https://github.com/pelotech) fork of
[mitodl/keycloak-scim](https://github.com/mitodl/keycloak-scim).
What's added relative to upstream:

- **LDAP federation support.** Users imported via Keycloak's LDAP
  User Federation (lazy import, periodic sync, explicit sync) now
  propagate to SCIM. Upstream's event-listener-only design didn't
  catch federation imports — see
  [`docs/ldap-federation-support.md`](docs/ldap-federation-support.md)
  for the full design.
- **LDAP-deletion reconciler.** A configurable periodic task
  closes the gap left by upstream Keycloak issue
  [#35235](https://github.com/keycloak/keycloak/issues/35235): users
  deleted from LDAP no longer linger in your SCIM sink.
- **Performance work for 10k+ user deployments.** Async dispatch on
  a worker pool brings full-sync throughput from ~22 users/sec to
  ~245 users/sec; reconciler deletion from ~22 to ~640 deletes/sec.
  See [`docs/performance.md`](docs/performance.md) for measurements
  and bottleneck analysis.
- **OCI image for K8s ImageVolume mounting.** Drop the plugin into
  a Keycloak pod without baking a custom image — see
  [Quick start](#quick-start) below.
- **Comprehensive test coverage.** 43 unit + 24 integration tests
  (Testcontainers-driven against real Keycloak + OpenLDAP +
  WireMock) plus a perf-test harness for scale work.

## Compatibility

| Component | Supported |
| --- | --- |
| Keycloak | 25.x, 26.x |
| Java (build + runtime) | 21 |
| Kubernetes (for ImageVolume mounting) | 1.36+ |
| Architectures (OCI image) | linux/amd64, linux/arm64 |

## Quick start

Two paths to getting the plugin loaded into Keycloak:

### Kubernetes ImageVolume (recommended)

Mount the published OCI image as a Kubernetes
[`image` volume](https://kubernetes.io/docs/concepts/storage/volumes/#image)
on Keycloak's `/opt/keycloak/providers/`:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: keycloak
spec:
  containers:
    - name: keycloak
      image: quay.io/keycloak/keycloak:25.0.6
      args: ["start-dev"]
      volumeMounts:
        - name: scim-provider
          mountPath: /opt/keycloak/providers
          readOnly: true
  volumes:
    - name: scim-provider
      image:
        # Pin by digest in production. Tag shown for readability.
        reference: ghcr.io/pelotech/keycloak-scim:1.0.0
        pullPolicy: IfNotPresent
```

The image is `FROM scratch` — payload only, no shell, no entrypoint.
Multi-arch manifest, signed with cosign keyless (GitHub OIDC), with
SPDX + CycloneDX SBOMs attached as cosign attestations.

Before deploying, verify the signature:

```sh
cosign verify \
  --certificate-identity-regexp "https://github.com/pelotech/keycloak-scim/.+" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  ghcr.io/pelotech/keycloak-scim:1.0.0
```

Inspect the SBOM:

```sh
cosign download attestation \
  --predicate-type https://spdx.dev/Document \
  ghcr.io/pelotech/keycloak-scim:1.0.0 \
  | jq -r '.payload | @base64d | fromjson | .predicate'
```

### Bare JAR (development)

```sh
git clone https://github.com/pelotech/keycloak-scim
cd keycloak-scim
./gradlew shadowJar
cp build/libs/keycloak-scim-*-all.jar /opt/keycloak/providers/
```

For local end-to-end testing, `docker-compose.yml` brings up
Keycloak + Postgres with the freshly-built JAR mounted in:

```sh
./gradlew prepareDockerContext
docker compose up
```

## Configuring a SCIM provider

After the plugin is loaded:

1. **Enable the event listener** *(if you want admin-REST and
   self-service events to propagate; LDAP-import propagation is
   handled separately by the LDAP mapper below)*:
   *Admin Console → Realm Settings → Events → Config* — add
   `scim` to *Event Listeners*.

2. **Add a SCIM provider component:**
   *Admin Console → User Federation → Add provider → scim*. Set
   `endpoint`, `auth-mode`, `auth-pass` (token) at minimum.
   Every config knob is documented in
   [`docs/configuration.md`](docs/configuration.md).

3. **Attach the LDAP mapper** *(only if you have LDAP federation
   and want LDAP-imported users to propagate)*:
   *Admin Console → User Federation → (your LDAP provider) →
   Mappers → Add → scim-ldap-sync*. No config required; presence
   is the configuration.

The plugin will now fan out user/group changes from each path
(admin REST, self-service, LDAP federation) to every configured
SCIM provider component in the realm.

## Documentation

- [`docs/configuration.md`](docs/configuration.md) — every
  config knob, attribute, endpoint, and JVM property.
- [`docs/ldap-federation-support.md`](docs/ldap-federation-support.md)
  — design doc for LDAP federation propagation and the reconciler.
- [`docs/performance.md`](docs/performance.md) — scale measurements,
  bottleneck analysis, async dispatch design.
- [`docs/releasing.md`](docs/releasing.md) — release runbook
  (release-please flow, OCI image publication, RC dry-runs).
- [`docs/installation.md`](docs/installation.md),
  [`docs/container.md`](docs/container.md) — additional install
  paths inherited from upstream.

## Status

`1.0.0` is in active preparation; track open work at
[`docs/release-1.0.0-todos.md`](docs/release-1.0.0-todos.md). Until
that lands, image tags are pre-1.0 and breaking changes between
patch versions are possible — pin by digest.

## License

Apache-2.0. See [`LICENSE`](LICENSE).
