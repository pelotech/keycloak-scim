# Federated Membership Removal Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Revision (as built):** During Task 3 the integration test surfaced that the bookkeeping **cannot** be stored as a `UserModel` attribute — under `editMode=READ_ONLY` federation the post-commit worker's user proxy is read-only (`ReadOnlyException`), and the writable import-thread user has no materialized groups at hook time. The storage was changed to Keycloak's **`UserFederatedStorageProvider`** (`session.getProvider(UserFederatedStorageProvider.class)`, keyed by `(realm, userId)`), which is writable for federated users regardless of edit mode. The diff logic, the `boolean` retry contract, and the test *intent* are unchanged; only the attribute IO moved (`getAttributes`/`setAttribute`/`removeAttribute` instead of `getAttributeStream`/`setAttribute`/`removeAttribute` on the user) and the unit tests mock the provider via `getProvider`. The authoritative description is the spec (Decision 3); the Task 2 code/test blocks below predate this and show the old user-attribute form.

**Goal:** Propagate LDAP-driven group-membership *removals* to SCIM — when a federated user is dropped from an LDAP group, emit a single-member REMOVE PATCH — by diffing the user's current groups against a per-component record of what was last propagated, on each import.

**Architecture:** No event fires for LDAP-driven membership changes, so the `onImportUserFromLDAP` hook is the only signal. The `SCOPE_GROUP` worker computes `current` = the user's groups (`getGroupsStream()` — the user's *own* groups, which does **not** enumerate any group's members and so cannot retrigger the recently-fixed re-import loop) and reads `stored` = a per-component multi-valued user attribute. `removed = stored − current` is sent as REMOVE PATCHes; additions stay re-asserted every import (unchanged). A removal that fails is kept in the stored set so the next import retries it; the attribute is rewritten to `current ∪ failed-removals` (or removed when empty).

**Tech Stack:** Java 17, Keycloak SPI (`LDAPStorageMapper`, `UserModel` attributes), Captain Goldfish SCIM SDK, JUnit 5 + Mockito (unit), Testcontainers + osixia/openldap + WireMock (integration). Build: Gradle (`./gradlew`).

**Spec:** `docs/superpowers/specs/2026-06-10-federated-membership-removal-design.md` — read it first.

**Branch:** `feat/federated-membership-removal` (already created off `main`).

---

## File Structure

- `src/main/java/sh/libre/scim/core/ScimClient.java` — `patchGroupMembership` return type `void → boolean` (consulted on REMOVE only); add `public String getComponentId()`.
- `src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java` — add `PROPAGATED_GROUPS_ATTR_PREFIX` constant; rewrite the `SCOPE_GROUP` dispatch lambda from add-only to the diff (removals + re-asserted additions + attribute write).
- `src/test/java/sh/libre/scim/core/EnsureGroupMembershipTest.java` — fix two `doNothing()` stubs broken by the `boolean` return.
- `src/test/java/sh/libre/scim/ldap/ScimLdapStorageMapperTest.java` — new unit tests for the diff (removal, retry-on-failure, idempotence, empty→remove).
- `src/integrationTest/java/sh/libre/scim/integration/ScimLdapGroupMembershipIT.java` — new end-to-end removal scenario + a member-REMOVE counter helper.
- `docs/roadmap.md` — mark membership removal done.
- `docs/ldap-federation-support.md` — update the membership-propagation description if it states "additions only".

---

## Chunk 1: Membership Removal

### Task 1: `patchGroupMembership` returns `boolean`; expose component id

The diff needs to (a) key the stored attribute by the SCIM component id, reachable from the worker's `ScimClient`, and (b) know whether a REMOVE actually applied. This task is pure plumbing on `ScimClient`; behavioral coverage lands in Tasks 2–3.

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (method `patchGroupMembership`, ~lines 352–411; add `getComponentId`)
- Modify: `src/test/java/sh/libre/scim/core/EnsureGroupMembershipTest.java:53,66`

