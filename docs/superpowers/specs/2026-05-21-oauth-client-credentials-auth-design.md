# OAuth 2.0 client_credentials auth for outbound SCIM — design

Date: 2026-05-21
Status: Draft (pre-implementation)

## Context

Some downstream SCIM receivers authenticate incoming callers by
verifying a Keycloak-issued JWT against the realm's JWKS and matching
a claim (typically `azp`) against a configured client id. This is the
canonical "machine-to-machine via OAuth 2.0 client_credentials" shape:
the SCIM caller mints a token at the realm's token endpoint, then
sends `Authorization: Bearer <jwt>` on each SCIM request.

This plugin today only supports static `BEARER` (opaque shared
secret), `BASIC_AUTH`, or `NONE` — none of which produce a JWT a
receiver can verify against Keycloak's JWKS.

This design adds an `OAuth 2.0 client_credentials` auth mode so the
plugin can send standard Keycloak-issued bearer tokens, lining up with
receivers that already expect this shape.

## Goals

1. Add `auth-mode=CLIENT_CREDENTIALS` to the SCIM provider component
   config. Configurable via Admin Console and Keycloak's component
   admin API.
2. On each SCIM call, mint (or reuse a cached) Keycloak access token
   via client_credentials grant, send as `Authorization: <token_type>
   <access_token>` (token_type defaults to `Bearer`).
3. Standards-compliant wire shape: `client_secret_basic` to the token
   endpoint, `Authorization: Bearer <jwt>` on SCIM requests, JWT
   carrying the standard `azp`/`iss`/`exp`/`iat` claims so any
   JWKS-based verifier can authenticate the caller.
4. Operational requirements specific to this plugin's high-volume
   async dispatch path:
   - **JVM-wide token cache** so the bulk LDAP-import path (which fans
     out via `ScimDispatcher.runAsync`, constructing a fresh
     `ScimClient` per worker per submission) doesn't flood the token
     endpoint.
   - **`expires_in` honored** with a 30-second skew so long-lived
     dispatcher instances don't silently fail at the token-TTL
     boundary.
   - **On-401/403 from the SCIM endpoint**, invalidate the cached
     token, mint a fresh one, retry the SCIM op once.
5. Additive change. `NONE`, `BASIC_AUTH`, `BEARER` paths unchanged. No
   migration required for existing operators.

## Non-goals

The following were considered and explicitly deferred:

- **OIDC discovery** (`oidc-issuer` + `.well-known/openid-configuration`
  lookup) — operator supplies the token endpoint URL directly.
- **`client_secret_post` client authentication** — only
  `client_secret_basic` is supported. Add when there's a concrete IdP
  that requires Post.
- **`private_key_jwt` / mTLS bearer (RFC 8705)** — same reasoning. The
  `OAuthClientCredentialsTokenSource` class is structured so a sibling
  source class can be added later without changing `ScimClient`'s
  integration seam.
- **`audience` parameter** — Keycloak doesn't honor `audience` on the
  client_credentials request body (audience claims come from
  realm-level token mappers, not request params), so passing it on
  the wire would have no effect. Receivers that need an `aud` claim
  should configure it via a token mapper on the Keycloak client.
- **Proactive token refresh** (background scheduler refreshing before
  expiry) — lazy refresh with 30s skew is correct (in-flight requests
  during a mint aren't 401'd by Keycloak yet) and the ~1–2% throughput
  cost at expiry boundaries during sustained bulk imports is
  acceptable. A future swap to proactive is an internal change to
  `OAuthClientCredentialsTokenSource` with no API impact.
- **Retry on token-endpoint 5xx** — symmetric with the existing
  documented `ScimResilienceIT#serverErrorIsNotRetriedGap` for SCIM
  5xx. Any future widening should cover both endpoints together.
- **End-to-end test against a live Keycloak token endpoint** — the
  WireMock-based integration tests cover the wire contract. A
  live-Keycloak test is a planned follow-up to catch JWT-format
  divergence; tracked separately.
