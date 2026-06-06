# ScimClient Auth Extraction + Adapter-Factory Cleanup Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove reflection-based adapter instantiation and extract the auth/header concern out of `ScimClient` into a focused `ScimAuthHeaders` collaborator, while fixing a latent `BASIC_AUTH` double-lookup bug.

**Architecture:** Two behavior-preserving refactors plus one scoped bug fix. (1) Replace the `Class<A>`+reflection adapter instantiation with a `public AdapterFactory` functional interface invoked via constructor references (`UserAdapter::new`). (2) Move the header maps, auth-mode setup, token source, and `sendWithAuthRefresh`/`refreshAuthHeader` into a new `ScimAuthHeaders` class that `ScimClient` owns; `ScimClient` keeps the request-builder lifecycle, retry registry, CRUD, and sync. (3) Fix `BasicAuthentication` to use already-resolved credentials.

**Tech Stack:** Java 21, Gradle (Kotlin DSL), JUnit 5 + AssertJ + Mockito (unit), Testcontainers + WireMock (integration), Captain-Goldfish SCIM SDK, resilience4j, Keycloak SPI.

**Spec:** `docs/superpowers/specs/2026-06-05-scimclient-auth-extraction-design.md`

---

## File Structure

**Created:**
- `src/main/java/sh/libre/scim/core/AdapterFactory.java` — `public` functional interface `(KeycloakSession, String) -> Adapter`.
- `src/main/java/sh/libre/scim/core/ScimAuthHeaders.java` — owns header maps, auth-mode setup, token source, and 401/403 refresh.

**Modified (main):**
- `src/main/java/sh/libre/scim/core/ScimClient.java` — swap `Class<A>` → `AdapterFactory` on 6 methods + `getAdapter`; delegate auth to `ScimAuthHeaders`; drop the moved members.
- `src/main/java/sh/libre/scim/event/ScimEventListenerProvider.java` — `*.class` → `::new` (14 sites).
- `src/main/java/sh/libre/scim/storage/ScimStorageProviderFactory.java` — `*.class` → `::new` (2 sites).
- `src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java` — `*.class` → `::new` (2 sites).
- `src/main/java/sh/libre/scim/reconcile/ReconcilerRunner.java` — `*.class` → `::new` (1 site).

**Modified (test):**
- `src/test/java/sh/libre/scim/ldap/ScimLdapStorageMapperTest.java` — `verify(...).create(UserAdapter.class, ...)` → matcher form.
- `src/test/java/sh/libre/scim/core/ScimClientAuthBranchTest.java` — retarget `client.defaultHeaders`/`client.tokenSource`/`client.sendWithAuthRefresh` to `client.auth.*`; add `BASIC_AUTH` test.

---

## Chunk 1: Baseline, adapter factory, basic-auth fix, auth extraction

### Task 0: Establish a green baseline

**Files:** none (verification only).

- [ ] **Step 1: Run the unit test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. If anything fails here, STOP — the baseline must be green before refactoring. Capture the failing output and surface it.

- [ ] **Step 2: Confirm integration tests compile**

Run: `./gradlew compileIntegrationTestJava`
Expected: BUILD SUCCESSFUL. (Full `integrationTest` needs Docker and is run at the end, Task 4.)

---

### Task 1: Introduce `AdapterFactory` and remove reflection

**Files:**
- Create: `src/main/java/sh/libre/scim/core/AdapterFactory.java`
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (`getAdapter` `:201-209`; method signatures at `:211, 280, 349, 382, 405, 472`; recursive calls at `:394, 397`)
- Modify call sites: `event/ScimEventListenerProvider.java`, `storage/ScimStorageProviderFactory.java`, `ldap/ScimLdapStorageMapper.java`, `reconcile/ReconcilerRunner.java`
- Modify test: `src/test/java/sh/libre/scim/ldap/ScimLdapStorageMapperTest.java`

- [ ] **Step 1: Create the `AdapterFactory` interface**

Create `src/main/java/sh/libre/scim/core/AdapterFactory.java`:

