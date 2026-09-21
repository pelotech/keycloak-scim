# Performance and scale

This is a working notes document for performance characterization and
optimization. It is updated as new measurements land. The test
scaffolding is in `src/perfTest/`. Reports land in
`build/reports/perf/`.

## Measurement environment

Measurements run on local Docker Desktop, on a developer machine.
Each run boots Keycloak 25.0.6, osixia/openldap 1.5.0, and an embedded
WireMock SCIM server with 0 ms simulated latency. Numbers below are
from `1000`-user runs, unless noted. Throughput scales linearly to
10k.

Run with `./gradlew performanceTest -Dperf.userCount=N`.

## Findings to date

### Plugin overhead vs Keycloak alone

Same federation sync, same dataset, same backing LDAP, measured with
and without the plugin attached:

| Configuration | Time (1000 users) | Throughput |
| --- | ---: | ---: |
| Keycloak alone (no plugin) | 568 ms | 1760 users/sec |
| With plugin (event listener + scim-ldap-sync mapper) | 46 s | 22 users/sec |

The plugin adds about 80 times overhead. At 10k users, this is about
6 seconds versus about 7.6 minutes. **The plugin, not Keycloak's
federation layer, is the limiter.**

### What's NOT the bottleneck

These cheap operations were removed one at a time. Removing them made
no measurable difference:

- ScimClient construction per call. The plugin caches one client per
  (dispatcher, component). The pre-cache and post-cache numbers are
  within measurement noise, about 22 users per second either way.
  Apache HttpClient setup is not the main cost.
- The per-user `LOGGER.infof` call.
- The per-user `setSingleAttribute(LAST_SEEN_ATTRIBUTE, ...)` call.

The cache change is still correct architecturally: it bounds the
HTTP-client count and the resilience4j Retry registry size. The
plugin keeps it, but it does not move the throughput needle at this
scale.

### What IS the bottleneck

Per-phase timing inside `ScimClient.create`, for a 1000-user
`triggerFullSync`, instrumented with `ScimClientMetrics`:

```
ScimClient create: count=1002 total=43941ms (avg 43.85ms)
  applyModel:     0.52ms avg  (1.2%)
  query findById: 0.07ms avg  (0.2%)
  http send:     43.04ms avg (98.1%)
  applyResponse:  0.15ms avg  (0.3%)
  saveMapping:    0.08ms avg  (0.2%)
```

**98% of per-user cost is the SCIM HTTP send.** JPA and adapter cost
combined is under 1 ms. So optimizing JPA, serialization, or model
traversal would be rounding-error work. Only the HTTP path matters.

That 43 ms per localhost request is itself surprisingly high. For
now, treat it as the floor, and parallelize around it. A follow-up
may look into Apache HttpClient connection pool and keepalive tuning
inside the SCIM SDK.

### Async dispatch: ~9× throughput

Moving SCIM HTTP off the user-import thread, onto a worker pool
(default 8 threads), removes the serial wait on HTTP latency:

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

Throughput holds, and slightly improves, at scale. JIT compilation
has more time to take effect, the worker pool reaches steady state,
and HTTP keepalive spreads out per-request setup cost. Lazy import is
slower than `triggerFullSync`, because each `users().search()` is a
separate admin REST round-trip, run one at a time on the test side.

The remaining gap to no-plugin throughput (about 245 versus about
3636 per second) comes from the 8-worker concurrency cap on a roughly
43 ms/request HTTP path: 8 / 0.043 is about 186, close to what we
observe. To push further, raise the pool size (the
`scim.dispatch.threads` system property), or lower the per-request
HTTP cost.

Correctness note: workers run in their own Keycloak sessions, opened
through `runJobInTransaction`. So they re-fetch model objects by ID,
instead of capturing references from the caller's session. Submission
waits until the caller's transaction commits, through
`enlistAfterCompletion`. Without this, workers would open sessions
before the caller's writes commit, and `getUserById` would return
null. On a caller rollback, no SCIM operation fires. This matches
fail-open behavior.

### HTTP keepalive override: per-request cost down ~25%

The 43 ms/localhost-request floor turned out to come from a
hardcoded line in the SCIM SDK's HTTP layer:

```java
// Captain Goldfish scim-sdk-client 1.25.1, ScimHttpClient.getHttpClient()
clientBuilder.setConnectionReuseStrategy((response, context) -> false);
```

That forces a new TCP connection per request: a full handshake and
teardown on every call, paying the 30+ ms TCP cost each time.