- **Configurable extra headers** (e.g., `X-Forwarded-Tenant`) —
  separately discussed nice-to-have, not blocking parity.

## Architecture

Three files change; one new file:

```
src/main/java/sh/libre/scim/
├── storage/ScimStorageProviderFactory.java   ← +1 enum value, +4 config fields, +1 validator branch
├── core/ScimClient.java                       ← +CLIENT_CREDENTIALS auth branch, +on-401 retry helper
└── core/OAuthClientCredentialsTokenSource.java   (NEW)
```

Data flow on a SCIM call:

```
ScimEventListener / async worker
        │
        ▼
ScimClient.create / replace / delete / importResources
        │
        │  ┌─ existing path (NONE/BASIC_AUTH/BEARER):
        │  │     defaultHeaders["Authorization"] set once at construction
        │  │
        │  └─ CLIENT_CREDENTIALS path (this work):
        │       1. tokenSource.currentAuthorizationHeader()
        │            ├─ cached & not near expiry → return cached header
        │            └─ else → POST grant_type=client_credentials → cache → return
        │       2. defaultHeaders.put("Authorization", value)
        │       3. send SCIM request
        │       4. if 401/403:
        │            tokenSource.invalidate()
        │            refresh header
        │            retry SCIM request once
        ▼
Remote SCIM endpoint
```

The token source is JVM-static, keyed by `componentId`. All `ScimClient`
instances bound to the same Keycloak component share one cache entry.
`runAsync` workers that each construct a fresh `ScimClient` hit the
cache, not the token endpoint.

## Configuration

`ScimStorageProviderFactory.configMetadata` modifies the existing
`auth-mode` property and adds four new properties.

```java
.property()
  .name("auth-mode")
  .type(LIST_TYPE)
  .label("Auth mode")
  .options("NONE", "BASIC_AUTH", "BEARER", "CLIENT_CREDENTIALS")  // ← +CLIENT_CREDENTIALS
  .defaultValue("NONE")
  .add()
// existing auth-user / auth-pass properties unchanged
.property().name("oauth-client-id").type(STRING_TYPE)
  .label("OAuth client ID")
  .helpText("Required when auth-mode is CLIENT_CREDENTIALS.").add()
.property().name("oauth-client-secret").type(PASSWORD)
  .label("OAuth client secret")
  .helpText("Required when auth-mode is CLIENT_CREDENTIALS. Stored via Keycloak Vault Provider where configured.").add()
.property().name("oauth-token-endpoint").type(STRING_TYPE)
  .label("OAuth token endpoint")
  .helpText("Full URL of the OAuth 2.0 token endpoint. Required when auth-mode is CLIENT_CREDENTIALS.").add()
.property().name("oauth-scope").type(STRING_TYPE)
  .label("OAuth scope")
  .helpText("Optional space-separated OAuth scopes. Sent as the 'scope' parameter on the token request when set; omitted when blank.").add()
```

### Validation

`ScimStorageProviderFactory.validateConfiguration` is extended with a
new branch. When `auth-mode = CLIENT_CREDENTIALS`:

- `oauth-client-id`, `oauth-client-secret`, `oauth-token-endpoint` must
  all be non-blank.
- `oauth-token-endpoint` must parse as an absolute URL with scheme
  `http` or `https`.
- `oauth-scope` is unvalidated (operator's responsibility; IdPs vary in
  accepted scope syntax).

Violations throw `ComponentValidationException` with field-targeted
messages, same pattern as the existing `ReconcilerConfigValidator`.

### Lifecycle

`ScimStorageProviderFactory.onUpdate` invokes
`OAuthClientCredentialsTokenSource.invalidate(componentId)` whenever
the operator saves changes to the component, so the next request mints
a fresh token against the new config. `onCreate` doesn't need this
(cache miss yields a mint anyway). `preRemove` should also invalidate
(cheap, prevents stale cache after component deletion).

### Admin Console UX