```java
package sh.libre.scim.core;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RoleMapperModel;
import de.captaingoldfish.scim.sdk.common.resources.ResourceNode;

/**
 * Constructs an {@link Adapter} for a given session + component, replacing the
 * previous reflection-based instantiation in {@link ScimClient}. Implemented in
 * practice by the adapter constructors as method references — {@code UserAdapter::new},
 * {@code GroupAdapter::new}.
 *
 * <p>Must be {@code public}: it is a parameter type on {@code ScimClient}'s public
 * CRUD/sync methods, whose callers live in other packages.
 */
@FunctionalInterface
public interface AdapterFactory<M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> {
    A create(KeycloakSession session, String componentId);
}
```

- [ ] **Step 2: Change `getAdapter` to take a factory**

In `ScimClient.java`, replace the reflective `getAdapter` (`:201-209`) with:

```java
    protected <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> A getAdapter(
            AdapterFactory<M, S, A> factory) {
        return factory.create(session, this.model.getId());
    }
```

- [ ] **Step 3: Swap the parameter on the six public operations**

In `ScimClient.java`, change each method's first parameter from `Class<A> aClass` to `AdapterFactory<M, S, A> factory`, and update the `getAdapter(aClass)` calls inside them to `getAdapter(factory)`. Methods: `create` (`:211`), `replace` (`:280`), `delete` (`:349`), `refreshResources` (`:382`), `importResources` (`:405`), `sync` (`:472`). Inside `refreshResources`, the recursive calls become `this.create(factory, resource)` and `this.replace(factory, resource)` (`:394, 397`). Inside `sync`, the delegations become `this.importResources(factory, syncRes)` and `this.refreshResources(factory, syncRes)` (`:475, 478`). Generic type parameters on the method signatures stay exactly as they are.

- [ ] **Step 4: Flip the production call sites to constructor references**

Replace `UserAdapter.class` → `UserAdapter::new` and `GroupAdapter.class` → `GroupAdapter::new` at every call into these methods:
- `event/ScimEventListenerProvider.java` — lines 49, 54, 57, 77, 79, 86, 97, 105, 109, 113, 121, 123, 131, 135
- `storage/ScimStorageProviderFactory.java` — lines 283, 286
- `ldap/ScimLdapStorageMapper.java` — lines 50, 55
- `reconcile/ReconcilerRunner.java` — line 132

Use a search to confirm none remain:

Run: `grep -rn "Adapter\.class" src/main/java`
Expected: no output.

- [ ] **Step 5: Fix the Mockito verifications in `ScimLdapStorageMapperTest`**

A method reference is a fresh lambda with no stable `equals`, so verifying against `UserAdapter::new` would never match. Change the two verifications (`:70, :93`) to a typed matcher. Add imports if missing: `import static org.mockito.ArgumentMatchers.any;` and `import static org.mockito.ArgumentMatchers.eq;` (note `eq` is already imported per `:25`), plus `import sh.libre.scim.core.AdapterFactory;`, `import sh.libre.scim.core.UserAdapter;` (likely already present), `import org.keycloak.models.UserModel;`, `import de.captaingoldfish.scim.sdk.common.resources.User;`.

```java
verify(client).create(
    ArgumentMatchers.<AdapterFactory<UserModel, User, UserAdapter>>any(), eq(user));
verify(client).replace(
    ArgumentMatchers.<AdapterFactory<UserModel, User, UserAdapter>>any(), eq(user));
```

If the existing `verify` lines pass `user` as a plain argument (no matcher), switching one argument to a matcher requires all arguments to be matchers — hence `eq(user)`. Keep/extend any `@SuppressWarnings({"unchecked", "rawtypes"})` already on the test method so the generic-witness `any()` does not warn-fail.

- [ ] **Step 6: Run the suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. The reflection is gone and all call sites compile against the factory.

- [ ] **Step 7: Confirm integration sources still compile**

