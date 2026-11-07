# Effective SCIM Roles Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send each user's effective SCIM roles (composites, parent groups, client roles), and replace the unbounded group fan-out in the admin request with one delayed, hash-checked role refresh per component.

**Architecture:** `UserAdapter.apply` computes roles with `RoleUtils.getDeepUserRoleMappings`. A new `BODY_HASH` column on the mapping row stores a hash of the body the last refresh sent. Role-structure admin events ask a per-node `RoleRefreshScheduler` for a refresh after commit. The scheduler runs a user-only refresh through `ScimSync` that skips unmapped users and users whose hash is unchanged.

**Tech Stack:** Java 21, Keycloak SPI (compiled against 25.0.6 on this branch), JPA + Liquibase, Jackson, JUnit 5 + Mockito + AssertJ, Testcontainers + WireMock.

**Spec:** `docs/superpowers/specs/2026-11-05-effective-scim-roles-design.md`. Read it first.

---

## Ground rules

- Work on branch `feat/effective-scim-roles`. It starts from `fix/scim-patch-correctness`.
- Only one agent may edit, commit, or run Gradle in this checkout at a time. Before you start, check `pgrep -f GradleWorkerMain` and `git status`.
- Commit messages: Conventional Commits. No `Co-Authored-By` trailer. No "Generated with" footer. Do not push.
- Write comments and docs in short, plain sentences, one idea each.
- Unit tests: `./gradlew test --tests '<class>' -q`. Integration tests need Docker: `./gradlew integrationTest --tests '<class>'`.
- This branch still has `Adapter.skipRefresh()`. Another branch removes it. Do not touch it here.

## File structure

| File | Change | Responsibility |
| --- | --- | --- |
| `src/main/java/sh/libre/scim/core/UserAdapter.java` | Modify `apply(UserModel)` | Effective marked roles |
| `src/main/java/sh/libre/scim/core/ScimBodyHash.java` | Create | Canonical JSON and SHA-256 of a SCIM body |
| `src/main/java/sh/libre/scim/jpa/ScimResource.java` | Modify | `bodyHash` field and `updateBodyHash` query |
| `src/main/resources/META-INF/scim-resource-changelog.xml` | Modify | `BODY_HASH` column |
| `src/main/java/sh/libre/scim/core/ScimClient.java` | Modify `refreshOne`, add `writeBodyHash` | Role-refresh mode, hash check and hash write |
| `src/main/java/sh/libre/scim/core/RefreshPageStep.java` | Modify | Carries the role-refresh flag to `refreshOne` |
| `src/main/java/sh/libre/scim/core/RoleRefreshResult.java` | Create | How a role refresh ended |
| `src/main/java/sh/libre/scim/core/ScimSync.java` | Modify | `runRoleRefresh`; `refreshUsers` returns completion |
| `src/main/java/sh/libre/scim/core/RoleRefreshScheduler.java` | Create | Per-node state machine and executor |
| `src/main/java/sh/libre/scim/core/ScimDispatcher.java` | Modify | `requestRoleRefresh()` after commit |
| `src/main/java/sh/libre/scim/event/ScimEventListenerProviderFactory.java` | Modify | Starts and stops the scheduler |
| `src/main/java/sh/libre/scim/event/ScimEventListenerProvider.java` | Modify | Event routing and `groupConfersScimRole` |
| `src/integrationTest/.../IntegrationTestBase.java` | Modify | Long role-refresh delay on the shared container |
| `src/integrationTest/.../ScimEffectiveRolesIT.java` | Create | End-to-end role refresh tests |
| `src/perfTest/.../RoleRefreshPerfTest.java` | Create | Measurement at N users |
| `build.gradle.kts` | Modify | Forward the delay property to test JVMs |
| `docs/configuration.md`, `docs/upgrading-to-2.0.md`, `docs/performance.md` | Modify | Operator docs and measured numbers |

`docs/upgrading-to-2.0.md` is on branch `docs/upgrading-to-2.0`, not on this branch. Task 13 says how to handle that.

---

## Chunk 1: Data

### Task 1: Effective roles in `UserAdapter.apply`

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/UserAdapter.java` (`apply(UserModel)`, the `rolesSet` block)
- Test: `src/test/java/sh/libre/scim/core/UserAdapterTest.java`

- [ ] **Step 1: Write the failing test**

Add these imports to `UserAdapterTest`:

```java
import java.util.stream.Stream;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.models.GroupModel;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
```

Add these members:

```java
    private static RoleModel role(String name, boolean marked, RoleModel... composites) {
        var r = mock(RoleModel.class);
        when(r.getName()).thenReturn(name);
        when(r.getFirstAttribute("scim")).thenReturn(marked ? "true" : null);
        when(r.isComposite()).thenReturn(composites.length > 0);
        when(r.getCompositesStream()).thenAnswer(i -> Stream.of(composites));
        return r;
    }

    /**
     * Roles reach SCIM from composites, parent groups, and client roles. A
     * realm role and a client role with the same name appear once. Unmarked
     * roles stay out. The list is sorted.
     */
    @Test
    void applySendsEffectiveMarkedRoles() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.getConfig()).thenReturn(new MultivaluedHashMap<>());
        var direct = role("direct", true);
        var plain = role("plain", false);
        var viaComposite = role("via-composite", true);
        var composite = role("composite", false, viaComposite);
        var fromParent = role("from-parent", true);
        var sharedRealm = role("shared", true);
        var sharedClient = role("shared", true);
        var parent = mock(GroupModel.class);
        when(parent.getRoleMappingsStream()).thenAnswer(i -> Stream.of(fromParent));
        var child = mock(GroupModel.class);
        when(child.getParentId()).thenReturn("parent-id");
        when(child.getParent()).thenReturn(parent);
        when(child.getRoleMappingsStream()).thenAnswer(i -> Stream.of(sharedClient));
        var user = mock(UserModel.class);
        when(user.getId()).thenReturn("u1");
        when(user.getUsername()).thenReturn("alice");
        when(user.isEnabled()).thenReturn(true);
        when(user.getRoleMappingsStream()).thenAnswer(i -> Stream.of(direct, plain, composite, sharedRealm));
        when(user.getGroupsStream()).thenAnswer(i -> Stream.of(child));

        var fresh = new UserAdapter(session, COMPONENT_ID);
        fresh.apply(user);

        assertArrayEquals(new String[] {"direct", "from-parent", "shared", "via-composite"}, fresh.getRoles());
    }
```

- [ ] **Step 2: Run the test and see it fail**

Run: `./gradlew test --tests 'sh.libre.scim.core.UserAdapterTest' -q`
Expected: FAIL in `applySendsEffectiveMarkedRoles`. Today `via-composite` and `from-parent` are missing, and the order is not fixed.

- [ ] **Step 3: Implement**

In `UserAdapter.apply(UserModel)`, replace the block from `var rolesSet = new HashSet<String>();` through `setRoles(roles);` with:

```java
        // Effective roles: direct mappings, every group and its parents, and
        // composites expanded. Only roles marked scim="true" are sent. One
        // name appears once, and the list is sorted so the body is stable.
        var roles = RoleUtils.getDeepUserRoleMappings(user).stream()
                .filter(r -> "true".equals(r.getFirstAttribute("scim")))
                .map(RoleModel::getName)
                .distinct()
                .sorted()
                .toArray(String[]::new);
        setRoles(roles);
```

Add `import org.keycloak.models.utils.RoleUtils;`. Remove `import java.util.HashSet;` if nothing else uses it.

- [ ] **Step 4: Run the tests and see them pass**

Run: `./gradlew test --tests 'sh.libre.scim.core.UserAdapter*' -q`
Expected: PASS. `UserAdapterExtensionTest` must still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/UserAdapter.java src/test/java/sh/libre/scim/core/UserAdapterTest.java
git commit -m "feat!: send effective SCIM roles from composites and parent groups" -m "BREAKING CHANGE: the SCIM roles attribute now holds every marked role a user has, including roles from composite roles and parent groups. Before, it held only direct mappings and the roles of direct groups."
```

### Task 2: `ScimBodyHash`

**Files:**
- Create: `src/main/java/sh/libre/scim/core/ScimBodyHash.java`
- Test: `src/test/java/sh/libre/scim/core/ScimBodyHashTest.java`

- [ ] **Step 1: Write the failing test**

```java
package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ScimBodyHashTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void keyOrderDoesNotChangeTheHash() throws Exception {
        var a = JSON.readTree("{\"userName\":\"a\",\"name\":{\"givenName\":\"A\",\"familyName\":\"B\"}}");
        var b = JSON.readTree("{\"name\":{\"familyName\":\"B\",\"givenName\":\"A\"},\"userName\":\"a\"}");

        assertThat(ScimBodyHash.of(a)).isEqualTo(ScimBodyHash.of(b));
    }

    @Test
    void idAndMetaAreIgnored() throws Exception {
        var plain = JSON.readTree("{\"userName\":\"a\"}");
        var withIds = JSON.readTree("{\"userName\":\"a\",\"id\":\"remote-1\",\"meta\":{\"location\":\"x\"}}");

        assertThat(ScimBodyHash.of(withIds)).isEqualTo(ScimBodyHash.of(plain));
    }

    @Test
    void aChangedRoleChangesTheHash() throws Exception {
        var before = JSON.readTree("{\"roles\":[{\"value\":\"a\"}]}");
        var after = JSON.readTree("{\"roles\":[{\"value\":\"a\"},{\"value\":\"b\"}]}");

        assertThat(ScimBodyHash.of(after)).isNotEqualTo(ScimBodyHash.of(before));
    }

    @Test
    void theHashIsSixtyFourHexCharacters() throws Exception {
        assertThat(ScimBodyHash.of(JSON.readTree("{}"))).matches("[0-9a-f]{64}");
    }

    @Test
    void theInputIsNotChanged() throws Exception {
        var body = JSON.readTree("{\"id\":\"remote-1\",\"userName\":\"a\"}");

        ScimBodyHash.of(body);

        assertThat(body.has("id")).isTrue();
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `./gradlew test --tests 'sh.libre.scim.core.ScimBodyHashTest' -q`
Expected: compile failure, `ScimBodyHash` does not exist.

- [ ] **Step 3: Implement**

```java
package sh.libre.scim.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The hash of a SCIM body that a refresh stores on the mapping row. Two bodies
 * that differ only in key order, in {@code id}, or in {@code meta} have the
 * same hash. {@code id} is the remote id, which the plugin does not decide.
 * Array order is kept.
 */