- [ ] **Step 1: Add the `getComponentId` accessor**

In `ScimClient` (near the constructors), add — **`public`**, because the caller (`ScimLdapStorageMapper`) is in a different package (`sh.libre.scim.ldap`):

```java
/** The SCIM provider component id — stable across syncs/restarts. */
public String getComponentId() {
    return model.getId();
}
```

- [ ] **Step 2: Change `patchGroupMembership` to return `boolean`**

Change the signature from `public void patchGroupMembership(...)` to `public boolean patchGroupMembership(...)` and add returns. The returned value is **only consulted on the REMOVE path**; ADD/event callers ignore it. The three returns:

```java
public boolean patchGroupMembership(
        AdapterFactory<GroupModel, Group, GroupAdapter> factory,
        String groupId, String userId, boolean isAdd) {

    if (!this.model.get(GROUP_PATCH_OP_KEY, false)) {
        var group = session.groups().getGroupById(
                session.getContext().getRealm(), groupId);
        this.replace(factory, group);
        return true; // replace fallback re-sends the full member list — treat as applied
    }

    var adapter = getAdapter(factory);
    try (var span = TRACING.startSpan(
            isAdd ? "scim.group.member.add" : "scim.group.member.remove",
            "Group", scimApplicationBaseUrl)) {
        try {
            adapter.setId(groupId);
            var groupMapping = adapter.query("findById", groupId).getSingleResult();
            adapter.apply(groupMapping);

            var userMapping = adapter.query("findById", userId, "User").getSingleResult();
            String userExternalId = userMapping.getExternalId();
            String url = genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId());

            var retry = registry.retry("patchMembership");
            ServerResponse<Group> response = auth.sendWithAuthRefresh(
                () -> retry.executeSupplier(() -> {
                    try {
                        return adapter.toMembershipPatchBuilder(
                                scimRequestBuilder, url, userExternalId, isAdd)
                            .sendRequest();
                    } catch (ResponseException e) {
                        throw new RuntimeException(e);
                    }
                }));

            span.setHttpStatus(response.getHttpStatus());
            if (!response.isSuccess()) {
                LOGGER.warnf("Failed to PATCH membership for group %s / user %s: %d %s",
                        groupId, userId, response.getHttpStatus(), response.getResponseBody());
                return false; // genuine failure after resilience4j retries
            }
            return true; // applied
        } catch (NoResultException e) {
            span.recordError(e);
            LOGGER.infof("Skipping membership patch: no SCIM mapping for group %s or user %s",
                    groupId, userId);
            return true; // nothing to remove → applied (do not retry a phantom forever)
        }
    }
}
```

Leave `ensureGroupMembership` unchanged — its `this.patchGroupMembership(factory, groupId, userId, true);` statement still compiles and correctly ignores the new return.

- [ ] **Step 3: Fix the broken `doNothing()` stubs**

`EnsureGroupMembershipTest` spies `ScimClient` and stubs `patchGroupMembership` with `doNothing()`, which no longer compiles for a `boolean`-returning method. At both `EnsureGroupMembershipTest.java:53` and `:66`, replace:

```java
doNothing().when(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));
```
with:
```java
doReturn(true).when(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));
```

Add `import static org.mockito.Mockito.doReturn;`. **Keep** the `import static org.mockito.Mockito.doNothing;` — line 52 still uses `doNothing().when(client).provisionGroupForMembership(...)`. This task only *adds* `doReturn` and edits the two `patchGroupMembership` stubs; nothing is removed.

- [ ] **Step 4: Compile + run the affected unit tests**

