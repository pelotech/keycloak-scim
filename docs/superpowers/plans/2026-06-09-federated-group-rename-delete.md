# Federated Group Rename/Delete Propagation — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Propagate LDAP-federated group renames (push `displayName`) and deletes (SCIM `DELETE` for orphaned mappings) to SCIM via a new group phase in the existing reconciler.

**Architecture:** Extend `ReconcilerRunner` with a group phase that runs after the user phase: a sequential scan classifies each `Group`-type mapping as delete (local group gone), rename (name drifted vs a `scim-synced-name` attribute, or attribute absent), or no-op; then parallel workers issue SCIM `DELETE` (reuse `ScimClient.delete`) and a new targeted `ScimClient.reconcileGroupName` (displayName-only PATCH). `run()` returns a result struct instead of an int.

**Tech Stack:** Java 21, Keycloak SPI, Captain Goldfish SCIM SDK, resilience4j, JUnit 5 + Mockito (unit), Testcontainers + OpenLDAP + WireMock (integration).

**Spec:** `docs/superpowers/specs/2026-06-09-federated-group-rename-delete-design.md`

---

## Chunk 1: Rename primitives (adapter + client method)

### Task 1: `GroupAdapter.toDisplayNamePatchBuilder`

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/GroupAdapter.java` (add method after `toMembershipPatchBuilder`, ~line 228; add a public constant near the top)
- Test: `src/test/java/sh/libre/scim/core/GroupDisplayNamePatchTest.java` (create)

A displayName-only REPLACE PATCH, parallel to the existing `toMembershipPatchBuilder`. Also introduce the `scim-synced-name` attribute name as a public constant here (single source of truth; the reconciler reads it, `reconcileGroupName` writes it).

- [ ] **Step 1: Write the failing test** (mirror `GroupMembershipPatchTest`'s setup exactly — same `@Mock` fields, `@BeforeEach`, and `ScimRequestBuilder` construction)

```java
package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.captaingoldfish.scim.sdk.client.ScimClientConfig;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupDisplayNamePatchTest {

    private static final String GROUP_URL = "https://scim.example/scim/v2/Groups/grp-ext-1";

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock JpaConnectionProvider jpaConnectionProvider;
    @Mock EntityManager entityManager;

    private GroupAdapter adapter;
    private ScimRequestBuilder scimRequestBuilder;

    @BeforeEach
    void setUp() {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-id");
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpaConnectionProvider);
        when(jpaConnectionProvider.getEntityManager()).thenReturn(entityManager);

        adapter = new GroupAdapter(session, "component-id");
        scimRequestBuilder = new ScimRequestBuilder(
            "https://scim.example/scim/v2", ScimClientConfig.builder().build());
    }

    @Test
    void buildsDisplayNameReplaceOnly() {
        adapter.setDisplayName("engineers-renamed");

        var patch = adapter.toDisplayNamePatchBuilder(scimRequestBuilder, GROUP_URL);

        String body = patch.getResource();
        assertThat(body).contains("\"op\":\"replace\"");
        assertThat(body).contains("\"path\":\"displayName\"");
        assertThat(body).contains("engineers-renamed");
        // displayName-only: no member operations in the body.
        assertThat(body).doesNotContain("members");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "sh.libre.scim.core.GroupDisplayNamePatchTest"`
Expected: FAIL — `toDisplayNamePatchBuilder` does not exist (compile error).

- [ ] **Step 3: Implement**

Add the constant near the top of `GroupAdapter` (with the other fields, ~line 26):

```java
    /** Group attribute holding the displayName last pushed to SCIM; owned by the reconciler. */
    public static final String SYNCED_NAME_ATTRIBUTE = "scim-synced-name";
```

Add the method after `toMembershipPatchBuilder`:

```java
    /**
     * Builds a minimal {@code REPLACE displayName} PATCH — no member list — for
     * propagating a federated group rename. Parallel to
     * {@link #toMembershipPatchBuilder}; used by the reconciler's group phase.
     */
    public PatchBuilder<Group> toDisplayNamePatchBuilder(
            ScimRequestBuilder scimRequestBuilder, String url) {
        var patchBuilder = scimRequestBuilder.patch(url, Group.class);
        patchBuilder.addOperation()
            .path("displayName")
            .op(PatchOp.REPLACE)
            .value(displayName)
            .next()
            .build();
        return patchBuilder;
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "sh.libre.scim.core.GroupDisplayNamePatchTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/GroupAdapter.java \
        src/test/java/sh/libre/scim/core/GroupDisplayNamePatchTest.java
git commit -m "feat(group): add displayName-only PATCH builder for federated rename"
```

### Task 2: `ScimClient.reconcileGroupName`

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (add after `ensureGroupMembership`)
- Test: `src/test/java/sh/libre/scim/core/ReconcileGroupNameTest.java` (create)

Pushes the current group name to SCIM and, on success, stamps `scim-synced-name`. Models its structure on `patchGroupMembership`: `group-patchOp=true` → targeted displayName PATCH; `group-patchOp=false` → full `replace(group)` fallback. Missing local group or missing mapping → `infof` skip.

The unit test pins the branching with a Mockito spy (like `EnsureGroupMembershipTest`): `group-patchOp=false` delegates to `replace`; missing local group skips entirely. The end-to-end PATCH wiring is covered by the IT (Task 6). The `any()` matcher caveat from `EnsureGroupMembershipTest` applies — use `any()` for the factory argument.

- [ ] **Step 1: Write the failing test**

```java
package sh.libre.scim.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

class ReconcileGroupNameTest {

    private ScimClient newClient(boolean groupPatchOp, GroupModel group) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        config.putSingle("group-patchOp", Boolean.toString(groupPatchOp));
        model.setConfig(config);
        model.setId("comp-grp");

        var session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        var groups = mock(GroupProvider.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(session.groups()).thenReturn(groups);
        when(groups.getGroupById(realm, "grp-1")).thenReturn(group);

        // ScimClient ctor does not touch the session; the spy stubs the public methods under test.
        return new ScimClient(model, session);
    }

    @Test
    void groupPatchOpOff_fallsBackToReplace() {
        var group = mock(GroupModel.class);
        var client = spy(newClient(false, group));
        doNothing().when(client).replace(any(), eq(group));

        client.reconcileGroupName(GroupAdapter::new, "grp-1");

        verify(client).replace(any(), eq(group));
    }

    @Test
    void missingLocalGroup_isSkipped() {
        var client = spy(newClient(true, null));

        client.reconcileGroupName(GroupAdapter::new, "grp-1");

        verify(client, never()).replace(any(), any());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "sh.libre.scim.core.ReconcileGroupNameTest"`
Expected: FAIL — `reconcileGroupName` does not exist.

- [ ] **Step 3: Implement** (model on `patchGroupMembership` at `ScimClient.java:333-380`)

```java
/**
 * Pushes a federated group's current name to SCIM and records it in the
 * {@link GroupAdapter#SYNCED_NAME_ATTRIBUTE} attribute. Used by the
 * reconciler's group phase to propagate LDAP-origin renames, which fire no
 * admin event.
 *
 * <p>When {@code group-patchOp=true}, sends a targeted displayName-only PATCH.
 * When {@code group-patchOp=false}, falls back to a full {@link #replace}
 * (which re-sends the member list and runs its own 405/404 recovery branches,
 * but does update the name). A missing local group or missing SCIM mapping is
 * logged and skipped. The attribute is stamped only after a successful push,
 * so a failed attempt is retried on the next pass.
 */
public void reconcileGroupName(
        AdapterFactory<GroupModel, Group, GroupAdapter> factory, String groupId) {
    var group = session.groups().getGroupById(session.getContext().getRealm(), groupId);
    if (group == null) {
        LOGGER.infof("Skipping group name reconcile: group %s not found locally", groupId);
        return;
    }
    if (!this.model.get(GROUP_PATCH_OP_KEY, false)) {
        this.replace(factory, group);
        group.setSingleAttribute(GroupAdapter.SYNCED_NAME_ATTRIBUTE, group.getName());
        return;
    }
    var adapter = getAdapter(factory);
    try (var span = TRACING.startSpan("scim.group.rename", "Group", scimApplicationBaseUrl)) {
        try {
            adapter.apply(group);                       // id = groupId, displayName = current name
            var mapping = adapter.query("findById", groupId).getSingleResult();
            adapter.apply(mapping);                      // externalId from mapping (displayName guard keeps current name)
            String url = genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId());

            var retry = registry.retry("reconcileGroupName");
            ServerResponse<Group> response = auth.sendWithAuthRefresh(
                () -> retry.executeSupplier(() -> {
                    try {
                        return adapter.toDisplayNamePatchBuilder(scimRequestBuilder, url).sendRequest();
                    } catch (ResponseException e) {
                        throw new RuntimeException(e);
                    }
                }));

            span.setHttpStatus(response.getHttpStatus());
            if (response.isSuccess()) {
                group.setSingleAttribute(GroupAdapter.SYNCED_NAME_ATTRIBUTE, group.getName());
            } else {
                LOGGER.warnf("Failed to PATCH displayName for group %s: %d %s",
                        groupId, response.getHttpStatus(), response.getResponseBody());
            }
        } catch (NoResultException e) {
            span.recordError(e);
            LOGGER.infof("Skipping group name reconcile: no SCIM mapping for group %s", groupId);
        }
    }
}
```

> Verify during TDD: that `adapter.apply(group)` then `adapter.apply(mapping)` yields both the current `displayName` (from the `GroupModel`) and the `externalId` (from the mapping). `GroupAdapter.setDisplayName` only sets when currently null (`GroupAdapter.java:37-41`), so applying the `GroupModel` first preserves the live name. If the resolution differs, mirror exactly what `patchGroupMembership` does to obtain `externalId`/`url`, and set `displayName` explicitly via `adapter.setDisplayName(group.getName())` before building the patch. `NoResultException`, `ResponseException`, `Group`, `GROUP_PATCH_OP_KEY` are already imported/defined in this file.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "sh.libre.scim.core.ReconcileGroupNameTest"`
Expected: PASS (2 tests). Apply the `any()` matcher fallback if a spy stub fails to intercept.

- [ ] **Step 5: Full unit suite + commit**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/java/sh/libre/scim/core/ScimClient.java \
        src/test/java/sh/libre/scim/core/ReconcileGroupNameTest.java
git commit -m "feat(group): add reconcileGroupName to push federated renames"
```

---

## Chunk 2: Reconciler group phase + result struct

### Task 3: Change `ReconcilerRunner.run()` to return a result struct

**Files:**
- Modify: `src/main/java/sh/libre/scim/reconcile/ReconcilerRunner.java`
- Modify: `src/main/java/sh/libre/scim/reconcile/ScimReconcileResourceProvider.java:60-62`
- Modify: `src/main/java/sh/libre/scim/reconcile/ReconcilerScheduler.java:66`

Pure refactor (no behavior change yet): introduce the struct, keep user-deletion behavior identical, update both callers. Existing reconciler integration tests (`ScimPropagationFromLdapIT#reconcilerDeletesScimResourcesForMissingLdapUsers`, `#scheduledReconcilerFiresOnItsOwn`) must stay green — they assert the `{"deleted": N}` shape, which we preserve.

- [ ] **Step 1: Add the result record** to `ReconcilerRunner` (top of class):

```java
    /** Outcome of one reconciliation pass. */
    public record ReconcileResult(int usersDeleted, int groupsDeleted, int groupsRenamed) {}
```

- [ ] **Step 2: Change `run()`'s signature and the user-phase return**

Change `public int run()` to `public ReconcileResult run()`. Where the user phase currently `return 0;` (line 102) and `return toDelete.size();` (line 150), capture the user count in a local `int usersDeleted` instead of returning, then at the very end:

```java
        // (group phase added in Task 4; for now groups are 0)
        return new ReconcileResult(usersDeleted, 0, 0);
```

Restructure so the early `if (toDelete.isEmpty())` sets `usersDeleted = 0` and skips Phase 2 rather than returning.

- [ ] **Step 3: Update the endpoint caller** (`ScimReconcileResourceProvider.java:60-62`):

```java
        var result = new ReconcilerRunner(session, component, threshold).run();
        return Response.ok(
            "{\"deleted\":" + result.usersDeleted()
            + ",\"groupsRenamed\":" + result.groupsRenamed()
            + ",\"groupsDeleted\":" + result.groupsDeleted() + "}",
            MediaType.APPLICATION_JSON).build();
```

Add the import for `ReconcilerRunner.ReconcileResult` if needed (or reference via `ReconcilerRunner.ReconcileResult`). Update the class Javadoc at line 28 to document the extended response body.

- [ ] **Step 4: Update the scheduler caller** (`ReconcilerScheduler.java:66`):

```java
                var result = new ReconcilerRunner(innerSession, latest, threshold).run();
                // keep existing log intent; include group counts
```

Update the surrounding log line to report `result.usersDeleted()`, `result.groupsDeleted()`, `result.groupsRenamed()` (read the existing log statement and extend it; keep the `usersDeleted` value where `deleted` was logged).

- [ ] **Step 5: Verify compile + existing reconciler tests unaffected**

Run: `./gradlew compileJava test`
Expected: BUILD SUCCESSFUL. (Unit suite has no direct `run()` test; integration tests are exercised in Task 6 / existing ITs.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/sh/libre/scim/reconcile/
git commit -m "refactor(reconcile): return ReconcileResult struct from run()"
```

### Task 4: Add the group phase to `ReconcilerRunner.run()`

**Files:**
- Modify: `src/main/java/sh/libre/scim/reconcile/ReconcilerRunner.java`
- Test: `src/test/java/sh/libre/scim/reconcile/GroupActionTest.java` (create)

Add a package-private static classifier (unit-tested) and the group phase (Phase-1 sequential scan, Phase-2 parallel dispatch), mirroring the user phase.

- [ ] **Step 1: Write the failing test for the classifier**

```java
package sh.libre.scim.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;

import sh.libre.scim.core.GroupAdapter;
import sh.libre.scim.reconcile.ReconcilerRunner.GroupAction;

class GroupActionTest {

    @Test
    void nullGroup_isDelete() {
        assertThat(ReconcilerRunner.classifyGroup(null)).isEqualTo(GroupAction.DELETE);
    }

    @Test
    void absentSyncedName_isRename() {
        var g = mock(GroupModel.class);
        when(g.getName()).thenReturn("engineers");
        when(g.getFirstAttribute(GroupAdapter.SYNCED_NAME_ATTRIBUTE)).thenReturn(null);
        assertThat(ReconcilerRunner.classifyGroup(g)).isEqualTo(GroupAction.RENAME);
    }

    @Test
    void driftedName_isRename() {
        var g = mock(GroupModel.class);
        when(g.getName()).thenReturn("engineers-renamed");
        when(g.getFirstAttribute(GroupAdapter.SYNCED_NAME_ATTRIBUTE)).thenReturn("engineers");
        assertThat(ReconcilerRunner.classifyGroup(g)).isEqualTo(GroupAction.RENAME);
    }

    @Test
    void matchingName_isNoop() {
        var g = mock(GroupModel.class);
        when(g.getName()).thenReturn("engineers");
        when(g.getFirstAttribute(GroupAdapter.SYNCED_NAME_ATTRIBUTE)).thenReturn("engineers");
        assertThat(ReconcilerRunner.classifyGroup(g)).isEqualTo(GroupAction.NOOP);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "sh.libre.scim.reconcile.GroupActionTest"`
Expected: FAIL — `GroupAction`/`classifyGroup` do not exist.

- [ ] **Step 3: Implement the classifier + group phase**

Add the enum and classifier to `ReconcilerRunner`:

```java
    enum GroupAction { DELETE, RENAME, NOOP }

    /**
     * Classifies a group mapping. A null group (local model gone) is a delete;
     * a name that differs from {@link GroupAdapter#SYNCED_NAME_ATTRIBUTE} — or
     * an absent attribute (first sighting) — is a rename; otherwise no-op.
     * No federation-origin filter: orphan-delete and name-drift are correct and
     * harmless for local groups too (see design doc).
     */
    static GroupAction classifyGroup(GroupModel group) {
        if (group == null) {
            return GroupAction.DELETE;
        }
        String synced = group.getFirstAttribute(GroupAdapter.SYNCED_NAME_ATTRIBUTE);
        if (synced == null || !synced.equals(group.getName())) {
            return GroupAction.RENAME;
        }
        return GroupAction.NOOP;
    }
```

Add a private `reconcileGroups()` method that mirrors the user phase, and call it from `run()` before building the result. Phase 1 (caller session, sequential): query `findByComponentAndType` with `type="Group"`; for each mapping, `session.groups().getGroupById(realm, m.getId())`, classify, and bucket into `groupsToDelete` / `groupsToRename` (both `List<String>` of group ids). Phase 2 (parallel, reusing the exact worker idiom from the user phase, lines 114-149): for each delete id, `workerClient.delete(GroupAdapter::new, id)`; for each rename id, `workerClient.reconcileGroupName(GroupAdapter::new, id)`. Return the two counts.

```java
    private int[] reconcileGroups() {  // returns {deleted, renamed}
        var realm = session.getContext().getRealm();
        var em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        var mappings = em.createNamedQuery("findByComponentAndType", ScimResource.class)
            .setParameter("realmId", realm.getId())
            .setParameter("componentId", scimProvider.getId())
            .setParameter("type", "Group")
            .getResultList();

        List<String> toDelete = new ArrayList<>();
        List<String> toRename = new ArrayList<>();
        for (ScimResource m : mappings) {
            var group = session.groups().getGroupById(realm, m.getId());
            switch (classifyGroup(group)) {
                case DELETE -> toDelete.add(m.getId());
                case RENAME -> toRename.add(m.getId());
                case NOOP -> { }
            }
        }
        if (toDelete.isEmpty() && toRename.isEmpty()) {
            return new int[]{0, 0};
        }

        var sessionFactory = session.getKeycloakSessionFactory();
        var realmId = realm.getId();
        var componentId = scimProvider.getId();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String id : toDelete) {
            futures.add(dispatchGroupOp(sessionFactory, realmId, componentId, id, true));
        }
        for (String id : toRename) {
            futures.add(dispatchGroupOp(sessionFactory, realmId, componentId, id, false));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            LOGGER.warnf(e.getCause(), "reconciler: one or more parallel group ops failed");
        }
        return new int[]{toDelete.size(), toRename.size()};
    }

    private CompletableFuture<Void> dispatchGroupOp(
            org.keycloak.models.KeycloakSessionFactory sessionFactory,
            String realmId, String componentId, String groupId, boolean isDelete) {
        return ScimDispatcher.dispatchAsync(() ->
            KeycloakModelUtils.runJobInTransaction(sessionFactory, workerSession -> {
                var workerRealm = workerSession.realms().getRealm(realmId);
                if (workerRealm == null) return;
                workerSession.getContext().setRealm(workerRealm);
                var component = workerRealm.getComponent(componentId);
                if (component == null
                    || !ScimStorageProviderFactory.ID.equals(component.getProviderId())) {
                    return;
                }
                var workerClient = new ScimClient(component, workerSession);
                try {
                    if (isDelete) {
                        workerClient.delete(GroupAdapter::new, groupId);
                    } else {
                        workerClient.reconcileGroupName(GroupAdapter::new, groupId);
                    }
                } finally {
                    workerClient.close();
                }
            }));
    }
```

Wire into `run()`: after the user phase computes `usersDeleted`, call `int[] g = reconcileGroups();` and `return new ReconcileResult(usersDeleted, g[0], g[1]);`. Add imports: `org.keycloak.models.GroupModel` and `sh.libre.scim.core.GroupAdapter`. The `dispatchGroupOp` snippet references `org.keycloak.models.KeycloakSessionFactory` — either add that import too or keep it fully-qualified consistently (the snippet's parameter type is already FQN); do not half-import. `CompletableFuture`/`CompletionException`/`KeycloakModelUtils`/`ScimStorageProviderFactory`/`ScimDispatcher`/`ScimResource` are already imported in this file.

> Consider extracting the shared worker-dispatch body (user delete + group ops) if it reads as duplication, but only if it stays clear; the code-quality review will weigh in. Don't over-abstract pre-emptively.

- [ ] **Step 4: Run the classifier test + full unit suite**

Run: `./gradlew test --tests "sh.libre.scim.reconcile.GroupActionTest"` then `./gradlew test`
Expected: PASS; no regressions.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/reconcile/ReconcilerRunner.java \
        src/test/java/sh/libre/scim/reconcile/GroupActionTest.java
git commit -m "feat(reconcile): group phase — orphan delete + name-drift rename"
```

---

## Chunk 3: Spike + integration coverage

### Task 5: Spike — federated group delete behavior + attribute persistence

**Files:**
- Inspect/extend: `src/integrationTest/java/sh/libre/scim/integration/IntegrationTestBase.java` (LDAP helpers: `newRealmWithScimAndLdapGroups`, `deleteLdapEntry`, `modifyLdapAttribute`, and the manual reconcile trigger `postReconcile(realm, componentId, thresholdHours)` around line 558)

> Note on idioms: `triggerFullSync` is **not** a helper — it's the inline admin call `r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync")` (see `ScimLdapGroupMembershipIT` / `ScimPropagationFromLdapIT`). The reconcile trigger **is** a helper: `postReconcile(...)`, used by `reconcilerDeletesScimResourcesForMissingLdapUsers`.
- Scratch test (deleted after): `src/integrationTest/java/sh/libre/scim/integration/SpikeGroupDeleteIT.java`

Resolves the two unknowns the design is gated on. Requires Docker.

- [ ] **Step 1: Probe whether Keycloak removes a federated group on LDAP delete**

Write a scratch IT that: uses `newRealmWithScimAndLdapGroups(...)`, `triggerFullSync` (materializes `cn=engineers`), asserts the group exists in Keycloak; then `deleteLdapEntry("cn=engineers,ou=groups,dc=test,dc=local")` (add this LDIF DN helper usage), `triggerFullSync` again, and checks whether `r.realm().groups().groups()` still contains `engineers`.
- If the group is **removed** → orphan-based delete is viable; record this.
- If the group **persists** → STOP. Report `STATUS: BLOCKED` with the observation: delete must be deferred (rename-only ships); the design's decision 2 deferral applies. Surface to the human.

- [ ] **Step 2: Probe attribute persistence**

In the same scratch test (before deletion), set `scim-synced-name` on the materialized group via the admin API or a session, run `triggerFullSync`, and assert the attribute is still present afterwards. Confirms the rename baseline survives sync.

- [ ] **Step 3: Run, record findings, delete the scratch test**

Run: `./gradlew integrationTest --tests "sh.libre.scim.integration.SpikeGroupDeleteIT"`
Record both findings in the Task 6 test comments. Delete `SpikeGroupDeleteIT`. Commit any reusable `IntegrationTestBase` helpers added (e.g. an LDAP group-delete helper) separately:

```bash
git add src/integrationTest/
git commit -m "test(ldap): group-delete spike helpers for reconciler IT"
```

### Task 6: Integration scenarios

**Files:**
- Create: `src/integrationTest/java/sh/libre/scim/integration/ScimGroupReconcileIT.java`

Mirror `ScimLdapGroupMembershipIT` / `ScimPropagationFromLdapIT` idioms (WireMock stubs, `await().atMost(20, SECONDS)`, the manual reconcile trigger used by `reconcilerDeletesScimResourcesForMissingLdapUsers` — find how that test invokes the reconcile endpoint/runner and reuse it). Configure `propagation-user=true`, `propagation-group=true`, `group-patchOp=true`, and `reconciler-enabled=true` with the necessary threshold/interval config those existing reconciler ITs use.

- [ ] **Step 1: Rename scenario**

Provision a federated group (full sync + member import so the group has a SCIM mapping). Then rename it in LDAP (`modifyLdapAttribute` on the group `cn`, or replace the group entry), `triggerFullSync` (Keycloak picks up the new name), trigger reconcile, and assert a `displayName` REPLACE PATCH to `/Groups/.*` fires whose body contains the new name and does **not** contain `members`.

- [ ] **Step 2: Rename idempotency**

Trigger reconcile again with no further LDAP change; assert no additional displayName PATCH (the `scim-synced-name` attribute short-circuits).

- [ ] **Step 3: Delete-gap characterization (the spike showed delete is NOT viable)**

Task 5's spike confirmed Keycloak does **not** remove the local `GroupModel` when its LDAP source group is deleted (the `#35235`-analogue for groups), so orphan-based SCIM DELETE never fires. Rather than omit the scenario, **pin the gap** — mirroring the existing user-side `ScimPropagationFromLdapIT#ldapSyncAloneDoesNotPropagateDeletion`:

Provision the group (member import) and reconcile once (stamps `scim-synced-name`). Then delete the LDAP group entry (`deleteLdapEntry("cn=engineers,ou=groups,dc=test,dc=local")`), `triggerFullSync`, and trigger reconcile. Assert that **no SCIM `DELETE` to `/Groups/.*` is issued** (the federated group persists locally → `getGroupById` is non-null → `classifyGroup` never returns `DELETE`), characterizing the current gap. Use a short settle (`sleepQuietly`/await) so a delete would have happened if it were going to. Add a clear comment: this test pins the deferral; if it turns red (Keycloak starts pruning federated groups, or a staleness witness is added), the gap closed and the delete path now propagates — update accordingly. Name it e.g. `ldapGroupDeleteIsNotYetPropagated`.

Helper available from the spike: `findGroupByName(RealmResource, String)` in `IntegrationTestBase`.

- [ ] **Step 4: Run + commit**

Run: `./gradlew integrationTest --tests "sh.libre.scim.integration.ScimGroupReconcileIT"`
Expected: PASS (3 scenarios: rename, rename-idempotency, delete-gap-pin). Run 2-3× to confirm non-flaky.

```bash
git add src/integrationTest/java/sh/libre/scim/integration/ScimGroupReconcileIT.java
git commit -m "test(reconcile): integration-cover federated group rename/delete"
```

---

## Chunk 4: Documentation

### Task 7: Roadmap + ldap-federation-support

**Files:**
- Modify: `docs/roadmap.md` (the "Group rename and delete for federated groups" item)
- Modify: `docs/ldap-federation-support.md`

- [ ] **Step 1: Update `docs/roadmap.md`**

Mark group rename/delete `_Done._` (or `_Partially done._` if the spike deferred delete): rename propagates via the reconciler's group phase (`reconcileGroupName` + `scim-synced-name` attribute); delete via orphaned-mapping SCIM DELETE (note if deferred pending the spike outcome). Reference `ScimGroupReconcileIT`. Match existing bullet voice/wrapping.

- [ ] **Step 2: Update `docs/ldap-federation-support.md`**

Document the group reconciliation phase: rename detection via `scim-synced-name`, orphan-based delete, the extended `{"deleted":N,"groupsRenamed":R,"groupsDeleted":D}` response, that it rides the existing `reconciler-enabled` flag, and any spike-driven delete deferral.

- [ ] **Step 3: Commit**

```bash
git add docs/roadmap.md docs/ldap-federation-support.md
git commit -m "docs(reconcile): document federated group rename/delete propagation"
```

---

## Done criteria

- [ ] `./gradlew test` passes (incl. `GroupDisplayNamePatchTest`, `ReconcileGroupNameTest`, `GroupActionTest`).
- [ ] `./gradlew integrationTest --tests "sh.libre.scim.integration.ScimGroupReconcileIT"` passes (Docker).
- [ ] Existing reconciler ITs still green (response shape preserved via the `deleted` key).
- [ ] Spike finding recorded; delete shipped or explicitly deferred with documentation.
- [ ] Roadmap + ldap-federation-support updated. No CHANGELOG.md edit.
```
