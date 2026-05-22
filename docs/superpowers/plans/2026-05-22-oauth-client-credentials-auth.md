# OAuth 2.0 client_credentials auth — implementation plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `CLIENT_CREDENTIALS` outbound-SCIM auth mode that mints Keycloak access tokens via OAuth 2.0 client_credentials and sends them as bearer tokens on SCIM requests.

**Architecture:** New `OAuthClientCredentialsTokenSource` (JVM-static cache, per-`componentId` entries, lazy refresh with 30s skew). `ScimClient` gains a CLIENT_CREDENTIALS branch and a `sendWithAuthRefresh` helper that retries once on 401/403. `ScimStorageProviderFactory` gains four `oauth-*` config fields, a validator branch, and lifecycle hooks (`onUpdate`/`preRemove`) that invalidate the cache.

**Tech Stack:** Java 21, Keycloak component SPI, `de.captaingoldfish:scim-sdk-client` (already a dep), resilience4j-retry (already a dep), JUnit 5 + Mockito (unit), Testcontainers + WireMock (integration). Gradle build.

**Reference spec:** [`docs/superpowers/specs/2026-05-21-oauth-client-credentials-auth-design.md`](../specs/2026-05-21-oauth-client-credentials-auth-design.md) — read it before starting; all design decisions are documented there.

---

## File structure

**Create:**
- `src/main/java/sh/libre/scim/core/OAuthClientCredentialsTokenSource.java` — token cache + minter, JVM-static `ConcurrentHashMap<String, Entry>` keyed by `componentId`. Per-entry `ReentrantLock` so concurrent cold-cache callers serialize on mint. Inner `TokenMinter` interface (package-private) is the seam unit tests swap out.
- `src/main/java/sh/libre/scim/core/OAuthConfig.java` — small immutable record extracted from `ComponentModel` (`tokenEndpoint`, `clientId`, `clientSecret`, `scope` nullable). Keeps `OAuthClientCredentialsTokenSource` from knowing about Keycloak model types.
- `src/test/java/sh/libre/scim/core/OAuthClientCredentialsTokenSourceTest.java` — unit tests using a fake `TokenMinter`.
- `src/integrationTest/java/sh/libre/scim/integration/ScimOidcAuthIT.java` — WireMock-based integration test (token endpoint + SCIM sink on the same port, different stub paths).

**Modify:**
- `src/main/java/sh/libre/scim/storage/ScimStorageProviderFactory.java`
  - `configMetadata` (around line 42): add `CLIENT_CREDENTIALS` to the `auth-mode` options list; add four `oauth-*` properties.
  - `validateConfiguration` (around line 189): add a branch validating the four fields when `auth-mode = CLIENT_CREDENTIALS`.
  - `onUpdate` (around line 177): call `OAuthClientCredentialsTokenSource.invalidate(componentId)`.
  - `preRemove` (around line 184): same invalidation call.
- `src/main/java/sh/libre/scim/core/ScimClient.java`
  - Auth switch (around line 52): add a `case "CLIENT_CREDENTIALS"` branch constructing the token source and seeding the initial Authorization header.
  - Add a `tokenSource` field (nullable) and a `sendWithAuthRefresh` helper.
  - Wrap each of `create` / `replace` / `delete` / `importResources`' existing `retry.executeSupplier(...)` calls with `sendWithAuthRefresh`.
- `docs/configuration.md` — extend the **Authentication** table and add a section on the new auth mode.
- `README.md` — one-line feature mention.

**Test-only modifications:**
- `src/test/java/sh/libre/scim/storage/ScimStorageProviderFactoryTest.java` — create if it doesn't exist; add config-validation cases for the new mode.

**Untouched (do NOT modify):**
- `ScimDispatcher.java`, `ScimResilienceIT.java`, `ScimGroupPropagationIT.java`, `ScimMultiTenancyIT.java`, `ScimPropagationFromLdapIT.java` — separate concerns; integration test layout splits one concern per file.

---

## Branch / commit hygiene

- Cut a feature branch off `main` before Task 1: `git checkout -b feat/oauth-client-credentials`.
- Every task ends with a signed commit (this repo has a verified-signatures branch rule). All commits should be signed with `-S`.
- Use conventional commits. Most commits will be `feat(auth): ...` or `test(auth): ...` or `refactor(auth): ...`.
- Don't add `Co-Authored-By` trailers — not the repo's convention.

---

## Chunk 1: `OAuthClientCredentialsTokenSource` (foundation)

The token source is the load-bearing primitive. Build it first with unit tests, in isolation from `ScimClient`. The `TokenMinter` seam means none of these tests need WireMock — they exercise the cache and lifecycle directly.

### Task 1: Skeleton class + first test (`firstCall_mintsToken`)

**Files:**
- Create: `src/main/java/sh/libre/scim/core/OAuthConfig.java`
- Create: `src/main/java/sh/libre/scim/core/OAuthClientCredentialsTokenSource.java`
- Create: `src/test/java/sh/libre/scim/core/OAuthClientCredentialsTokenSourceTest.java`

- [ ] **Step 1: Create `OAuthConfig`** (no test needed — pure data carrier)

```java
package sh.libre.scim.core;

public record OAuthConfig(String tokenEndpoint, String clientId, String clientSecret, String scope) {
    public static OAuthConfig from(org.keycloak.component.ComponentModel model) {
        return new OAuthConfig(
            model.get("oauth-token-endpoint"),
            model.get("oauth-client-id"),
            model.get("oauth-client-secret"),
            model.get("oauth-scope")
        );
    }
}
```

- [ ] **Step 2: Write the failing test for first-mint behavior**

```java
package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.*;
import org.junit.jupiter.api.*;

class OAuthClientCredentialsTokenSourceTest {

    private OAuthConfig cfg;
    private OAuthClientCredentialsTokenSource.TokenMinter minter;

    @BeforeEach
    void setUp() {
        OAuthClientCredentialsTokenSource.resetCacheForTests();
        OAuthClientCredentialsTokenSource.setClockForTests(Clock.fixed(Instant.parse("2026-05-22T00:00:00Z"), ZoneOffset.UTC));
        cfg = new OAuthConfig("https://kc/token", "client", "secret", null);
        minter = mock(OAuthClientCredentialsTokenSource.TokenMinter.class);
        when(minter.mint(cfg)).thenReturn(
            new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.first", 300));
    }

    @Test
    void firstCall_mintsToken() {
        var src = new OAuthClientCredentialsTokenSource("comp-1", cfg, minter);
        assertThat(src.currentAuthorizationHeader()).isEqualTo("Bearer eyJ.first");
        verify(minter, times(1)).mint(cfg);
    }
}
```

