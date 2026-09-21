# keycloak-scim

A Keycloak provider that sends user and group changes to one or more
[SCIM 2.0](http://www.simplecloud.info) servers
([RFC 7643](https://datatracker.ietf.org/doc/html/rfc7643),
[RFC 7644](https://datatracker.ietf.org/doc/html/rfc7644)).
Keycloak stays the source of truth for identity. Downstream
applications get user create, update, and delete events, and group
membership changes, through SCIM. They do not need direct access to
your LDAP or Keycloak.

This is a long-lived [pelotech](https://github.com/pelotech) fork of
[mitodl/keycloak-scim](https://github.com/mitodl/keycloak-scim).
What's added relative to upstream:

- **LDAP federation support.** Users imported through Keycloak's LDAP
  User Federation now propagate to SCIM. This covers lazy import,
  periodic sync, and explicit sync. The upstream event-listener design
  did not catch federation imports. See
  [`docs/ldap-federation-support.md`](docs/ldap-federation-support.md)
  for the full design.
- **LDAP-deletion reconciler.** A configurable periodic task closes a
  gap in upstream Keycloak (issue
  [#35235](https://github.com/keycloak/keycloak/issues/35235)). Users
  deleted from LDAP no longer linger on the SCIM server.
- **Performance work for 10k+ user deployments.** Async dispatch on a
  worker pool raises full-sync throughput from about 22 users per
  second to about 245. Reconciler deletion rises from about 22 to
  about 640 deletes per second. See
  [`docs/performance.md`](docs/performance.md) for measurements and
  bottleneck analysis.
- **OAuth 2.0 client_credentials auth.** Outbound SCIM calls can use an
  access token from the client_credentials grant, sent as a bearer
  token. This matches what SCIM servers that verify JWKS expect. See
  [`docs/configuration.md`](docs/configuration.md) for setup.
- **OpenTelemetry tracing.** Every outbound SCIM operation emits a
  `CLIENT` span, nested under the active Keycloak request span. This
  works automatically on Keycloak 26 and later, when tracing is
  enabled. On Keycloak 25.x, it falls back to a no-op. See
  [`docs/tracing.md`](docs/tracing.md) for setup and examples.
- **OCI image for Kubernetes ImageVolume mounting.** Add the plugin to
  a Keycloak pod without building a custom image. See
  [Quick start](#quick-start) below.
- **Comprehensive test coverage.** 43 unit tests and 24 integration
  tests, run with Testcontainers against real Keycloak, OpenLDAP, and
  WireMock. A performance-test harness covers scale work.

## Compatibility

| Component | Supported |
| --- | --- |
| Keycloak | 25.x, 26.x |
| Java (build + runtime) | 21 |
| Kubernetes (for ImageVolume mounting) | 1.33+ (image volumes beta, on by default) |
| Architectures (OCI image) | linux/amd64, linux/arm64 |

## Quick start

Four ways to load the plugin into Keycloak, based on your scenario:

### ImageVolume (single extension)

*Use this when keycloak-scim is the only provider extension you add.*

Mount the published OCI image as a Kubernetes
[`image` volume](https://kubernetes.io/docs/concepts/storage/volumes/#image)
onto Keycloak's providers directory.

**The providers directory depends on the Keycloak image:**

| Image | Providers directory |
| --- | --- |
| `quay.io/keycloak/keycloak` (official) | `/opt/keycloak/providers/` |
| `docker.io/bitnami/keycloak` (and downstream mirrors) | `/opt/bitnami/keycloak/providers/` |

Mount the image's filesystem **onto the providers directory**. Do not
use `subPath`. The published image is `FROM scratch` and contains only
`/keycloak-scim.jar`, so the directory ends up holding just that one
JAR:

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
          mountPath: /opt/keycloak/providers
          readOnly: true
  volumes:
    - name: scim-provider
      image:
        # Pin by digest in production. Tag shown for readability.
        reference: ghcr.io/pelotech/keycloak-scim:1.0.0
        pullPolicy: IfNotPresent
```

> **Do not mount just the JAR with `subPath`.** A Kubernetes `image`
> volume supports only a **directory** `subPath`, never a single file.
> `subPath: keycloak-scim.jar` fails the mount
> (`ImageVolumeMountFailed: only directory subpath is supported`), and
> the container never starts. Mount the whole image at the providers
> directory instead. (Validated on Kubernetes v1.35 with containerd
> 2.2.)
>
> **This mount replaces the entire providers directory** with the
> image's read-only contents. Anything else in that directory is
> hidden. Also, one `image:` volume maps to one OCI image and one
> mount. This is fine for the single-extension case in this section.
> To add keycloak-scim *alongside* other extensions, use
> [Runtime compose (multiple extensions)](#runtime-compose-multiple-extensions)
> below.

For Bitnami Keycloak, change `mountPath` to
`/opt/bitnami/keycloak/providers`.

The image is `FROM scratch`: it holds only the payload, with no shell
and no entrypoint. It is a multi-arch manifest (linux/amd64 and
linux/arm64), signed with cosign keyless through GitHub OIDC. SPDX and
CycloneDX SBOMs are attached as cosign attestations.

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
  | grep -qx scim && echo "scim provider registered" || echo "MISSING: see Troubleshooting"
```

Keycloak's failure mode for a misconfigured providers mount is
silent. There is no error log, the SPI just never registers. This
check tells the difference between "JAR loaded" and "JAR ignored."

### Runtime compose (multiple extensions)

*Use this when you add keycloak-scim alongside other provider
extensions.*

Populate a shared `emptyDir` at startup. Mount each extension as a
read-only `image:` volume. An init container, which is the Keycloak
image itself, copies each JAR into the `emptyDir` with `cp`. Keycloak
then boots against the populated directory. This works for any number
of extensions: one `image:` volume and one copy line per extension.

A complete, copy-pasteable Deployment is in
[`examples/kubernetes/keycloak-multi-extension.yaml`](examples/kubernetes/keycloak-multi-extension.yaml).

**Tradeoff.** Providers mounted at runtime are not part of a
`kc.sh build`-augmented image. So Keycloak augments at pod boot, which
costs time on every pod start, instead of once at image-build time.
For build-time augmentation, use
[Custom image (build-time augmentation)](#custom-image-build-time-augmentation).

*Optional.* Because the init container is the Keycloak image, it can
also run `kc.sh build` to augment once during init, instead of on
every boot. This also requires sharing the augmentation output
directory between the init and main containers.

### Custom image (build-time augmentation)

*Use this when you want a baked, pre-augmented image, and you have a
build pipeline.*

Bake the provider JAR into a Keycloak image, and augment it with
`kc.sh build`. The published `FROM scratch` image works well as a
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

For local end-to-end testing, `docker-compose.yml` starts Keycloak and
Postgres, with the freshly built JAR mounted in:

```sh
./gradlew prepareDockerContext
docker compose up
```

## Configuring a SCIM provider

After the plugin is loaded:

1. **Enable the event listener.** Do this if you want admin-REST and
   self-service events to propagate. LDAP-import propagation is
   separate; see the LDAP mapper step below. Go to
   *Admin Console → Realm Settings → Events → Config* and add `scim`
   to *Event Listeners*.

2. **Add a SCIM provider component.** Go to
   *Admin Console → User Federation → Add provider → scim*. Set at
   least `endpoint`, `auth-mode`, and `auth-pass` (the token). Every
   setting is documented in
   [`docs/configuration.md`](docs/configuration.md).

3. **Attach the LDAP mapper.** Do this only if you have LDAP
   federation and want LDAP-imported users to propagate. Go to
   *Admin Console → User Federation → (your LDAP provider) → Mappers →
   Add → scim-ldap-sync*. It needs no settings. Attaching it is the
   configuration.

The plugin now sends user and group changes from each path (admin
REST, self-service, LDAP federation) to every configured SCIM provider
component in the realm.

**To automate this**, use realm import JSON, `kcadm`, or the admin
REST API. All three steps are scriptable. See
[Headless / automated provisioning](docs/configuration.md#headless--automated-provisioning)
for examples, the multivalued-mapping gotcha, and the Keycloak 25+
user-profile prerequisite.

## Performance: SCIM `/Bulk` batching (opt-in)

By default, the plugin sends one HTTP request per resource change. It
dispatches these asynchronously over a bounded, back-pressured worker
pool. So a large federation sync, or a slow SCIM server, paces the
producer instead of growing Keycloak's heap without limit. For
federation-sync user creates, you can also combine many `POST /Users`
calls into one SCIM `/Bulk` request.

**Turning it on or off.** This is off by default. Per SCIM provider
component, set `bulk-enabled = true` (*Admin Console → your SCIM
provider → config*, or the component API). Tune the batch size with
`-Dscim.dispatch.bulkBatchSize=<K>` on the Keycloak process. The
default is `20`. Set it to at most your SCIM server's advertised
`maxOperations`. Only the LDAP-import **create** path is batched.
Replace, delete, and group-membership calls still go one at a time.
This requires a SCIM server that supports `/Bulk`.

**Measured, not assumed.** `BulkLatencySweepIT`
(`./gradlew performanceTest --tests 'sh.libre.scim.perf.BulkLatencySweepIT'`)
tests bulk on and off, at sink round-trip times of 5, 50, and 200 ms,
over a 2000-user sync. It runs 5 times per combination:

| Sink round-trip | per-op sync | `/Bulk` sync | Result |
| ---: | ---: | ---: | --- |
| 200 ms | 53.4 s | 7.5 s | **~7× faster** |
| 50 ms | 14.1 s | 5.8 s | **~2.4× faster** |
| 5 ms | 2.6 s | 6.1 s | **slower**: batching overhead costs more than the round-trips it saves |

The payoff scales with **network distance to your SCIM server**.
Enable `/Bulk` for remote or high-latency targets. For local or
very-low-latency servers, the per-op lane is already faster. This is
why it stays off by default. Peak memory does **not** differ much
between the two lanes. Both add only single-digit to low-double-digit
MiB per sync, small compared to Keycloak's own footprint.

> These wins are a **lower bound**. The test harness (WireMock) models
> only the network round-trip. It does not model a real SCIM server's
> per-request processing time, which `/Bulk` also reduces. Full
> methodology, run-to-run variance, the memory analysis, and the
> bounded-queue back-pressure design are in
> [`docs/performance.md`](docs/performance.md).

## SCIM extension attributes (opt-in)

Map Keycloak user attributes to SCIM extension-schema attributes. The
plugin sends them outbound on every user create, update, refresh, and
bulk sync. No code changes are required.

**Configuring mappings.** Per SCIM provider component, add one or more
rows to `user-extension-mappings` (*Admin Console → your SCIM provider
→ config*, or the component API). Each row uses this grammar:

```
<keycloakAttr> = <scimSchemaUrn>:<attr> [; type=<t>] [; multi]
```

`type` converts the raw string value before serializing. Supported
values are `string` (default), `boolean`, `integer`, `decimal`,
`dateTime`, and `reference`. Add `; multi` for multivalued attributes.
It emits a JSON array from all values of that Keycloak attribute. The
plugin supports both the IETF Enterprise User extension and custom URN
schemas.

**Example rows:**

```
# IETF Enterprise User extension
kcDept = urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department

# Custom schema, boolean
kcActive = urn:example:custom:2.0:User:active ; type=boolean

# Custom schema, multivalued string
kcLabels = urn:example:custom:2.0:User:labels ; multi
```

The plugin rejects a malformed row at component save time, with a
validation error. Leave the setting empty (the default) to send no
extension attributes. The full grammar, all type tokens, and the
Enterprise User field constraints are in
[`docs/configuration.md`](docs/configuration.md#user-extension-attributes).

## Documentation

- [`docs/configuration.md`](docs/configuration.md): every setting,
  attribute, endpoint, and JVM property.
- [`docs/tracing.md`](docs/tracing.md): OpenTelemetry tracing. What is
  instrumented, how to enable it, and examples for Jaeger and Tempo.
- [`docs/ldap-federation-support.md`](docs/ldap-federation-support.md):
  design for LDAP federation propagation and the reconciler.
- [`docs/performance.md`](docs/performance.md): scale measurements,
  bottleneck analysis, the async dispatch and bounded-queue
  back-pressure design, and the SCIM `/Bulk` latency-sweep results.
- [`docs/releasing.md`](docs/releasing.md): release runbook. Covers
  the release-please flow, OCI image publication, and RC dry-runs.

## Status

`1.0.x` is released and stable. Versioning follows
[SemVer](https://semver.org/) and is driven by release-please from
conventional commits. Pin production deployments to a released tag or
digest. Post-1.0 work is tracked in
[`docs/roadmap.md`](docs/roadmap.md). This work covers known gaps and
refinements. None of them block normal operation.

## Troubleshooting

**The JAR is on disk, but the SCIM provider is not visible in the
admin console or in `/admin/serverinfo`.** This is almost always one
of these causes:

- **Wrong providers directory** for the Keycloak image in use. The
  official `quay.io/keycloak/keycloak` image uses
  `/opt/keycloak/providers/`. Bitnami uses
  `/opt/bitnami/keycloak/providers/`. See
  [Quick start](#quick-start) for the mount-path table.
- **The JAR was unpacked and repacked** without keeping the
  `META-INF/services/*` files. Keycloak's provider discovery uses SPI,
  and reads those service descriptors at boot. Without them, Keycloak
  does not load the factory classes, even though they are on the
  classpath.

The check in [Quick start](#quick-start) shows either failure right
away. If `scim` is not in the `/admin/serverinfo` provider list, the
JAR did not register.

## License

Apache-2.0. See [`LICENSE`](LICENSE).