final class ScimBodyHash {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ScimBodyHash() {}

    /** SHA-256 of the canonical form of {@code body}, as 64 lowercase hex characters. */
    static String of(JsonNode body) {
        JsonNode canonical = canonical(body);
        if (canonical instanceof ObjectNode object) {
            object.remove("id");
            object.remove("meta");
        }
        try {
            byte[] bytes = JSON.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("could not hash a SCIM body", e);
        }
    }

    /**
     * A copy with object keys sorted at every level. Jackson's ObjectNode keeps
     * insertion order, so the sort is done here, not through a mapper setting.
     */
    static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                sorted.set(name, canonical(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> copy.add(canonical(element)));
            return copy;
        }
        return node;
    }
}
```

- [ ] **Step 4: Run the test and see it pass**

Run: `./gradlew test --tests 'sh.libre.scim.core.ScimBodyHashTest' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimBodyHash.java src/test/java/sh/libre/scim/core/ScimBodyHashTest.java
git commit -m "feat: add a canonical hash of SCIM bodies"
```

### Task 3: `BODY_HASH` column

**Files:**
- Modify: `src/main/java/sh/libre/scim/jpa/ScimResource.java`
- Modify: `src/main/resources/META-INF/scim-resource-changelog.xml`

No unit test: the column is exercised by Task 5's tests and by the integration tests in Chunk 5.

- [ ] **Step 1: Add the field and the query**

In `ScimResource`, add a named query to `@NamedQueries`:

```java
                @NamedQuery(name = "updateBodyHash", query = "update ScimResource set bodyHash = :hash where realmId = :realmId and componentId = :componentId and type = :type and id = :id and externalId = :externalId"),
```

Add the field after `deactivatedAt`, with its accessors:

```java
        /**
         * SHA-256 (hex) of the SCIM body the last refresh sent for this
         * resource. Null until a refresh sends it. Only a refresh writes it;
         * see the effective roles design.
         */
        @Column(name = "BODY_HASH", length = 64)
        private String bodyHash;

        public String getBodyHash() {
                return bodyHash;
        }

        public void setBodyHash(String bodyHash) {
                this.bodyHash = bodyHash;
        }
```

- [ ] **Step 2: Add the Liquibase change**

Before `</databaseChangeLog>`, add:

```xml
    <changeSet author="keycloak-scim" id="scim-resource-body-hash-2.0">
        <!-- SHA-256 (hex) of the SCIM body the last refresh sent. A role
             refresh skips a user whose current body has the same hash.
             Null means "not sent by a refresh yet", which never matches. -->
        <addColumn tableName="SCIM_RESOURCE">
            <column name="BODY_HASH" type="VARCHAR(64)">
                <constraints nullable="true" />
            </column>
        </addColumn>
    </changeSet>
```

- [ ] **Step 3: Compile and run the unit tests**

Run: `./gradlew test -q`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/sh/libre/scim/jpa/ScimResource.java src/main/resources/META-INF/scim-resource-changelog.xml
git commit -m "feat: add a body hash column to SCIM mappings"
```

---

## Chunk 2: Refresh

### Task 4: `RoleRefreshResult`

**Files:**
- Create: `src/main/java/sh/libre/scim/core/RoleRefreshResult.java`

- [ ] **Step 1: Create the enum**

```java
package sh.libre.scim.core;

/** How one role refresh of a component ended. {@link RoleRefreshScheduler} acts on it. */
enum RoleRefreshResult {
    /** The refresh visited every user. Some users may have failed. */
    FINISHED,
    /** The lease was held by another run, or could not be taken. Try again later. */
    REFUSED,
    /** The refresh started and did not visit every user. */
    STOPPED,
    /** The realm or component is gone or turned off, so nothing ran. */
    SKIPPED
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava -q`
Expected: success. No commit yet; Task 5 commits it.

### Task 5: Role-refresh mode and hash write in `refreshOne`

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (`refreshOne`, new `writeBodyHash`, new `bodyHashOf`)
- Modify: `src/main/java/sh/libre/scim/core/RefreshPageStep.java`
- Test: `src/test/java/sh/libre/scim/core/ScimClientRoleRefreshTest.java`

- [ ] **Step 1: Write the failing tests**

The tests use a real `UserAdapter`, because the hash only applies to users. They stub `replaceApplied`, `createApplied` and `writeBodyHash` on a spy, as `ScimClientDeactivateTest` does.

```java
package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.user.SynchronizationResult;
import sh.libre.scim.jpa.ScimResource;

/** The role-refresh mode of refreshOne: hash check, no creates, hash write. */
class ScimClientRoleRefreshTest {

    private KeycloakSession session;
    private ComponentModel model;
    private EntityManager em;
    private TypedQuery<ScimResource> findById;
    private UserModel user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-1");
        model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId("comp-1");
        when(realm.getComponent("comp-1")).thenReturn(model);
        var jpa = mock(JpaConnectionProvider.class);
        em = mock(EntityManager.class);
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpa);
        when(jpa.getEntityManager()).thenReturn(em);
        findById = mock(TypedQuery.class);
        when(em.createNamedQuery(eq("findById"), eq(ScimResource.class))).thenReturn(findById);
        when(findById.setParameter(anyString(), any())).thenReturn(findById);
        user = mock(UserModel.class);
        when(user.getId()).thenReturn("u1");
        when(user.getUsername()).thenReturn("alice");
        when(user.isEnabled()).thenReturn(true);
        when(user.getRoleMappingsStream()).thenAnswer(i -> Stream.empty());
        when(user.getGroupsStream()).thenAnswer(i -> Stream.empty());
    }

    private ScimResource mapping(String bodyHash) {
        var m = new ScimResource();
        m.setId("u1");
        m.setRealmId("realm-1");
        m.setComponentId("comp-1");
        m.setType("User");
        m.setExternalId("ext-1");
        m.setBodyHash(bodyHash);
        when(findById.getResultList()).thenReturn(List.of(m));
        when(findById.getSingleResult()).thenReturn(m);
        return m;
    }

    private ScimClient client() {
        var client = spy(new ScimClient(model, session));
        doReturn(true).when(client).replaceApplied(any(), any());
        doReturn(true).when(client).createApplied(any());
        doNothing().when(client).writeBodyHash(any(), anyString(), anyString());
        return client;
    }

    /** The hash a refresh would compute for the user in setUp. */
    private String currentHash() {
        var adapter = new UserAdapter(session, "comp-1");
        adapter.apply(user);
        return ScimClient.bodyHashOf(adapter);
    }

    @Test
    void roleRefreshSkipsAnUnchangedUser() {
        var client = client();
        mapping(currentHash());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).replaceApplied(any(), any());
    }

    @Test
    void roleRefreshSendsAChangedUserAndStoresTheHash() {
        var client = client();
        var m = mapping("0".repeat(64));

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client).replaceApplied(any(), eq(m));
        verify(client).writeBodyHash(m, "ext-1", currentHash());
    }

    @Test
    void roleRefreshSendsAUserWithNoHash() {
        var client = client();
        mapping(null);

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client).replaceApplied(any(), any());
    }

    @Test
    void roleRefreshNeverCreates() {
        var client = client();
        when(findById.getResultList()).thenReturn(List.of());
        when(findById.getSingleResult()).thenThrow(new jakarta.persistence.NoResultException());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).createApplied(any());
    }

    @Test
    void aPeriodicRefreshSendsAnUnchangedUserAndStoresTheHash() {
        var client = client();
        var m = mapping(currentHash());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), false);

        verify(client).replaceApplied(any(), eq(m));
        verify(client).writeBodyHash(eq(m), eq("ext-1"), anyString());
    }

    @Test
    void aFailedReplaceStoresNoHash() {
        var client = client();
        mapping(null);
        doReturn(false).when(client).replaceApplied(any(), any());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).writeBodyHash(any(), anyString(), anyString());
    }

    /**
     * A 404 re-create changes the mapping row's external id inside the page
     * transaction. The re-create path edits the same managed row that
     * refreshOne passed in.
     */
    @Test
    void aReCreateStoresNoHash() {
        var client = client();
        var m = mapping(null);
        doAnswer(i -> {
            m.setExternalId("ext-2");
            return true;
        }).when(client).replaceApplied(any(), any());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).writeBodyHash(any(), anyString(), anyString());
    }
}
```

Before you run it, check two things in the code and adjust the test to match, not the other way round:
- How `Adapter.getMapping()` reads the row (`getResultList` or `getSingleResult`), and how `Adapter.query` builds the query (`createNamedQuery(name, ScimResource.class)` with `setParameter`). Stub what it really calls.
- That `UserAdapter.apply` works with this `session` mock. It reads `realm.getComponent("comp-1").getConfig()` for extension mappings; the model above has a real config.

Note: today `Adapter.setExternalId` only sets a value when none is set, so the
re-create path writes the old external id back, and the key does not change.
That is an existing bug, tracked separately. Do not fix it in this plan. The
check above compares the row, so it stays correct when that bug is fixed.

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew test --tests 'sh.libre.scim.core.ScimClientRoleRefreshTest' -q`
Expected: compile failure: no 5-argument `refreshOne`, no `writeBodyHash`, no `bodyHashOf`.

- [ ] **Step 3: Implement in `ScimClient`**

Add imports if missing: `java.util.Objects`, `org.keycloak.models.utils.KeycloakModelUtils`.

Rename the existing `refreshOne(factory, resource, syncRes, policy)` to the 5-argument form below, and keep a 4-argument overload for the group path and the existing tests:

```java
    <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> RefreshOutcome refreshOne(
            AdapterFactory<M, S, A> factory, M resource, SynchronizationResult syncRes, SyncErrorPolicy policy) {
        return refreshOne(factory, resource, syncRes, policy, false);
    }
```

Add this parameter to the Javadoc of the 5-argument form:

```java
     * @param roleRefresh true for a role refresh: skip a resource with no
     *     mapping, and skip a user whose body hash matches the stored one.
     *     False for a periodic or manual sync, which sends every resource.