The four `oauth-*` fields render alphabetically among the other
properties in Keycloak's Admin Console. We accept the small UX clutter
of "this field only applies in some auth modes" rather than try to do
dynamic show/hide — Keycloak's `ProviderConfigProperty` doesn't expose
conditional visibility, and the help text disambiguates.

## Token cache + source

New class `sh.libre.scim.core.OAuthClientCredentialsTokenSource`. JVM-
static cache, per-entry mint locks, lazy `expires_in − 30s` refresh,
on-401 invalidation.

```java
public final class OAuthClientCredentialsTokenSource {
    private static final int EXPIRY_SKEW_SECONDS = 30;
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();

    private final String componentId;
    private final OAuthConfig config;   // tokenEndpoint, clientId, clientSecret, scope (nullable)
    private final TokenMinter minter;   // package-private seam for tests

    public String currentAuthorizationHeader();
    public void invalidate();
    public static void invalidate(String componentId);
}
```

### Cache entry

```java
private record Entry(String authorizationHeader, Instant refreshAt, ReentrantLock mintLock) {}
```

- `authorizationHeader`: full ready-to-use header value (`"Bearer
  eyJ..."` or `"<token_type> ..."` if the response declared a non-default
  token type).
- `refreshAt`: `now + (expires_in − 30s)`. Beyond that we treat the
  entry as expired even if the JWT itself is technically still valid.
- `mintLock`: per-entry `ReentrantLock` so two threads can't race to
  mint two tokens for the same `componentId`.

### `currentAuthorizationHeader()` flow

1. Look up `componentId` in `CACHE`. If present and
   `clock.instant().isBefore(entry.refreshAt)`, return its header.
2. Otherwise acquire the entry's `mintLock` (using `CACHE.compute` to
   atomically create-or-get the lock-bearing entry).
3. Re-check inside the lock (another thread may have minted while we
   waited).
4. If still stale, POST `grant_type=client_credentials` (plus
   `&scope=...` if configured) to `oauth-token-endpoint` with HTTP Basic
   `(URLEncoded(client_id), URLEncoded(client_secret))` via
   `ScimHttpClient`. Parse response with `JsonHelper`. Compute new
   `refreshAt`. Replace the cache entry.
5. Release lock; return header.

### `invalidate()`

Removes the entry from `CACHE`. Next caller mints fresh. No-op if not
present.

### Concurrency invariants

- Reads are lock-free (the `ConcurrentHashMap` get + `Instant.isBefore`
  comparison).
- Writes (mint) hold the per-entry lock. Other entries are unaffected.
- A 10k-user import with 8 workers, cold cache: one worker mints, the
  other 7 block briefly on the lock, then all 8 read from cache. ~1
  token endpoint POST per token TTL, regardless of worker count.

### Clock for testability

`clock` is a `static Clock` field, defaulting to `Clock.systemUTC()`,
swappable in tests via a package-private setter. Same pattern as the
reconciler tests.

### Cache key scope

