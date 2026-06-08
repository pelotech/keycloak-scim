# SCIM 5xx / 429 Retry — Design

## Problem

`ScimClient`'s resilience4j retry only fires on thrown exceptions
(`ProcessingException`, `IORuntimeException` — i.e. network-layer
faults). The Captain-Goldfish SCIM SDK does **not** throw on HTTP error
responses: a 5xx comes back as a `ServerResponse` with
`isSuccess() == false`. So today a transient `503` from the SCIM sink
results in a **single attempt, no retry** — the operation is abandoned
and the user is left unmapped until the next sync.

This gap is currently pinned by
`ScimResilienceIT#serverErrorIsNotRetriedGap`, which asserts exactly one
attempt on a persistent `503` and documents that widening the policy
should flip the assertion.

Most operators expect transient server errors to retry with backoff,
the same way network faults already do.

## Goal

Retry SCIM operations on transient HTTP status responses (5xx and
`429`) using the existing exponential-backoff policy, closing the
pinned gap.

## Non-goals (deliberately deferred)

- **Token-endpoint 5xx retry.** `OAuthClientCredentialsTokenSource`'s
  `HttpTokenMinter` is a separate mechanism (plain Apache HttpClient,
  throws `RuntimeException` on non-2xx) that runs outside the
  resilience4j path. Retrying it is independent work, tracked as a
  fast-follow in `docs/roadmap.md`.
- **`importResources` list-GET.** The list call has no retry wrapper
  today; this change does not add one.
- **Unwrapped fallback `sendRequest()` calls.** The 405→PATCH and
  404/400 re-create paths in `replace`, and the `DELETE_REMOTE` path in
  `importResources`, issue raw requests outside the retry supplier.
  Out of scope.

## Decisions

1. **Scope:** SCIM endpoint only; token endpoint is a fast-follow.
2. **Retryable status set:** `429` (Too Many Requests) plus all `5xx`
   (`status >= 500`). `429` is the most retry-appropriate status — the
   server is explicitly asking the client to back off, which is exactly
   what exponential backoff provides.
3. **Uniform across operations:** `create`, `replace`, and `delete`
   share one `RetryConfig`, so a single result predicate covers all
   three. `create` (a non-idempotent `POST`) is retried too — this is
   consistent with the **existing** network-fault retry, which already
   retries `create` and carries the identical "did it land before the
   failure?" duplicate risk. SCIM duplicate-create normally returns
   `409 Conflict` (not retryable here → stops), and the import/mapping
   reconciliation path already cleans up dangling mappings.

## Design

### Retryable-status predicate

A package-private static on `ScimClient`, so the policy is unit-testable
without constructing a client or standing up Keycloak:

```java
// 429 (rate-limited) + any 5xx are transient; retry with backoff.
// 401/403 are deliberately excluded — sendWithAuthRefresh handles those
// (token re-mint + one retry), and this retry runs inside that wrapper.
static boolean isRetryableStatus(int status) {
    return status == 429 || status >= 500;
}
```

### Wiring

Add `.retryOnResult(...)` to the existing `RetryConfig.custom()` chain
in the private `ScimClient` constructor, alongside `.retryExceptions(...)`:

```java
.retryOnResult(result ->
    result instanceof ServerResponse<?> resp && isRetryableStatus(resp.getHttpStatus()))
```

`maxAttempts(10)` and `IntervalFunction.ofExponentialBackoff()` are
reused unchanged.

`importResources` is unaffected: it never calls `retry.executeSupplier`,
so the predicate only runs for the suppliers actually wrapped by the
retry (`create`/`replace`/`delete`). Adding `.retryOnResult` to the
shared config does not retroactively wrap the list-GET.

### Behavior

- **Transient 5xx/429:** retried up to the `maxAttempts` cap with
  exponential backoff. On recovery, the successful `ServerResponse`
  flows through normally.
- **Exhaustion:** the last (still-failing) `ServerResponse` is returned.
  Each operation's existing `!response.isSuccess()` logging fires
  exactly as it does today — no change to the terminal path.
- **Non-retryable statuses** (2xx, 4xx including `400/404/409`, and
  `401/403`): predicate returns `false`, behavior unchanged. `401/403`
  remain the responsibility of `sendWithAuthRefresh`, which wraps the
  retry supplier.
- **`replace` fallback paths unchanged.** A real `405/404/400` is
  non-retryable, so it returns immediately from the supplier and
  `replace`'s existing fallback logic (405→PATCH, 404/400 re-create)
  runs exactly as today. The only change is that a transient `5xx` on
  the initial PUT now retries *before* reaching that fallback block,
  rather than falling straight through.

### Interaction with `sendWithAuthRefresh`

The resilience4j retry executes *inside* the `sendWithAuthRefresh`
supplier. A 5xx is therefore retried within a single auth-refresh
cycle; the auth-refresh's own 401/403 re-mint-and-retry sits one level
out. Their status sets are disjoint, so neither mechanism retries on
the other's trigger.

Note one bounded interaction: `sendWithAuthRefresh` re-invokes the
*entire* supplier (i.e. the full `retry.executeSupplier`) once on a
401/403. In the rare case where auth expiry and a 5xx outage overlap
(`401 → re-mint → 5xx, 5xx, ...`), the second invocation gets a fresh
retry budget — so a single operation can incur up to `2 × maxAttempts`.
This is bounded and acceptable; it is not a new unbounded path.

## Testing

### Unit (fast, no Docker)

A new unit test on `isRetryableStatus`:

- Retryable → `true`: `429`, `500`, `502`, `503`, `504`.
- Not retryable → `false`: `200`, `201`, `400`, `401`, `403`, `404`,
  `409`.

This is the authoritative coverage for the status-set decision,
including `429`.

### Integration (flip the pinned gap)

Rewrite `ScimResilienceIT#serverErrorIsNotRetriedGap` →
`serverErrorIsRetriedAndEventuallySucceeds`, mirroring the existing
`retryOnConnectionFaultEventuallySucceeds`: a WireMock scenario serving
`429`, `503`, then a clean `201`. Assert at least 3 attempts to
`/Users` and eventual success.

Serving both a `429` and a `503` before the success exercises **both**
arms of `isRetryableStatus` (`== 429` and `>= 500`) end-to-end in a
single scenario, at no extra cost — so no separate `429` IT is needed.
The unit test remains the authoritative coverage for the full status
set; the IT proves the wiring.

The `ScimResilienceIT` class-level Javadoc currently documents the
old "does NOT retry on HTTP error responses … 5xx no-retry gap-pinning
assertion" behavior. Update it alongside the renamed test so it
describes the new 5xx + 429 retry behavior.

## Docs

- Update the retry-config comment in `ScimClient` (currently states 5xx
  "are not currently retried" and references the old IT name) to
  describe the 5xx + 429 result-based retry.
- Update the `ScimResilienceIT` class-level Javadoc (see Integration
  testing above).
- Rewrite the `docs/roadmap.md` Resilience entry — do **not** just
  check it off. The current bullet frames a *combined* SCIM+token gap
  ("should cover both SCIM-endpoint and token-endpoint 5xx together").
  Replace it with: (a) the SCIM 5xx/429 retry marked done, and (b) a
  new standalone fast-follow bullet for the still-open **token-endpoint
  5xx retry**, dropping the "cover both together" framing.
- Touch up the roadmap Auth-mode bullet's "Token-endpoint 5xx retry"
  line, whose "symmetric with the existing SCIM 5xx no-retry gap"
  cross-reference goes stale once that gap is closed.