```

In the 5-argument body, replace the block from `if (mapping == null) {` through the end of the `else` branch (`pushed = replaceApplied(adapter, mapping);` and its closing `}`) with:

```java
            if (mapping == null) {
                if (roleRefresh) {
                    // A role refresh only updates users that were already
                    // sent. It never creates one.
                    return RefreshOutcome.CONTINUE;
                }
                LOGGER.info("Creating it");
                // The meter reports the cost of a create, so only this branch
                // adds the apply it paid for.
                ScimClientMetrics.APPLY_MODEL_NANOS.add(applyNanos);
                branch = "create";
                pushed = createApplied(adapter);
            } else {
                String hash = bodyHashOf(adapter);
                if (roleRefresh && hash != null && hash.equals(mapping.getBodyHash())) {
                    LOGGER.debugf("Skipping unchanged resource %s", adapter.getId());
                    return RefreshOutcome.CONTINUE;
                }
                LOGGER.info("Replacing it");
                branch = "replace";
                // A 404 re-create edits this same managed row and can change
                // its external id, which is part of the key. Read it first.
                String externalIdBefore = mapping.getExternalId();
                // Hand over the row read above. A second read of it would take
                // page capacity and return the same row.
                pushed = replaceApplied(adapter, mapping);
                if (pushed && hash != null && Objects.equals(externalIdBefore, mapping.getExternalId())) {
                    // A re-create that changed the row's key holds a lock on
                    // it in the page transaction, so it skips this write. The
                    // next role refresh sends that user once more.
                    writeBodyHash(mapping, externalIdBefore, hash);
                }
            }
```

Add these two methods next to `refreshOne`:

```java
    /** The body hash for a user, or null for any other resource type. */
    // package-private so a test can compute the expected value
    static String bodyHashOf(Adapter<?, ?> adapter) {
        return adapter instanceof UserAdapter user ? ScimBodyHash.of(user.toSCIM(false)) : null;
    }

    /**
     * Stores a body hash in its own short transaction, so the page does not
     * hold a lock on the mapping row while it pushes other users. A failure
     * only means the next role refresh sends the user again.
     */
    // package-private so a test can stub it
    void writeBodyHash(ScimResource mapping, String externalId, String hash) {
        try {
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), s ->
                s.getProvider(JpaConnectionProvider.class).getEntityManager()
                    .createNamedQuery("updateBodyHash")
                    .setParameter("hash", hash)
                    .setParameter("realmId", mapping.getRealmId())
                    .setParameter("componentId", mapping.getComponentId())
                    .setParameter("type", mapping.getType())
                    .setParameter("id", mapping.getId())
                    .setParameter("externalId", externalId)
                    .executeUpdate());
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not store the body hash of %s %s; the next role refresh sends it again",
                mapping.getType(), mapping.getId());
        }
    }
```

If `createApplied` is private, make it package-private so the test can stub it.

- [ ] **Step 4: Pass the flag through `RefreshPageStep`**

In `RefreshPageStep`, add a field and a second constructor. Keep the existing constructor; it passes `false`:

```java
    /** True for a role refresh. See {@link ScimClient#refreshOne}. */
    private final boolean roleRefresh;

    RefreshPageStep(KeycloakSessionFactory sessionFactory, String realmId, ComponentModel model,
                    Duration pageBudget, Clock clock, BooleanSupplier leaseLost, Runnable progressReporter) {
        this(sessionFactory, realmId, model, pageBudget, clock, leaseLost, progressReporter, false);
    }

    RefreshPageStep(KeycloakSessionFactory sessionFactory, String realmId, ComponentModel model,
                    Duration pageBudget, Clock clock, BooleanSupplier leaseLost, Runnable progressReporter,
                    boolean roleRefresh) {
        // existing body of the old constructor, unchanged
        this.roleRefresh = roleRefresh;
    }
```

In `run`, change the push to:

```java
                            u -> client.refreshOne(UserAdapter::new, u, counters, policy, roleRefresh));
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test -q`
Expected: PASS, including the new class and every existing test.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimClient.java src/main/java/sh/libre/scim/core/RefreshPageStep.java src/main/java/sh/libre/scim/core/RoleRefreshResult.java src/test/java/sh/libre/scim/core/ScimClientRoleRefreshTest.java
git commit -m "feat: skip unchanged users in a role refresh and store body hashes"
```

### Task 5b: Event paths clear the body hash

**Files:**
- Modify: `src/main/java/sh/libre/scim/jpa/ScimResource.java` (one named query)
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (`replace`, `createApplied`, new `clearBodyHash`)
- Test: `src/test/java/sh/libre/scim/core/ScimClientRoleRefreshTest.java`

Why: an event can send a body that the stored hash does not describe. A
later change can then bring the user back to the hashed body, and a role
refresh would skip a user whose server copy is wrong. See the spec section
"Who clears it". A null hash never matches, so clearing is always safe.

- [ ] **Step 1: Write the failing tests**

Add to `ScimClientRoleRefreshTest`:

```java
    @Test
    void anEventReplaceClearsTheHash() {
        var client = spy(new ScimClient(model, session));
        doReturn(true).when(client).replaceBody(any(), any());
        doNothing().when(client).clearBodyHash(any());

        client.replace(UserAdapter::new, user);

        verify(client).clearBodyHash(any());
    }

    @Test
    void aFailedEventReplaceKeepsTheHash() {
        var client = spy(new ScimClient(model, session));
        doReturn(false).when(client).replaceBody(any(), any());
        doNothing().when(client).clearBodyHash(any());

        client.replace(UserAdapter::new, user);

        verify(client, never()).clearBodyHash(any());
    }

    @Test
    void aReactivationThroughCreateClearsTheHash() {
        var client = spy(new ScimClient(model, session));
        var tombstone = mapping(null);
        tombstone.setDeactivatedAt(1L);
        doReturn(true).when(client).replaceApplied(any());
        doNothing().when(client).clearBodyHash(any());
        var adapter = new UserAdapter(session, "comp-1");
        adapter.apply(user);

        client.createApplied(adapter);

        verify(client).clearBodyHash(adapter);
    }
```

Check how `replace` calls `replaceBody` today (two or three arguments) and
stub that overload. Check how `createApplied` reads the tombstone
(`adapter.query("findById", ...).getResultList()` and
`isDeactivatedTombstone`), and make `mapping(...)` return a row that test
treats as a tombstone.

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew test --tests 'sh.libre.scim.core.ScimClientRoleRefreshTest' -q`
Expected: compile failure, no `clearBodyHash`.

- [ ] **Step 3: Implement**

In `ScimResource`, add a named query:

```java
                @NamedQuery(name = "clearBodyHash", query = "update ScimResource set bodyHash = null where realmId = :realmId and componentId = :componentId and type = :type and id = :id"),
```

In `ScimClient`, add:

```java
    /**
     * Clears a user's body hash after an event sent that user. The event's
     * body may differ from the one the hash describes, so the next role
     * refresh must send the user. Runs in the caller's transaction. A null
     * hash never matches, so a clear is always safe.
     */
    // package-private so a test can stub it
    void clearBodyHash(Adapter<?, ?> adapter) {
        if (!"User".equals(adapter.getType())) {
            return;
        }
        getEM().createNamedQuery("clearBodyHash")
            .setParameter("realmId", session.getContext().getRealm().getId())
            .setParameter("componentId", model.getId())
            .setParameter("type", adapter.getType())
            .setParameter("id", adapter.getId())
            .executeUpdate();
    }
```

Change `replace` so it clears after a successful send:

```java
    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void replace(
            AdapterFactory<M, S, A> factory, M kcModel) {
        var adapter = getAdapter(factory);
        // The model is applied inside the span, so a fault there is traced and
        // handled the way it always has been.
        if (replaceBody(adapter, () -> adapter.apply(kcModel))) {
            clearBodyHash(adapter);
        }
    }
```

In `createApplied`, change the reactivation branch:

```java
            if (isDeactivatedTombstone(existing)) {
                LOGGER.infof("Create for deactivated mapping %s: reactivating via replace", adapter.getId());
                boolean pushed = replaceApplied(adapter);
                if (pushed) {
                    clearBodyHash(adapter);
                }
                return pushed;
            }
```

Use the real field names for the component model and the session in
`ScimClient` (`model`, `session`); check them before you paste.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/jpa/ScimResource.java src/main/java/sh/libre/scim/core/ScimClient.java src/test/java/sh/libre/scim/core/ScimClientRoleRefreshTest.java
git commit -m "feat: clear the body hash when an event sends a user"
```

### Task 6: `ScimSync.runRoleRefresh`

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimSync.java`
- Test: `src/test/java/sh/libre/scim/core/ScimSyncTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `ScimSyncTest`:

```java
    @Test
    void aRoleRefreshThatFindsTheLeaseHeldIsRefused() {
        var sessionFactory = mock(KeycloakSessionFactory.class);
        var store = new FakeLeaseStore();
        store.ensureRow("comp-1");
        store.inOneTransaction(l -> { l.take("comp-1", "other", 1_000_000L); return null; });

        var result = ScimSync.runRoleRefresh(sessionFactory, "realm-1", model("propagation-user", "true"),
            () -> false, store, Clock.fixed(Instant.ofEpochMilli(1_000_500L), ZoneOffset.UTC));

        assertThat(result).isEqualTo(RoleRefreshResult.REFUSED);
        verifyNoInteractions(sessionFactory);
    }

    @Test
    void aRoleRefreshThatCannotTakeTheLeaseIsRefused() {
        var sessionFactory = mock(KeycloakSessionFactory.class);
        var store = new FakeLeaseStore();
        store.ensureFailure = new IllegalStateException("database down");

        var result = ScimSync.runRoleRefresh(sessionFactory, "realm-1", model("propagation-user", "true"),
            () -> false, store, Clock.systemUTC());

        assertThat(result).isEqualTo(RoleRefreshResult.REFUSED);
        verifyNoInteractions(sessionFactory);
    }

    @Test
    void aRoleRefreshAfterShutdownStopsBeforeTheFirstPage() {
        var sessionFactory = mock(KeycloakSessionFactory.class);
        var store = new FakeLeaseStore();

        var result = ScimSync.runRoleRefresh(sessionFactory, "realm-1", model("propagation-user", "true"),
            () -> true, store, Clock.systemUTC());

        assertThat(result).isEqualTo(RoleRefreshResult.STOPPED);
        verifyNoInteractions(sessionFactory);
        assertThat(store.rows.get("comp-1").holder).isNull();
    }
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew test --tests 'sh.libre.scim.core.ScimSyncTest' -q`
Expected: compile failure, no `runRoleRefresh`.

- [ ] **Step 3: Implement**

Change `refreshUsers` to take a `roleRefresh` flag and return whether it finished:

```java
    private static boolean refreshUsers(KeycloakSessionFactory sessionFactory, String realmId,
                                        ComponentModel model, Clock clock, SynchronizationResult result,
                                        BooleanSupplier lost, Runnable progress, boolean roleRefresh) {
```