Run: `./gradlew compileIntegrationTestJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/sh/libre/scim/core/AdapterFactory.java \
        src/main/java/sh/libre/scim/core/ScimClient.java \
        src/main/java/sh/libre/scim/event/ScimEventListenerProvider.java \
        src/main/java/sh/libre/scim/storage/ScimStorageProviderFactory.java \
        src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java \
        src/main/java/sh/libre/scim/reconcile/ReconcilerRunner.java \
        src/test/java/sh/libre/scim/ldap/ScimLdapStorageMapperTest.java
git commit -m "refactor(core): replace adapter reflection with AdapterFactory constructor refs"
```

---

### Task 2: Fix the `BASIC_AUTH` double-lookup bug (TDD)

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (`BasicAuthentication` `:120-126`)
- Test: `src/test/java/sh/libre/scim/core/ScimClientAuthBranchTest.java`

- [ ] **Step 1: Write the failing test**

Add to `ScimClientAuthBranchTest.java`. Add imports `java.util.Base64`, `java.nio.charset.StandardCharsets` if absent.

```java
    @Test
    void basicAuth_buildsHeaderFromConfiguredCredentials() {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "BASIC_AUTH");
        config.putSingle("auth-user", "scim-user");
        config.putSingle("auth-pass", "s3cr3t");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId("comp-basic");

        var client = new ScimClient(model, mock(KeycloakSession.class));

        String expected = "Basic " + Base64.getEncoder()
            .encodeToString("scim-user:s3cr3t".getBytes(StandardCharsets.UTF_8));
        assertThat(client.defaultHeaders.get(HttpHeaders.AUTHORIZATION)).isEqualTo(expected);
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew test --tests "sh.libre.scim.core.ScimClientAuthBranchTest"`
Expected: FAIL — the buggy `BasicAuthentication` re-resolves `model.get("scim-user")`/`model.get("s3cr3t")` (both null), so the header is not the expected `Basic` value.

(If the SDK's `getAuthorizationHeaderValue()` uses a non-standard base64 encoding, the failure output will reveal the actual format — adjust the `expected` construction to match the SDK's encoding of `scim-user:s3cr3t`, but do NOT weaken the assertion to accept the null-derived value.)

- [ ] **Step 3: Apply the fix**

In `ScimClient.java`, replace `BasicAuthentication` (`:120-126`) so it uses the passed-in values directly:

```java
    protected String BasicAuthentication(String username, String password) {
        return BasicAuth.builder()
            .username(username)
            .password(password)
            .build()
            .getAuthorizationHeaderValue();
    }
```

The caller (`:88-91`) is unchanged: it still passes `model.get("auth-user")`, `model.get("auth-pass")`.

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew test --tests "sh.libre.scim.core.ScimClientAuthBranchTest"`
Expected: PASS (all methods, including the new one).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimClient.java \
        src/test/java/sh/libre/scim/core/ScimClientAuthBranchTest.java
git commit -m "fix(auth): BASIC_AUTH used config keys as credentials instead of values"
```

---

### Task 3: Extract `ScimAuthHeaders`

**Files:**
- Create: `src/main/java/sh/libre/scim/core/ScimAuthHeaders.java`
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (constructors `:47-118`; `genScimClientConfig` `:128-153`; remove `BasicAuthentication`/`BearerAuthentication`/`refreshAuthHeader`/`sendWithAuthRefresh`/`buildTokenSourceFromModel`; replace internal `sendWithAuthRefresh(...)` calls at `:234, 292, 360, 412`)
- Modify test: `src/test/java/sh/libre/scim/core/ScimClientAuthBranchTest.java`

- [ ] **Step 1: Create `ScimAuthHeaders`**

Create `src/main/java/sh/libre/scim/core/ScimAuthHeaders.java`:

