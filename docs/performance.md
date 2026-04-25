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

(In progress — measurement underway. Will be filled in when the
no-op-dispatcher experiment completes and subsequent commits land.)

## Planned next steps

1. **Localize the cost** with progressively-more-aggressive instrumentation:
   no-op `dispatcher.run` to isolate plugin-internal work from
   `ScimClient.create`; if `ScimClient.create` is dominant, no-op the
   HTTP send to isolate JPA + adapter cost.
2. **Targeted optimizations** based on what (1) reveals. Likely
   candidates in descending order of probable impact:
   - Async / queued SCIM dispatch (biggest lever, biggest change)
   - SCIM `/Bulk` batching where the remote supports it
   - Skip the per-user `findById` JPA query when we know the mapping
     can't exist (e.g., during a first-time full sync)
3. **Group membership at scale**: GroupAdapter.apply(GroupModel) eagerly
   loads ALL members on every event, and a 10k-member group's PUT
   carries 10k Member objects each requiring a JPA findById. Even one
   membership change re-sends the full membership list. Incremental
   PATCH (op=ADD / op=REMOVE on `members`) instead of full replace is
   the right shape — already implemented as a config flag
   (`group-patchOp`), but the client-side code rebuilds the full
   member list rather than patching just the delta. Needs a rework.
4. **LDAP-group-membership-from-LDAP gap**: our scim-ldap-sync mapper
   has no `onImportGroupFromLDAP` hook (the analogue to user import).
   Groups federated from LDAP don't propagate. This is an architectural
   gap to address separately, after the user-side perf work lands.

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
