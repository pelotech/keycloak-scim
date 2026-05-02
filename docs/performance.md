# Performance and scale

A working notes document for the perf characterization and optimization
work. Updated as measurements land. The corresponding test scaffolding
is in `src/perfTest/`; reports land in `build/reports/perf/`.

## Measurement environment

Local Docker Desktop on a developer machine. Each run boots Keycloak
25.0.6, osixia/openldap 1.5.0, and an embedded WireMock SCIM sink with
0 ms simulated latency. Numbers reported below are from `1000`-user
runs unless noted. Throughput scales linearly to 10k.

Run with `./gradlew performanceTest -Dperf.userCount=N`.

## Findings to date

### Plugin overhead vs Keycloak alone

Same federation sync, same dataset, same backing LDAP — measured with
and without the plugin attached:

| Configuration | Time (1000 users) | Throughput |
| --- | ---: | ---: |
| Keycloak alone (no plugin) | 568 ms | 1760 users/sec |
| With plugin (event listener + scim-ldap-sync mapper) | 46 s | 22 users/sec |

The plugin contributes ~80× overhead. At 10k users this is ~6 s vs
~7.6 minutes. **The plugin, not Keycloak's federation layer, is the
limiter.**

### What's NOT the bottleneck

Cheap operations stripped one at a time, no measurable improvement:

- ScimClient construction per call. We cache one client per
  (dispatcher, component); pre-cache and post-cache numbers are
  inside measurement noise (~22 users/sec both ways). Apache
  HttpClient setup isn't the dominant cost.
- The per-user `LOGGER.infof` call.
- The per-user `setSingleAttribute(LAST_SEEN_ATTRIBUTE, ...)` call.

The cache change is correct architecturally (bounded HTTP-client
count, bounded resilience4j Retry registry size) and is kept; it just
doesn't move the throughput needle at this scale.

### What IS the bottleneck

Per-phase timing inside `ScimClient.create` for a 1000-user
triggerFullSync (instrumented via `ScimClientMetrics`):

```
ScimClient create: count=1002 total=43941ms (avg 43.85ms)
  applyModel:     0.52ms avg  (1.2%)
  query findById: 0.07ms avg  (0.2%)
  http send:     43.04ms avg (98.1%)
  applyResponse:  0.15ms avg  (0.3%)
  saveMapping:    0.08ms avg  (0.2%)
```

**98% of per-user cost is the SCIM HTTP send.** JPA + adapter cost
combined is under 1ms. Implication: optimizing JPA, serialization,
or model traversal would be rounding-error work; only the HTTP path
matters.

That 43ms-per-localhost-request is itself surprisingly high. For now
treat it as the floor and parallelize around it; a follow-up may dig
into Apache HttpClient connection pool / keepalive tuning inside the
SCIM SDK.

### Async dispatch: ~9× throughput

Pulling SCIM HTTP off the user-import thread onto a worker pool
(default 8 threads) collapses synchronous serialization on HTTP
latency:

| Configuration | triggerFullSync (1000 users) | Throughput |
| --- | ---: | ---: |
| Keycloak alone, no plugin | 549 ms | 1821 users/sec |
| With plugin, sync dispatch | 46 s | 22 users/sec |
| With plugin, **async dispatch** | **5.4 s** | **186 users/sec** |

Lazy import is similar (4.5 s → 222 users/sec).

Verified at full 10k scale (`./gradlew performanceTest -Dperf.userCount=10000`):

| Scenario | Time (10k users) | Throughput |
| --- | ---: | ---: |
| Keycloak alone, no plugin | 2.84 s | 3520 users/sec |
| triggerFullSync with plugin | 46.18 s | 216.6 users/sec |
| Lazy-import via admin REST search | 1m 21.78s | 122.3 users/sec |
| Reconciler deletion (parallel) | 15.70 s | 636.9 deletes/sec |

Throughput holds (and slightly improves) at scale — JIT compilation
has more time to take effect, the worker pool reaches steady state,
and HTTP keepalive amortizes per-request setup. Lazy-import is
slower than triggerFullSync because each `users().search()` is a
separate admin REST round-trip serialized on the test side.

The remaining gap to no-plugin (~245 vs ~3636/sec) is the 8-worker
concurrency cap on a ~43 ms/request HTTP path: 8/0.043 ≈ 186 —
close to what we observe. To push further: raise pool size
(`scim.dispatch.threads` system property) or fix the per-request
HTTP cost.