Inside it:
- build the step with `new RefreshPageStep(sessionFactory, realmId, model, pageBudget, clock, lost, progress, roleRefresh)`,
- name the span `roleRefresh ? "scim.sync.roleRefresh" : "scim.sync.refresh"`,
- after the `if (!outcome.completed())` block, keep `progress.run();` and add `return outcome.completed();` at the end. The `try` block needs a local, for example `boolean completed;` set from `outcome.completed()`.

In `runStages`, the call becomes `refreshUsers(sessionFactory, realmId, model, clock, result, lost, progress, false)`. The lambda still compiles as a `Runnable`.

Add, after `run(...)`:

```java
    /**
     * Runs one role refresh of a component: the user refresh stage only,
     * skipping unmapped users and users whose body hash is unchanged. It runs
     * whatever {@code sync-refresh} says, because that setting controls the
     * periodic and manual syncs. See {@link RoleRefreshScheduler}.
     *
     * @param shutdown true once the node is stopping. The run checks it with
     *     the lease, between users.
     */
    static RoleRefreshResult runRoleRefresh(KeycloakSessionFactory sessionFactory, String realmId,
                                            ComponentModel model, BooleanSupplier shutdown) {
        return runRoleRefresh(sessionFactory, realmId, model, shutdown,
            new JpaLeaseStore(sessionFactory), Clock.systemUTC());
    }

    // package-private so a test can pass a fake store
    static RoleRefreshResult runRoleRefresh(KeycloakSessionFactory sessionFactory, String realmId,
                                            ComponentModel model, BooleanSupplier shutdown,
                                            LeaseStore store, Clock clock) {
        var lease = new SyncLease(store, model.getId(), clock);
        try {
            if (lease.acquire() == SyncLease.Decision.REFUSE) {
                return RoleRefreshResult.REFUSED;
            }
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "SCIM role refresh: component %s could not take its lease; it will try again",
                model.getId());
            return RoleRefreshResult.REFUSED;
        }
        BooleanSupplier lost = () -> lease.lost() || shutdown.getAsBoolean();
        var result = new SynchronizationResult();
        var completed = new AtomicBoolean(false);
        long start = System.nanoTime();
        try {
            lease.startHeartbeat();
            unlessLost(lost, "role refresh", result, () ->
                runContained(model, "role refresh", result, () -> completed.set(
                    refreshUsers(sessionFactory, realmId, model, clock, result, lost,
                        lease::reportProgress, true))));
        } finally {
            lease.release();
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.infof("SCIM role refresh of component %s %s in %d ms: %d users sent, %d failed",
            model.getId(), completed.get() ? "finished" : "stopped", millis,
            result.getUpdated(), result.getFailed());
        return completed.get() ? RoleRefreshResult.FINISHED : RoleRefreshResult.STOPPED;
    }
```

