# SCIM Failure Handling — Typed Exceptions, Critical-Path Rollback, Skip/Stop — Design

**Status:** Approved (design phase)
**Date:** 2026-06-29

## Problem

Today every SCIM propagation failure is handled the same way: swallowed. The
client methods return `boolean` and log a warning on `!isSuccess()`
(`ScimClient.handleCreateResponse`, `ScimClient.java:286-290`), and the dispatcher
catches a bare `Exception` and logs it (`ScimDispatcher.runOne`,
`ScimDispatcher.java:138-142`). There is exactly one custom exception in the whole
codebase, internal to OAuth token minting.

This fail-open posture is the right **default** — it keeps Keycloak available when
a downstream SCIM sink is slow or broken, and the reconciler converges state
later. But it is the *only* behavior available, which leaves two gaps:

1. **No way to make SCIM a hard requirement.** Some deployments want a Keycloak
   user create/update to **fail** if it cannot be provisioned downstream (e.g.
   compliance: "a user must not exist here unless it exists there"). Today that is
   impossible — the operation always succeeds and the failure is a log line.

2. **Failures are untyped and indiscriminate.** A missing local↔external mapping,
   malformed remote data, and an unreachable endpoint are logged identically and
   treated identically. Batch syncs keep hammering a dead endpoint one record at a
   time, and operators cannot tell *why* a federated user failed to propagate.

## Goal

Introduce a small typed exception taxonomy and let each **execution context**
decide what a failure means, while preserving today's behavior as the default
everywhere.

- A `ScimClient` failure is **classified** (mapping / remote-data / endpoint) and
  carries a **transient-vs-permanent** signal.
- An opt-in **critical-path** mode can roll back the originating Keycloak
  operation on the interactive event path.
- Batch syncs make a **category-aware skip-vs-stop** decision instead of blindly
  continuing.
- The LDAP federation path keeps its async fail-open + self-heal behavior, gaining
  only category-aware logging.

**Non-goals:** changing the default behavior of any path; a durable outbox / dead-
letter queue (noted as a future add); multi-endpoint atomic rollback (documented
as an explicit limitation, below).

## Background — the three execution contexts

The decision of what a SCIM failure means depends entirely on *where* it happens.
The plugin has three distinct propagation contexts, and "rollback" is meaningful
in only one of them:

| # | Context | Entry point | Execution | Failure handling |
|---|---|---|---|---|
| 1 | Interactive events | `ScimEventListenerProvider` | **synchronous, pre-commit** (see below) | `rollback-strategy` (opt-in); default fail-open |
| 2 | LDAP federation | `ScimLdapStorageMapper.onImportUserFromLDAP` | async, post-commit (`runAsync`/bulk) | always async fail-open + **self-heal on next import** |
| 3 | Batch sync + reconciler | storage-provider sync, `ReconcilerRunner` | loop over many resources | `sync-on-error` (category-aware skip/stop) |

### Transaction-timing finding (why rollback is feasible in context 1)

Rollback requires that propagation runs *inside* the originating transaction,
before it commits. Verified against Keycloak 25 source
(`keycloak-server-spi-private` `EventListenerTransaction`, and
`EmailEventListenerProvider`):

- Keycloak invokes `EventListenerProvider.onEvent(...)` **pre-commit**, inside the
  live request transaction. Listeners with post-commit side effects (email,
  logging) deliberately **defer** them by buffering into an
  `EventListenerTransaction` enlisted via `enlistAfterCompletion`. That deferral
  only makes sense because `onEvent` itself fires before commit.
- Our `ScimEventListenerProvider` does **not** defer — it dispatches inline via
  `dispatcher.run(...)` (`ScimEventListenerProvider.java:49-57, 71-139`). So it
  runs pre-commit, and `session.getTransactionManager().setRollbackOnly()` from
  that path **will** undo the originating Keycloak operation.

