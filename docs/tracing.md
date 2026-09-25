# Tracing

keycloak-scim emits [OpenTelemetry](https://opentelemetry.io/) spans for
every outbound SCIM operation. On Keycloak 26 and later, Quarkus bundles
the OTel SDK. These spans appear automatically in whatever tracing
backend you have configured. The plugin needs no extra configuration for
this. On Keycloak 25.x, OTel is absent. The plugin detects this and falls
back to a no-op. Tracing stays silently disabled on 25.x.

## Requirements

| Requirement | Detail |
| --- | --- |
| Keycloak version | 26.x or later |
| Keycloak tracing enabled | `--tracing-enabled=true` (or `KC_TRACING_ENABLED=true`) |
| OTel collector | Any OTLP-compatible backend (Jaeger, Tempo, Honeycomb, …) |

## What gets traced

Each SCIM operation becomes a `CLIENT` span. It is a child of whatever
Keycloak span is active on the calling thread, for example the
admin-REST handler that triggered the propagation. A sync produces one
outer span, wrapping every per-resource create or replace call inside
it.

For users, a `sync-refresh` run pages through the population, one page
per transaction. All of those page transactions run inside the same
outer `scim.sync.refresh` span.

| Span name | Triggered by |
| --- | --- |
| `scim.create` | User or group creation propagation |
| `scim.replace` | User or group update propagation |
| `scim.delete` | User or group deletion propagation |
| `scim.group.member.add` | Single user added to a group (`group-patchOp=true`) |
| `scim.group.member.remove` | Single user removed from a group (`group-patchOp=true`) |
| `scim.sync.refresh` | `refreshResources` (outbound triggerFullSync) |
| `scim.sync.import` | `importResources` (inbound sync) |
| `scim.deactivate` | User deprovisioning propagation, when `delete-mode=deactivate` |
| `scim.bulkCreate` | Batched user-create propagation on the LDAP-import path, when `bulk-enabled=true` |

### Span attributes

| Attribute | Value |
| --- | --- |
| `scim.resource_type` | `User` or `Group` |
| `server.address` | Base URL of the SCIM server |
| `http.response.status_code` | HTTP status returned by the SCIM server |
| `error.type` | `HTTP_<code>` on a response of 400 or higher, or the exception's class name when the operation recorded an error |

## Enabling tracing in Keycloak

Keycloak 26 ships OpenTelemetry support through Quarkus. Enable it with
two flags, and point it at an OTLP collector:

```sh
kc.sh start \
  --tracing-enabled=true \
  --tracing-endpoint=http://otel-collector:4317
```

Or set environment variables instead. This is useful for container
deployments:

```sh
KC_TRACING_ENABLED=true
KC_TRACING_ENDPOINT=http://otel-collector:4317
```

The OTLP exporter sends over gRPC by default, on port 4317. For
HTTP/protobuf export, use port 4318 and set
`--tracing-endpoint-type=http/protobuf`.

### Sampler

Keycloak defaults to `parent_based_always_on`. A request that arrives
with an incoming trace context is sampled. A standalone request is
sampled at 100%. For high-traffic environments, use a ratio sampler
instead:

```sh
KC_TRACING_SAMPLER_TYPE=ratio
KC_TRACING_SAMPLER_RATIO=0.1   # 10 %
```

## Example: Jaeger via Docker Compose

This is a minimal setup. It boots Keycloak 26 with tracing enabled,
alongside a Jaeger all-in-one backend and a local SCIM sink:

```yaml
services:
  jaeger:
    image: jaegertracing/all-in-one:1.62
    ports:
      - "16686:16686"   # Jaeger UI
      - "4317:4317"     # OTLP gRPC
    environment:
      COLLECTOR_OTLP_ENABLED: "true"

  keycloak:
    image: quay.io/keycloak/keycloak:26.6.3
    command: start-dev
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
      KC_TRACING_ENABLED: "true"
      KC_TRACING_ENDPOINT: http://jaeger:4317
    volumes:
      - ./keycloak-scim.jar:/opt/keycloak/providers/keycloak-scim.jar:ro
    depends_on:
      - jaeger
    ports:
      - "8080:8080"
```

Run it:

```sh
./gradlew shadowJar
cp build/libs/keycloak-scim-*-all.jar .
docker compose up
```

Open the Jaeger UI at `http://localhost:16686`. Select the `keycloak`
service. Trigger a SCIM operation, for example create a user or run a
sync. You should see `scim.create` and `scim.replace` spans nested
under the Keycloak request span that started them.

## Example: Kubernetes with Grafana Tempo

Add the tracing environment variables to your Keycloak Deployment,
alongside the plugin mount from the
[Quick start](../README.md#quick-start):

```yaml
env:
  - name: KC_TRACING_ENABLED
    value: "true"
  - name: KC_TRACING_ENDPOINT
    value: http://tempo.monitoring.svc.cluster.local:4317
  # Optional: reduce sample rate in production
  - name: KC_TRACING_SAMPLER_TYPE
    value: ratio
  - name: KC_TRACING_SAMPLER_RATIO
    value: "0.1"
```

keycloak-scim spans appear in Tempo, or any other OTLP-compatible
backend, under the service name `keycloak`. They link to the Keycloak
request spans that triggered them.