`componentId` is Keycloak's UUID per realm component, so the JVM-wide
cache key has no cross-realm collision risk. If Keycloak ever
introduces deterministic component IDs (it hasn't), we'd need to mix
`realmId` into the key. Noted assumption, not defensive code.

### Process lifetime

The cache lives for JVM lifetime. No eviction beyond `invalidate()`.
Memory cost: one `Entry` per configured SCIM component, each ~few
hundred bytes. Realistic operator configs (handful of SCIM remotes per
Keycloak) make this negligible.

## ScimClient integration

### Constructor

`ScimClient.java` gains a `tokenSource` field (null for non-OAuth
modes) and a new switch case:

```java
private final OAuthClientCredentialsTokenSource tokenSource;

switch (model.get("auth-mode")) {
    case "BEARER":      ... // unchanged
    case "BASIC_AUTH":  ... // unchanged
    case "CLIENT_CREDENTIALS":
        tokenSource = new OAuthClientCredentialsTokenSource(
            model.getId(), OAuthConfig.from(model));
        defaultHeaders.put(AUTHORIZATION, tokenSource.currentAuthorizationHeader());
        break;
    default:            tokenSource = null;
}
```

Initial header is set at construction so the SDK's `defaultHeaders`
map has a value from the start. On every subsequent SCIM call, if
`tokenSource != null`, the header is re-read from the source —
returning either the cached value (fast path) or a freshly minted one.

### On-401 retry helper

```java
private <S> ServerResponse<S> sendWithAuthRefresh(Supplier<ServerResponse<S>> op) {
    if (tokenSource == null) {
        return op.get();   // legacy modes: no refresh path
    }
    refreshAuthHeader();   // sets defaultHeaders[AUTHORIZATION] from tokenSource
    ServerResponse<S> r = op.get();
    if (r.getHttpStatus() == 401 || r.getHttpStatus() == 403) {
        tokenSource.invalidate();
        refreshAuthHeader();
        r = op.get();      // retry exactly once
    }
    return r;
}
```

`create` / `replace` / `delete` / `importResources` wrap their existing
`retry.executeSupplier(...)` calls with this helper:

```java
ServerResponse<S> response = sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
    try {
        return scimRequestBuilder.create(...).sendRequest();
    } catch (ResponseException e) { throw new RuntimeException(e); }
}));
```

### Composition with resilience4j

The two layers handle disjoint failure modes:

- **resilience4j (inner)**: transport-level failures
  (`ProcessingException`, `IORuntimeException`) with exponential
  backoff. Returns a `ServerResponse` only once transport succeeds.
- **`sendWithAuthRefresh` (outer)**: HTTP-level 401/403 returned by the
  receiver. Refreshes and retries once.

A transient socket reset still gets up to 10 backoff retries from
resilience4j; a stale token gets one refresh-and-retry from the outer
wrapper; both can happen in one call without interfering. The
documented 5xx no-retry gap (`ScimResilienceIT#serverErrorIsNotRetriedGap`)
is intentionally unchanged.

### Why mutate `defaultHeaders` rather than rebuild

The SDK's `ScimRequestBuilder` is expensive to construct (Apache
HttpClient pool, internal retry registry, etc.) and is designed to be
long-lived. The headers map is passed by reference into
`ScimClientConfig`; the SDK reads it on each request. Mutating it is
the supported path for dynamic headers.

### Header isolation across `ScimClient` instances

Each `ScimClient` constructs its own `defaultHeaders` HashMap, so two
clients pointing at the same `componentId` won't race on the same map.
They will share the underlying `OAuthClientCredentialsTokenSource`
static cache entry, which is the desired behavior.

## Error handling

The plugin's posture is **fail-open per operation**: a single SCIM
call's failure is logged at ERROR and the worker continues. This work
preserves that posture; it introduces no new "halt the dispatcher"
failure categories.

| Failure | Behavior | Log |
|---|---|---|
| Token endpoint non-2xx (e.g. invalid client_secret → 401) | `mint` throws RuntimeException(status + truncated body). Propagates to the dispatcher's existing per-op try/catch. Subsequent calls re-attempt. | ERROR (existing) |
| Token endpoint network failure / timeout | Same propagation path. | ERROR (existing) |
| Token response missing `access_token` | `IllegalStateException("token response missing access_token")`. Same propagation. | ERROR (existing) |
| Token response missing `expires_in` | Use 60s default; WARN once per `componentId` (static seen-once set). | WARN, one-shot |
| SCIM endpoint 401/403 | `sendWithAuthRefresh` invalidates cached token, mints fresh, retries op once. If retry also 401/403, existing per-op WARN handling applies. | INFO ("refreshing token after 401"), then WARN if retry also 401 |
| Token expires between pre-call refresh and SDK send | Receiver returns 401; on-401 path handles it. No special case. | as above |
| Component deleted while token cached | Entry remains until JVM restart or next `invalidate`. `preRemove` hook calls `invalidate(componentId)` as defensive cleanup. | n/a |
| Operator changes any `oauth-*` field | `onUpdate` calls `invalidate(componentId)`. Next mint uses fresh config. | DEBUG |
| Concurrent cold-cache misses | Per-entry `ReentrantLock`: first wins and mints; others observe fresh entry inside the lock and skip mint. Single token-endpoint POST. | DEBUG |
| Token endpoint flaps | Each failure throws; each success caches for `expires_in − 30s`. No accumulation. | as above |