```java
package sh.libre.scim.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import de.captaingoldfish.scim.sdk.client.http.BasicAuth;
import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.resources.base.ScimObjectNode;

import org.keycloak.component.ComponentModel;

import com.google.common.net.HttpHeaders;

/**
 * Owns the outbound-auth concern for a {@link ScimClient}: the HTTP header maps
 * handed to the SCIM SDK request builder, and the token-refresh-on-401/403 retry
 * for {@code CLIENT_CREDENTIALS} mode.
 *
 * <p>{@link #headers()} returns the live map instance (NOT a copy): it is shared
 * by reference with the SDK request builder via {@code ScimClient.genScimClientConfig},
 * and {@link #refreshAuthHeader()} mutates that same instance so a re-minted token
 * is picked up on the retry. Returning a defensive copy here would silently break
 * token refresh.
 */
class ScimAuthHeaders {

    private final Map<String, String> defaultHeaders = new HashMap<>();
    private final Map<String, String> expectedResponseHeaders = new HashMap<>();
    final OAuthClientCredentialsTokenSource tokenSource;

    ScimAuthHeaders(ComponentModel model) {
        this(model, buildTokenSourceFromModel(model));
    }

    // package-private for tests: inject an explicit token source (stub minter)
    // so unit tests avoid real HTTP.
    ScimAuthHeaders(ComponentModel model, OAuthClientCredentialsTokenSource tokenSource) {
        this.tokenSource = tokenSource;

        if (tokenSource != null) {
            defaultHeaders.put(HttpHeaders.AUTHORIZATION, tokenSource.currentAuthorizationHeader());
        } else {
            switch (model.get("auth-mode")) {
                case "BEARER":
                    defaultHeaders.put(HttpHeaders.AUTHORIZATION,
                        BearerAuthentication(model.get("auth-pass")));
                    break;
                case "BASIC_AUTH":
                    defaultHeaders.put(HttpHeaders.AUTHORIZATION,
                        BasicAuthentication(model.get("auth-user"), model.get("auth-pass")));
                    break;
            }
        }

        defaultHeaders.put(HttpHeaders.CONTENT_TYPE, model.get("content-type"));
    }

    private static OAuthClientCredentialsTokenSource buildTokenSourceFromModel(ComponentModel model) {
        if ("CLIENT_CREDENTIALS".equals(model.get("auth-mode"))) {
            return new OAuthClientCredentialsTokenSource(
                model.getId(),
                OAuthConfig.from(model),
                new OAuthClientCredentialsTokenSource.HttpTokenMinter(model.getId()));
        }
        return null;
    }

    Map<String, String> headers() {
        return defaultHeaders;
    }

    Map<String, String> expectedResponseHeaders() {
        return expectedResponseHeaders;
    }

    protected String BasicAuthentication(String username, String password) {
        return BasicAuth.builder()
            .username(username)
            .password(password)
            .build()
            .getAuthorizationHeaderValue();
    }

    protected String BearerAuthentication(String token) {
        return "Bearer " + token;
    }

    private void refreshAuthHeader() {
        assert tokenSource != null;
        defaultHeaders.put(HttpHeaders.AUTHORIZATION, tokenSource.currentAuthorizationHeader());
    }

    <S extends ScimObjectNode> ServerResponse<S> sendWithAuthRefresh(Supplier<ServerResponse<S>> op) {
        if (tokenSource == null) {
            return op.get();
        }
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
}
```

- [ ] **Step 2: Rewire `ScimClient` constructors**

In `ScimClient.java`: remove the fields `contentType`, `defaultHeaders`, `expectedResponseHeaders`, `tokenSource` (`:40, 43, 44, 45`) and add `final protected ScimAuthHeaders auth;` (package-private access is fine — keep it reachable from same-package tests; `protected`/package-private both work since tests are same-package). Remove the `buildTokenSourceFromModel` static method (`:51-59`) — it now lives in `ScimAuthHeaders`. Replace the two existing constructors and the auth-seeding block with three:

