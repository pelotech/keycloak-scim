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
- **OAuth 2.0 client_credentials auth.** Outbound SCIM can mint
  access tokens via the client_credentials grant and send them as
  bearer tokens, matching what JWKS-verifying SCIM receivers expect.
  See [`docs/configuration.md`](docs/configuration.md) for setup.
- **OpenTelemetry tracing.** Every outbound SCIM operation emits a
  `CLIENT` span that nests under the active Keycloak request span.
  Works automatically on Keycloak 26+ when tracing is enabled; falls
  back to a no-op on 25.x. See [`docs/tracing.md`](docs/tracing.md)
  for setup and examples.
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

Four ways to get the plugin loaded into Keycloak, by scenario:

### ImageVolume (single extension)

*Use when keycloak-scim is the only provider extension you're adding.*

Mount the published OCI image as a Kubernetes
[`image` volume](https://kubernetes.io/docs/concepts/storage/volumes/#image)
onto Keycloak's providers directory.

**Providers directory depends on the Keycloak image flavor:**

| Image | Providers directory |
| --- | --- |
| `quay.io/keycloak/keycloak` (official) | `/opt/keycloak/providers/` |
| `docker.io/bitnami/keycloak` (and downstream mirrors) | `/opt/bitnami/keycloak/providers/` |

Mount the single JAR via `subPath` so any other providers already in
the directory (e.g. distro-bundled SPIs) aren't shadowed:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: keycloak
spec:
  containers:
    - name: keycloak
      image: quay.io/keycloak/keycloak:26.6.3
      args: ["start-dev"]
      volumeMounts:
        - name: scim-provider
          mountPath: /opt/keycloak/providers/keycloak-scim.jar
          subPath: keycloak-scim.jar
          readOnly: true
  volumes:
    - name: scim-provider
      image:
        # Pin by digest in production. Tag shown for readability.
        reference: ghcr.io/pelotech/keycloak-scim:1.0.0
        pullPolicy: IfNotPresent
```

> **This does not compose for multiple extensions.** An `image:` volume
> maps one OCI image to one mount, but Keycloak loads all providers from
> a single directory. To add keycloak-scim *alongside* other extensions,
> use [Runtime compose (multiple extensions)](#runtime-compose-multiple-extensions)
> below.

For Bitnami Keycloak, change `mountPath` to
`/opt/bitnami/keycloak/providers/keycloak-scim.jar`.

The image is `FROM scratch` — payload only, no shell, no entrypoint.
Multi-arch manifest (linux/amd64 + linux/arm64), signed with cosign
keyless (GitHub OIDC), with SPDX + CycloneDX SBOMs attached as cosign
attestations.

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

After deploying, confirm the SCIM provider registered with Keycloak:

```sh
# Get an admin access token first, e.g.:
#   TOKEN=$(curl -sf -X POST "$KC_URL/realms/master/protocol/openid-connect/token" \
#     -d grant_type=password -d username=admin -d password=admin \
#     -d client_id=admin-cli | jq -r .access_token)

curl -sf -H "Authorization: Bearer $TOKEN" "$KC_URL/admin/serverinfo" \
  | jq -r '.componentTypes."org.keycloak.storage.UserStorageProvider"[].id' \
  | grep -qx scim && echo "scim provider registered" || echo "MISSING — see Troubleshooting"
```

Keycloak's failure mode for a misconfigured providers mount is silent
— no error log, the SPI just never registers. This recipe
distinguishes "JAR loaded" from "JAR ignored."

### Runtime compose (multiple extensions)

*Use when you're adding keycloak-scim alongside other provider extensions.*

Populate a shared `emptyDir` at startup: mount each extension as a
read-only `image:` volume, let an init container (the Keycloak image
itself — it has `cp` and a shell) copy each JAR into the `emptyDir`,
then boot Keycloak against the populated directory. Composes to any
number of extensions — one `image:` volume and one copy line each.

A complete, copy-pasteable Deployment is in
[`examples/kubernetes/keycloak-multi-extension.yaml`](examples/kubernetes/keycloak-multi-extension.yaml).

**Tradeoff:** providers mounted at runtime aren't part of a
`kc.sh build`-augmented image, so Keycloak augments at pod boot (a
per-pod startup cost) rather than once at image-build time. For
build-time augmentation, use
[Custom image (build-time augmentation)](#custom-image-build-time-augmentation).

*Optional:* because the init container is the Keycloak image, it can
also run `kc.sh build` to augment once during init instead of every
boot — this additionally requires sharing the augmentation output
directory between the init and main containers.

### Custom image (build-time augmentation)

*Use when you want a baked, pre-augmented image and have a build pipeline.*

Bake the provider JAR into a Keycloak image and augment with
`kc.sh build`. The published `FROM scratch` image is an ideal
`COPY --from` source:

```dockerfile
FROM quay.io/keycloak/keycloak:26.6.3 AS builder
# Pin by digest in production; tag shown for readability.
COPY --from=ghcr.io/pelotech/keycloak-scim:1.0.0 /keycloak-scim.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.6.3
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start"]
```

Add more extensions with additional
`COPY --from=<image> /<jar> /opt/keycloak/providers/` lines before the
`kc.sh build`.

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

## Performance: SCIM `/Bulk` batching (opt-in)

By default the plugin issues one HTTP request per resource change,
dispatched asynchronously over a **bounded, back-pressured** worker
pool — so a large federation sync or a slow SCIM sink paces the
producer instead of growing Keycloak's heap without bound. For
**federation-sync user creates**, you can additionally coalesce many
`POST /Users` into a single SCIM `/Bulk` request.

**Turning it on/off — off by default.** Per SCIM provider component,
set `bulk-enabled = true` (*Admin Console → your SCIM provider →
config*, or via the component API). Tune the batch size with
`-Dscim.dispatch.bulkBatchSize=<K>` on the Keycloak process (default
`20`; set it **≤ your SCIM server's advertised `maxOperations`**).
Only the LDAP-import **create** path is batched — replace, delete, and
group-membership stay one-request-each. Requires a SCIM server that
supports `/Bulk`.

**Does it pay off? Measured, not assumed.** `BulkLatencySweepIT`
(`./gradlew performanceTest --tests 'sh.libre.scim.perf.BulkLatencySweepIT'`)
sweeps bulk {on, off} × sink round-trip {5, 50, 200 ms} over a
2000-user sync, 5 runs per cell:

| Sink round-trip | per-op sync | `/Bulk` sync | Result |
| ---: | ---: | ---: | --- |
| 200 ms | 53.4 s | 7.5 s | **~7× faster** |
| 50 ms | 14.1 s | 5.8 s | **~2.4× faster** |
| 5 ms | 2.6 s | 6.1 s | **slower** — batching overhead exceeds the round-trips it saves |

The payoff scales with **network distance to your SCIM sink**: enable
`/Bulk` for remote / high-latency targets; for local or very-low-latency
sinks the per-op lane is already faster, which is why it stays off by
default. Peak memory is **not** a differentiator between the two lanes
(both add only single- to low-double-digit MiB per sync, dwarfed by
Keycloak's own footprint).

> These wins are a **lower bound**: the test harness (WireMock) models
> the network round-trip only, not a real SCIM server's per-request
> processing overhead, which `/Bulk` also amortizes. Full methodology,
> the run-to-run variance, the memory analysis, and the bounded-queue
> back-pressure design are in
> [`docs/performance.md`](docs/performance.md).

## SCIM extension attributes (opt-in)

Map Keycloak user attributes to SCIM extension-schema attributes and
push them outbound on every user create, update, refresh, and bulk
sync — no code changes required.

**Configuring mappings.** Per SCIM provider component, add one or more
rows to `user-extension-mappings` (*Admin Console → your SCIM provider
→ config*, or via the component API). Each row uses the grammar:

```
<keycloakAttr> = <scimSchemaUrn>:<attr> [; type=<t>] [; multi]
```

`type` coerces the raw string value before serialising — supported
values: `string` (default), `boolean`, `integer`, `decimal`,
`dateTime`, `reference`. Add `; multi` for multivalued attributes (emits
a JSON array from all values of that Keycloak attribute). Both the IETF
Enterprise User extension and arbitrary custom URN schemas are
supported.

**Example rows:**

```
# IETF Enterprise User extension
kcDept = urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department

# Custom schema — boolean
kcActive = urn:example:custom:2.0:User:active ; type=boolean

# Custom schema — multivalued string
kcLabels = urn:example:custom:2.0:User:labels ; multi
```

A malformed row is rejected at component save time with a validation
error. Leave the property empty (the default) to send no extension
attributes. The full grammar, all type tokens, and Enterprise User
field constraints are documented in
[`docs/configuration.md`](docs/configuration.md#user-extension-attributes).

## Documentation

- [`docs/configuration.md`](docs/configuration.md) — every
  config knob, attribute, endpoint, and JVM property.
- [`docs/tracing.md`](docs/tracing.md) — OpenTelemetry tracing:
  what's instrumented, how to enable it, Jaeger and Tempo examples.
- [`docs/ldap-federation-support.md`](docs/ldap-federation-support.md)
  — design doc for LDAP federation propagation and the reconciler.
- [`docs/performance.md`](docs/performance.md) — scale measurements,
  bottleneck analysis, async dispatch + bounded-queue back-pressure
  design, and the SCIM `/Bulk` latency-sweep characterization.
- [`docs/releasing.md`](docs/releasing.md) — release runbook
  (release-please flow, OCI image publication, RC dry-runs).

## Status

`1.0.x` is released and stable; versioning follows
[SemVer](https://semver.org/) and is driven by release-please from
conventional commits. Pin production deployments to a released tag (or
digest). Post-1.0 work — known gaps and refinements, none of which
block normal operation — is tracked in [`docs/roadmap.md`](docs/roadmap.md).

## Troubleshooting

**The JAR is on disk but the SCIM provider isn't visible in the admin
console or in `/admin/serverinfo`.** Almost always one of:

- **Wrong providers directory** for the Keycloak image flavor in use.
  Vanilla `quay.io/keycloak/keycloak` uses `/opt/keycloak/providers/`;
  Bitnami uses `/opt/bitnami/keycloak/providers/`. See [Quick
  start](#quick-start) for the mount-path table.
- **JAR was unpacked and repacked** without preserving the
  `META-INF/services/*` files. Keycloak's provider discovery is
  SPI-based and reads those service descriptors at boot; without
  them, the factory classes won't be loaded even though they're on
  the classpath.

The verification recipe in [Quick start](#quick-start) surfaces
either failure mode immediately — if `scim` isn't in the
`/admin/serverinfo` provider list, the JAR didn't register.

## License

Apache-2.0. See [`LICENSE`](LICENSE).