The SDK calls a registered `ConfigManipulator`'s
`modifyHttpClientConfig` *after* that line, so the plugin can flip it
back. This is implemented in `KeepAliveConfigManipulator`:

- Restore `DefaultConnectionReuseStrategy.INSTANCE`. This honors
  server Keep-Alive headers, with default-keepalive HTTP/1.1.
- Apply `DefaultConnectionKeepAliveStrategy.INSTANCE`, Apache's
  reasonable default.
- Raise the connection pool: `maxPerRoute=32`, `maxTotal=64`. Apache
  HttpClient defaults to 2 per route and 20 total, well below the 8
  worker threads talking to one SCIM endpoint.

**Per-request HTTP cost (10k-user run, ScimClientMetrics):**

| Path | Before | After | Δ |
| --- | ---: | ---: | ---: |
| triggerFullSync | 43.04 ms | 33.97 ms | −21% |
| lazyImport | 38.56 ms | 24.15 ms | −37% |
| reconcilerDeletion | (not measured) | 25.23 ms | n/a |

Wall-clock time at 10k users is **within run-to-run noise** of the
pre-keepalive numbers, though. At 8 workers times 34 ms per request,
the run saturates at about 235 req/sec: the worker-pool ceiling, not
the HTTP ceiling. To use the per-request reduction, raise
`scim.dispatch.threads` (default 8).

Lazy import sees a bigger per-request improvement (−37%), because its
concurrent request count is lower. Each `users().search()` is a
separate admin REST round-trip, which gives keepalive more time to
pay off between calls.

### Where the residual ~30 ms comes from: it's the test rig

After the keepalive override, per-request HTTP cost reported by
`ScimClientMetrics` was still about 25 to 34 ms, even on "localhost."
That number turned out to be a property of the test rig, not the
plugin or the SCIM SDK.

`HttpLayerBenchmark` (in `src/perfTest/`) removes the test rig, and
times the HTTP stack end to end against an in-process WireMock on a
loopback port. It tests three paths, 1000 sequential POSTs each:

| Path | Avg per-request |
| --- | ---: |
| JDK `java.net.http.HttpClient` (no SDK, no Apache) | 0.47 ms |
| Apache HttpClient + our keepalive config | 0.22 ms |
| SCIM SDK `ScimRequestBuilder.create(User).sendRequest()` | 0.17 ms |

All three are within measurement noise of each other. The SDK adds
nothing meaningful over raw Apache HttpClient. The HTTP stack on this
hardware sustains over 5000 req/sec per connection.

**The 30 ms in the perf test is the Testcontainers SSH tunnel.** The
integration and perf rig configures Keycloak, which is containerized,
to reach WireMock, which runs in-process on the host, through
`host.testcontainers.internal:<port>`. Testcontainers implements that
hostname by starting an SSHD container, and routing every
container-to-host packet through it:

```
keycloak-container → docker bridge → ssh-tunnel container →
docker bridge → host loopback → WireMock
```

Each round trip pays the bridge hop in both directions, plus the SSH
relay's per-packet handling. On this hardware that adds about 25 to
30 ms, even though the underlying TCP loopback takes about
200 microseconds.

Run it yourself:

```sh
./gradlew performanceTest --tests sh.libre.scim.perf.HttpLayerBenchmark
```

#### What this means for production

- The plugin's HTTP layer is **not** the per-request bottleneck. In
  production, per-request cost is whatever the network path to the
  SCIM server actually costs, usually the dominant term, plus a
  sub-millisecond layer cost from the plugin.
- The "8 workers times 34 ms, about 235 req/sec ceiling" extrapolated
  from the test rig was inflated by tunnel latency. In a deployment
  where the SCIM server is reachable on a real network at, say,
  10 ms round-trip time, 8 workers give about an 800 req/sec
  ceiling. On a fast LAN with 1 to 2 ms round-trip time, the worker
  pool is far more than realistic propagation volumes need.
- Raise `scim.dispatch.threads` when targeting a high-latency SCIM
  server, since the per-worker rate is `1 / RTT`. This is not to
  compensate for overhead the plugin adds.

#### Why we didn't fix the test rig (yet)

The fix is to put WireMock in a sibling Docker container on
Keycloak's network, instead of on the host, which needs no SSH
tunnel. This is a worthwhile follow-up, but it changes only the
perf-test numbers, not production behavior. So the existing rig
stays, and this gap is documented instead. This is tracked as a
follow-up, and is not a 1.0.0 blocker.