The existing comment at `ScimEventListenerProvider.java:90` ("Admin events fire
post-commit") is **imprecise** and must be corrected as part of this work:
`getUserById` returns null after a DELETE because the row was already *flushed*
within the still-open transaction, not because the transaction committed. The
observable is right; the "post-commit" explanation is wrong and nearly caused a
mis-design.

By contrast, context 2's `runAsync` intentionally uses `enlistAfterCompletion`
(`ScimDispatcher.java:195`) so workers run **after** commit, in their own
sessions — which is exactly why rollback cannot and must not apply there.

## Design

### 1. Exception taxonomy

New package `sh.libre.scim.core.exceptions`:

```
ScimPropagationException (abstract, extends RuntimeException)
  ├─ abstract boolean isTransient();
  ├─ InconsistentScimMappingException        // missing/ambiguous local↔external mapping — isTransient() == false
  ├─ UnexpectedScimDataException             // malformed/unexpected remote data        — isTransient() == false
  └─ InvalidResponseFromScimEndpointException // non-2xx after retries, or transport failure
        // carries httpStatus (or 0 for transport); isTransient() == (status == 429 || status >= 500 || transport failure)
```

- `isTransient()` is the **single predicate** both the rollback and skip/stop
  decisions consult. Neither decision re-inspects HTTP codes — they ask the
  exception. This keeps classification in one place (the client).
- **Unchecked** (`extends RuntimeException`). Rationale: all `ScimClient` calls
  flow through `run(Consumer<ScimClient>)`, `runAsync(BiConsumer<…>)`, and
  `stream.forEach` lambdas, none of which permit checked exceptions. Going checked
  would force throwing-functional-interface variants of the entire dispatcher API.
  Failure handling is **centralized in three places** (`runOne`, the async worker,
  the batch loops), so the compiler-enforcement benefit of checked exceptions is
  marginal; unchecked costs nothing and touches no signatures. (The existing
  `OAuthClientCredentialsTokenSource` exception is unrelated and unchanged.)

### 2. `ScimClient` — classify and throw

`ScimClient` becomes the one place that maps an internal failure to a taxonomy
type. After the existing resilience4j retry is exhausted:

| Internal condition | Thrown |
|---|---|
| `ServerResponse.isSuccess() == false` | `InvalidResponseFromScimEndpointException(httpStatus)` |
| transport / `ProcessingException` (connection refused, timeout) | `InvalidResponseFromScimEndpointException` (transport; transient) |
| required local↔external mapping missing/ambiguous (`NoResultException` where a mapping is required) | `InconsistentScimMappingException` |
| malformed remote data while applying a response/import | `UnexpectedScimDataException` |

Existing internal fallbacks are **not** failures and are unchanged — they only
throw if the *final* attempt fails:

- replace → create on 404/400 (`ScimClient.java` replace path),
- group PUT → PATCH on 405,
- **delete with no mapping = idempotent no-op** (never throws; today's
  `NoResultException`-swallow on delete is preserved).

#### Membership methods keep a three-way signal (the one subtlety)

`patchGroupMembership` and `ensureGroupMembership` today return `boolean`, where
`false` carries a *specific non-error meaning*: "the member's user mapping isn't
present yet (lazy-import lag) — do not record it, retry on next import"
(`ScimLdapStorageMapper.java:104-119`). That contract must survive. These two
methods therefore keep three outcomes:

- returns `true` → applied;
- returns `false` → **not applicable yet** (member mapping absent) → caller retries
  next import — *unchanged self-heal*;
- **throws** `ScimPropagationException` → hard failure → caller logs by category /
  decides per context.

So "retry next import" stays a `boolean`; only hard failures become exceptions.

### 3. Context 1 — critical-path rollback (`ScimDispatcher.runOne`)

The interactive event path is already synchronous and pre-commit, so the change
is surgical and lives entirely in `runOne` (`ScimDispatcher.java:135-143`). Today
it catches `Exception` and logs. New behavior:

```
catch (ScimPropagationException e):
    switch component.get("rollback-strategy", "never"):
        case "never":         log(e)                               // today's behavior
        case "always":        setRollbackOnly(); logError(e)
        case "critical-only": if (e.isTransient()) setRollbackOnly(); logError(e)
                              else                 log(e)          // permanent → don't trap the admin forever
// non-ScimPropagationException RuntimeException → existing catch-and-log (defensive)
```

The new `catch (ScimPropagationException e)` block must be placed **before** the
existing broad `catch (Exception e)` at `ScimDispatcher.java:140` (a more-specific
catch must precede the broader one), with the existing block retained as the
defensive fallback.

`setRollbackOnly()` is `session.getTransactionManager().setRollbackOnly()`. When
the JAX-RS resource finishes, the transaction manager rolls back instead of
committing, so the originating admin/account operation visibly fails and its DB
effect (the flushed create/update/delete) is undone.

`critical-only` rolls back **only on transient failures** (endpoint down / 5xx /
429-exhausted) — where retrying the operation later may succeed. It never rolls
back on permanent failures (bad mapping, malformed data, 4xx), because that would
permanently block the admin on a condition a retry cannot fix.

This path covers every interactive trigger uniformly — user create/update/delete
(`ScimEventListenerProvider.java:49-57, 77, 86, 97`), group create/update/delete
(`:105, 109, 113`), membership (`:122-124`), and role-mapping fan-out (`:132-137`).

### 4. Context 2 — LDAP federation (catch-and-classify, behave the same)

Behavior is **unchanged** (async fail-open + self-heal); only the `catch` sites
improve their logging:

- the `runAsync` worker (`ScimDispatcher.java:219-227`) and the user-replace path
  (`ScimLdapStorageMapper.java:58-62`): catch `ScimPropagationException`, log **by
  category** rather than generically;
- the membership delta `forEach` loops (`ScimLdapStorageMapper.java:104-119`): wrap
  each member call so a thrown `ScimPropagationException` is caught **per-member**
  and treated exactly like today's `false` — the group is not recorded, so it
  retries on the next import. Per-member granularity is preserved; one bad member
  never aborts the whole user's reconciliation.

`rollback-strategy` is **ignored** here (and forbidden in combination with bulk;
see validation). Rolling back an LDAP import would abort the federation import
(user absent from Keycloak → cannot authenticate) and destroy the throughput
design that pulls SCIM HTTP off the import thread
(`ScimLdapStorageMapper.java:47-53`).

### 5. Context 3 — batch sync + reconciler (`SyncErrorPolicy`)

A small enum resolved from `sync-on-error`:

```
enum SyncErrorPolicy { AUTO, CONTINUE, STOP;
    boolean shouldStopRun(ScimPropagationException e) {
        return switch (this) {
            case AUTO     -> e.isTransient();  // endpoint down/5xx/429 → stop; per-record permanent → skip
            case CONTINUE -> false;
            case STOP     -> true;
        };
    }
}
```

Applied in the batch loops — `ScimClient.refreshResources` (`:539`, the
`sync-refresh` push of local resources outward), `ScimClient.importResources`
(`:563`, the `sync-import` pull), and `ReconcilerRunner` — orchestrated
per-component by `ScimClient.sync` (`:632`, invoked from
`ScimStorageProviderFactory.java:311-314`). On a caught
`ScimPropagationException`: log by category, then `shouldStopRun(e)` → either
`break` (mark the run aborted) or
tally-and-continue. Counts feed the existing `SynchronizationResult` (failed /
skipped) so operators see what happened. `AUTO` is the default: a per-record
permanent error skips that record; a transient endpoint-level error stops the run
(every remaining record would fail against the same dead endpoint).

### 6. Configuration + validation

Two properties added in `ScimStorageProviderFactory.getConfigProperties()`
(alongside `bulk-enabled` `:118`, `group-patchOp` `:151`, `user-patchOp` `:158`):

| Key | Values | Default | Context |
|---|---|---|---|
| `rollback-strategy` | `never` / `always` / `critical-only` | `never` | 1 |
| `sync-on-error` | `auto` / `continue` / `stop` | `auto` | 3 |

Validation in the factory's existing config-validation path (where OAuth and
extension mappings are already validated): **reject** `rollback-strategy ≠ never`
combined with `bulk-enabled = true`, with a clear `ComponentValidationException`.
A `bulk-enabled` component is by definition a high-volume federation-import sink
whose creates defer to the bulk lane post-commit; pre-commit rollback cannot apply
to it, so the combination is incoherent.