Correctness: workers run in their own Keycloak sessions opened via
`runJobInTransaction`, so they re-fetch model objects by id rather
than capturing references from the caller's session. Submission is
deferred until the caller's transaction commits via
`enlistAfterCompletion` — without this, workers open sessions before
the caller's writes are committed and `getUserById` returns null.
On caller rollback, no SCIM op fires (consistent with fail-open
semantics).

### HTTP keepalive override: per-request cost down ~25%

The 43 ms/localhost-request floor turned out to come from a hardcoded
line in the SCIM SDK's HTTP layer:

```java
// Captain Goldfish scim-sdk-client 1.25.1, ScimHttpClient.getHttpClient()
clientBuilder.setConnectionReuseStrategy((response, context) -> false);
```

That forces a new TCP connection per request — full handshake +
teardown each call, paying the 30+ ms TCP cost on every operation.

The SDK invokes a registered `ConfigManipulator`'s
`modifyHttpClientConfig` *after* that line, so we can flip it back.
Implementation in `KeepAliveConfigManipulator`:

- Restore `DefaultConnectionReuseStrategy.INSTANCE` (honor server
  Keep-Alive headers, default-keepalive HTTP/1.1).
- Apply `DefaultConnectionKeepAliveStrategy.INSTANCE` (Apache's
  reasonable default).
- Raise the connection pool: `maxPerRoute=32`, `maxTotal=64`.
  Apache HttpClient defaults to 2/route, 20 total — way below 8
  worker threads talking to one SCIM endpoint.

**Per-request HTTP cost (10k-user run, ScimClientMetrics):**

| Path | Before | After | Δ |
| --- | ---: | ---: | ---: |
| triggerFullSync | 43.04 ms | 33.97 ms | −21% |
| lazyImport | 38.56 ms | 24.15 ms | −37% |
| reconcilerDeletion | (not measured) | 25.23 ms | — |

Wall-clock at 10k users is **within run-to-run noise** of the
pre-keepalive numbers, though, because at 8 workers × 34 ms/req we
saturate at ~235 req/sec — the worker-pool ceiling, not the HTTP
ceiling. To capitalize on the per-request reduction, raise
`scim.dispatch.threads` (default 8).

Lazy-import sees a bigger per-request improvement (−37%) because
its concurrent request count is lower (each `users().search()` is
a separate admin REST round-trip), giving keepalive more time to
amortize between calls.

### Where the residual ~30 ms comes from: it's the test rig

After the keepalive override, per-request HTTP cost reported by
`ScimClientMetrics` was still ~25–34 ms, even on "localhost." That
number turned out to be a property of the test rig, not the
plugin or the SCIM SDK.

`HttpLayerBenchmark` (in `src/perfTest/`) strips the test rig away
and times the HTTP stack end-to-end against an in-process WireMock
on a loopback port. Three paths, 1000 sequential POSTs each:

| Path | Avg per-request |
| --- | ---: |
| JDK `java.net.http.HttpClient` (no SDK, no Apache) | 0.47 ms |
| Apache HttpClient + our keepalive config | 0.22 ms |
| SCIM SDK `ScimRequestBuilder.create(User).sendRequest()` | 0.17 ms |

All three are within measurement noise of each other; the SDK adds
nothing meaningful over raw Apache HttpClient. The HTTP stack on
this hardware sustains ~5000+ req/sec/connection.

**The 30 ms in the perf test is the Testcontainers SSH tunnel.**
The integration / perf rig configures Keycloak (containerized) to
reach WireMock (in-process on the host) via
`host.testcontainers.internal:<port>`. Testcontainers implements
that hostname by spinning up an SSHD container and routing every
container-to-host packet through it:

```
keycloak-container → docker bridge → ssh-tunnel container →
docker bridge → host loopback → WireMock
```

Each round-trip pays the bridge hop both directions plus the SSH
relay's per-packet handling. On this hardware that's ~25–30 ms,
even though the underlying TCP loopback is ~200 µs.

Run it yourself:

```sh
./gradlew performanceTest --tests sh.libre.scim.perf.HttpLayerBenchmark
```

#### What this means for production

- The plugin's HTTP layer is **not** the per-request bottleneck.
  In production, per-request cost is whatever the network path to
  the SCIM sink actually is — usually the dominant term — plus a
  sub-millisecond layer cost from us.
- The "8 workers × 34 ms ≈ 235 req/sec ceiling" we extrapolated
  from the test rig was inflated by tunnel latency. In a deployment
  where the SCIM sink is reachable on a real network at, say,
  10 ms RTT, 8 workers ≈ 800 req/sec ceiling; on a fast LAN with
  1–2 ms RTT, the worker pool is wildly over-provisioned for
  realistic propagation volumes.