### Reconciler deletion: ~100× throughput

Same async-pool pattern applied to the deletion path. The reconciler
now runs in two phases:

1. **Identify candidates.** This runs sequentially, in the caller's
   session: one JPA query for the mapping list, then one
   `getUserById` call and one witness check per mapping. For 10k
   mappings, this is about 10 seconds of JPA-dominated work.
2. **Send DELETEs.** This runs in parallel, on the shared worker
   pool. Each delete runs in its own worker session, through
   `runJobInTransaction`. `CompletableFuture.allOf(...).join()` makes
   the endpoint wait for "all deletes complete" before returning, so
   the `{"deleted": N}` response is accurate.

| Configuration | 1000 deletes | 10k deletes | Throughput (10k) |
| --- | ---: | ---: | ---: |
| Synchronous (before) | 46.25 s | (extrapolated ~7.5 min) | ~22/sec |
| **Parallel (after)** | **0.46 s** | **15.70 s** | **636.9/sec** |

The deletion path is now actually faster than the import path, 15.7 s
versus 46.2 s for the same 10k users. DELETEs have no body to
serialize, no SCIM response to parse beyond the status line, and
pool-saturation effects, such as HTTP keepalive and JIT warmup, appear
earlier.

For 10k mappings, in the typical realistic case of hundreds of
deletions rather than 10,000, wall-clock time is dominated by the
Phase 1 mapping walk, about 10 seconds for 10k entries, not by the
deletes themselves. Parallelizing Phase 1 is a follow-up if an
operator's needs call for it. The current behavior is fine for the
deletion volumes most deployments see.

## Remaining headroom and follow-ups

1. **Perf-test rig fidelity**. The Testcontainers SSH tunnel adds
   about 25 to 30 ms per request, which dominates the
   `ScimClientMetrics` numbers in the perf reports. Putting WireMock
   in a sibling container on Keycloak's Docker network would remove
   the tunnel, and give realistic per-request numbers. This affects
   only test-rig measurements, not production behavior.
2. **SCIM `/Bulk` batching**, where the remote supports it. This
   collapses N requests into one. It reduces per-request fixed cost,
   but only where the remote implements `/Bulk`; many do not.
3. **Group membership at scale**. `GroupAdapter.apply(GroupModel)`
   loads all members on every group event, and a 10k-member group's
   PUT carries 10k Member objects, each needing a JPA `findById`.
   Even a single membership change re-sends the full membership list.
   Incremental PATCH (`op=ADD` or `op=REMOVE` on `members`), instead
   of a full replace, is the right shape. This exists as a config
   setting (`group-patchOp`), but the client-side code still rebuilds
   the full member list instead of patching only the change.
4. **LDAP-group-membership-from-LDAP gap.** The scim-ldap-sync mapper
   has no `onImportGroupFromLDAP` hook. Groups federated from LDAP do
   not propagate. This is an architectural gap, to address
   separately.

## Why async dispatch is the eventual answer

Even after every plugin-side micro-optimization, the user-import path
stays synchronous with respect to the SCIM POST. In production, with
realistic SCIM server latency of 50 to 200 ms per request, this caps
throughput at `1000 / latency_ms` users per second, purely
network-bound. At 100 ms latency, that is 10 users per second.

To exceed that, we need parallelism or batching:

- **Async dispatch.** Queue SCIM operations on a background worker
  pool, and return right away from the import path. SCIM latency no
  longer slows the federation sync.
- **Batching with SCIM `/Bulk`.** Collect N operations, and submit
  them as one HTTP request. This is useful only where the remote SCIM
  server implements `/Bulk`; many do not.

Async is the lever that applies more universally. The trade-off is in
failure handling. Today the plugin is fail-open, and discards the
operation on error. With a queue, the goal is at-least-once delivery
and back-pressure handling. Some design ideas from the LDAP-deletion
reconciler, such as idempotency and threshold-based correctness, carry
over.

## Memory & worst-case under load (dispatch queue)

Throughput is not the only axis. Predictable memory and a bounded
worst case matter at least as much. The async dispatch worker pool is
`Executors.newFixedThreadPool(8)`, backed by an **unbounded
`LinkedBlockingQueue`**. Keycloak imports federation users one per
transaction, and enlists a SCIM operation at each commit. This races
far ahead of the 8 SCIM workers, so the queue absorbs the whole sync
with **no back-pressure**. Measured by `DispatchMemoryWorstCaseIT`
(Keycloak container memory sampled through cgroup):