Both defaults reproduce today's behavior exactly, so existing components are
unaffected.

## Known limitations (out of scope)

- **Multi-endpoint atomicity.** `run()` fans out to *all* matching components
  (`ScimDispatcher.java:126-133`). If two are critical-path and the second fails,
  `setRollbackOnly()` undoes the Keycloak operation, but the first endpoint's SCIM
  write was already sent and **cannot be un-sent** — and its mapping row rolls back
  with the transaction, leaving an orphan resource on that endpoint. This is
  libre.sh's unsolved compensation TODO. v1 **documents** that critical-path is
  intended for a **single** critical endpoint; orphans on sibling endpoints are not
  auto-cleaned (a future compensation/DLQ concern). This caveat must also surface
  into the user-facing configuration docs, not just this spec — it is the one
  behavioral sharp edge an operator enabling `rollback-strategy` must understand.
- **No durable dead-letter queue.** Context 2 failures self-heal on the next
  import and context 3 failures are tallied, but neither persists a record of
  users that failed after N attempts. A DLQ is a clean future add, deferred.

## Testing

### Unit

- **`isTransient()` classification:** 429 / 5xx / transport → transient; 4xx /
  mapping / data → permanent.
- **`ScimClient` throws the right type** for each `ServerResponse` status and for a
  thrown `ProcessingException` (extends the existing `ScimClient*Test` /
  `ScimClientRetryTest` patterns).