- `scim.dispatch.threads` should still be raised when targeting
  a high-latency SCIM sink (the per-worker rate is `1 / RTT`),
  but not because of overhead the plugin is adding.

#### Why we didn't fix the test rig (yet)

The fix is to put WireMock in a sibling Docker container on
Keycloak's network rather than on the host (no SSH tunnel needed).
That's a worthwhile follow-up but doesn't change any production
behavior — only the perf-test numbers — so we've kept the existing
rig and documented the gap. Tracked as a follow-up; not a 1.0.0
blocker.

### Reconciler deletion: ~100× throughput

Same async-pool pattern applied to the deletion path. The reconciler
now runs in two phases:

1. **Identify candidates** (sequential, in caller's session): one
   JPA query for the mapping list, then one `getUserById` +
   witness-evaluate per mapping. For 10k mappings this is ~10s of
   JPA-dominated work.
2. **Issue DELETEs** (parallel, on the shared worker pool): each
   delete runs in its own worker session via `runJobInTransaction`.
   `CompletableFuture.allOf(...).join()` gives the endpoint a
   synchronous "all deletes complete" return semantics so the
   `{"deleted": N}` response is meaningful.

| Configuration | 1000 deletes | 10k deletes | Throughput (10k) |
| --- | ---: | ---: | ---: |
| Synchronous (before) | 46.25 s | (extrapolated ~7.5 min) | ~22/sec |
| **Parallel (after)** | **0.46 s** | **15.70 s** | **636.9/sec** |

The deletion path is now actually faster than the import path
(15.7 s vs 46.2 s for the same 10k users). DELETEs have no body to
serialize, no SCIM response to parse beyond the status line, and
pool-saturation effects (HTTP keepalive, JIT warmup) appear earlier.

For 10k mappings with the typical realistic case of "100s of
deletions, not 10k," the wall-clock is dominated by the Phase 1
mapping walk (~10s for 10k entries) rather than the deletes
themselves. Parallelizing Phase 1 is a follow-up if the operator
shape calls for it; the current behavior is fine for the deletion
volumes most deployments will see.

## Remaining headroom and follow-ups

1. **Perf-test rig fidelity**. The Testcontainers SSH tunnel adds
   ~25–30 ms per request, which dominates the `ScimClientMetrics`
   numbers in our perf reports. Putting WireMock in a sibling
   container on Keycloak's Docker network would eliminate the
   tunnel and give realistic per-request numbers. Doesn't affect
   production behavior — only test-rig measurements.
2. **SCIM `/Bulk` batching** where the remote supports it. Collapses
   N requests into one. Reduces per-request fixed cost but only
   useful where the remote implements `/Bulk` (many do not).
3. **Group membership at scale**. `GroupAdapter.apply(GroupModel)`
   eagerly loads ALL members on every group event, and a 10k-member
   group's PUT carries 10k Member objects each requiring a JPA
   findById. Even a single membership change re-sends the full
   membership list. Incremental PATCH (op=ADD / op=REMOVE on
   `members`) instead of full replace is the right shape — exists as
   a config flag (`group-patchOp`), but the client-side code still
   rebuilds the full member list rather than patching the delta.
4. **LDAP-group-membership-from-LDAP gap**: our scim-ldap-sync mapper
   has no `onImportGroupFromLDAP` hook. Groups federated from LDAP
   don't propagate. Architectural gap to address separately.

## Why async dispatch is the eventual answer

Even after every plugin-side micro-optimization, the user-import path
remains synchronous w.r.t. the SCIM POST. In production with realistic
SCIM sink latency (50–200 ms per request), this caps throughput at
1000/latency_ms users/sec — purely network-bound. At 100 ms latency,
that's 10 users/sec.

To exceed that, we need parallelism or batching:

- **Async dispatch**: queue SCIM operations on a background worker
  pool; return immediately from the import path. The federation sync
  is no longer slowed by SCIM latency.
- **Batching via SCIM /Bulk**: collect N operations and submit as one
  HTTP request. Only useful where the remote SCIM server implements
  /Bulk (many do not).

Async is the more universally-applicable lever. Trade-off is on
failure semantics — currently fail-open with the operation discarded
on error; with a queue, we want at-least-once delivery and
back-pressure handling. Some of the design considerations from the
LDAP-deletion reconciler (idempotency, threshold-based correctness)
carry over.