Check `SyncLease`: if `lease.lost()` already returns true right after `acquire()` in the fake store, the shutdown test above still holds. If `startHeartbeat()` needs a session factory, move it inside `unlessLost`, after the check. Keep the release in `finally`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimSync.java src/test/java/sh/libre/scim/core/ScimSyncTest.java
git commit -m "feat: add a lease-guarded role refresh of users"
```

---

## Chunk 3: Scheduler

### Task 7: `RoleRefreshScheduler`

**Files:**
- Create: `src/main/java/sh/libre/scim/core/RoleRefreshScheduler.java`
- Test: `src/test/java/sh/libre/scim/core/RoleRefreshSchedulerTest.java`

- [ ] **Step 1: Write the failing tests**

The test drives the state machine with a fake delayer that records tasks and a fake runner that returns a chosen result.

```java
package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoleRefreshSchedulerTest {

    private final Deque<Runnable> scheduled = new ArrayDeque<>();
    private final Deque<RoleRefreshResult> results = new ArrayDeque<>();
    private final List<String> runs = new ArrayList<>();
    private Runnable duringRun = () -> {};

    private final RoleRefreshScheduler scheduler = new RoleRefreshScheduler(
        (realmId, componentId, shutdown) -> {
            runs.add(componentId);
            duringRun.run();
            return results.isEmpty() ? RoleRefreshResult.FINISHED : results.pop();
        },
        (task, delay) -> scheduled.add(task),
        Duration.ofSeconds(60),
        null);

    private void runNext() {
        scheduled.pop().run();
    }

    @Test
    void aRequestSchedulesOneRun() {
        scheduler.request("r", "c");

        assertThat(scheduled).hasSize(1);
        assertThat(scheduler.state("c")).isEqualTo(RoleRefreshScheduler.State.SCHEDULED);
    }

    @Test
    void aSecondRequestBeforeTheRunSchedulesNothing() {
        scheduler.request("r", "c");
        scheduler.request("r", "c");

        assertThat(scheduled).hasSize(1);
    }

    @Test
    void aFinishedRunGoesIdle() {
        scheduler.request("r", "c");
        runNext();

        assertThat(runs).containsExactly("c");
        assertThat(scheduler.state("c")).isNull();
        assertThat(scheduled).isEmpty();
    }

    @Test
    void aRequestDuringARunSchedulesOneMore() {
        scheduler.request("r", "c");
        duringRun = () -> {
            scheduler.request("r", "c");
            scheduler.request("r", "c");
        };
        runNext();

        assertThat(scheduled).hasSize(1);
        assertThat(scheduler.state("c")).isEqualTo(RoleRefreshScheduler.State.SCHEDULED);
    }

    @Test
    void aRefusedRunTriesAgain() {
        results.add(RoleRefreshResult.REFUSED);
        scheduler.request("r", "c");
        runNext();

        assertThat(scheduled).hasSize(1);
        assertThat(scheduler.state("c")).isEqualTo(RoleRefreshScheduler.State.SCHEDULED);
    }

    @Test
    void aStoppedRunDoesNotRetry() {
        results.add(RoleRefreshResult.STOPPED);
        scheduler.request("r", "c");
        runNext();

        assertThat(scheduled).isEmpty();
        assertThat(scheduler.state("c")).isNull();
    }

    @Test
    void aStoppedRunWithANewerRequestRunsAgain() {
        results.add(RoleRefreshResult.STOPPED);
        scheduler.request("r", "c");
        duringRun = () -> scheduler.request("r", "c");
        runNext();

        assertThat(scheduled).hasSize(1);
    }

    @Test
    void aSkippedRunGoesIdleEvenWithANewerRequest() {
        results.add(RoleRefreshResult.SKIPPED);
        scheduler.request("r", "c");
        duringRun = () -> scheduler.request("r", "c");
        runNext();

        assertThat(scheduled).isEmpty();
        assertThat(scheduler.state("c")).isNull();
    }

    @Test
    void aRunnerThatThrowsCountsAsStopped() {
        var throwing = new RoleRefreshScheduler(
            (realmId, componentId, shutdown) -> { throw new IllegalStateException("boom"); },
            (task, delay) -> scheduled.add(task), Duration.ofSeconds(60), null);
        throwing.request("r", "c");
        runNext();

        assertThat(throwing.state("c")).isNull();
        assertThat(scheduled).isEmpty();
    }

    @Test
    void componentsAreIndependent() {
        scheduler.request("r", "a");
        scheduler.request("r", "b");

        assertThat(scheduled).hasSize(2);
    }

    private static org.keycloak.component.ComponentModel component(String providerId, String enabled,
                                                                   String propagationUser) {
        var m = new org.keycloak.component.ComponentModel();
        var config = new org.keycloak.common.util.MultivaluedHashMap<String, String>();
        if (enabled != null) {
            config.putSingle("enabled", enabled);
        }
        if (propagationUser != null) {
            config.putSingle("propagation-user", propagationUser);
        }
        m.setConfig(config);
        m.setProviderId(providerId);
        return m;
    }

    @Test
    void onlyAnEnabledScimComponentWithUserPropagationIsRefreshable() {
        assertThat(RoleRefreshScheduler.refreshable(component("scim", null, "true"))).isTrue();
        assertThat(RoleRefreshScheduler.refreshable(component("scim", "false", "true"))).isFalse();
        assertThat(RoleRefreshScheduler.refreshable(component("scim", "true", "false"))).isFalse();
        assertThat(RoleRefreshScheduler.refreshable(component("scim", "true", null))).isFalse();
        assertThat(RoleRefreshScheduler.refreshable(component("ldap", "true", "true"))).isFalse();
        assertThat(RoleRefreshScheduler.refreshable(null)).isFalse();
    }

    @Test
    void aRunnerThatThrowsAnErrorStillFreesTheComponent() {
        var erroring = new RoleRefreshScheduler(
            (realmId, componentId, shutdown) -> { throw new StackOverflowError(); },
            (task, delay) -> scheduled.add(task), Duration.ofSeconds(60), null);
        erroring.request("r", "c");
        try {
            runNext();
        } catch (StackOverflowError expected) {
            // The error still reaches the executor.
        }

        assertThat(erroring.state("c")).isNull();
    }

    @Test
    void afterShutdownRequestsAreIgnoredAndNothingIsRescheduled() {
        results.add(RoleRefreshResult.REFUSED);
        scheduler.request("r", "c");
        scheduler.shutdown();
        runNext();
        scheduler.request("r", "d");

        assertThat(scheduled).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew test --tests 'sh.libre.scim.core.RoleRefreshSchedulerTest' -q`
Expected: compile failure.

- [ ] **Step 3: Implement**

```java
package sh.libre.scim.core;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;

import sh.libre.scim.storage.ScimStorageProviderFactory;

/**
 * Runs at most one role refresh of each component at a time on this node,
 * a short delay after the last request. Several requests in a row become one
 * refresh. A request during a refresh causes one more refresh after it.
 *
 * <p>The state is in memory. A request that a stopped node never ran waits for
 * the next periodic sync. See the effective roles design.
 */
public final class RoleRefreshScheduler {

    private static final Logger LOGGER = Logger.getLogger(RoleRefreshScheduler.class);

    /** JVM system property: seconds between a request and its refresh. */
    static final String DELAY_PROPERTY = "scim.roles.refreshDelaySeconds";
    static final long DEFAULT_DELAY_SECONDS = 60;

    /** A component with no entry is idle. */
    enum State { SCHEDULED, RUNNING, RUNNING_AGAIN }

    @FunctionalInterface
    interface Runner {
        RoleRefreshResult run(String realmId, String componentId, BooleanSupplier shutdown);
    }

    @FunctionalInterface
    interface Delayer {
        void schedule(Runnable task, Duration delay);
    }

    private static volatile RoleRefreshScheduler current;

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final Runner runner;
    private final Delayer delayer;
    private final Duration delay;
    private final ScheduledExecutorService ownedExecutor;
    private volatile boolean shutdown;

    RoleRefreshScheduler(Runner runner, Delayer delayer, Duration delay, ScheduledExecutorService ownedExecutor) {
        this.runner = runner;
        this.delayer = delayer;
        this.delay = delay;
        this.ownedExecutor = ownedExecutor;
    }

    /** Starts this node's scheduler. Called once, from the event listener factory. */
    public static synchronized void start(KeycloakSessionFactory sessionFactory) {
        if (current != null) {
            return;
        }
        var executor = new ScheduledThreadPoolExecutor(1, r -> {
            var thread = new Thread(r, "scim-role-refresh");
            thread.setDaemon(true);
            return thread;
        });
        // Runs that wait for their delay are dropped at shutdown.
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        var delay = Duration.ofSeconds(Long.getLong(DELAY_PROPERTY, DEFAULT_DELAY_SECONDS));
        current = new RoleRefreshScheduler(
            (realmId, componentId, isShutdown) -> runForComponent(sessionFactory, realmId, componentId, isShutdown),
            (task, d) -> executor.schedule(task, d.toMillis(), TimeUnit.MILLISECONDS),
            delay,
            executor);
    }

    /** Stops this node's scheduler. A running refresh stops at its next user. */
    public static synchronized void stop() {
        var scheduler = current;
        current = null;
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /** This node's scheduler, or null before start and after stop. */
    public static RoleRefreshScheduler current() {
        return current;
    }

    /** Asks for one role refresh of a component. */
    public void request(String realmId, String componentId) {
        if (shutdown) {
            return;
        }
        boolean[] schedule = {false};
        states.compute(componentId, (id, state) -> {
            if (state == null) {
                schedule[0] = true;
                return State.SCHEDULED;
            }
            return state == State.SCHEDULED ? State.SCHEDULED : State.RUNNING_AGAIN;
        });
        if (schedule[0]) {
            scheduleRun(realmId, componentId);
        }
    }

    void shutdown() {
        shutdown = true;
        if (ownedExecutor != null) {
            // Not shutdownNow: an interrupt can land inside a JDBC or HTTP
            // call and break the lease release. The running refresh sees the
            // flag through its lost check and stops at the next user. See
            // SyncLease.stopHeartbeat for the same choice.
            ownedExecutor.shutdown();
        }
    }

    // package-private for tests; null means idle
    State state(String componentId) {
        return states.get(componentId);
    }

    private void scheduleRun(String realmId, String componentId) {
        if (shutdown) {
            states.remove(componentId);
            return;
        }
        try {
            delayer.schedule(() -> run(realmId, componentId), delay);
        } catch (RejectedExecutionException e) {
            states.remove(componentId);
        }
    }

    private void run(String realmId, String componentId) {
        if (shutdown) {
            states.remove(componentId);
            return;
        }
        // Moves to RUNNING before anything is read, so a request after this
        // point causes one more refresh instead of being absorbed.
        if (!states.replace(componentId, State.SCHEDULED, State.RUNNING)) {
            return;
        }
        RoleRefreshResult result = RoleRefreshResult.STOPPED;
        try {
            result = runner.run(realmId, componentId, () -> shutdown);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "SCIM role refresh failed for component %s", componentId);
        } finally {
            // In finally, so even an Error leaves the component able to
            // refresh again. Without it, the state would stay RUNNING.
            finish(realmId, componentId, result);
        }
    }

    private void finish(String realmId, String componentId, RoleRefreshResult result) {
        boolean[] schedule = {false};
        states.compute(componentId, (id, state) -> {
            boolean newerRequest = state == State.RUNNING_AGAIN;
            boolean again = result == RoleRefreshResult.REFUSED
                || (newerRequest && result != RoleRefreshResult.SKIPPED);
            if (again && !shutdown) {
                schedule[0] = true;
                return State.SCHEDULED;
            }
            return null;
        });
        if (schedule[0]) {
            scheduleRun(realmId, componentId);
        }
    }

    /** Reads the component again, then runs the refresh if it is still on. */
    private static RoleRefreshResult runForComponent(KeycloakSessionFactory sessionFactory, String realmId,
                                                     String componentId, BooleanSupplier isShutdown) {
        ComponentModel model = KeycloakModelUtils.runJobInTransactionWithResult(sessionFactory, session -> {
            var realm = session.realms().getRealm(realmId);
            if (realm == null) {
                return null;
            }
            var component = realm.getComponent(componentId);
            return refreshable(component) ? component : null;
        });
        if (model == null) {
            return RoleRefreshResult.SKIPPED;
        }
        return ScimSync.runRoleRefresh(sessionFactory, realmId, model, isShutdown);
    }

    /** Whether a component still exists and is on for user propagation. */
    // package-private for tests
    static boolean refreshable(ComponentModel component) {
        return component != null
            && ScimStorageProviderFactory.ID.equals(component.getProviderId())
            && component.get("enabled", true)
            && component.get("propagation-user", false);
    }
}
```

- [ ] **Step 4: Run the tests and see them pass**

Run: `./gradlew test --tests 'sh.libre.scim.core.RoleRefreshSchedulerTest' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/RoleRefreshScheduler.java src/test/java/sh/libre/scim/core/RoleRefreshSchedulerTest.java
git commit -m "feat: add a per-node scheduler for role refreshes"
```

### Task 8: Request after commit, and wire the scheduler

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimDispatcher.java`
- Modify: `src/main/java/sh/libre/scim/event/ScimEventListenerProviderFactory.java`
- Test: `src/test/java/sh/libre/scim/core/ScimDispatcherRoleRefreshTest.java`

- [ ] **Step 1: Write the failing test**

First add this package-private hook to `RoleRefreshScheduler`. Only tests use it:

```java
    // package-private: tests install a scheduler with fake parts
    static synchronized void install(RoleRefreshScheduler scheduler) {
        current = scheduler;
    }
```

Then create `src/test/java/sh/libre/scim/core/ScimDispatcherRoleRefreshTest.java`. It captures the enlisted transaction, as `ScimDispatcherRollbackTest` mocks the transaction manager, and reads the scheduler's state to see the requests.

```java
package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.RealmModel;
import org.mockito.ArgumentCaptor;
import sh.libre.scim.storage.ScimStorageProviderFactory;

class ScimDispatcherRoleRefreshTest {

    private KeycloakSession session;
    private KeycloakTransactionManager transactions;
    private RoleRefreshScheduler scheduler;

    private static ComponentModel component(String id, String propagationUser) {
        var m = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("propagation-user", propagationUser);
        m.setConfig(config);
        m.setId(id);
        m.setProviderId(ScimStorageProviderFactory.ID);
        return m;
    }

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        transactions = mock(KeycloakTransactionManager.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-1");
        when(realm.getComponentsStream())
            .thenAnswer(i -> Stream.of(component("on", "true"), component("off", "false")));
        when(session.getTransactionManager()).thenReturn(transactions);
        scheduler = new RoleRefreshScheduler(
            (realmId, componentId, shutdown) -> RoleRefreshResult.FINISHED,
            (task, delay) -> {}, Duration.ofSeconds(60), null);
        RoleRefreshScheduler.install(scheduler);
    }

    @AfterEach
    void tearDown() {
        RoleRefreshScheduler.stop();
    }

    private KeycloakTransaction enlisted() {
        var captor = ArgumentCaptor.forClass(KeycloakTransaction.class);
        verify(transactions).enlistAfterCompletion(captor.capture());
        return captor.getValue();
    }

    @Test
    void aRequestIsMadeOnlyAfterCommitAndOnlyForUserPropagation() {
        new ScimDispatcher(session).requestRoleRefresh();
        var transaction = enlisted();
        assertThat(scheduler.state("on")).isNull();

        transaction.commit();

        assertThat(scheduler.state("on")).isEqualTo(RoleRefreshScheduler.State.SCHEDULED);
        assertThat(scheduler.state("off")).isNull();
    }

    @Test
    void aRollbackMakesNoRequest() {
        new ScimDispatcher(session).requestRoleRefresh();

        enlisted().rollback();

        assertThat(scheduler.state("on")).isNull();
    }

    @Test
    void withNoSchedulerNothingIsEnlisted() {
        RoleRefreshScheduler.stop();

        new ScimDispatcher(session).requestRoleRefresh();

        verify(transactions, never()).enlistAfterCompletion(any());
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `./gradlew test --tests 'sh.libre.scim.core.ScimDispatcherRoleRefreshTest' -q`
Expected: compile failure, no `requestRoleRefresh`.

- [ ] **Step 3: Implement `requestRoleRefresh`**

In `ScimDispatcher`, add:

```java
    /**
     * Asks for one role refresh of every SCIM component in the realm that has
     * enabled=true and propagation-user=true. The request is made after the
     * caller's transaction commits, so the refresh reads the change it serves.
     * A rollback makes no request.
     */
    public void requestRoleRefresh() {
        var scheduler = RoleRefreshScheduler.current();
        if (scheduler == null) {
            return;
        }
        var realm = session.getContext().getRealm();
        var realmId = realm.getId();
        List<String> componentIds = realm.getComponentsStream()
            .filter(m -> ScimStorageProviderFactory.ID.equals(m.getProviderId())
                && m.get("enabled", true)
                && m.get("propagation-user", false))
            .map(ComponentModel::getId)
            .toList();
        if (componentIds.isEmpty()) {
            return;
        }
        session.getTransactionManager().enlistAfterCompletion(new KeycloakTransaction() {
            private volatile boolean done = false;

            @Override public void begin() {}

            @Override
            public void commit() {
                componentIds.forEach(id -> scheduler.request(realmId, id));
                done = true;
            }

            @Override
            public void rollback() {
                done = true;
            }

            @Override public void setRollbackOnly() {}
            @Override public boolean getRollbackOnly() { return false; }
            @Override public boolean isActive() { return !done; }
        });
    }
```

- [ ] **Step 4: Start and stop the scheduler with the factory**

In `ScimEventListenerProviderFactory`:

```java
    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // postInit, not init: a refresh needs the session factory.
        RoleRefreshScheduler.start(factory);
    }

    @Override
    public void close() {
        RoleRefreshScheduler.stop();
    }
```

Add `import sh.libre.scim.core.RoleRefreshScheduler;`.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimDispatcher.java src/main/java/sh/libre/scim/core/RoleRefreshScheduler.java src/main/java/sh/libre/scim/event/ScimEventListenerProviderFactory.java src/test/java/sh/libre/scim/core/ScimDispatcherRoleRefreshTest.java
git commit -m "feat: request role refreshes after commit"
```

---

## Chunk 4: Events

### Task 9: `groupConfersScimRole` covers parents and composites

**Files:**
- Modify: `src/main/java/sh/libre/scim/event/ScimEventListenerProvider.java`
- Test: `src/test/java/sh/libre/scim/event/ScimEventListenerProviderTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `ScimEventListenerProviderTest`:

```java
    @Test
    void confersScimRole_throughAParentGroup() {
        var marked = role("true");
        var parent = mock(GroupModel.class);
        when(parent.getRoleMappingsStream()).thenAnswer(i -> Stream.of(marked));
        var child = mock(GroupModel.class);
        when(child.getRoleMappingsStream()).thenAnswer(i -> Stream.empty());
        when(child.getParent()).thenReturn(parent);

        assertTrue(listener.groupConfersScimRole(child));
    }

    @Test
    void confersScimRole_throughAComposite() {
        var marked = role("true");
        var composite = role(null);
        when(composite.isComposite()).thenReturn(true);
        when(composite.getCompositesStream()).thenAnswer(i -> Stream.of(marked));
        var group = mock(GroupModel.class);
        when(group.getRoleMappingsStream()).thenAnswer(i -> Stream.of(composite));

        assertTrue(listener.groupConfersScimRole(group));
    }
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew test --tests 'sh.libre.scim.event.ScimEventListenerProviderTest' -q`
Expected: the two new tests FAIL.

- [ ] **Step 3: Implement**

Replace the body of `groupConfersScimRole` and update its Javadoc:

```java
    /**
     * Whether the group, or any parent group, has a role mapping whose
     * composite expansion includes a role marked scim="true". This is the
     * same set of roles UserAdapter sends. If false, a membership change on
     * this group cannot change any member's SCIM roles.
     */
    // package-private for tests
    boolean groupConfersScimRole(GroupModel group) {
        for (var g = group; g != null; g = g.getParent()) {
            boolean marked = RoleUtils.expandCompositeRolesStream(g.getRoleMappingsStream())
                    .anyMatch(r -> "true".equals(r.getFirstAttribute("scim")));
            if (marked) {
                return true;
            }
        }
        return false;
    }
```

Add `import org.keycloak.models.utils.RoleUtils;`.

- [ ] **Step 4: Run the tests and see them pass**

Run: `./gradlew test --tests 'sh.libre.scim.event.ScimEventListenerProviderTest' -q`
Expected: PASS, including the three existing tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/event/ScimEventListenerProvider.java src/test/java/sh/libre/scim/event/ScimEventListenerProviderTest.java
git commit -m "feat: count parent groups and composites in groupConfersScimRole"
```

### Task 10: Route role-structure events to a role refresh

**Files:**
- Modify: `src/main/java/sh/libre/scim/event/ScimEventListenerProvider.java`
- Test: `src/test/java/sh/libre/scim/event/ScimEventListenerProviderTest.java`

- [ ] **Step 1: Write the failing tests**

The listener builds its own `ScimDispatcher` in its constructor. Its `dispatcher` field is package-visible, so a test in `sh.libre.scim.event` can replace it with a mock. The listener's `getUser` and `getGroup` call `session.getContext().getRealm()`, `session.users()` and `session.groups()`. So each routing test builds its own listener on a session mock with deep stubs.

Add a helper and tests:

```java
    private static AdminEvent event(ResourceType type, OperationType op, String path) {
        var e = new AdminEvent();
        e.setResourceType(type);
        e.setOperationType(op);
        e.setResourcePath(path);
        return e;
    }

    private static ScimDispatcher routed(AdminEvent e) {
        var routing = new ScimEventListenerProvider(mock(KeycloakSession.class, RETURNS_DEEP_STUBS));
        var dispatcher = mock(ScimDispatcher.class);
        routing.dispatcher = dispatcher;
        routing.onEvent(e, false);
        return dispatcher;
    }

    @Test
    void aRoleGrantedToAGroupRequestsARoleRefreshAndSendsNoUser() {
        var d = routed(event(ResourceType.REALM_ROLE_MAPPING, OperationType.CREATE,
            "groups/g1/role-mappings/realm"));
        verify(d).requestRoleRefresh();
        verify(d, never()).run(any(), any());
    }

    @Test
    void aClientRoleGrantedToAGroupRequestsARoleRefresh() {
        var d = routed(event(ResourceType.CLIENT_ROLE_MAPPING, OperationType.CREATE,
            "groups/g1/role-mappings/clients/c1"));
        verify(d).requestRoleRefresh();
    }

    @Test
    void aClientRoleGrantedToAUserSendsThatUser() {
        var d = routed(event(ResourceType.CLIENT_ROLE_MAPPING, OperationType.CREATE,
            "users/u1/role-mappings/clients/c1"));
        verify(d).run(eq(ScimDispatcher.SCOPE_USER), any());
        verify(d, never()).requestRoleRefresh();
    }

    @Test
    void aRealmRoleChangeRequestsARoleRefresh() {
        var d = routed(event(ResourceType.REALM_ROLE, OperationType.UPDATE, "roles-by-id/r1"));
        verify(d).requestRoleRefresh();
    }

    @Test
    void aClientRoleChangeRequestsARoleRefresh() {
        var d = routed(event(ResourceType.CLIENT_ROLE, OperationType.DELETE, "clients/c1/roles/x"));
        verify(d).requestRoleRefresh();
    }

    @Test
    void aClientDeleteRequestsARoleRefresh() {
        var d = routed(event(ResourceType.CLIENT, OperationType.DELETE, "clients/c1"));
        verify(d).requestRoleRefresh();
    }

    @Test
    void aClientUpdateDoesNothing() {
        var d = routed(event(ResourceType.CLIENT, OperationType.UPDATE, "clients/c1"));
        verifyNoInteractions(d);
    }

    @Test
    void aMoveUnderANewParentRequestsARoleRefreshOnly() {
        var d = routed(event(ResourceType.GROUP, OperationType.UPDATE, "groups/parent-1/children"));
        verify(d).requestRoleRefresh();
        verify(d, never()).run(any(), any());
    }

    @Test
    void aMoveToTheTopLevelRequestsARoleRefreshOnly() {
        var d = routed(event(ResourceType.GROUP, OperationType.UPDATE, "groups"));
        verify(d).requestRoleRefresh();
        verify(d, never()).run(any(), any());
    }

    @Test
    void aGroupRenameUpdatesTheGroupAndRequestsARoleRefresh() {
        var d = routed(event(ResourceType.GROUP, OperationType.UPDATE, "groups/g1"));
        verify(d).run(eq(ScimDispatcher.SCOPE_GROUP), any());
        verify(d).requestRoleRefresh();
    }

    @Test
    void aGroupDeleteDeletesTheGroupAndRequestsARoleRefresh() {
        var d = routed(event(ResourceType.GROUP, OperationType.DELETE, "groups/g1"));
        verify(d).run(eq(ScimDispatcher.SCOPE_GROUP), any());
        verify(d).requestRoleRefresh();
    }

    @Test
    void aTopLevelGroupCreateIsUnchanged() {
        var d = routed(event(ResourceType.GROUP, OperationType.CREATE, "groups/g1"));
        verify(d).run(eq(ScimDispatcher.SCOPE_GROUP), any());
        verify(d, never()).requestRoleRefresh();
    }
```

Imports to add: `org.keycloak.events.admin.AdminEvent`, `OperationType`, `ResourceType`, `sh.libre.scim.core.ScimDispatcher`, and Mockito's `RETURNS_DEEP_STUBS`, `any`, `eq`, `never`, `verify`, `verifyNoInteractions`.

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew test --tests 'sh.libre.scim.event.ScimEventListenerProviderTest' -q`
Expected: most new tests FAIL.

- [ ] **Step 3: Implement**

Make all of the edits below before you compile. The new prefix declares `var type`, and the old `REALM_ROLE_MAPPING` block uses the same name; the replacement block removes that clash.

In the constructor, change the `GROUP` pattern and add one for client role mappings:

```java
        // "groups" (a move to the top level), "groups/{id}", or
        // "groups/{id}/children" (a subgroup create, or a move under {id}).
        patterns.put(ResourceType.GROUP, Pattern.compile("^groups(?:/([\\w-]+)(/children)?)?"));
        patterns.put(ResourceType.REALM_ROLE_MAPPING, Pattern.compile("^(.+)/(.+)/role-mappings"));
        patterns.put(ResourceType.CLIENT_ROLE_MAPPING, Pattern.compile("^(.+)/(.+)/role-mappings"));
```

At the start of `onEvent(AdminEvent, boolean)`, before the pattern lookup:

```java
        // A change to a role, or a deleted client, can change the roles of
        // any number of users. The path does not matter.
        var type = event.getResourceType();
        if (type == ResourceType.REALM_ROLE || type == ResourceType.CLIENT_ROLE
                || (type == ResourceType.CLIENT && event.getOperationType() == OperationType.DELETE)) {
            dispatcher.requestRoleRefresh();
            return;
        }
```

Replace the `GROUP` block with:

```java
        if (event.getResourceType() == ResourceType.GROUP) {
            var groupId = matcher.group(1);
            boolean children = matcher.group(2) != null;
            var op = event.getOperationType();
            LOGGER.infof("group %s %s", groupId, op);
            if (op == OperationType.UPDATE && (groupId == null || children)) {
                // A move. The path names the new parent, or no group, and a
                // move does not change the moved group's SCIM body. Only the
                // members' roles can change.
                dispatcher.requestRoleRefresh();
                return;
            }
            if (groupId == null) {
                return;
            }
            if (op == OperationType.CREATE) {
                var group = getGroup(groupId);
                dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.create(GroupAdapter::new, group));
            }
            if (op == OperationType.UPDATE) {
                var group = getGroup(groupId);
                dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.replace(GroupAdapter::new, group));
                // The event does not say whether this was a rename. A role
                // refresh that finds no change sends nothing.
                dispatcher.requestRoleRefresh();
            }
            if (op == OperationType.DELETE) {
                dispatcher.run(ScimDispatcher.SCOPE_GROUP,
                        client -> client.delete(GroupAdapter::new, groupId));
                dispatcher.requestRoleRefresh();
            }
        }
