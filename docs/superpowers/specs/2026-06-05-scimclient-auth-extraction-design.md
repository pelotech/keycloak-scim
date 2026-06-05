# ScimClient auth extraction + adapter-factory cleanup

**Date:** 2026-06-05
**Status:** Approved (design)
**Type:** Behavior-preserving refactor

## Background

Two code-quality items from the post-1.0.0 roadmap
(`docs/release-1.0.0-todos.md`):

- **D-ScimClient-split** — `ScimClient` (~485 lines) has accumulated mass.
- **D-Adapter-reflection** — `ScimClient.getAdapter(Class<A>)` instantiates
  adapters reflectively, which is awkward and loses compile-time safety.

This refactor addresses both with the smallest blast radius that removes the
genuine awkwardness, deliberately avoiding new service abstractions the
codebase doesn't otherwise use (the "conservative" option of the three
considered; the batch-sync and per-operation splits are left for later if the
class keeps growing).

Goal: no behavior change. The existing unit + integration test suite is the
correctness oracle.

## Change 1 — Replace reflection with a constructor-reference factory

Today every public operation takes a `Class<A> aClass` token and
`getAdapter` reflectively invokes the `(KeycloakSession, String)`
constructor, wrapping any failure in a `RuntimeException`:

```java
protected <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> A getAdapter(
        Class<A> aClass) {
    try {
        return aClass.getDeclaredConstructor(KeycloakSession.class, String.class)
                .newInstance(session, this.model.getId());
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}
```

There are exactly two concrete adapters — `UserAdapter` and `GroupAdapter` —
both already exposing the `(KeycloakSession, String)` constructor.

Introduce a functional interface in the `core` package:

```java
@FunctionalInterface
interface AdapterFactory<M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> {
    A create(KeycloakSession session, String componentId);
}
```

- `getAdapter` becomes `getAdapter(AdapterFactory<M,S,A> factory)` with body
  `factory.create(session, model.getId())` — no reflection, no `try/catch`.
- The `Class<A> aClass` parameter on `create`, `replace`, `delete`, `sync`,
  `refreshResources`, and `importResources` becomes
  `AdapterFactory<…> factory`. The recursive calls inside `refreshResources`
  (`this.create` / `this.replace`) thread the factory through.

### Call sites

All call sites flip `XAdapter.class` → `XAdapter::new`. The method
references type-check against the matching constructor.

- `event/ScimEventListenerProvider.java` (~14 sites)
- `storage/ScimStorageProviderFactory.java` (`sync(...)` — 2 sites)
- `ldap/ScimLdapStorageMapper.java` (`create`/`replace` — 2 sites)
- `reconcile/ReconcilerRunner.java` (`delete` — 1 site)

## Change 2 — Extract `ScimAuthHeaders` collaborator

New class `core/ScimAuthHeaders.java` owning the auth/header concern.

**Holds:** the `defaultHeaders` and `expectedResponseHeaders` maps and the
`OAuthClientCredentialsTokenSource tokenSource`.

**Constructor (`ComponentModel`):** runs the auth-mode switch
(`BEARER` / `BASIC_AUTH` / `CLIENT_CREDENTIALS`), seeds the authorization
header and content-type, and builds the token source — the
`buildTokenSourceFromModel` logic moves here.

**Methods (moved verbatim from `ScimClient`):** `sendWithAuthRefresh`,
`refreshAuthHeader` (private), `BasicAuthentication`, `BearerAuthentication`.
Exposes `headers()` and `expectedResponseHeaders()` accessors so the request
builder can be configured.

**Package-private test constructor:** accepts an explicit
`OAuthClientCredentialsTokenSource` (mirrors the one `ScimClient` has today)
so unit tests inject a stub minter without real HTTP.

### What stays in `ScimClient`

Everything else, plus a `final ScimAuthHeaders auth` field (package-private so
tests can reach `auth.headers()` / `auth.tokenSource`). Specifically:

- The request-builder lifecycle (`scimRequestBuilder`, `close()`).
- `genScimClientConfig()` — stays here (it builds the SDK config that the
  builder needs), but pulls the header maps from `auth.headers()` /
  `auth.expectedResponseHeaders()`.
- The TLS toggle `tlsHostnameVerificationDisabled()` — TLS is client config,
  not auth, so it stays with the config assembly.
- The retry registry, CRUD, sync, `getEM`, `getRealmId`, `genScimUrl`. CRUD/
  sync operations call `auth.sendWithAuthRefresh(...)`.

The test-injection constructor `ScimClient(model, session, tokenSource)` is
preserved; it builds a `ScimAuthHeaders` from the injected source and
delegates to the shared init path.

### Why this seam

The subtle part today is that the SDK request builder and `refreshAuthHeader`
share one mutable `defaultHeaders` map instance: `genScimClientConfig` passes
the map reference to the SDK, and on a 401/403 `refreshAuthHeader` mutates
that same instance so the next request picks up the new token. Putting the map
under single ownership in `ScimAuthHeaders` — which hands the *same reference*
to `genScimClientConfig` and mutates it on refresh — preserves the exact
semantics while making the coupling explicit and the logic independently
testable.

## Behavior preservation & test migration

Pure refactor; the existing suite is the oracle.

- **`ScimClientAuthBranchTest`** — retarget field reads:
  `client.defaultHeaders` → `client.auth.headers()`,
  `client.tokenSource` → `client.auth.tokenSource`,
  `client.sendWithAuthRefresh(op)` → `client.auth.sendWithAuthRefresh(op)`.
  The `new ScimClient(model, session, tokenSource)` test constructor is kept,
  so those lines are unchanged.
- **`ScimClientTlsTest`**, **`ScimClientCreateResponseTest`**,
  **`UserAdapterTest`** — unaffected (no moved members; `getAdapter`/`*.class`
  not referenced).
- Any test passing `XAdapter.class` (e.g. **`ScimLdapStorageMapperTest`**)
  flips to `XAdapter::new`.
- Integration tests cover the wiring end-to-end: `ScimResilienceIT`
  (retry + 401 refresh), the propagation ITs (CRUD/sync), `ScimMultiTenancyIT`
  (multi-provider fan-out).

## Verification plan

1. Establish a green baseline — `./gradlew test` and confirm
   `integrationTest` compiles — *before* any edit.
2. Change 1 → `./gradlew test`. Then Change 2 → `./gradlew test`.
3. Add a focused `ScimAuthHeadersTest` only if it covers logic the migrated
   tests don't already exercise (avoid duplicate coverage).
4. Final: `./gradlew test integrationTest`; LSP diagnostics clean.

## Non-goals

- No batch-sync (`ScimSyncService`) extraction or per-operation command
  classes (the "B"/"C" options). Revisit if `ScimClient` keeps growing.
- No behavior change: no new retry semantics, no auth changes, no API changes
  to the event/storage/ldap/reconcile callers beyond the `*.class` → `::new`
  swap.
