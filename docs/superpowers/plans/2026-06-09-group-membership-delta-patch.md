# Group Membership Delta PATCH — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For `GROUP_MEMBERSHIP` events, send a minimal single-member PATCH (ADD/REMOVE) instead of a full-member-list `replace()`, eliminating the "re-send all N members on one membership change" cost.

**Architecture:** One new `GroupAdapter.toMembershipPatchBuilder()` building a minimal PATCH; one new `ScimClient.patchGroupMembership()` that routes (PATCH when `group-patchOp=true`, else fall back to `replace`), looks up group + user mappings, and sends via the shared retry/auth path; one event-listener rewire on the `GROUP_MEMBERSHIP` branch. `replace()`, the `GROUP` attribute path, and `group-patchOp=false` deployments are untouched.

**Tech Stack:** Java 21, Gradle (Kotlin DSL), JUnit 5 + AssertJ + Mockito + WireMock (unit), Testcontainers + WireMock (integration), Captain-Goldfish SCIM SDK, resilience4j, Keycloak SPI.

**Spec:** `docs/superpowers/specs/2026-06-09-group-membership-delta-patch-design.md`

---

## File Structure

**Modified (main):**
- `src/main/java/sh/libre/scim/core/GroupAdapter.java` — add `toMembershipPatchBuilder(...)`.
- `src/main/java/sh/libre/scim/core/ScimClient.java` — add `patchGroupMembership(...)`.
- `src/main/java/sh/libre/scim/event/ScimEventListenerProvider.java` — rewire the `GROUP_MEMBERSHIP` branch.

**Created (test):**
- `src/test/java/sh/libre/scim/core/ScimClientMembershipPatchTest.java` — 5 unit cases.

**Modified (test):**
- `src/integrationTest/java/sh/libre/scim/integration/ScimGroupPropagationIT.java` — 2 delta-patch cases.

**Modified (docs):**
- `docs/roadmap.md` — mark "Incremental PATCH delta" done.
- `docs/tracing.md` — add the two new span names.

---

## Chunk 1: Adapter + client + wiring

### Task 0: Green baseline

- [ ] **Step 1:** `./gradlew test` → BUILD SUCCESSFUL. If red, STOP and surface output.
- [ ] **Step 2:** `./gradlew compileIntegrationTestJava` → BUILD SUCCESSFUL.

---

### Task 1: `GroupAdapter.toMembershipPatchBuilder()`

**Files:** Modify `src/main/java/sh/libre/scim/core/GroupAdapter.java`.

- [ ] **Step 1:** Add the method (imports `PatchOp`, `Member`, `PatchBuilder`, `List` already present):

```java
public PatchBuilder<Group> toMembershipPatchBuilder(
        ScimRequestBuilder scimRequestBuilder,
        String url,
        String userExternalId,
        boolean isAdd) {
    var patchBuilder = scimRequestBuilder.patch(url, Group.class);
    if (isAdd) {
        patchBuilder.addOperation()
            .path("members")
            .op(PatchOp.ADD)
            .valueNodes(List.of(Member.builder().value(userExternalId).build()))
            .next()
            .build();
    } else {
        // RFC 7644 §3.5.2.2: filter path targets exactly this member.
        patchBuilder.addOperation()
            .path("members[value eq \"" + userExternalId + "\"]")
            .op(PatchOp.REMOVE)
            .next()
            .build();
    }
    return patchBuilder;
}
```

- [ ] **Step 2:** `./gradlew compileJava` → BUILD SUCCESSFUL.

---

### Task 2: `ScimClient.patchGroupMembership()`

**Files:** Modify `src/main/java/sh/libre/scim/core/ScimClient.java`.

- [ ] **Step 1:** Add imports if missing: `org.keycloak.models.GroupModel`, `de.captaingoldfish.scim.sdk.common.resources.Group`.

- [ ] **Step 2:** Add the method (after `delete`). The double-typed factory param lets the event listener keep passing `GroupAdapter::new`:

```java
public void patchGroupMembership(
        AdapterFactory<GroupModel, Group, GroupAdapter> factory,
        String groupId, String userId, boolean isAdd) {

    if (!this.model.get("group-patchOp", false)) {
        var group = session.groups().getGroupById(
                session.getContext().getRealm(), groupId);
        this.replace(factory, group);
        return;
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
            }
        } catch (NoResultException e) {
            span.recordError(e);
            LOGGER.infof("Skipping membership patch: no SCIM mapping for group %s or user %s",
                    groupId, userId);
        }
    }
}
```