```

Replace the `REALM_ROLE_MAPPING` block with one that handles both mapping types:

```java
        if (event.getResourceType() == ResourceType.REALM_ROLE_MAPPING
                || event.getResourceType() == ResourceType.CLIENT_ROLE_MAPPING) {
            var kind = matcher.group(1);
            var id = matcher.group(2);
            LOGGER.infof("%s %s %s roles", event.getOperationType(), kind, id);
            if ("users".equals(kind)) {
                var user = getUser(id);
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter::new, user));
            } else if ("groups".equals(kind)) {
                // Was a PUT for every member, inside this request. A role
                // refresh after commit reaches members of subgroups too, and
                // sends only users whose roles changed.
                dispatcher.requestRoleRefresh();
            }
        }
```

- [ ] **Step 4: Run the tests and see them pass**

Run: `./gradlew test -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/event/ScimEventListenerProvider.java src/test/java/sh/libre/scim/event/ScimEventListenerProviderTest.java
git commit -m "feat!: send role-structure changes through a role refresh" -m "BREAKING CHANGE: a role granted to or removed from a group no longer sends a PUT for each member inside the admin request. One role refresh after commit sends the members whose roles changed, including members of subgroups. Role changes, group moves, group deletes and client deletes also request a role refresh."
```

---

## Chunk 5: Integration tests, measurement, docs

### Task 11: Keep role refreshes out of the shared test container

**Files:**
- Modify: `src/integrationTest/java/sh/libre/scim/integration/IntegrationTestBase.java` (`keycloak()`)
- Modify: `build.gradle.kts`

The shared container serves many test classes, and WireMock counts every `/Users` request. A role refresh that one test schedules must not fire inside a later test.

- [ ] **Step 1: Set the delay on the shared container**

In `IntegrationTestBase`, add:

```java
    /**
     * Role-refresh delay for the shared Keycloak. Long by default, so a role
     * refresh that one test schedules never fires inside a later test. The
     * perf task sets it short. A test class that checks role refreshes runs
     * its own container.
     */
    protected static final String ROLE_REFRESH_DELAY_SECONDS =
        System.getProperty("it.roles.refreshDelaySeconds", "86400");