### Operational note worth documenting

If the token endpoint is permanently broken (wrong URL, dead
Keycloak), every SCIM event in the realm produces an ERROR-log entry
until the operator fixes it. We don't add backoff or circuit-breaking
at the token endpoint layer — that would mask the real problem (config
error) and complicate the cache state machine. Log volume in that
scenario is the same as today for any other persistent SCIM failure.

### Explicitly not handled

- **No retry on token-endpoint 5xx.** Symmetric with the existing SCIM
  endpoint 5xx gap. A future widening of resilience4j should cover both
  endpoints together.
- **No proactive refresh-ahead-of-expiry.** Refresh happens on demand
  at `currentAuthorizationHeader()` calls. Bursts at the expiry
  boundary briefly serialize on the per-entry lock; one mints, the
  rest block, then all read from cache. Acceptable. (See Non-goals.)

## Testing

### Unit tests — `OAuthClientCredentialsTokenSourceTest` (new)

Uses a `TokenMinter` seam (package-private constructor) so cache-logic
tests don't need WireMock.

| Test | What it pins |
|---|---|
| `firstCall_mintsToken` | Empty cache → mint invoked once → returns `Bearer <token>` |
| `secondCallBeforeRefreshAt_returnsCached` | No second mint |
| `secondCallAfterRefreshAt_mintsAgain` | Mint invoked again after advancing the package-private `clock` |
| `concurrentMisses_singleMint` | 16 threads on cold cache → exactly 1 mint observed |
| `invalidate_dropsEntry` | After `invalidate()`, next call mints |
| `tokenResponseMissingAccessToken_throws` | `IllegalStateException` propagates |
| `missingExpiresIn_defaultsTo60s_warnsOncePerComponentId` | First call WARNs, second call doesn't; refresh fires at +30s |
| `httpNonSuccess_throws` | RuntimeException with status code and truncated body |
| `nonDefaultTokenType_usedInHeader` | `token_type: DPoP` → header is `DPoP <access_token>` |
| `scopeAppendedToBodyWhenConfigured` / `scopeOmittedWhenBlank` | Form-encoded body matches |
| `clientCredentialsUrlEncoded` | Basic header computed from URL-encoded id/secret per RFC 6749 §2.3.1 |

### Config-validation unit test — extend or add to `ScimStorageProviderFactory` tests

| Test | What it pins |
|---|---|
| `clientCredentials_missingClientId_rejected` | `ComponentValidationException` |
| `clientCredentials_missingClientSecret_rejected` | same |
| `clientCredentials_missingTokenEndpoint_rejected` | same |
| `clientCredentials_malformedTokenEndpoint_rejected` | non-URL, non-http(s) scheme |
| `clientCredentials_allFieldsPresent_accepted` | no exception |
| `legacyAuthModes_unchanged` | NONE/BASIC_AUTH/BEARER paths untouched |

### Integration test — `ScimOidcAuthIT` (new file alongside `ScimResilienceIT`)

WireMock serves both the OAuth token endpoint and the SCIM sink on one
port (different stub paths).

