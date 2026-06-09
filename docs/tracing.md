# Tracing

keycloak-scim emits [OpenTelemetry](https://opentelemetry.io/) spans for
every outbound SCIM operation. On Keycloak 26+, where Quarkus bundles the
OTel SDK, these spans appear automatically in whatever tracing backend you
have configured — no additional plugin configuration required. On
Keycloak 25.x, the plugin detects that OTel is absent and falls back to a
no-op; tracing is silently disabled.

## Requirements

| Requirement | Detail |
| --- | --- |
| Keycloak version | 26.x or later |
| Keycloak tracing enabled | `--tracing-enabled=true` (or `KC_TRACING_ENABLED=true`) |
| OTel collector | Any OTLP-compatible backend (Jaeger, Tempo, Honeycomb, …) |

## What gets traced

Each SCIM operation becomes a `CLIENT` span that is a child of whatever
Keycloak span is active on the calling thread (e.g. the admin-REST handler
that triggered the propagation). Sync operations produce one outer span
wrapping all per-resource create/replace calls.

| Span name | Triggered by |
| --- | --- |
| `scim.create` | User or group creation propagation |
| `scim.replace` | User or group update propagation |
| `scim.delete` | User or group deletion propagation |
| `scim.group.member.add` | Single user added to a group (`group-patchOp=true`) |
| `scim.group.member.remove` | Single user removed from a group (`group-patchOp=true`) |
| `scim.sync.refresh` | `refreshResources` (outbound triggerFullSync) |
| `scim.sync.import` | `importResources` (inbound sync) |

### Span attributes

| Attribute | Value |
| --- | --- |
| `scim.resource_type` | `User` or `Group` |
| `server.address` | Base URL of the SCIM endpoint |
| `http.response.status_code` | HTTP status returned by the SCIM server |
| `error.type` | Set on non-2xx responses (`HTTP_4xx`/`HTTP_5xx`) or exceptions |

## Enabling tracing in Keycloak

Keycloak 26 ships OpenTelemetry support via Quarkus. Enable it with two
flags and point it at an OTLP collector:

```sh
kc.sh start \
  --tracing-enabled=true \
  --tracing-endpoint=http://otel-collector:4317
```

Or via environment variables (useful in container deployments):

```sh
KC_TRACING_ENABLED=true
KC_TRACING_ENDPOINT=http://otel-collector:4317
```

The OTLP exporter sends over gRPC by default (port 4317). For HTTP/protobuf
export use port 4318 and set `--tracing-endpoint-type=http/protobuf`.

### Sampler

Keycloak defaults to `parent_based_always_on` — requests that arrive with
an incoming trace context are sampled; standalone requests are sampled
100%. For high-traffic environments use a ratio sampler:

```sh
KC_TRACING_SAMPLER_TYPE=ratio
KC_TRACING_SAMPLER_RATIO=0.1   # 10 %
```

## Example: Jaeger via Docker Compose

A minimal setup that boots Keycloak 26 with tracing enabled alongside a
Jaeger all-in-one backend and a local SCIM sink:

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

Open the Jaeger UI at `http://localhost:16686`, select the `keycloak`
service, and trigger a SCIM operation (create a user, run a sync). You
should see `scim.create` / `scim.replace` spans nested under the
Keycloak request span that initiated them.

## Example: Kubernetes with Grafana Tempo

Add the tracing env vars to your Keycloak Deployment alongside the plugin
mount from the [Quick start](../README.md#quick-start):

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

keycloak-scim spans will appear in Tempo (or any other OTLP-compatible
backend) under the service name `keycloak`, linked to the Keycloak request
spans that triggered them.
