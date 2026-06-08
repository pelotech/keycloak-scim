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

### Interaction with `sendWithAuthRefresh`

The resilience4j retry executes *inside* the `sendWithAuthRefresh`
supplier. A 5xx is therefore retried within a single auth-refresh
cycle; the auth-refresh's own 401/403 re-mint-and-retry sits one level
out and is unaffected. The two mechanisms compose without overlap
because their status sets are disjoint.

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
`503`, `503`, then a clean `201`. Assert at least 3 attempts to
`/Users` and eventual success.

`429` and `503` traverse the identical retry path, so a second
integration test for `429` is redundant — the unit test covers the
status set, and the IT proves the wiring once.

## Docs

- Update the retry-config comment in `ScimClient` (currently states 5xx
  "are not currently retried" and references the old IT name) to
  describe the 5xx + 429 result-based retry.
- Update `docs/roadmap.md`: mark the SCIM 5xx item done, and break out
  the **token-endpoint 5xx retry** as its own remaining fast-follow
  entry under Resilience.