| Test | What it pins |
|---|---|
| `clientCredentialsHappyPath_bearerOnScimRequest` | mode=CLIENT_CREDENTIALS; trigger admin user create; verify (a) one POST to `/token` with Basic header + `grant_type=client_credentials`, (b) POST to `/Users` with `Authorization: Bearer <returned-token>` |
| `cachedAcrossSubsequentEvents` | Two admin user creates in sequence; exactly one `/token` POST |
| `cachedAcrossAsyncWorkers` | LDAP-import path fanning out via `runAsync`; one `/token` POST despite N SCIM POSTs |
| `expiryTriggersRefresh` | Stub `/token` returns `expires_in: 1`; sleep > 1s + skew; second event → second `/token` POST |
| `scim401TriggersRefreshAndRetry` | Stub `/Users` → 401 once then 201; stub `/token` → fresh JWT each call; verify two `/token` POSTs and two `/Users` POSTs |
| `tokenEndpointDown_eventFailsButPluginSurvives` | Stub `/token` → 503; trigger event → SCIM POST never happens, error logged; next event re-attempts mint |
| `scopeForwardedToTokenEndpoint` | Configure `oauth-scope=scim:write`; verify `/token` request body contains `scope=scim%3Awrite` |

### Test-file naming

A separate `ScimOidcAuthIT` keeps each integration class focused on
one concern, matching the existing `ScimGroupPropagationIT` /
`ScimMultiTenancyIT` / `ScimResilienceIT` split. `ScimResilienceIT`'s
existing `bearerTokenAppearsOnScimRequests` covers the static BEARER
path and stays put.

### Explicitly not tested (deferred gaps)

- **End-to-end against a real Keycloak token endpoint** — tempting for
  catching JWT-format weirdness, but inflates the test stack
  (additional realm + client + service account + role mappings) and
  the WireMock path covers the wire contract. Tracked as a planned
  follow-up.
- **Proactive refresh** — out of scope (Non-goals).
- **`expires_in: 0` edge case** — undefined per RFC; treat as
  immediately expired (re-mint on next call). One assertion noting the
  observation is enough; no behavior contract.

## Docs updates

| File | Change |
|---|---|
| `docs/configuration.md` | Extend the **Authentication** table with `CLIENT_CREDENTIALS` and the four `oauth-*` fields. Add a short section *"OAuth 2.0 client_credentials"* explaining cache behavior (JVM-wide, per `componentId`, `expires_in − 30s` skew), `client_secret_basic` choice, on-401 refresh-retry. Note in **What's NOT configurable** that proactive refresh and token-endpoint 5xx retry are deliberate omissions, mirroring the existing retry-policy note. |
| `README.md` | One-line mention of the new auth mode in the feature list. |
| `docs/superpowers/specs/2026-05-21-oauth-client-credentials-auth-design.md` | This document. |

`CHANGELOG.md` is **not** edited — it's generated by release-please
from the conventional commit message (`feat(auth): ...`).

### Operator-facing acceptance criterion (for `docs/configuration.md`)

> Configuring `auth-mode=CLIENT_CREDENTIALS` with valid
> `oauth-client-id`, `oauth-client-secret`, and `oauth-token-endpoint`
> against a Keycloak realm produces SCIM requests bearing
> `Authorization: Bearer <jwt>`, where the JWT is a standard Keycloak
> access token (`azp` = configured client id, signed by the realm's
> JWKS). Any SCIM receiver that verifies callers by JWKS signature +
> `azp` will accept the requests without further configuration.

## Out of scope (deferred)

Per Non-goals, these are not part of this work but are tracked as
follow-ups:

- OIDC discovery via `oidc-issuer`
- `client_secret_post` client authentication
- `private_key_jwt`, mTLS bearer (RFC 8705)
- `audience` request parameter (confirmed never needed)
- Proactive refresh-ahead-of-expiry
- Token-endpoint 5xx retry
- Live-Keycloak token-endpoint integration test
- Configurable extra headers

## References

- RFC 6749 §4.4 — OAuth 2.0 Client Credentials Grant
- RFC 6749 §2.3.1 — `client_secret_basic` client authentication
- This project's existing dispatcher async path:
  `src/main/java/sh/libre/scim/core/ScimDispatcher.java#runAsync`
- This project's existing static-bearer test:
  `src/integrationTest/java/sh/libre/scim/integration/ScimResilienceIT.java#bearerTokenAppearsOnScimRequests`
- This project's release flow: `docs/releasing.md`