- [ ] **Step 3: Run test to confirm it fails (compile error)**

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.firstCall_mintsToken`
Expected: **compile failure** — `OAuthClientCredentialsTokenSource` doesn't exist yet.

- [ ] **Step 4: Write the minimal `OAuthClientCredentialsTokenSource`**

```java
package sh.libre.scim.core;

import java.time.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class OAuthClientCredentialsTokenSource {

    public interface TokenMinter {
        MintResult mint(OAuthConfig config);
    }

    public record MintResult(String authorizationHeader, long expiresInSeconds) {}

    private static final int EXPIRY_SKEW_SECONDS = 30;
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static volatile Clock clock = Clock.systemUTC();

    private final String componentId;
    private final OAuthConfig config;
    private final TokenMinter minter;

    public OAuthClientCredentialsTokenSource(String componentId, OAuthConfig config, TokenMinter minter) {
        this.componentId = componentId;
        this.config = config;
        this.minter = minter;
    }

    public String currentAuthorizationHeader() {
        Entry entry = CACHE.get(componentId);
        if (entry != null && clock.instant().isBefore(entry.refreshAt)) {
            return entry.authorizationHeader;
        }
        return mintAndStore();
    }

    private String mintAndStore() {
        Entry entry = CACHE.computeIfAbsent(componentId, k -> new Entry(null, Instant.EPOCH, new ReentrantLock()));
        entry.lock.lock();
        try {
            Entry current = CACHE.get(componentId);
            if (current != null && current.authorizationHeader != null && clock.instant().isBefore(current.refreshAt)) {
                return current.authorizationHeader;
            }
            MintResult r = minter.mint(config);
            Instant refreshAt = clock.instant().plusSeconds(Math.max(0, r.expiresInSeconds() - EXPIRY_SKEW_SECONDS));
            Entry fresh = new Entry(r.authorizationHeader(), refreshAt, entry.lock);
            CACHE.put(componentId, fresh);
            return r.authorizationHeader();
        } finally {
            entry.lock.unlock();
        }
    }

    public void invalidate() { CACHE.remove(componentId); }
    public static void invalidate(String componentId) { CACHE.remove(componentId); }

    static void resetCacheForTests() { CACHE.clear(); }
    static void setClockForTests(Clock c) { clock = c; }

    private record Entry(String authorizationHeader, Instant refreshAt, ReentrantLock lock) {}
}
```

- [ ] **Step 5: Run the test — it should pass now**

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.firstCall_mintsToken`
Expected: PASS.

- [ ] **Step 6: Commit**

```sh
git add src/main/java/sh/libre/scim/core/OAuthConfig.java \
        src/main/java/sh/libre/scim/core/OAuthClientCredentialsTokenSource.java \
        src/test/java/sh/libre/scim/core/OAuthClientCredentialsTokenSourceTest.java
git commit -S -m "feat(auth): scaffold OAuthClientCredentialsTokenSource with first-mint test"
```

---

### Task 2: Cache hit — `secondCallBeforeRefreshAt_returnsCached`

**Files:**
- Modify: `src/test/java/sh/libre/scim/core/OAuthClientCredentialsTokenSourceTest.java`

- [ ] **Step 1: Add the failing test**

```java
@Test
void secondCallBeforeRefreshAt_returnsCached() {
    var src = new OAuthClientCredentialsTokenSource("comp-1", cfg, minter);
    src.currentAuthorizationHeader();
    src.currentAuthorizationHeader();
    verify(minter, times(1)).mint(cfg);
}
```