```java
    public ScimClient(ComponentModel model, KeycloakSession session) {
        this(model, session, new ScimAuthHeaders(model));
    }

    // package-private for tests: inject an explicit token source.
    ScimClient(ComponentModel model, KeycloakSession session, OAuthClientCredentialsTokenSource tokenSource) {
        this(model, session, new ScimAuthHeaders(model, tokenSource));
    }

    private ScimClient(ComponentModel model, KeycloakSession session, ScimAuthHeaders auth) {
        this.model = model;
        this.session = session;
        this.scimApplicationBaseUrl = model.get("endpoint");
        this.auth = auth;

        scimRequestBuilder = new ScimRequestBuilder(scimApplicationBaseUrl, genScimClientConfig());

        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(10)
            .intervalFunction(IntervalFunction.ofExponentialBackoff())
            .retryExceptions(ProcessingException.class, IORuntimeException.class)
            .build();

        registry = RetryRegistry.of(retryConfig);
    }
```

Preserve the existing explanatory comments on the retry config (the `IORuntimeException`/5xx-not-retried notes at `:103-114`) — carry them into the new private constructor verbatim.

- [ ] **Step 3: Point `genScimClientConfig` at the auth maps**

In `genScimClientConfig` (`:128-153`), change `.httpHeaders(defaultHeaders)` → `.httpHeaders(auth.headers())` and `.expectedHttpResponseHeaders(expectedResponseHeaders)` → `.expectedHttpResponseHeaders(auth.expectedResponseHeaders())`. Leave the `KeepAliveConfigManipulator`, the `tlsHostnameVerificationDisabled()` branch, and `tlsHostnameVerificationDisabled()` itself in `ScimClient` unchanged.

- [ ] **Step 4: Remove the moved members and delegate refresh calls**

In `ScimClient.java`: delete `BasicAuthentication` (`:120-126`), `BearerAuthentication` (`:160-162`), `refreshAuthHeader` (`:164-167`), and `sendWithAuthRefresh` (`:169-184`). Replace each internal call `sendWithAuthRefresh(...)` with `auth.sendWithAuthRefresh(...)` at the four call sites (`:234, 292, 360, 412`). Remove now-unused imports (`BasicAuth`, `HttpHeaders`, `Supplier`, `ScimObjectNode`) — let the compiler/LSP guide which are unused.

- [ ] **Step 5: Retarget the auth-branch tests to `client.auth`**

In `ScimClientAuthBranchTest.java`, update field/method access (the `new ScimClient(model, session, ts)` constructor and `new ScimClient(model, session)` stay unchanged):
- `client.defaultHeaders.get(...)` → `client.auth.headers().get(...)` (incl. the new `basicAuth_...` test from Task 2)
- `client.tokenSource` → `client.auth.tokenSource`
- `client.sendWithAuthRefresh(op)` → `client.auth.sendWithAuthRefresh(op)`

- [ ] **Step 6: Run the suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Check LSP diagnostics on the touched files**

Confirm no unresolved imports or unused-symbol errors remain in `ScimClient.java`, `ScimAuthHeaders.java`, and `ScimClientAuthBranchTest.java`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimAuthHeaders.java \
        src/main/java/sh/libre/scim/core/ScimClient.java \
        src/test/java/sh/libre/scim/core/ScimClientAuthBranchTest.java
git commit -m "refactor(core): extract ScimAuthHeaders from ScimClient"
```

---

### Task 4: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Full unit suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Integration suite (requires Docker)**

Run: `./gradlew integrationTest`
Expected: BUILD SUCCESSFUL. Key coverage: `ScimResilienceIT` (retry + 401 refresh path through `auth.sendWithAuthRefresh`), the propagation ITs (CRUD/sync via the factory), `ScimMultiTenancyIT` (multi-provider fan-out).

- [ ] **Step 3: Confirm no reflection or stray `*.class` adapter usage remains**

Run: `grep -rn "getDeclaredConstructor\|Adapter\.class" src/main/java`
Expected: no output.

- [ ] **Step 4: Report line-count delta for `ScimClient`**

Run: `wc -l src/main/java/sh/libre/scim/core/ScimClient.java src/main/java/sh/libre/scim/core/ScimAuthHeaders.java`
Expected: `ScimClient.java` materially smaller than the original 485 lines; the auth concern now isolated in `ScimAuthHeaders.java`.