- [ ] **Step 3:** `./gradlew compileJava` → BUILD SUCCESSFUL. Confirm `replace(factory, group)` typechecks against the `AdapterFactory<GroupModel, Group, GroupAdapter>` param (it does — `replace`'s signature is generic over `<M, S, A>`).

---

### Task 3: Rewire the event listener

**Files:** Modify `src/main/java/sh/libre/scim/event/ScimEventListenerProvider.java` (`GROUP_MEMBERSHIP` block, ~`:116-124`).

- [ ] **Step 1:** Replace the group `replace` dispatch. Keep the user `replace` line:

```java
if (event.getResourceType() == ResourceType.GROUP_MEMBERSHIP) {
    var userId = matcher.group(1);
    var groupId = matcher.group(2);
    LOGGER.infof("%s %s from %s", event.getOperationType(), userId, groupId);
    boolean isAdd = event.getOperationType() == OperationType.CREATE;
    dispatcher.run(ScimDispatcher.SCOPE_GROUP,
        client -> client.patchGroupMembership(GroupAdapter::new, groupId, userId, isAdd));
    var user = getUser(userId);
    dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter::new, user));
}
```

- [ ] **Step 2:** `./gradlew compileJava` → BUILD SUCCESSFUL.

- [ ] **Step 3:** Commit.

```bash
git add src/main/java/sh/libre/scim/core/GroupAdapter.java \
        src/main/java/sh/libre/scim/core/ScimClient.java \
        src/main/java/sh/libre/scim/event/ScimEventListenerProvider.java
git commit -m "feat(group): send delta PATCH for single membership changes"
```

---

## Chunk 2: Tests

### Task 4: Unit — `ScimClientMembershipPatchTest`

**Files:** Create `src/test/java/sh/libre/scim/core/ScimClientMembershipPatchTest.java`. Model on `ScimClientRetryTest` (WireMock + seeded `ScimResource` mappings).

- [ ] **Step 1:** Write the five cases from the spec:
  - `patchMembership_addSendsSingleMemberAdd` — body has `"op":"add"`, `"path":"members"`, exactly one member.
  - `patchMembership_removeSendsSingleMemberRemove` — body has `"op":"remove"`, path `members[value eq "..."]`.
  - `patchMembership_fallsBackToReplaceWhenPatchOpDisabled` — `group-patchOp=false` → a `PUT /Groups/...` fires, no `PATCH`.
  - `patchMembership_skipsWhenGroupMappingMissing` — no group mapping → zero HTTP requests.
  - `patchMembership_skipsWhenUserMappingMissing` — group mapping present, user absent → zero HTTP requests.

- [ ] **Step 2:** `./gradlew test --tests "sh.libre.scim.core.ScimClientMembershipPatchTest"` → PASS.

- [ ] **Step 3:** Full unit suite `./gradlew test` → BUILD SUCCESSFUL.

- [ ] **Step 4:** Commit.

```bash
git add src/test/java/sh/libre/scim/core/ScimClientMembershipPatchTest.java
git commit -m "test(group): unit-cover membership delta PATCH and fallback paths"
```

---

### Task 5: Integration — extend `ScimGroupPropagationIT`

**Files:** Modify `src/integrationTest/java/sh/libre/scim/integration/ScimGroupPropagationIT.java`.

- [ ] **Step 1:** Add two cases (component configured `group-patchOp=true`):
  - `groupMembershipAdd_withPatchOp_sendsDeltaAdd` — add one user; assert captured request is `PATCH`, body `"op":"add"`, one member, no pre-existing members in body.
  - `groupMembershipRemove_withPatchOp_sendsDeltaRemove` — remove the user; assert `PATCH`, `"op":"remove"`, filter path.

- [ ] **Step 2:** `./gradlew integrationTest --tests "sh.libre.scim.integration.ScimGroupPropagationIT"` (Docker) → BUILD SUCCESSFUL.

- [ ] **Step 3:** Commit.

```bash
git add src/integrationTest/java/sh/libre/scim/integration/ScimGroupPropagationIT.java
git commit -m "test(group): integration-cover membership delta PATCH"
```

---

## Chunk 3: Docs + full verification

### Task 6: Docs

**Files:** `docs/roadmap.md`, `docs/tracing.md`.

- [ ] **Step 1:** In `docs/roadmap.md`, mark the "Incremental PATCH delta" bullet done (mirror the resilience entries' `_Done._` style with a one-line note on what shipped).
- [ ] **Step 2:** In `docs/tracing.md`, add `scim.group.member.add` and `scim.group.member.remove` rows to the span-inventory table.
- [ ] **Step 3:** Commit.

```bash
git add docs/roadmap.md docs/tracing.md
git commit -m "docs(group): mark membership delta PATCH done; add new span names"
```

---

### Task 7: Full verification

- [ ] **Step 1:** `./gradlew test` → BUILD SUCCESSFUL.
- [ ] **Step 2:** `./gradlew integrationTest` (Docker) → BUILD SUCCESSFUL. Key coverage: `ScimGroupPropagationIT` (delta add/remove + the untouched full-replace name path).
- [ ] **Step 3:** `grep -rn "client.replace(GroupAdapter" src/main/java` → only the `GROUP` attribute-update and the `USER`-create-joins-group sites remain; the `GROUP_MEMBERSHIP` branch no longer calls `replace`.