- **`runOne` rollback decision:** {never, always, critical-only} × {transient,
  permanent} → assert `setRollbackOnly()` is/ isn't called (mock
  `KeycloakTransactionManager`, verify invocation).
- **`SyncErrorPolicy.shouldStopRun`:** {AUTO, CONTINUE, STOP} × {transient,
  permanent}.
- **Config validation:** `rollback-strategy ≠ never` + `bulk-enabled` → rejected
  (extends `ScimStorageProviderFactoryValidationTest`).
- **LDAP membership self-heal:** a per-member thrown `ScimPropagationException` →
  group not recorded → retried next import (extends `EnsureGroupMembershipTest` /
  `GroupMembershipPatchTest`).

### Integration (WireMock + Testcontainers Keycloak)

- **Critical-path rollback:** WireMock returns 503 on user-create; admin creates a
  user. With `rollback-strategy=always`, assert the **Keycloak user does not
  exist** afterward (operation rolled back). With `never`, assert it **does** exist
  (fail-open unchanged).
- **critical-only + permanent (400):** assert the user **is not** rolled back
  (exists).
- **Batch stop-on-transient:** WireMock 503 during a sync; assert the run aborts
  early and `SynchronizationResult` reflects the abort.

### Regression

Full unit + integration suites stay green — defaults preserve current behavior, so
the executor/event/import/membership/reconciler tests are unaffected.

## Rejected alternatives

- **Checked exceptions** (libre.sh's approach, with custom throwing functional
  interfaces): rejected because it fights our `Consumer`/`BiConsumer`/stream-based
  dispatcher; the enforcement benefit is marginal when handling is centralized in
  three places. See §1.
- **Rollback on the batch / LDAP paths:** rejected — aborting an LDAP import breaks
  federation login and the throughput design; rolling back a multi-record sync over
  one bad record is wrong. Those paths use skip/stop and self-heal instead.
- **Granular libre.sh-style skip/stop** (separate push/pull/group-member/bad-
  config/bad-data toggles): rejected as over-configuration; the category-aware
  `AUTO` default plus one override covers the need (§5).