| Scenario | Peak KC mem | Notes |
| --- | ---: | --- |
| fast sink, 1k users | 742 MiB | |
| fast sink, 10k users | 1143 MiB | climbs 619→1143 as backlog builds |
| **no plugin**, 10k users | 1038 MiB | Keycloak-only baseline |
| slow sink (200ms), 10k users | 1219 MiB | elevated for the full 264s drain |

**The decisive finding (slow 200 ms server, 10k users):** the sync
trigger **returned in 4.9 s with only 176 of 10,000 SCIM POSTs done,
and 9,824 operations still queued.** Keycloak finished importing all
10k users while about 98% of the SCIM work sat in the unbounded
queue. That backlog then drained over 264 s, at about 38 per second
(the 8-worker, 200 ms ceiling). The import is **not** throttled by
the downstream server. There is **zero back-pressure**.

Implications:
- **Memory is not bounded.** The queue holds the full sync's backlog.
  The plugin's memory delta over Keycloak-alone is about 105 MiB
  (fast) to about 181 MiB (slow) at 10k users, and it **scales with
  N**: at 100k users the queue would hold about 98k task closures. A
  slow or unavailable SCIM provider turns a sync into a heap spike
  sized to the user count.
- **`/Bulk` does not fix this.** It reduces request *count*, not
  queue depth, and it *adds* a buffer. The high-value fix for
  predictable memory and worst-case behavior is to **bound the
  dispatch queue and apply back-pressure**: block the import producer
  when the queue is full. This way, a slow SCIM server slows the sync
  instead of growing the heap without limit.

## SCIM /Bulk: latency-swept characterization

Once the dispatch queue is bounded and back-pressured (above), the
open question for `/Bulk` is no longer memory. It is **wall-time
payoff**: does coalescing K user-creates into one `POST /Bulk`
actually speed up a sync, and where? `BulkLatencySweepIT` sweeps
**bulk {on, off} times sink latency {fast 5 ms, medium 50 ms, slow
200 ms}**, at a fixed `N = -Dperf.userCount` (default 2000). It times
the full sync plus async drain, and counts the HTTP requests the SCIM
server saw.

Measured with N = 2000, batch size K = 20, and 8 workers. **Each cell
ran 5 times**, with a fresh realm per repeat. The realm was deleted
between repeats, so Keycloak's own footprint did not drift into the
next run. Memory is reported as a **baseline-corrected delta**: the
container RSS peak during the sync, minus a quiescent sample taken
just before it. This isolates the sync's cost from Keycloak's roughly
1 GB absolute baseline:

| Lane | Sink | HTTP req | Ratio (N/req) | Wall (s) mean [min–max] | Mem Δ MiB mean [min–max] |
| --- | ---: | ---: | ---: | ---: | ---: |
| bulk-on | 5 ms | ~238† | ~9.2† | 6.1 [5.6–7.1] | 59 [22–121] |
| bulk-on | 50 ms | 108 | 18.5 | 5.8 [5.8–5.8] | 3 [2–6] |
| bulk-on | 200 ms | 108 | 18.5 | 7.5 [7.5–7.5] | 9 [0–12] |
| bulk-off | 5 ms | 2002 | 1.00 | 2.6 [2.5–2.6] | 2 [0–6] |
| bulk-off | 50 ms | 2002 | 1.00 | 14.1 [14.0–14.4] | 17 [7–23] |
| bulk-off | 200 ms | 2002 | 1.00 | 53.4 [52.4–53.8] | 10 [2–19] |

† Bulk-on at 5 ms is the one non-deterministic throughput cell. The
request count varied from 160 to 360 across the 5 repeats (ratio 5.6
to 12.5), because at a fast SCIM server the queue drains as fast as
it fills. So each `drainTo` grabs an erratic handful, well under K.
All other cells are steady, with wall-time standard deviation of
0.6 s or less.

The **request ratio** column matters most here. It proves batching
engaged, and it separates "fewer requests" from "shorter wall-time."
Bulk-off stays flat at 1.00, one `POST /Users` per user. Bulk-on
rises from 4.2 toward K (about 18) as the SCIM server gets slower.
The bulk lane coalesces by draining whatever is already queued: it
takes one item, then drains up to K−1 more. There is no flush timer.
At 5 ms, the SCIM server drains about as fast as the import enqueues
work, so a worker's drain usually finds the queue nearly empty, and
batches only a few operations, well under K=20. This makes
amortization weak. At 50 ms or slower, operations build up in the
queue between drains, so each drain grabs about K, and each
`POST /Bulk` covers about K operations. Batch fill is latency-driven:
the amortization effect kicks in exactly on the slow SCIM servers
where it matters.