```

In `keycloak()`, add to the container builder:

```java
                    .withEnv("JAVA_OPTS_APPEND", "-Dscim.roles.refreshDelaySeconds=" + ROLE_REFRESH_DELAY_SECONDS)
```

- [ ] **Step 2: Forward the property**

In `build.gradle.kts`:
- In the `integrationTest` task, change `listOf("keycloak.image")` to `listOf("keycloak.image", "it.roles.refreshDelaySeconds")`.
- In the `performanceTest` task, add `"it.roles.refreshDelaySeconds"` to the forwarded list, and after that loop add:

```kotlin
    // A perf run measures role refreshes on the shared container, so the
    // delay is short there unless the caller sets it.
    if (System.getProperty("it.roles.refreshDelaySeconds") == null) {
        systemProperty("it.roles.refreshDelaySeconds", "1")
    }
```

- [ ] **Step 3: Run the full integration suite**

Run: `./gradlew integrationTest` (about 15 minutes; run it in the background).
Expected: all tests pass. If a test fails because it expected a PUT for each member right after a role was granted to a group, that test checked the removed fan-out. Move its check to `ScimEffectiveRolesIT` (Task 12) and remove it from the old class.

- [ ] **Step 4: Commit**

```bash
git add src/integrationTest/java/sh/libre/scim/integration/IntegrationTestBase.java build.gradle.kts
git commit -m "test: keep role refreshes out of the shared integration container"
```

### Task 12: `ScimEffectiveRolesIT`

**Files:**
- Create: `src/integrationTest/java/sh/libre/scim/integration/ScimEffectiveRolesIT.java`

This class runs its own Keycloak with a 5-second delay, like `ScimSyncLeaseStaleIT`. It needs no directory server.

- [ ] **Step 1: Write the test class**

```java
package sh.libre.scim.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

/**
 * End-to-end role refreshes. Runs its own Keycloak with a short role-refresh
 * delay, so a refresh fires within seconds and cannot leak into other test
 * classes that share a container.
 */
class ScimEffectiveRolesIT extends IntegrationTestBase {

    private static final int DELAY_SECONDS = 5;
    private static KeycloakContainer rolesKeycloak;
    private static Keycloak rolesAdmin;

    @BeforeAll
    static void startRolesKeycloak() {
        rolesKeycloak = new KeycloakContainer(KEYCLOAK_IMAGE)
            .withProviderLibsFrom(List.of(PLUGIN_JAR))
            .withNetwork(network)
            .withEnv("JAVA_OPTS_APPEND", "-Dscim.roles.refreshDelaySeconds=" + DELAY_SECONDS);
        rolesKeycloak.start();
        rolesAdmin = AdminClients.forContainer(rolesKeycloak);
    }

    @AfterAll
    static void stopRolesKeycloak() {
        if (rolesAdmin != null) {
            rolesAdmin.close();
        }
        if (rolesKeycloak != null) {
            rolesKeycloak.stop();
        }
    }

    /** A realm with the SCIM provider and the scim event listener, and no directory. */
    private RealmResource newRealm() {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        String realmName = "it-" + UUID.randomUUID().toString().substring(0, 8);
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(realmName);
        realmRep.setEnabled(true);
        rolesAdmin.realms().create(realmRep);
        RealmResource realm = rolesAdmin.realm(realmName);
        // Users only. Group propagation adds requests these tests do not check.
        addScimStorageProvider(realm, cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("propagation-group", "false");
        });
        enableScimEventListener(realm);
        return realm;
    }

    private static RoleRepresentation markedRole(RealmResource realm, String name) {
        var role = new RoleRepresentation();
        role.setName(name);
        role.setAttributes(Map.of("scim", List.of("true")));
        realm.roles().create(role);
        return realm.roles().get(name).toRepresentation();
    }

    private static RoleRepresentation plainRole(RealmResource realm, String name) {
        var role = new RoleRepresentation();
        role.setName(name);
        realm.roles().create(role);
        return realm.roles().get(name).toRepresentation();
    }

    /** The body of the newest PUT /Users/* whose body names {@code userName}, or "" if none. */
    private static String newestUserPutFor(String userName) {
        return wireMock.getAllServeEvents().stream() // newest first
            .filter(e -> "PUT".equals(e.getRequest().getMethod().getName()))
            .filter(e -> e.getRequest().getUrl().startsWith("/Users/"))
            .map(e -> e.getRequest().getBodyAsString())
            .filter(b -> b.contains("\"" + userName + "\""))
            .findFirst()
            .orElse("");
    }

    /** Counts the one line each role refresh logs when it ends. */
    private static int roleRefreshLogLines(String componentId) {
        var matcher = Pattern.compile("SCIM role refresh of component " + Pattern.quote(componentId)
                + " (finished|stopped) in ")
            .matcher(rolesKeycloak.getLogs());
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String scimComponentId(RealmResource realm) {
        return realm.components().query(realm.toRepresentation().getId(),
                "org.keycloak.storage.UserStorageProvider").stream()
            .filter(c -> "scim".equals(c.getProviderId()))
            .findFirst().orElseThrow().getId();
    }

    @Test
    void aRoleGrantedToAParentGroupReachesSubgroupMembers() {
        var realm = newRealm();
        String componentId = scimComponentId(realm);
        int before = roleRefreshLogLines(componentId);
        var role = markedRole(realm, "from-parent");
        // Creating the role requests a role refresh. Let it end first, so the
        // "admin request sends no user" check below cannot see its PUT.
        await().atMost(30, SECONDS).until(() -> roleRefreshLogLines(componentId) > before);
        String parentId = createGroup(realm, "parent");
        var childRep = new GroupRepresentation();
        childRep.setName("child");
        realm.groups().group(parentId).subGroup(childRep).close();
        String childId = realm.groups().group(parentId).getSubGroups(0, 10, true).get(0).getId();
        String userId = createAdminUser(realm, "sub-member", "sub-member@test.local");
        awaitUserPostFor("sub-member");
        realm.users().get(userId).joinGroup(childId);

        realm.groups().group(parentId).roles().realmLevel().add(List.of(role));
        // The delay is 5 seconds, so a PUT that names the role right after
        // the admin call returns came from inside the admin request.
        assertTrue(!newestUserPutFor("sub-member").contains("from-parent"),
            "the admin request itself sends no user");

        await().atMost(30, SECONDS).untilAsserted(() ->
            assertTrue(newestUserPutFor("sub-member").contains("from-parent"),
                "the subgroup member gets the parent group's role"));
    }

    @Test
    void aDirectGrantIsSentAtOnce() {
        var realm = newRealm();
        String componentId = scimComponentId(realm);
        int before = roleRefreshLogLines(componentId);
        var role = markedRole(realm, "direct-grant");
        // Creating the role requests a role refresh. Let it end first, so the
        // check below can only pass through the direct grant.
        await().atMost(30, SECONDS).until(() -> roleRefreshLogLines(componentId) > before);
        String userId = createAdminUser(realm, "granted", "granted@test.local");
        awaitUserPostFor("granted");

        realm.users().get(userId).roles().realmLevel().add(List.of(role));

        await().atMost(DELAY_SECONDS - 1, SECONDS).untilAsserted(() ->
            assertTrue(newestUserPutFor("granted").contains("direct-grant"),
                "a direct grant sends the user before any role refresh could"));
    }

    /**
     * The sequence that needs the event path to clear the hash: a refresh
     * stores the hash of a body without the role, an event sends the role,
     * and removing the role brings the body back to the hashed one.
     */
    @Test
    void removingARoleFromACompositeRemovesItFromHolders() {
        var realm = newRealm();
        String componentId = scimComponentId(realm);
        var inner = markedRole(realm, "inner");
        var outer = plainRole(realm, "outer");
        realm.roles().get("outer").addComposites(List.of(inner));
        String userId = createAdminUser(realm, "holder", "holder@test.local");
        awaitUserPostFor("holder");
        // Let a role refresh store the hash of the body without "inner".
        int before = roleRefreshLogLines(componentId);
        plainRole(realm, "nudge");
        await().atMost(30, SECONDS).until(() -> roleRefreshLogLines(componentId) > before);
        realm.users().get(userId).roles().realmLevel().add(List.of(outer));
        await().atMost(30, SECONDS).untilAsserted(() ->
            assertTrue(newestUserPutFor("holder").contains("inner"), "a direct grant sends the role at once"));

        realm.roles().get("outer").deleteComposites(List.of(inner));

        await().atMost(30, SECONDS).untilAsserted(() ->
            assertTrue(!newestUserPutFor("holder").contains("inner"),
                "the holder loses the role that left the composite"));
    }

    @Test
    void tenRoleEditsInARowRunOneRefresh() throws Exception {
        var realm = newRealm();
        String componentId = scimComponentId(realm);
        int before = roleRefreshLogLines(componentId);

        for (int i = 0; i < 10; i++) {
            plainRole(realm, "burst-" + i);
        }

        await().atMost(30, SECONDS).until(() -> roleRefreshLogLines(componentId) > before);
        Thread.sleep((DELAY_SECONDS + 3) * 1000L);
        assertEquals(before + 1, roleRefreshLogLines(componentId), "ten edits make one role refresh");
    }

    @Test
    void movingAGroupGivesAndTakesItsParentsRole() {
        var realm = newRealm();
        var role = markedRole(realm, "via-move");
        String parentId = createGroup(realm, "move-parent");
        realm.groups().group(parentId).roles().realmLevel().add(List.of(role));
        String movedId = createGroup(realm, "moved");
        String userId = createAdminUser(realm, "mover", "mover@test.local");
        awaitUserPostFor("mover");
        realm.users().get(userId).joinGroup(movedId);
        var movedRep = realm.groups().group(movedId).toRepresentation();

        realm.groups().group(parentId).subGroup(movedRep).close();
        await().atMost(30, SECONDS).untilAsserted(() ->
            assertTrue(newestUserPutFor("mover").contains("via-move"), "a move under the parent gives the role"));

        realm.groups().add(realm.groups().group(movedId).toRepresentation()).close();
        await().atMost(30, SECONDS).untilAsserted(() ->
            assertTrue(!newestUserPutFor("mover").contains("via-move"), "a move to the top level takes it away"));
    }

    @Test
    void aManualSyncStillSendsEveryUser() {
        var realm = newRealm();
        var scim = realm.components().component(scimComponentId(realm)).toRepresentation();
        scim.getConfig().putSingle("sync-refresh", "true");
        realm.components().component(scim.getId()).update(scim);
        createAdminUser(realm, "steady", "steady@test.local");
        awaitUserPostFor("steady");
        int before = wireMock.countRequestsMatching(putRequestedFor(urlPathMatching("/Users/.*")).build()).getCount();

        realm.userStorage().syncUsers(scim.getId(), "triggerFullSync");
        realm.userStorage().syncUsers(scim.getId(), "triggerFullSync");

        int after = wireMock.countRequestsMatching(putRequestedFor(urlPathMatching("/Users/.*")).build()).getCount();
        assertTrue(after - before >= 2, "each manual sync sends the unchanged user");
    }
}
```

The helpers it uses (`addScimStorageProvider`, `enableScimEventListener`, `createGroup`, `createAdminUser`, `awaitUserPostFor`) exist in `IntegrationTestBase` with these signatures.

- [ ] **Step 2: Run it**

Run: `./gradlew integrationTest --tests 'sh.libre.scim.integration.ScimEffectiveRolesIT'`
Expected: PASS. If the move test fails, print the admin event paths from the container log and compare them with the spec's events table before you change code.

- [ ] **Step 3: Commit**

```bash
git add src/integrationTest/java/sh/libre/scim/integration/ScimEffectiveRolesIT.java
git commit -m "test: cover role refreshes end to end"
```

### Task 13: Measure a role refresh

**Files:**
- Create: `src/perfTest/java/sh/libre/scim/perf/RoleRefreshPerfTest.java`
- Modify: `docs/performance.md`

- [ ] **Step 1: Write the perf test**

```java
package sh.libre.scim.perf;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RoleRepresentation;