Run: `./gradlew test --tests 'sh.libre.scim.core.EnsureGroupMembershipTest' --tests 'sh.libre.scim.core.GroupMembershipPatchTest'`
Expected: PASS. (`GroupMembershipPatchTest` exercises `toMembershipPatchBuilder` directly with no HTTP, so it is unaffected by the return-type change; it must still pass.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sh/libre/scim/core/ScimClient.java \
        src/test/java/sh/libre/scim/core/EnsureGroupMembershipTest.java
git commit -m "feat(group): patchGroupMembership signals applied; expose component id"
```

---

### Task 2: Diff-based removal in the mapper

Rewrite the `SCOPE_GROUP` dispatch lambda from add-only to the stored-set diff. This is the core of the feature. TDD: the four unit tests below pin removal, retry-on-failure, idempotence, and empty→remove before the implementation exists.

**Files:**
- Modify: `src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java` (constant + the `SCOPE_GROUP` lambda, lines 60–71)
- Test: `src/test/java/sh/libre/scim/ldap/ScimLdapStorageMapperTest.java`

- [ ] **Step 1: Write the failing unit tests**

Add these to `ScimLdapStorageMapperTest`. They mirror the file's existing capture pattern (capture the `BiConsumer` handed to `runAsync`, then invoke it with mocks). Capture the **`SCOPE_GROUP`** consumer specifically. Imports to add (verified against the current file's import block, lines 3–32): `java.util.stream.Stream`, `org.keycloak.models.GroupModel`, `sh.libre.scim.core.GroupAdapter`, `static org.mockito.ArgumentMatchers.any`, `static org.mockito.ArgumentMatchers.argThat`, `static org.mockito.Mockito.never`. (`org.keycloak.models.UserProvider`, `KeycloakContext`, `RealmModel`, `ScimClient`, `ScimDispatcher`, `ArgumentCaptor`, `BiConsumer` are already imported and reused by the helpers below. The `argThat` lambda parameter is an inferred `List<String>` — no `java.util.List` import is needed. `GroupProvider` is **not** used.)

**Strictness:** the class is currently strict (`@ExtendWith(MockitoExtension.class)` with no settings). These tests stub `client.getComponentId()`, `user.getGroupsStream()`, and `user.getAttributeStream(...)`, and capture the `SCOPE_USER` consumer without invoking it. To avoid `UnnecessaryStubbingException` on the shared/uninvoked stubs, add `@MockitoSettings(strictness = Strictness.LENIENT)` to the class (import `org.mockito.junit.jupiter.MockitoSettings` and `org.mockito.quality.Strictness`). The red signal for Step 2 then comes from the **verifies** (the missing `patchGroupMembership(...,false)` / `setAttribute` / `removeAttribute` calls), not from stubbing strictness.

A small helper builds the worker mocks and returns the captured `SCOPE_GROUP` consumer:

```java
@SuppressWarnings({"unchecked", "rawtypes"})
private BiConsumer<ScimClient, KeycloakSession> captureGroupConsumer(String userId) {
    when(user.getId()).thenReturn(userId);
    mapper.onImportUserFromLDAP(ldapObject, user, realm, false);
    ArgumentCaptor<BiConsumer> captor = ArgumentCaptor.forClass(BiConsumer.class);
    verify(dispatcher).runAsync(eq(ScimDispatcher.SCOPE_GROUP), captor.capture());
    return (BiConsumer<ScimClient, KeycloakSession>) captor.getValue();
}

private KeycloakSession workerSessionReturning(String userId) {
    var ws = mock(KeycloakSession.class);
    var ctx = mock(KeycloakContext.class);
    var wRealm = mock(RealmModel.class);
    var users = mock(UserProvider.class);
    when(ws.getContext()).thenReturn(ctx);
    when(ctx.getRealm()).thenReturn(wRealm);
    when(ws.users()).thenReturn(users);
    when(users.getUserById(wRealm, userId)).thenReturn(user);
    return ws;
}

private GroupModel group(String id) {
    var g = mock(GroupModel.class);
    when(g.getId()).thenReturn(id);
    return g;
}
```

Tests (note `@MockitoSettings(strictness = Strictness.LENIENT)` on the class may be needed since the SCOPE_USER consumer is captured-but-not-invoked; if the existing class isn't lenient, add it or use `lenient()` on the shared stubs):

```java
@Test
void removesGroupsTheUserHasLeft() {
    var consumer = captureGroupConsumer("u1");
    var ws = workerSessionReturning("u1");
    var client = mock(ScimClient.class);
    when(client.getComponentId()).thenReturn("comp-1");
    when(user.getGroupsStream()).thenReturn(Stream.of(group("A")));       // current = {A}
    when(user.getAttributeStream("scim-propagated-groups-comp-1"))
            .thenReturn(Stream.of("A", "B"));                              // stored = {A,B}
    when(client.patchGroupMembership(any(), eq("B"), eq("u1"), eq(false))).thenReturn(true);

    consumer.accept(client, ws);

    verify(client).patchGroupMembership(any(), eq("B"), eq("u1"), eq(false)); // removed B
    verify(client).ensureGroupMembership(any(), eq("A"), eq("u1"));           // re-asserted A
    verify(user).setAttribute(eq("scim-propagated-groups-comp-1"),
            argThat(l -> l.size() == 1 && l.contains("A")));
}

@Test
void keepsFailedRemovalInStoredSet() {
    var consumer = captureGroupConsumer("u1");
    var ws = workerSessionReturning("u1");
    var client = mock(ScimClient.class);
    when(client.getComponentId()).thenReturn("comp-1");
    when(user.getGroupsStream()).thenReturn(Stream.of(group("A")));       // current = {A}
    when(user.getAttributeStream("scim-propagated-groups-comp-1"))
            .thenReturn(Stream.of("A", "B"));                              // stored = {A,B}
    when(client.patchGroupMembership(any(), eq("B"), eq("u1"), eq(false))).thenReturn(false);

    consumer.accept(client, ws);

    // B's removal failed → keep it so the next import retries: stored = {A,B}
    verify(user).setAttribute(eq("scim-propagated-groups-comp-1"),
            argThat(l -> l.size() == 2 && l.contains("A") && l.contains("B")));
}

@Test
void noMembershipChangeEmitsNoRemoval() {
    var consumer = captureGroupConsumer("u1");
    var ws = workerSessionReturning("u1");
    var client = mock(ScimClient.class);
    when(client.getComponentId()).thenReturn("comp-1");
    when(user.getGroupsStream()).thenReturn(Stream.of(group("A"), group("B")));
    when(user.getAttributeStream("scim-propagated-groups-comp-1"))
            .thenReturn(Stream.of("A", "B"));

    consumer.accept(client, ws);

    verify(client, never()).patchGroupMembership(any(), any(), eq("u1"), eq(false));
    verify(client).ensureGroupMembership(any(), eq("A"), eq("u1"));
    verify(client).ensureGroupMembership(any(), eq("B"), eq("u1"));
}

@Test
void removesAttributeWhenNoGroupsRemain() {
    var consumer = captureGroupConsumer("u1");
    var ws = workerSessionReturning("u1");
    var client = mock(ScimClient.class);
    when(client.getComponentId()).thenReturn("comp-1");
    when(user.getGroupsStream()).thenReturn(Stream.empty());              // current = {}
    when(user.getAttributeStream("scim-propagated-groups-comp-1"))
            .thenReturn(Stream.of("A"));                                  // stored = {A}
    when(client.patchGroupMembership(any(), eq("A"), eq("u1"), eq(false))).thenReturn(true);

    consumer.accept(client, ws);

    verify(client).patchGroupMembership(any(), eq("A"), eq("u1"), eq(false));
    verify(user).removeAttribute("scim-propagated-groups-comp-1");
    verify(client, never()).ensureGroupMembership(any(), any(), any());
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests 'sh.libre.scim.ldap.ScimLdapStorageMapperTest'`
Expected: the four new tests FAIL on their verifies — the current lambda is add-only, so it never calls `patchGroupMembership(...,false)`, `getAttributeStream`, `setAttribute`, or `removeAttribute`. (Existing tests still pass; class is now LENIENT so the unused stubs don't themselves error.)

- [ ] **Step 3: Implement the diff lambda**

In `ScimLdapStorageMapper`, add the constant near `LAST_SEEN_ATTRIBUTE`:

```java
public static final String PROPAGATED_GROUPS_ATTR_PREFIX = "scim-propagated-groups-";
```

Add imports: `java.util.HashSet`, `java.util.Set`, `java.util.stream.Collectors`, `org.keycloak.models.GroupModel` (already imported), `java.util.List` (already imported).

Replace the `SCOPE_GROUP` dispatch block (current lines 66–71) with:

```java
// Reconcile the user's group memberships. LDAP-driven changes fire no
// GROUP_MEMBERSHIP event, so this hook is the only signal.
//   - removals: groups we last propagated but the user has left -> REMOVE PATCH
//   - additions: re-assert the user's current memberships (idempotent)
// `getGroupsStream()` reads the user's OWN groups; it does NOT enumerate any
// group's members, so it cannot retrigger the federated re-import loop.
dispatcher.runAsync(ScimDispatcher.SCOPE_GROUP, (client, workerSession) -> {
    var u = workerSession.users().getUserById(
            workerSession.getContext().getRealm(), userId);
    if (u == null) return;

    Set<String> current = u.getGroupsStream()
            .map(GroupModel::getId)
            .collect(Collectors.toSet());

    String attr = PROPAGATED_GROUPS_ATTR_PREFIX + client.getComponentId();
    Set<String> stored = u.getAttributeStream(attr).collect(Collectors.toSet());

    // Removals — keep any whose REMOVE did not apply, so the next import retries.
    Set<String> kept = new HashSet<>();
    stored.stream().filter(gid -> !current.contains(gid)).forEach(gid -> {
        if (!client.patchGroupMembership(GroupAdapter::new, gid, userId, false)) {
            kept.add(gid);
        }
    });

    // Additions — re-assert current memberships (idempotent).
    current.forEach(gid ->
            client.ensureGroupMembership(GroupAdapter::new, gid, userId));

    // Record what SCIM now reflects = current ∪ failed-removals.
    Set<String> next = new HashSet<>(current);
    next.addAll(kept);
    if (next.isEmpty()) {
        u.removeAttribute(attr);
    } else {
        u.setAttribute(attr, List.copyOf(next));
    }
});
```

Also update the stale comment block above the old dispatch (lines 60–65) — it says "Additions only" — to reflect that removals now propagate via the diff.

- [ ] **Step 4: Run the unit tests to verify they pass**

Run: `./gradlew test --tests 'sh.libre.scim.ldap.ScimLdapStorageMapperTest'`
Expected: PASS (all, including the four new ones and the existing SCOPE_USER/last-seen tests).

- [ ] **Step 5: Full unit-test sweep + LSP diagnostics**

Run: `./gradlew test`
Expected: PASS. Check no compile warnings/errors in the two modified mains.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java \
        src/test/java/sh/libre/scim/ldap/ScimLdapStorageMapperTest.java
git commit -m "feat(group): propagate LDAP-driven membership removals via stored-set diff"
```

---

### Task 3: End-to-end removal integration test

Prove the removal propagates through a real federated sync and — critically — that the diff path does **not** reawaken the re-import loop (the spec's loop-safety guard).

**Files:**
- Modify: `src/integrationTest/java/sh/libre/scim/integration/ScimLdapGroupMembershipIT.java`

- [ ] **Step 1: Read the existing IT + base harness to reuse**

Read `ScimLdapGroupMembershipIT.java` in full (it already has the `newRealmWithScimAndLdapGroups` setup, the `stubScim*Ok()` stubs, and the private `syncUntilMembershipsAdded(TestRealm r)` convergence helper this scenario reuses), plus these `IntegrationTestBase.java` helpers:
- `memberAddPatchCount()` (468–475) — `wireMock.countRequestsMatching(patchRequestedFor(urlPathMatching("/Groups/.*")).withRequestBody(containing("\"op\":\"add\"")).withRequestBody(containing("members")).build()).getCount()`.
- `modifyLdapAttribute(dn, attr, value)` (534–544) — a JNDI **`REPLACE_ATTRIBUTE`** of `attr` to a single `value` (throws `NamingException`).
- `sleepQuietly(int seconds)` — used by the existing resync loop.
- The sync trigger used throughout the IT: `r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync")` (the string `"triggerFullSync"` is an *argument*, not a method).
- The seeded group: `cn=engineers,ou=groups,dc=test,dc=local`, a `groupOfNames` with `member: uid=alice,...` and `member: uid=bob,...` (`seed.ldif:33–37`).

Key constraint: a `groupOfNames` requires ≥1 `member`, so the scenario removes **alice** and leaves **bob** — it must not empty the group.

- [ ] **Step 2: Add a member-REMOVE counter helper**

Mirror `memberAddPatchCount` exactly, swapping the op. Add to `IntegrationTestBase` (next to `memberAddPatchCount`) so it shares the established idiom:

```java
/** Current count of single-member delta remove PATCHes (op=remove) to /Groups/*. */
protected int memberRemovePatchCount() {
    return wireMock.countRequestsMatching(
        patchRequestedFor(urlPathMatching("/Groups/.*"))
            .withRequestBody(containing("\"op\":\"remove\""))
            .withRequestBody(containing("members"))
            .build()
    ).getCount();
}
```

- [ ] **Step 3: Write the failing removal scenario**

Use the file's real harness — `newRealmWithScimAndLdapGroups(cfg -> ...)`, the `syncUntilMembershipsAdded(r)` convergence helper, `r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync")` as the sync trigger, `sleepQuietly(int)`, and JUnit `assertTrue` (already imported). There is **no** `triggerFullSync()` method and AssertJ is not imported here — do not introduce either. Dropping alice is a `REPLACE_ATTRIBUTE` of `member` to bob's DN only (`modifyLdapAttribute`), which keeps the `groupOfNames` non-empty. Add this `@Test` to `ScimLdapGroupMembershipIT`:

```java
@Test
void removingUserFromLdapGroupEmitsRemovePatch() throws Exception {
    stubScimUserCreateOk();
    stubScimGroupCreateOk();
    stubScimGroupPatchOk();

    var r = newRealmWithScimAndLdapGroups(cfg -> {
        cfg.putSingle("propagation-user", "true");
        cfg.putSingle("propagation-group", "true");
        cfg.putSingle("group-patchOp", "true");
    });

    // 1. Converge alice+bob as members of engineers (SCIM holds the group + both
    //    members; each user's stored attribute records engineers).
    syncUntilMembershipsAdded(r);
    assertTrue(memberRemovePatchCount() == 0,
        "no removals expected before dropping a member, got " + memberRemovePatchCount());

    // 2. Drop alice from the LDAP group (REPLACE member -> bob only; keeps bob).
    modifyLdapAttribute("cn=engineers,ou=groups,dc=test,dc=local",
        "member", "uid=bob,ou=users,dc=test,dc=local");

    // 3. Re-sync until alice's removal is observed. Her next import sees engineers
    //    gone from her current groups while her stored set still records it ->
    //    single-member REMOVE PATCH. (Mirror syncUntilMembershipsAdded's loop.)
    long deadline = System.currentTimeMillis() + 120_000;
    while (memberRemovePatchCount() < 1 && System.currentTimeMillis() < deadline) {
        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
        sleepQuietly(5);
    }
    assertTrue(memberRemovePatchCount() >= 1,
        "expected >=1 member-remove PATCH after dropping alice, got " + memberRemovePatchCount());
}
```

Keep the assertion a lower bound (`>= 1`) — the resync loop may re-trigger; the guard against *spurious* re-removal on an unchanged sync is the unit test `noMembershipChangeEmitsNoRemoval`, not this IT.

- [ ] **Step 4: Add the loop-safety guard (non-optional)**

The central hazard is reawakening the re-import loop, whose signature is **per-sync** explosion — it produced ~1,388 member PATCHes from a *single* affected sync for 2 members. Bounded re-assertion, by contrast, sends only ~1–2 PATCHes per sync. So measure the **delta across one additional clean sync** (robust to however many convergence resyncs ran), not a total. Append to the test:

```java
    // Loop-safety: one more sync must add only a bounded number of member
    // PATCHes (re-assertion is ~1-2 per member). A re-import regression would
    // add thousands in this single sync.
    int before = memberAddPatchCount() + memberRemovePatchCount();
    r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
    sleepQuietly(10);
    int delta = (memberAddPatchCount() + memberRemovePatchCount()) - before;
    assertTrue(delta < 20,
        "a single sync must add only bounded member PATCHes (loop regression -> "
            + "thousands), got " + delta);
```

(There is no `onImportUserFromLDAP` invocation counter exposed in the integration tests, so the per-sync member-PATCH delta is the guard.)

- [ ] **Step 5: Run the integration test**

Run: `./gradlew integrationTest --tests 'sh.libre.scim.integration.ScimLdapGroupMembershipIT'`
Expected: PASS. NOTE: local IT runs use warm/reused containers and are timing-non-representative (~seconds locally vs minutes in CI) — a green local run is necessary but not sufficient; CI is the real gate. If the removal doesn't converge locally, widen the resync deadline rather than weakening the assertion.

- [ ] **Step 6: Commit**

```bash
git add src/integrationTest/java/sh/libre/scim/integration/ScimLdapGroupMembershipIT.java
git commit -m "test(group): integration-cover LDAP-driven membership removal + loop safety"
```

---

### Task 4: Documentation

**Files:**
- Modify: `docs/roadmap.md` (the "LDAP-federated group membership" bullet)
- Modify: `docs/ldap-federation-support.md` (if it states membership is additions-only)

- [ ] **Step 1: Update the roadmap bullet**

Grep `docs/roadmap.md` for the bullet by text (`grep -n "deferred reconciler-style follow-up" docs/roadmap.md`) rather than trusting a line range. In the "LDAP-federated group membership" entry, replace the closing sentences that defer removal:

> Membership REMOVAL (user dropped from an LDAP group) is not yet handled; it is a deferred reconciler-style follow-up.

with a "done" note describing the mechanism actually built:

> Membership REMOVAL now propagates too: on each import the `SCOPE_GROUP` worker diffs the user's current groups against a per-component record of previously-propagated groups (`scim-propagated-groups-<componentId>`, a user attribute) and sends a single-member REMOVE PATCH for each group the user has left. Additions stay re-asserted every import; only removals are diff-driven. A failed REMOVE is retained in the stored set and retried on the next import. The now-empty group is reaped by the member-presence reconciler. Verified by `ScimLdapStorageMapperTest` (diff/retry/idempotence units) and `ScimLdapGroupMembershipIT` (end-to-end remove + loop safety).

Do **not** hand-edit `CHANGELOG.md` — release-please regenerates it from the conventional commits (see `feedback_release_please`).

- [ ] **Step 2: Sync `docs/ldap-federation-support.md` if needed**

Grep it for "additions only" / "additions-only" / membership-propagation prose. If present, update to note removals propagate via the stored-set diff. If the doc doesn't describe membership at that level, skip (note the skip in the commit message).

- [ ] **Step 3: Commit**

```bash
git add docs/roadmap.md docs/ldap-federation-support.md
git commit -m "docs(group): mark federated membership removal done"
```

---

## Final verification

- [ ] `./gradlew test` — all unit tests green.
- [ ] `./gradlew integrationTest --tests 'sh.libre.scim.integration.ScimLdapGroupMembershipIT'` — green locally (CI is the real gate).
- [ ] Then use **superpowers:finishing-a-development-branch**.