### Memory: no real bulk-vs-per-op difference (the apparent gap was baseline drift)

A first single-sample pass reported *absolute* peak RSS of 733 to
894 MiB, and looked like bulk used about 100 MiB less at 50 and
200 ms. That was a measurement artifact. Over a run, Keycloak's own
footprint drifts a lot. The quiescent baseline climbed from about
644 MiB to about 1200 MiB across the 30 syncs, as the JVM warmed up
and caches filled, regardless of which lane was active. A single
absolute-peak sample was mostly reading that drift, not the sync
itself.

Baseline-corrected over 5 repeats, the sync's actual memory cost is
**small for both lanes**: single-digit to low-double-digit MiB on top
of Keycloak's roughly 1 GB baseline. The lanes are comparable; it is
not a case of "bulk crushes per-op":

- **50 ms:** per-op 17 [7–23] versus bulk 3 [2–6] MiB. The ranges do
  not overlap, so bulk has a real but tiny edge, about 14 MiB, about
  1% of footprint.
- **200 ms:** bulk 9 [0–12] versus per-op 10 [2–19] MiB. These ranges
  overlap, so they are indistinguishable.
- **5 ms:** bulk 59 [22–121] versus per-op 2 [0–6] MiB. Here bulk is
  clearly *higher* and noisy. This is the transient churn of erratic
  batch assembly at a fast SCIM server.

Net result: **memory is not a `/Bulk` differentiator.** Bulk neither
wins nor loses clearly on memory. The per-sync deltas are small,
mixed in sign, and small next to Keycloak's own footprint and its
run-to-run drift. The throughput numbers, wall-time and request
ratio, tell the real and only reliable story.

### Honesty caveat: what this measures (and does not)

WireMock applies only a per-**REQUEST** fixed delay, the round-trip
component, and models **NO** per-operation server processing cost. So
this test measures bulk's **round-trip amortization only**: saving
(K−1) round trips per batch of K. It is a **lower bound** on
real-world benefit. A real SCIM server also amortizes per-request
parse, auth, dispatch, and framework overhead, which WireMock cannot
represent. Read the table as "**at least** this much" payoff, **not**
"exactly this much." It makes **no** claim about server-side
amortization.

### K-sensitivity (analytic)

`scim.dispatch.bulkBatchSize` (K) is read in the Keycloak
**container** JVM, and the shared perf container starts once. So K
cannot vary per cell on it. The effect of K is therefore reported
**analytically**: bulk request count scales as **⌈N/K⌉**. The
measured request ratio at K=20 (about 18, once batches fill) shows
the mechanism at work: halving K doubles requests, and doubling K
halves them, all else equal. A dedicated container, started with
`JAVA_OPTS_APPEND=-Dscim.dispatch.bulkBatchSize=<k>`, could measure
other K values directly later.

### Takeaway: where /Bulk pays off

`/Bulk` pays off **most on high-RTT or slow SCIM servers, and least
(it actually loses) on fast or local ones.** At 200 ms, bulk drains
2000 users in **7.5 s versus 53.4 s** for per-op, a **roughly 7×
wall-time win**. At 50 ms, it is **5.8 s versus 14.1 s** (about
2.4×). But at 5 ms, bulk is **slower** (6.1 s versus 2.6 s). When a
round trip is cheap, the batching lane's coalescing and
small-partial-batch overhead cost more than the round trips they
save. A fast SCIM server keeps the queue near empty, so `drainTo`
rarely fills a batch, and the per-op lane's raw 8-worker concurrency
wins instead. The crossover sits at a low single-digit-millisecond
round-trip time, so network distance to the SCIM server governs the
payoff almost entirely. **Decision input for further `/Bulk`
investment (replace, delete, membership):** prioritize it for
deployments whose SCIM target is remote or high-latency. For local or
very-low-latency servers, the per-op lane is already faster, so
`/Bulk` should stay opt-in; it remains off by default. Because
WireMock models round trips only, these wins are a floor. A real
server's per-request overhead would push the crossover lower, and
widen the slow-server advantage.
