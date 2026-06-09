# Group Membership Delta PATCH — Design

## Problem

When a single user joins or leaves a group, the `GROUP_MEMBERSHIP` admin
event handler calls `ScimClient.replace(GroupAdapter::new, group)`. Even
when `group-patchOp=true`, this path:

1. Loads the **entire current member list** from the Keycloak DB
   (`getGroupMembersStream`).
2. Issues a SCIM `PATCH members REPLACE [all N members]` — the full list,
   regardless of how many actually changed.

For a 10 000-member group, every single membership change — one user
added or removed — re-serializes and re-sends all 10 000 members.
`ScimClientMetrics` shows HTTP send is ~98% of per-operation cost, so
payload size is the dominant term.

The event already carries exactly the right data: `userId`, `groupId`, and
`OperationType.CREATE` / `OperationType.DELETE`. It is thrown away.

## Goal

For `GROUP_MEMBERSHIP` events, send a **minimal PATCH**: a single-member
`ADD` or `REMOVE` operation against the already-synced remote group.
Never re-fetch or re-send the full membership list for a membership change.

## Non-goals

- **`group-patchOp=false` deployments.** Servers that don't support PATCH
  at all are unchanged — delta patch falls back to full `replace()` when
  `group-patchOp=false`. No behavioral change for those operators.
- **Group attribute changes** (name, etc.). The `GROUP` event path that
  calls `replace()` for attribute updates is untouched — a name change
  still sends a full PUT/PATCH. Only the `GROUP_MEMBERSHIP` path changes.
- **LDAP-federated group membership.** The `onImportGroupFromLDAP` gap
  (no SCIM propagation for LDAP-imported groups) is a separate roadmap
  item; this design doesn't address it.
- **Bulk batching.** Multiple membership changes in quick succession still
  produce one PATCH per change. Coalescing is a separate optimisation.

## Decisions

1. **`group-patchOp=false` fallback inside `ScimClient`.** The
   `GROUP_MEMBERSHIP` handler in the event listener always calls
   `patchGroupMembership()`. The method itself checks the component
   config and delegates to the existing `replace()` if `group-patchOp`
   is false. This keeps the routing decision co-located with the other
   `group-patchOp` logic and avoids leaking component-model concerns into
   the event listener.

2. **REMOVE uses an RFC 7644 filter path.**
   `"members[value eq \"<externalId>\"]"` removes exactly the one member
   without touching the rest. This is the correct RFC 7644 form. Using
   `PatchOp.REMOVE` with a `valueNodes` list is ambiguous across
   implementations; the filter path is unambiguous.

3. **ADD uses `path=members, op=ADD, value=[{value: externalId}]`.**
   RFC 7644 §3.5.2.1 — straightforward, widely supported.

4. **Retry.** `patchGroupMembership()` uses the shared `RetryRegistry`
   (same policy as `create`/`replace`/`delete`) so transient 5xx/429
   failures are retried with exponential backoff.

5. **Missing-mapping skip.** If the group or the user has no SCIM mapping
   (e.g. the user was never synced because their email wasn't verified),
   `NoResultException` is caught and the operation is skipped with a
   `LOGGER.infof`. This mirrors the existing `delete()` behaviour.

6. **No new adapter abstract method.** `toMembershipPatchBuilder()` is
   a concrete method on `GroupAdapter` only, not promoted to the
   `Adapter` abstract API. Membership delta is a group-specific concept;
   `UserAdapter` has no equivalent.

## Design

### New `ScimClient.patchGroupMembership()`

```java
public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>>
void patchGroupMembership(
        AdapterFactory<GroupModel, Group, GroupAdapter> factory,
        String groupId,
        String userId,
        boolean isAdd) {

    if (!this.model.get("group-patchOp", false)) {
        // Server doesn't support PATCH — fall back to full replace.
        var group = session.groups().getGroupById(
                session.getContext().getRealm(), groupId);
        this.replace((AdapterFactory) factory, group);
        return;
    }

    var adapter = getAdapter((AdapterFactory) factory);

    try (var span = TRACING.startSpan(
            isAdd ? "scim.group.member.add" : "scim.group.member.remove",
            "Group", scimApplicationBaseUrl)) {
        try {
            adapter.setId(groupId);
            var groupMapping = adapter.query("findById", groupId).getSingleResult();
            adapter.apply(groupMapping);

            var userMapping = adapter.query("findById", userId, "User")
                    .getSingleResult();
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
                        groupId, userId, response.getHttpStatus(),
                        response.getResponseBody());
            }
        } catch (NoResultException e) {
            span.recordError(e);
            LOGGER.infof("Skipping membership patch: no SCIM mapping for group %s"
                    + " or user %s", groupId, userId);
        }
    }
}
```

### New `GroupAdapter.toMembershipPatchBuilder()`

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
        // RFC 7644 §3.5.2.2: filter path targets the specific member.
        patchBuilder.addOperation()
            .path("members[value eq \"" + userExternalId + "\"]")
            .op(PatchOp.REMOVE)
            .next()
            .build();
    }
    return patchBuilder;
}
```

### Event listener change

In `ScimEventListenerProvider`, the `GROUP_MEMBERSHIP` block:

```java
// Before
var group = getGroup(groupId);
dispatcher.run(SCOPE_GROUP, client -> client.replace(GroupAdapter::new, group));

// After
boolean isAdd = event.getOperationType() == OperationType.CREATE;
dispatcher.run(SCOPE_GROUP,
    client -> client.patchGroupMembership(GroupAdapter::new, groupId, userId, isAdd));
```

The `client.replace(UserAdapter::new, user)` line that follows (updating the
user's group membership on the user object) is **unchanged**.

## Testing

### Unit — `ScimClientMembershipPatchTest` (new)

Fast, no Docker, stubs the HTTP layer with WireMock (same pattern as
`ScimClientRetryTest`).

- `patchMembership_addSendsSingleMemberAdd()` — stubs `PATCH /Groups/ext-1`
  to return `200`; asserts the request body contains
  `"op":"add","path":"members"` with exactly one member value and no other
  members.
- `patchMembership_removeSendsSingleMemberRemove()` — same stub; asserts
  `"op":"remove","path":"members[value eq ...]"`.
- `patchMembership_fallsBackToReplaceWhenPatchOpDisabled()` — verifies
  that when `group-patchOp=false` the method issues a `PUT /Groups/...`
  (full replace) instead of a `PATCH`.
- `patchMembership_skipsWhenGroupMappingMissing()` — no mapping in the DB;
  assert no HTTP request is sent.
- `patchMembership_skipsWhenUserMappingMissing()` — group mapping exists,
  user mapping missing; assert no HTTP request is sent.

### Integration — extend `ScimGroupPropagationIT`

`ScimGroupPropagationIT` currently verifies group membership propagation
by checking that a `PUT /Groups/...` fires when a user is added. Extend
it:

- `groupMembershipAdd_withPatchOp_sendsDeltaAdd()` — configures
  `group-patchOp=true`, adds one user to a group, captures the WireMock
  request, asserts: method is `PATCH`, body contains `"op":"add"` with
  exactly one member, does **not** contain other pre-existing members.
- `groupMembershipRemove_withPatchOp_sendsDeltaRemove()` — same setup,
  removes the user, asserts `"op":"remove"` with the filter path.

## Docs

- Update `docs/roadmap.md`: mark the "Incremental PATCH delta" bullet
  under Group propagation as done.
- Update `docs/tracing.md`: add `scim.group.member.add` and
  `scim.group.member.remove` to the span inventory table.
