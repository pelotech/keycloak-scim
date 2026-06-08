# Token-Endpoint 5xx / 429 Retry — Design

## Problem

`OAuthClientCredentialsTokenSource.HttpTokenMinter` mints OAuth
`client_credentials` tokens with a plain Apache `HttpClient`, outside the
resilience4j path that now guards SCIM-endpoint requests
(see `2026-06-07-scim-5xx-retry-design.md`). A transient failure from the
token endpoint — a `5xx`, a `429`, or a network blip — fails the mint
outright, which fails every SCIM operation that needs a fresh token at
that moment. The mint has no retry of any kind.

A second problem blocks adding one: `mint` throws a bare
`RuntimeException` for *every* failure, so nothing downstream can tell a
transient `503` (worth retrying) from a fatal `400 invalid_client`
(retrying just delays a guaranteed failure).

## Goal

Retry transient token-endpoint failures — network/IO faults, `5xx`, and
`429` — symmetric with the SCIM-endpoint retry, with a deliberately
small budget because retries run under the mint lock.

## Non-goals (deliberately deferred)

- **Proactive refresh-ahead-of-expiry.** Lazy refresh with 30s skew
  stays as-is (tracked separately in `docs/roadmap.md`).
- **Extracting a shared retry-policy helper.** `isRetryableStatus` lives
  on `ScimClient` and is reused here (same package). Hoisting it into a
  neutral type is deferred unless review calls for it — YAGNI for now.

## Decisions

1. **Scope:** retry network/IO faults + `5xx` + `429`. Never retry
   `4xx` — `400 invalid_client` / `401 bad credentials` are
   configuration errors, not transient.
2. **Mechanism:** reuse resilience4j (`resilience4j-retry` is already on
   the classpath via `ScimClient`; adds no new dependency).
3. **Budget:** `maxAttempts(3)` with exponential backoff — *not* the
   SCIM side's `maxAttempts(10)`. The mint runs under the per-component
   `ReentrantLock`, so each attempt + backoff stalls every token-needing
   thread; a long budget would turn a sustained outage into a ~20s lock
   stall. The `401`/`403` auth-refresh path already supplies a second
   re-mint cycle on top of this.

## Design

### Error typing

Introduce one nested exception on `OAuthClientCredentialsTokenSource` so
failures carry enough information to classify:

```java
static final class TokenEndpointException extends RuntimeException {
    final int status;   // HTTP status; 0 == transport fault (no response)
    // factories: http(int status, String message)
    //            transport(String message, Throwable cause)
}
```

`HttpTokenMinter.mint` is rewired to throw it:

- Non-2xx HTTP response → `TokenEndpointException.http(status, …)`
  (replaces today's bare `RuntimeException`).
- `IOException` (network/transport) → `TokenEndpointException.transport(…, cause)`,
  i.e. `status == 0`.
- Malformed body / missing `access_token` → stays `IllegalStateException`
  (a 2xx response with a junk body is not transient and is **not**
  retried).

### Retryable-failure predicate

A package-private static, mirroring `ScimClient.isRetryableStatus`, so the
policy is unit-testable without standing up a `Retry`:

```java
static boolean isRetryableMintFailure(Throwable t) {
    return t instanceof TokenEndpointException te
        && (te.status == 0 || ScimClient.isRetryableStatus(te.status));
}
```

`status == 0` covers transport faults; `isRetryableStatus` (429 + any
5xx) covers HTTP errors. `4xx`, `IllegalStateException` (malformed body),
and any other throwable fall through to `false` → not retried. Reusing
`ScimClient.isRetryableStatus` keeps a single source of truth for "which
HTTP status is transient" across both retry paths.

### Wiring

`OAuthClientCredentialsTokenSource` gains an instance `Retry`:

```java
RetryConfig.custom()
    .maxAttempts(3)
    .intervalFunction(IntervalFunction.ofExponentialBackoff())
    .retryOnException(OAuthClientCredentialsTokenSource::isRetryableMintFailure)
    .build();
```

`mintAndStore()` wraps the existing call:

```java
MintResult r = retry.executeSupplier(() -> minter.mint(config));
```

This sits **inside** the existing `entry.lock` critical section by
design (decision 3): concurrent token-needing threads queue behind one
retry sequence rather than each independently hammering a down endpoint.
The double-checked cache read on lock acquisition is unchanged, so a
thread that waited out another thread's successful retry still gets the
freshly-cached token without minting again.

### Logging

`HttpTokenMinter` currently logs `ERROR` on every non-2xx — under retry
that would emit up to three errors for one eventually-successful mint.
Split the level using the same policy that drives the retry:

- retryable status (`5xx`/`429`) or transport fault → `WARN` (will be
  retried);
- non-retryable `4xx` → `ERROR` (genuine configuration error, fatal).

A resilience4j `onRetry` event listener logs one `WARN` per retry attempt
(`retrying token mint for component … (attempt n) after …`).

### Behavior preservation

- **Transient blip** (`503 → 200`): mint retries and succeeds; the SCIM
  operation proceeds. New behavior.
- **Sustained outage** (persistent `503`): the 3 attempts exhaust, the
  `TokenEndpointException` propagates, the mint fails, and the operation
  fails fail-open exactly as today — the plugin survives. Unchanged.
- **Config error** (`400`/`401`): not retried; fails on the first
  attempt as today.

## Testing

### Unit (fast, no Docker)

New `OAuthClientCredentialsTokenSourceRetryTest` (or a case added to the
existing token-source test) over `isRetryableMintFailure` — this is the
real coverage for the policy decision, mirroring `ScimClientRetryTest`:

- `TokenEndpointException` with status `500/502/503/504/429` → `true`
- `TokenEndpointException` with status `0` (transport) → `true`
- `TokenEndpointException` with status `400/401/403/404/409/200` → `false`
- `IllegalStateException` (malformed body) → `false`
- generic `RuntimeException` → `false`

Per the SCIM precedent, the resilience4j wiring itself is proven by the
IT rather than a timing-dependent unit test.

### Integration

- **New positive case** in `ScimOidcAuthIT`: a WireMock token-endpoint
  scenario serving `503` then `200` (via the existing
  `useWireMockTokenEndpoint` harness). Assert the SCIM POST eventually
  succeeds and the token endpoint was hit ≥2 times. Mirrors
  `ScimResilienceIT#serverErrorIsRetriedAndEventuallySucceeds`.
- **Existing `tokenEndpointDown_eventFailsButPluginSurvives`:** stays
  green — a persistent `503` exhausts the retries and the operation
  still fails fail-open. Tighten its `attempts >= 1` assertion to
  `>= 2` so it also proves the retry now fires.

## Docs

Update `docs/roadmap.md`: mark **Token-endpoint 5xx retry** done (it is
currently an open fast-follow under "Auth mode"), cross-referencing the
SCIM-endpoint retry entry under "Resilience".