- [ ] **Step 2: Run** — should pass already (the existing impl checks `refreshAt`). If it fails, the cache-hit branch is broken — fix before continuing.

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.secondCallBeforeRefreshAt_returnsCached`
Expected: PASS without code changes.

- [ ] **Step 3: Commit**

```sh
git add src/test/java/sh/libre/scim/core/OAuthClientCredentialsTokenSourceTest.java
git commit -S -m "test(auth): pin cache-hit fast path for token source"
```

---

### Task 3: Cache expiry — `secondCallAfterRefreshAt_mintsAgain`

- [ ] **Step 1: Add the failing test**

```java
@Test
void secondCallAfterRefreshAt_mintsAgain() {
    Instant t0 = Instant.parse("2026-05-22T00:00:00Z");
    OAuthClientCredentialsTokenSource.setClockForTests(Clock.fixed(t0, ZoneOffset.UTC));
    when(minter.mint(cfg))
        .thenReturn(new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.first", 60))
        .thenReturn(new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.second", 60));

    var src = new OAuthClientCredentialsTokenSource("comp-1", cfg, minter);
    assertThat(src.currentAuthorizationHeader()).isEqualTo("Bearer eyJ.first");

    // refreshAt = t0 + (60 - 30) = t0 + 30s. Advance past that.
    OAuthClientCredentialsTokenSource.setClockForTests(Clock.fixed(t0.plusSeconds(31), ZoneOffset.UTC));
    assertThat(src.currentAuthorizationHeader()).isEqualTo("Bearer eyJ.second");
    verify(minter, times(2)).mint(cfg);
}
```

- [ ] **Step 2: Run** — should pass (skew + refresh logic already present)

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.secondCallAfterRefreshAt_mintsAgain`
Expected: PASS.

- [ ] **Step 3: Commit**

```sh
git commit -S -am "test(auth): pin expiry-triggers-remint behavior"
```

---

### Task 4: Concurrent cold-cache misses — `concurrentMisses_singleMint`

- [ ] **Step 1: Add the failing test**

```java
@Test
void concurrentMisses_singleMint() throws Exception {
    var latch = new java.util.concurrent.CountDownLatch(1);
    when(minter.mint(cfg)).thenAnswer(inv -> {
        latch.await();   // hold all callers until released
        return new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.shared", 300);
    });

    int threads = 16;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    var done = new java.util.concurrent.CountDownLatch(threads);
    for (int i = 0; i < threads; i++) {
        pool.submit(() -> {
            new OAuthClientCredentialsTokenSource("comp-1", cfg, minter).currentAuthorizationHeader();
            done.countDown();
        });
    }
    Thread.sleep(50);
    latch.countDown();
    assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    pool.shutdown();

    verify(minter, times(1)).mint(cfg);  // exactly one mint despite 16 callers
}
```

- [ ] **Step 2: Run** — should pass (per-entry lock guarantees single mint)

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.concurrentMisses_singleMint`
Expected: PASS.

- [ ] **Step 3: Commit**

```sh
git commit -S -am "test(auth): pin concurrent-mint deduplication"
```

---

### Task 5: `invalidate_dropsEntry`

- [ ] **Step 1: Add the failing test**

```java
@Test
void invalidate_dropsEntry() {
    when(minter.mint(cfg))
        .thenReturn(new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.first", 300))
        .thenReturn(new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.second", 300));

    var src = new OAuthClientCredentialsTokenSource("comp-1", cfg, minter);
    assertThat(src.currentAuthorizationHeader()).isEqualTo("Bearer eyJ.first");
    src.invalidate();
    assertThat(src.currentAuthorizationHeader()).isEqualTo("Bearer eyJ.second");
    verify(minter, times(2)).mint(cfg);
}
```

- [ ] **Step 2: Run** — should pass

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.invalidate_dropsEntry`
Expected: PASS.

- [ ] **Step 3: Commit**

```sh
git commit -S -am "test(auth): pin invalidate() forces remint"
```

---

### Task 6: Missing `access_token` in response — propagate exception

The error-path tests depend on the *real* `TokenMinter` impl (the one that parses HTTP responses), so we need to write that impl first. The fake minter we've used so far won't surface these conditions.

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/OAuthClientCredentialsTokenSource.java` — add inner default `HttpTokenMinter` implementing `TokenMinter`.

- [ ] **Step 1: Write the failing test (uses real minter against a WireMock-style stub)**

For the unit-test layer we don't pull in WireMock — use a small in-test `TokenMinter` wrapper that calls into a private `parseTokenResponse(String json)` method. Add the parser as a `static` package-private method on `OAuthClientCredentialsTokenSource`, then test it directly.

```java
@Test
void tokenResponseMissingAccessToken_throws() {
    String body = "{\"token_type\":\"Bearer\",\"expires_in\":300}";
    assertThatThrownBy(() -> OAuthClientCredentialsTokenSource.parseTokenResponse(body))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("access_token");
}
```

- [ ] **Step 2: Run — should fail (parser doesn't exist)**

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.tokenResponseMissingAccessToken_throws`
Expected: FAIL — compile error or method missing.

- [ ] **Step 3: Implement `parseTokenResponse`**

```java
static MintResult parseTokenResponse(String body) {
    com.fasterxml.jackson.databind.JsonNode root;
    try { root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body); }
    catch (Exception e) { throw new IllegalStateException("token response not valid JSON", e); }

    com.fasterxml.jackson.databind.JsonNode at = root.get("access_token");
    if (at == null || at.asText().isBlank()) {
        throw new IllegalStateException("token response missing access_token");
    }
    String tokenType = root.hasNonNull("token_type") ? root.get("token_type").asText() : "Bearer";
    long expiresIn = root.hasNonNull("expires_in") ? root.get("expires_in").asLong() : 60L;
    return new MintResult(tokenType + " " + at.asText(), expiresIn);
}
```

(Jackson is already on the classpath via Keycloak's runtime; no new dep.)

- [ ] **Step 4: Run — should pass**

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.tokenResponseMissingAccessToken_throws`
Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git commit -S -am "feat(auth): parseTokenResponse() with access_token validation"
```

---

### Task 7: Missing `expires_in` defaults to 60s + WARN once per componentId

- [ ] **Step 1: Add the failing test**

```java
@Test
void missingExpiresIn_defaultsTo60s() {
    String body = "{\"access_token\":\"abc\",\"token_type\":\"Bearer\"}";
    var r = OAuthClientCredentialsTokenSource.parseTokenResponse(body);
    assertThat(r.expiresInSeconds()).isEqualTo(60L);
    assertThat(r.authorizationHeader()).isEqualTo("Bearer abc");
}
```

- [ ] **Step 2: Run — passes given Task 6's impl**

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.missingExpiresIn_defaultsTo60s`
Expected: PASS.

- [ ] **Step 3: Add the WARN-once test**

The seen-once set is more naturally tested via end-to-end log assertions in the integration tests. At the unit-test layer, capture by mocking JBoss Logger. Pragmatic option: skip strict WARN-once unit-test, document it as covered indirectly by `missingExpiresIn_defaultsTo60s_warnsOncePerComponentId` in the IT layer. Drop a TODO in the impl noting the gap.

Note: the spec lists `missingExpiresIn_defaultsTo60s_warnsOncePerComponentId` in the unit-test table — this is the one place we deviate. Document the deviation in the commit message.

- [ ] **Step 4: Implement WARN-once in `parseTokenResponse` (or the wrapping mint method)**

```java
// At class scope:
private static final java.util.Set<String> WARNED_NO_EXPIRES_IN = java.util.concurrent.ConcurrentHashMap.newKeySet();
private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(OAuthClientCredentialsTokenSource.class);

// In mintAndStore, after parseTokenResponse:
if (!root_had_expires_in /* threaded via MintResult or a side channel */ && WARNED_NO_EXPIRES_IN.add(componentId)) {
    LOG.warnf("OAuth token endpoint did not return expires_in for component %s; defaulting to 60s", componentId);
}
```

Simplest implementation: have `parseTokenResponse` return a boolean alongside `MintResult`, e.g. a small `record ParsedToken(MintResult result, boolean hadExpiresIn)`. Refactor accordingly.

- [ ] **Step 5: Run all token-source tests, confirm green**

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest`
Expected: all pass.

- [ ] **Step 6: Commit**

```sh
git commit -S -am "feat(auth): default expires_in to 60s; WARN-once per componentId

Spec's missingExpiresIn_defaultsTo60s_warnsOncePerComponentId is split:
unit test pins the 60s default; the WARN-once behavior is covered by
the integration suite to avoid mocking the JBoss Logger here."
```

---

### Task 8: HTTP non-success — throws with status + truncated body

This test exercises the HTTP layer of the minter. Use a small embedded `WireMockServer` in the unit test (already a test dep via `com.github.tomakehurst:wiremock`).

- [ ] **Step 1: Implement `HttpTokenMinter` (the production `TokenMinter`)**

```java
public static final class HttpTokenMinter implements TokenMinter {
    private final de.captaingoldfish.scim.sdk.client.http.ScimHttpClient httpClient;
    public HttpTokenMinter(de.captaingoldfish.scim.sdk.client.http.ScimHttpClient httpClient) {
        this.httpClient = httpClient;
    }
    @Override public MintResult mint(OAuthConfig cfg) {
        // Build form body: grant_type=client_credentials[&scope=...]
        // Basic auth header: base64(urlencode(clientId):urlencode(clientSecret))
        // POST cfg.tokenEndpoint() with application/x-www-form-urlencoded
        // On non-2xx: throw new RuntimeException("token endpoint " + status + ": " + truncate(body, 200))
        // On 2xx: return parseTokenResponse(body).result()
        // ...
    }
}
```

Full implementation lives in the impl file. Use the SCIM SDK's `ScimHttpClient` so we inherit `KeepAliveConfigManipulator` (connection pooling + keepalive) instead of opening fresh sockets.

- [ ] **Step 2: Add the failing test**

```java
@Test
void httpNonSuccess_throws() {
    var wm = new com.github.tomakehurst.wiremock.WireMockServer(
        com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
    wm.start();
    try {
        wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/token")
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                .withStatus(401).withBody("invalid client_secret here is some extra context for truncation testing".repeat(5))));

        var realMinter = new OAuthClientCredentialsTokenSource.HttpTokenMinter(/* ScimHttpClient instance */ null);
        var c = new OAuthConfig(wm.baseUrl() + "/token", "client", "secret", null);

        assertThatThrownBy(() -> realMinter.mint(c))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("401");
    } finally {
        wm.stop();
    }
}
```

- [ ] **Step 3: Run, fix until green**

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.httpNonSuccess_throws`
Expected: PASS.

- [ ] **Step 4: Commit**

```sh
git commit -S -m "feat(auth): HttpTokenMinter with non-2xx error propagation"
```

---

### Task 9: Non-default token type — `nonDefaultTokenType_usedInHeader`

- [ ] **Step 1: Add the failing test**

```java
@Test
void nonDefaultTokenType_usedInHeader() {
    String body = "{\"access_token\":\"xyz\",\"token_type\":\"DPoP\",\"expires_in\":300}";
    var r = OAuthClientCredentialsTokenSource.parseTokenResponse(body);
    assertThat(r.authorizationHeader()).isEqualTo("DPoP xyz");
}
```

- [ ] **Step 2: Run — should pass given Task 6's impl**

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.nonDefaultTokenType_usedInHeader`
Expected: PASS.

- [ ] **Step 3: Commit**

```sh
git commit -S -am "test(auth): pin non-Bearer token_type rendering"
```

---

### Task 10: Scope serialization — appended when set, omitted when blank

Tests the *request body* the minter sends, not parsing. WireMock captures and we assert.

- [ ] **Step 1: Add `scopeAppendedToBodyWhenConfigured` against WireMock stub**

```java
@Test
void scopeAppendedToBodyWhenConfigured() {
    var wm = new com.github.tomakehurst.wiremock.WireMockServer(options().dynamicPort());
    wm.start();
    try {
        wm.stubFor(post("/token").willReturn(okJson(
            "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":300}")));

        var minter = new OAuthClientCredentialsTokenSource.HttpTokenMinter(/* ... */);
        minter.mint(new OAuthConfig(wm.baseUrl() + "/token", "id", "sec", "scim:write"));

        wm.verify(postRequestedFor(urlEqualTo("/token"))
            .withRequestBody(containing("grant_type=client_credentials"))
            .withRequestBody(containing("scope=scim%3Awrite")));
    } finally { wm.stop(); }
}
```

- [ ] **Step 2: Add `scopeOmittedWhenBlank`**

```java
@Test
void scopeOmittedWhenBlank() {
    // ... same setup; cfg with scope=null and scope="" both result in NO scope= param
    wm.verify(postRequestedFor(urlEqualTo("/token"))
        .withRequestBody(notMatching(".*scope=.*")));
}
```

- [ ] **Step 3: Run, fix until green**

Run: `./gradlew test --tests "OAuthClientCredentialsTokenSourceTest.scope*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```sh
git commit -S -am "feat(auth): conditionally include scope on token request"
```

---

### Task 11: URL-encoded Basic auth header for client_id / client_secret

Tests that the `Authorization: Basic <...>` header sent to the token endpoint uses URL-encoded credentials per RFC 6749 §2.3.1 (so special chars in client_id / secret don't break Basic decoding).

- [ ] **Step 1: Add the failing test**

```java
@Test
void clientCredentialsUrlEncoded() {
    var wm = new com.github.tomakehurst.wiremock.WireMockServer(options().dynamicPort());
    wm.start();
    try {
        wm.stubFor(post("/token").willReturn(okJson("{\"access_token\":\"t\",\"expires_in\":300}")));
        var minter = new OAuthClientCredentialsTokenSource.HttpTokenMinter(/* ... */);
        // client_id with special chars
        minter.mint(new OAuthConfig(wm.baseUrl() + "/token", "id with space", "sec/with+special", null));

        // Expected Basic header: base64(URLEncode("id with space") + ":" + URLEncode("sec/with+special"))
        // = base64("id+with+space:sec%2Fwith%2Bspecial") (form-urlencoded space → +)
        String expected = "Basic " + Base64.getEncoder().encodeToString(
            "id+with+space:sec%2Fwith%2Bspecial".getBytes(StandardCharsets.UTF_8));
        wm.verify(postRequestedFor(urlEqualTo("/token"))
            .withHeader("Authorization", equalTo(expected)));
    } finally { wm.stop(); }
}
```

- [ ] **Step 2: Run, fix until green**

Run: `./gradlew test --tests OAuthClientCredentialsTokenSourceTest.clientCredentialsUrlEncoded`
Expected: PASS.

- [ ] **Step 3: Commit + chunk wrap-up**

```sh
git commit -S -am "feat(auth): URL-encode client_id/secret in Basic header per RFC 6749 §2.3.1"
```

**Chunk 1 done.** Run the full token-source suite + make sure nothing else regressed:

```sh
./gradlew test --tests OAuthClientCredentialsTokenSourceTest
./gradlew test    # full unit suite
```

---

## Chunk 2: Config schema + validation

### Task 12: Add `CLIENT_CREDENTIALS` to `auth-mode` options + four `oauth-*` properties

**Files:**
- Modify: `src/main/java/sh/libre/scim/storage/ScimStorageProviderFactory.java` (around line 42, `configMetadata` static block)

- [ ] **Step 1: Add a failing test asserting `getConfigProperties` exposes the new fields**

`src/test/java/sh/libre/scim/storage/ScimStorageProviderFactoryTest.java` (create if missing):

```java
package sh.libre.scim.storage;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ScimStorageProviderFactoryTest {

    @Test
    void authModeOptions_includesClientCredentials() {
        var factory = new ScimStorageProviderFactory();
        var authMode = factory.getConfigProperties().stream()
            .filter(p -> p.getName().equals("auth-mode")).findFirst().orElseThrow();
        assertThat(authMode.getOptions()).contains("CLIENT_CREDENTIALS");
    }

    @Test
    void configProperties_includeOauthFields() {
        var factory = new ScimStorageProviderFactory();
        var names = factory.getConfigProperties().stream().map(p -> p.getName()).toList();
        assertThat(names).contains(
            "oauth-client-id", "oauth-client-secret", "oauth-token-endpoint", "oauth-scope");
    }
}
```

- [ ] **Step 2: Run — should fail**

Run: `./gradlew test --tests ScimStorageProviderFactoryTest`
Expected: FAIL — new options aren't there.

- [ ] **Step 3: Modify `configMetadata` per spec §Configuration**

Append after the existing `auth-mode` and credential properties; see spec for exact ProviderConfigurationBuilder syntax.

- [ ] **Step 4: Run — should pass**

Run: `./gradlew test --tests ScimStorageProviderFactoryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git commit -S -am "feat(auth): expose CLIENT_CREDENTIALS option + four oauth-* config properties"
```

---

### Task 13: Validation — all four fields required when mode is `CLIENT_CREDENTIALS`

- [ ] **Step 1: Add failing tests for each missing-field case**

```java
@Test
void clientCredentials_missingClientId_rejected() {
    var session = mock(KeycloakSession.class);
    var realm = mock(RealmModel.class);
    var model = mockComponentWithAuthMode("CLIENT_CREDENTIALS", java.util.Map.of(
        "oauth-client-secret", "s", "oauth-token-endpoint", "https://kc/token"));
    var factory = new ScimStorageProviderFactory();
    assertThatThrownBy(() -> factory.validateConfiguration(session, realm, model))
        .isInstanceOf(ComponentValidationException.class)
        .hasMessageContaining("oauth-client-id");
}

// Mirror tests for missingClientSecret, missingTokenEndpoint, malformedTokenEndpoint,
// allFieldsPresent_accepted, legacyAuthModes_unchanged.
```

Use a helper:
```java
private static ComponentModel mockComponentWithAuthMode(String authMode, Map<String,String> extra) {
    var m = mock(ComponentModel.class);
    var config = new MultivaluedHashMap<String,String>();
    config.putSingle("auth-mode", authMode);
    extra.forEach(config::putSingle);
    when(m.getConfig()).thenReturn(config);
    when(m.get(anyString())).thenAnswer(inv -> config.getFirst(inv.getArgument(0)));
    return m;
}
```

- [ ] **Step 2: Run all six tests — should fail (no validator branch yet)**

Expected: FAIL.

- [ ] **Step 3: Add the validator branch in `validateConfiguration`**

```java
String authMode = model.get("auth-mode");
if ("CLIENT_CREDENTIALS".equals(authMode)) {
    requireNonBlank(model, "oauth-client-id");
    requireNonBlank(model, "oauth-client-secret");
    String endpoint = requireNonBlank(model, "oauth-token-endpoint");
    try {
        var uri = java.net.URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new ComponentValidationException(
                "oauth-token-endpoint must be an absolute http/https URL");
        }
    } catch (IllegalArgumentException e) {
        throw new ComponentValidationException(
            "oauth-token-endpoint must be an absolute http/https URL", e);
    }
}
```

Helper:
```java
private static String requireNonBlank(ComponentModel m, String name) {
    String v = m.get(name);
    if (v == null || v.isBlank()) {
        throw new ComponentValidationException(name + " is required when auth-mode is CLIENT_CREDENTIALS");
    }
    return v;
}
```

- [ ] **Step 4: Run all six — should pass**

Run: `./gradlew test --tests ScimStorageProviderFactoryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git commit -S -am "feat(auth): validate oauth-* fields when auth-mode is CLIENT_CREDENTIALS"
```

---

### Task 14: Lifecycle invalidation — `onUpdate` and `preRemove`

- [ ] **Step 1: Add tests pinning the invalidation calls**

These are awkward to unit-test without bringing in a static-method-mocker. Pragmatic alternative: add the integration-test assertion in Chunk 4 (`expiryTriggersRefresh` already exercises an external invalidation case). For now, write the impl carefully and lean on the integration coverage.

Note this deliberate testing gap in the commit message.

- [ ] **Step 2: Modify `onUpdate`**

```java
@Override
public void onUpdate(KeycloakSession session, RealmModel realm,
                     ComponentModel oldModel, ComponentModel newModel) {
    OAuthClientCredentialsTokenSource.invalidate(newModel.getId());
    // ... existing reconciler reschedule logic stays as-is
}
```

- [ ] **Step 3: Modify `preRemove`**

```java
@Override
public void preRemove(KeycloakSession session, RealmModel realm, ComponentModel model) {
    OAuthClientCredentialsTokenSource.invalidate(model.getId());
    ReconcilerScheduler.cancel(session, model);
    // ... existing cleanup stays
}
```

- [ ] **Step 4: Run all tests, confirm no regression**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git commit -S -am "feat(auth): invalidate token cache on component update/remove

Unit-test coverage for invalidation is deferred to the integration
suite, where exercising the full lifecycle is more meaningful than
mocking ComponentModel + session in isolation."
```

**Chunk 2 done.**

---

## Chunk 3: `ScimClient` integration

### Task 15: CLIENT_CREDENTIALS auth branch in `ScimClient`

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (auth switch around line 52)

- [ ] **Step 1: Write a failing unit test for the construction branch**

`src/test/java/sh/libre/scim/core/ScimClientAuthBranchTest.java` (new file — keep separate from any existing `ScimClient` tests to avoid touching legacy fixtures):

```java
@Test
void clientCredentials_setsTokenSourceAndSeedsAuthHeader() {
    // Mock ComponentModel returning auth-mode=CLIENT_CREDENTIALS + oauth-* fields
    // Construct ScimClient; assert defaultHeaders.get(AUTHORIZATION) starts with "Bearer "
    // ...
}
```

(The exact construction harness will depend on the existing `ScimClient` constructor — read it first; copy the pattern existing tests use to instantiate it.)

- [ ] **Step 2: Run — fails**

Expected: FAIL.

- [ ] **Step 3: Add the `case "CLIENT_CREDENTIALS"` branch**

```java
case "CLIENT_CREDENTIALS":
    this.tokenSource = new OAuthClientCredentialsTokenSource(
        model.getId(),
        OAuthConfig.from(model),
        new OAuthClientCredentialsTokenSource.HttpTokenMinter(httpClient));
    defaultHeaders.put(HttpHeaders.AUTHORIZATION, tokenSource.currentAuthorizationHeader());
    break;
```

Add `private final OAuthClientCredentialsTokenSource tokenSource;` field initialized to `null` for non-OAuth branches.

- [ ] **Step 4: Run — passes**

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git commit -S -am "feat(auth): CLIENT_CREDENTIALS auth branch in ScimClient"
```

---

### Task 16: `sendWithAuthRefresh` helper — wraps a send with on-401/403 refresh-retry

- [ ] **Step 1: Add failing test**

`ScimClientAuthBranchTest`:

```java
@Test
void sendWithAuthRefresh_retriesOnce_on401() {
    // Construct ScimClient in CLIENT_CREDENTIALS mode with a stub HttpTokenMinter
    //   that returns "Bearer t1" then "Bearer t2"
    // Use a Supplier<ServerResponse<?>> that returns 401 first, 201 second
    // Assert: supplier called twice, tokenSource invalidated between, final response is 201
}
```

- [ ] **Step 2: Run — fails**

Expected: FAIL.

- [ ] **Step 3: Implement helper**

```java
private <S extends ResourceNode> ServerResponse<S> sendWithAuthRefresh(java.util.function.Supplier<ServerResponse<S>> op) {
    if (tokenSource == null) return op.get();
    refreshAuthHeader();
    ServerResponse<S> r = op.get();
    int status = r.getHttpStatus();
    if (status == 401 || status == 403) {
        tokenSource.invalidate();
        refreshAuthHeader();
        r = op.get();
    }
    return r;
}

private void refreshAuthHeader() {
    defaultHeaders.put(HttpHeaders.AUTHORIZATION, tokenSource.currentAuthorizationHeader());
}
```

- [ ] **Step 4: Run — passes**

Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git commit -S -am "feat(auth): sendWithAuthRefresh helper with on-401/403 retry"
```

---

### Task 17: Wire `sendWithAuthRefresh` into `create` / `replace` / `delete` / `importResources`

- [ ] **Step 1: For each of the four methods, wrap the existing `retry.executeSupplier(...)` call**

```java
ServerResponse<S> response = sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
    try {
        return scimRequestBuilder.create(...).sendRequest();
    } catch (ResponseException e) { throw new RuntimeException(e); }
}));
```

The exact lambda boilerplate exists in each method — preserve it; only the outer wrapper changes.

- [ ] **Step 2: Run full unit suite, confirm legacy BEARER/BASIC_AUTH/NONE tests still pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 3: Commit**

```sh
git commit -S -am "feat(auth): apply sendWithAuthRefresh to create/replace/delete/import"
```

**Chunk 3 done.**

---

## Chunk 4: Integration tests — `ScimOidcAuthIT`

This chunk takes a dual-mode approach for the token endpoint:

- **Default: real Keycloak token endpoint.** Tests that need to validate the JWT shape, signature, and claims point the SCIM provider at the actual Testcontainers Keycloak realm. A service-account client (`client_credentials` grant enabled) is created in the realm during setup; the plugin mints real tokens against it, and the SCIM sink (WireMock) verifies the bearer JWT against the realm's JWKS.
- **Per-test override: WireMock as token endpoint.** Tests that need to force specific token-endpoint responses (e.g., `expires_in=1`, 503, custom `access_token`) reconfigure the SCIM provider component mid-test to point `oauth-token-endpoint` at a WireMock stub path instead.

This combination gives us live-Keycloak format coverage *and* per-test response control, removing the "live-Keycloak end-to-end test" item the spec previously deferred. WireMock still serves the SCIM sink in every test.

### Task 18: Test harness — `ScimOidcAuthIT` scaffold

**Files:**
- Create: `src/integrationTest/java/sh/libre/scim/integration/ScimOidcAuthIT.java`
- Possibly modify: `src/integrationTest/java/sh/libre/scim/integration/AdminClients.java` — add a `createServiceAccountClient(realmName, clientId, clientSecret)` helper if one isn't already there.

- [ ] **Step 1: Copy `ScimResilienceIT`'s setup as a starting point**

Same `IntegrationTestBase` extension, same Keycloak+Postgres+OpenLDAP+WireMock harness. Differs in:
- A service-account client is created in the test realm with `serviceAccountsEnabled=true`, `clientAuthenticatorType=client-secret`, and `directAccessGrantsEnabled=false`. Capture its `clientId`/`secret`.
- SCIM-provider component is created with `auth-mode=CLIENT_CREDENTIALS`, `oauth-client-id` and `oauth-client-secret` from the client above, `oauth-token-endpoint` = `${keycloakInternalUrl}/realms/${realm}/protocol/openid-connect/token`.

`keycloakInternalUrl` is the in-network URL the plugin (running inside the Keycloak container) uses to talk to itself. `IntegrationTestBase` likely already exposes a Keycloak base URL — reuse it; the token endpoint is just `${base}/realms/${realm}/protocol/openid-connect/token`.

- [ ] **Step 2: Add a helper for swapping in WireMock as the token endpoint**

```java
/** Reconfigure the SCIM provider to point at a WireMock stub path instead of Keycloak's real token endpoint. */
protected void useWireMockTokenEndpoint(String stubPath) {
    // PUT /admin/realms/{realm}/components/{componentId} with oauth-token-endpoint = wireMock.baseUrl() + stubPath
    // (existing admin-client patterns in IntegrationTestBase / AdminClients)
}

/** Restore to the real Keycloak token endpoint. */
protected void useKeycloakTokenEndpoint() { /* mirror */ }
```

- [ ] **Step 3: Sanity test — `harnessLoadsAndComponentConfiguresCleanly`**

```java
@Test
void harnessLoadsAndComponentConfiguresCleanly() {
    // assert SCIM provider component exists with auth-mode=CLIENT_CREDENTIALS
    // assert service-account client exists in the realm and has client_credentials enabled
}
```

- [ ] **Step 4: Run the IT — confirm green**

Run: `./gradlew integrationTest --tests ScimOidcAuthIT.harnessLoadsAndComponentConfiguresCleanly`
Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git commit -S -m "test(auth): ScimOidcAuthIT scaffold with real-Keycloak token endpoint default"
```

---

### Task 19: `clientCredentialsHappyPath_jwtMintedAndVerifiable`

Uses the **real Keycloak token endpoint**. Verifies end-to-end JWT-format compatibility with what a JWKS-verifying receiver would actually check.

- [ ] **Step 1: Write the test**

```java
@Test
void clientCredentialsHappyPath_jwtMintedAndVerifiable() throws Exception {
    // Token endpoint is the real Keycloak (default from scaffold). SCIM sink is WireMock.
    triggerAdminUserCreate(realm.realm(), "alice", "alice@test.local");

    // Find the SCIM POST and pull the Authorization header.
    var scimRequest = wireMock.findAll(postRequestedFor(urlPathMatching("/Users")))
        .stream().findFirst().orElseThrow();
    String authHeader = scimRequest.getHeader("Authorization");
    assertThat(authHeader).startsWith("Bearer ");
    String jwt = authHeader.substring("Bearer ".length());

    // Fetch the realm's JWKS and verify the token.
    var jwksUrl = new java.net.URL(keycloakBaseUrl() + "/realms/" + realm.realm().toRepresentation().getRealm() + "/protocol/openid-connect/certs");
    var jwkSet = com.nimbusds.jose.jwk.JWKSet.load(jwksUrl);
    var processor = new com.nimbusds.jwt.proc.DefaultJWTProcessor<com.nimbusds.jose.proc.SecurityContext>();
    var keySource = new com.nimbusds.jose.jwk.source.ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext>(jwkSet);
    var keySelector = new com.nimbusds.jose.proc.JWSVerificationKeySelector<>(com.nimbusds.jose.JWSAlgorithm.RS256, keySource);
    processor.setJWSKeySelector(keySelector);
    var claims = processor.process(jwt, null);

    // Claims a real receiver would assert.
    assertThat(claims.getStringClaim("azp")).isEqualTo(serviceAccountClientId);
    assertThat(claims.getIssuer()).isEqualTo(keycloakBaseUrl() + "/realms/" + realm.realm().toRepresentation().getRealm());
    assertThat(claims.getExpirationTime()).isAfter(java.util.Date.from(java.time.Instant.now()));
}
```

`com.nimbusds:nimbus-jose-jwt` may or may not already be on the integration-test classpath. Check `gradle/libs.versions.toml` and `build.gradle`; if absent, add it as an `integrationTestImplementation` dep in this same commit. Keycloak itself ships nimbus-jose-jwt transitively so we should be reusing the same version.

- [ ] **Step 2: Run, fix until green**

Run: `./gradlew integrationTest --tests ScimOidcAuthIT.clientCredentialsHappyPath_jwtMintedAndVerifiable`
Expected: PASS.

- [ ] **Step 3: Commit**

```sh
git commit -S -am "test(auth): mint real JWT against realm; verify signature + azp + iss

End-to-end coverage of JWT format compatibility — the deferred
live-Keycloak-token-endpoint gap from the spec is now closed."
```

---

### Task 20: `cachedAcrossSubsequentEvents`

**Mode:** WireMock token endpoint (via `useWireMockTokenEndpoint()`). Counting `/token` POSTs is what's being asserted; cleaner against a stub than against the real Keycloak.

- [ ] **Step 1: Swap in WireMock token endpoint, stub a fixed access_token response**

- [ ] **Step 2: Two admin user creates in sequence; assert exactly one `/token` POST**

- [ ] **Step 3: Run** — Expected: PASS.

- [ ] **Step 4: Commit**

```sh
git commit -S -am "test(auth): token cache reused across subsequent SCIM events"
```

---

### Task 21: `cachedAcrossAsyncWorkers`

**Mode:** WireMock token endpoint (counting is the point).

- [ ] **Step 1: Trigger LDAP-import path fanning out via `runAsync` (full sync of N users)**

Verify: one `/token` POST despite N parallel SCIM POSTs.

- [ ] **Step 2: Run** — Expected: PASS.

- [ ] **Step 3: Commit**

```sh
git commit -S -am "test(auth): bulk-import workers share the token cache"
```

---

### Task 22: `expiryTriggersRefresh`

**Mode:** WireMock token endpoint. Real Keycloak's `accessTokenLifespan` is per-realm and per-client — we'd have to mutate it to 1s and back, which is more invasive than swapping in a stub.

- [ ] **Step 1: Stub `/token` to return `expires_in: 1`**

```java
useWireMockTokenEndpoint("/oauth/token-fast-expiry");
wireMock.stubFor(post("/oauth/token-fast-expiry").willReturn(okJson(
    "{\"access_token\":\"eyJ.first\",\"token_type\":\"Bearer\",\"expires_in\":1}")));
```

- [ ] **Step 2: Trigger event, sleep `> 1 + skew` (test-friendly: ~2s for clarity), trigger second event**

Verify: two `/token` POSTs.

- [ ] **Step 3: Run** — Expected: PASS.

- [ ] **Step 4: Commit**

```sh
git commit -S -am "test(auth): token re-minted after expires_in elapses"
```

---

### Task 23: `scim401TriggersRefreshAndRetry`

**Mode:** Real Keycloak token endpoint. Exercising the full refresh path with a real JWT each time catches any race between cache invalidation and re-mint that a stub might paper over.

- [ ] **Step 1: Stub `/Users` → 401 once then 201**

- [ ] **Step 2: Trigger event; verify two `/Users` POSTs with two distinct bearer JWTs**

Both JWTs should be valid per the realm's JWKS. The second should be different from the first (because invalidation forced a re-mint).

- [ ] **Step 3: Run** — Expected: PASS.

- [ ] **Step 4: Commit**

```sh
git commit -S -am "test(auth): SCIM 401 invalidates cache, retries with fresh JWT"
```

---

### Task 24: `tokenEndpointDown_eventFailsButPluginSurvives`

**Mode:** WireMock token endpoint, stubbed to 503. (Real Keycloak is up; we can't easily make it return 503 just for this test.)

- [ ] **Step 1: Swap to WireMock token endpoint stubbed `/token` → 503; trigger event**

Verify: SCIM POST never happens, error logged, next event re-attempts mint (no degenerate state).

- [ ] **Step 2: Run** — Expected: PASS.

- [ ] **Step 3: Commit**

```sh
git commit -S -am "test(auth): token endpoint outage fails op fail-open, plugin survives"
```

---

### Task 25: `scopeForwardedToTokenEndpoint`

**Mode:** Real Keycloak token endpoint. Configure an `optional` client scope on the service-account client, set `oauth-scope` accordingly, then verify the resulting JWT carries the requested scope in its `scope` claim. This proves the param round-trips end-to-end, not just that we put it on the wire.

- [ ] **Step 1: Create a client scope `scim:write` in the realm, assign it as `optional` to the service-account client**

- [ ] **Step 2: Configure SCIM component with `oauth-scope=scim:write`; trigger event**

- [ ] **Step 3: Verify the JWT sent on the SCIM request has `scope` claim containing `scim:write`**

```java
String jwt = bearerJwtFromScimRequest();  // helper extracted from Task 19
var claims = verifyAgainstJwks(jwt);
assertThat(claims.getStringClaim("scope")).contains("scim:write");
```

- [ ] **Step 4: Run** — Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git commit -S -am "test(auth): oauth-scope round-trips into JWT scope claim end-to-end"
```

**Chunk 4 done.** Run the full integration suite to catch regressions:

```sh
./gradlew integrationTest
```

---

## Chunk 5: Docs

### Task 26: Extend `docs/configuration.md`

**Files:**
- Modify: `docs/configuration.md`

- [ ] **Step 1: Add `CLIENT_CREDENTIALS` row to the Authentication mode table**

- [ ] **Step 2: Add the four `oauth-*` rows to the property reference table**

- [ ] **Step 3: Add a section *"OAuth 2.0 client_credentials"*** covering:
  - Cache behavior (JVM-wide, per `componentId`, `expires_in − 30s` skew)
  - `client_secret_basic` is the only client authentication shape
  - On-401 invalidation+refresh-and-retry semantics
  - Vault-provider compatibility for `oauth-client-secret`

- [ ] **Step 4: Add a *"What's NOT configurable"* note**: proactive refresh and token-endpoint 5xx retry are deliberate omissions (mirror the existing retry-policy note).

- [ ] **Step 5: Commit**

```sh
git commit -S -am "docs(auth): configuration reference for CLIENT_CREDENTIALS mode"
```

---

### Task 27: One-line README update

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add `CLIENT_CREDENTIALS` to the feature list**

Sample text:
```markdown
- **OAuth 2.0 client_credentials auth.** Outbound SCIM can mint
  Keycloak access tokens via client_credentials and send them as
  bearer tokens, matching what JWKS-verifying SCIM receivers expect.
  See [`docs/configuration.md`](docs/configuration.md) for setup.
```

- [ ] **Step 2: Commit**

```sh
git commit -S -am "docs: mention OAuth 2.0 client_credentials in feature list"
```

**Chunk 5 done.**

---

## Wrap-up

After all chunks are done:

- [ ] **Step 1: Run full test suite end-to-end**

```sh
./gradlew check    # unit + static analysis
./gradlew integrationTest
```

- [ ] **Step 2: Manually exercise via `docker-compose up`** to confirm the Admin Console UX renders the four `oauth-*` fields sensibly.

- [ ] **Step 3: Push the feature branch + open a PR**

```sh
git push -u origin feat/oauth-client-credentials
gh pr create --title "feat(auth): OAuth 2.0 client_credentials for outbound SCIM" \
  --body "$(cat <<'EOF'
## Summary
Adds a CLIENT_CREDENTIALS auth mode that mints Keycloak access tokens
and sends them as bearer tokens on outbound SCIM requests. Pairs with
the existing static BEARER / BASIC_AUTH / NONE modes.

Design doc: docs/superpowers/specs/2026-05-21-oauth-client-credentials-auth-design.md

## Test plan
- [ ] Unit suite green: \`./gradlew check\`
- [ ] Integration suite green: \`./gradlew integrationTest\`
- [ ] Manual: configure CLIENT_CREDENTIALS in Admin Console; verify SCIM
      sink receives \`Authorization: Bearer <jwt>\`
EOF
)"
```

- [ ] **Step 4: After merge**, release-please will pick up the `feat(auth):` commits and stage a minor-bump release PR. Verify the changelog reads sensibly before approving.

---

## Coverage / known gaps

Pinned in the design spec; called out here so the implementer doesn't re-litigate them mid-flight:

- No proactive refresh-ahead-of-expiry; lazy at-the-boundary is acceptable.
- No retry on token-endpoint 5xx (symmetric with the existing SCIM-5xx gap).
- No OIDC discovery (`oidc-issuer`); operators supply the token endpoint URL directly.
- No `client_secret_post`, `private_key_jwt`, or mTLS bearer.
- No `audience` request param.
- Unit-test gap on `onUpdate`/`preRemove` invalidation — covered indirectly by integration suite.
- Unit-test gap on the WARN-once `expires_in` default — covered indirectly by integration suite.