/**
 * Measures one role refresh over N mapped users with no change. Run with
 * {@code ./gradlew performanceTest --tests '*RoleRefreshPerfTest' -Dperf.userCount=10000}.
 * The perf task sets the role-refresh delay to 1 second on the shared container.
 */
class RoleRefreshPerfTest extends PerfTestBase {

    private static final int USER_COUNT = Integer.getInteger("perf.userCount", 1000);
    private static final Pattern FINISHED = Pattern.compile(
        "SCIM role refresh of component (\\S+) finished in (\\d+) ms: (\\d+) users sent, (\\d+) failed");

    @Test
    void aRoleRefreshWithNoChangeSendsNothing() throws Exception {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("sync-refresh", "true"));
        // Without the listener, the role edit below requests nothing.
        enableScimEventListener(r.realm());
        List<String> seeded = new ArrayList<>(seedLdapUsers("rr", USER_COUNT));
        try {
            r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
            await().atMost(10, MINUTES).until(() -> perUserPostCount() >= USER_COUNT);
            String scimId = r.realm().components().query(r.realm().toRepresentation().getId(),
                    "org.keycloak.storage.UserStorageProvider").stream()
                .filter(c -> "scim".equals(c.getProviderId())).findFirst().orElseThrow().getId();
            // A manual sync sends every user once and stores every hash.
            r.realm().userStorage().syncUsers(scimId, "triggerFullSync");

            int linesBefore = finishedLines(scimId).size();
            var role = new RoleRepresentation();
            role.setName("perf-trigger");
            r.realm().roles().create(role);

            await().atMost(10, MINUTES).until(() -> finishedLines(scimId).size() > linesBefore);
            var last = finishedLines(scimId).get(finishedLines(scimId).size() - 1);
            System.out.printf("[perf] role refresh, %d users, no change: %s ms, %s sent, %s failed%n",
                USER_COUNT, last[0], last[1], last[2]);
            assertEquals("0", last[1], "a refresh with no change sends no request");
        } finally {
            cleanupLdapEntries(seeded.stream().map(u -> "uid=" + u + ",ou=users,dc=test,dc=local").toList());
        }
    }

    /** {ms, sent, failed} for each finished role refresh of the component, oldest first. */
    private static List<String[]> finishedLines(String componentId) {
        var out = new ArrayList<String[]>();
        var m = FINISHED.matcher(keycloak().getLogs());
        while (m.find()) {
            if (m.group(1).equals(componentId)) {
                out.add(new String[] {m.group(2), m.group(3), m.group(4)});
            }
        }
        return out;
    }
}
```

Check `seedLdapUsers` (what it returns) and the DN pattern that other perf tests pass to `cleanupLdapEntries`, and match them. The `performanceTest` task times out after 30 minutes; the two waits above are 10 minutes each.

- [ ] **Step 2: Run it at 10,000 users**

Run: `./gradlew performanceTest --tests '*RoleRefreshPerfTest' -Dperf.userCount=10000`
Expected: PASS, and a `[perf] role refresh` line in the output. Also note, from the log, how long the manual sync took with the hash write, and compare with a manual sync on `main` if you can.

- [ ] **Step 3: Record the result**

In `docs/performance.md`, add a section "Role refresh" with the user count, the measured duration of a role refresh with no change, the number of requests (zero), and the cost the hash write adds to a manual sync. Say which Keycloak version and machine you measured on.

- [ ] **Step 4: Commit**

```bash
git add src/perfTest/java/sh/libre/scim/perf/RoleRefreshPerfTest.java docs/performance.md
git commit -m "test: measure a role refresh with no change"
```

### Task 14: Operator docs

**Files:**
- Modify: `docs/configuration.md`

- [ ] **Step 1: Update the SCIM roles section**

Rewrite `### SCIM roles` in `docs/configuration.md` to say:
- The plugin sends every marked role a user has: direct mappings, roles from every group and its parent groups, and roles inside composite roles. Realm and client roles both count. A name appears once.
- A change to one user's roles, or a user joining or leaving a group, sends that user at once.
- These changes request a **role refresh** of each component with `propagation-user=true`, about 60 seconds later: a role granted to or removed from a group, any change to a realm or client role, a group moved or deleted, and a client deleted. A group rename does not, because roles are sent by name.
- A role refresh reads every mapped user of the component. It sends only users whose SCIM body changed since the last refresh. It never creates users.
- Changes that make no admin event (LDAP mappers, identity provider mappers, imports) reach the server at the user's next write or the next periodic sync.
- With `user-patchOp=true`, `roles` is not sent. Keep `user-patchOp=false` for a component that sends SCIM roles.

Remove the old text that says composites and parent groups are not read, and the old list of changes that do not send a PUT.

- [ ] **Step 2: Update the sync behavior section**

In `### Sync behavior`, add:
- Run a periodic sync of the SCIM component once a day with `sync-refresh=true`. It is the safety net for role changes a node did not send before it stopped, and for changes that make no admin event.
- A periodic or manual sync sends every user, as before. It also undoes changes made directly on the SCIM server.
- If a role refresh holds the component's lease when the periodic sync starts, Keycloak skips that day's periodic sync.

- [ ] **Step 3: Add the JVM property**

In the JVM system properties table, add a row: `scim.roles.refreshDelaySeconds`, default `60`, "Seconds between a role-structure change and the role refresh it requests. Several changes within this time make one refresh."

- [ ] **Step 4: Commit**

```bash
git add docs/configuration.md
git commit -m "docs: describe effective SCIM roles and role refreshes"
```

- [ ] **Step 5: Upgrade notes (separate branch)**

`docs/upgrading-to-2.0.md` is on branch `docs/upgrading-to-2.0`. Do not edit it here. Report to the human that it needs these additions, so they can be made on that branch:
- A "SCIM roles include composites and parent groups" section under breaking changes: what changed, that every user with such roles gets a PUT, and that a role granted to a group no longer sends a PUT for each member inside the admin request.
- Under "Before you upgrade" or a new "After you upgrade" step: start one manual sync of each SCIM component with `sync-refresh=true`, so every user gets the roles that 1.x never sent.
- A recommendation to run a periodic sync once a day.

### Task 15: Final check

- [ ] **Step 1: Run every unit test**

Run: `./gradlew test -q`
Expected: PASS.

- [ ] **Step 2: Run the full integration suite**

Run: `./gradlew integrationTest` (about 15 minutes).
Expected: PASS. Report any failure with its output. Do not mark this task done while a test fails.

- [ ] **Step 3: Remove this plan from the branch**

Plans are scaffolding in this repo. Once every task is done:

```bash
git rm docs/superpowers/plans/2026-11-05-effective-scim-roles.md
git commit -m "chore: drop the implemented effective SCIM roles plan"
```
