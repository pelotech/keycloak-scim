package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end propagation scenarios for groups: create, update, delete, and
 * membership changes flow from Keycloak admin REST events through
 * {@code ScimEventListenerProvider} to the SCIM sink.
 *
 * <p>All tests configure the SCIM provider with {@code propagation-group=true}
 * and enable the {@code scim} event listener on the realm. The event listener
 * fires the group adapter on GROUP_CREATE / GROUP_UPDATE / GROUP_DELETE
 * events, and on GROUP_MEMBERSHIP it always dispatches a group write, plus a
 * user replace only when the group confers a {@code scim="true"} role (a
 * plain group can't change a member's SCIM resource, so the replace is
 * skipped).
 */
class ScimGroupPropagationIT extends IntegrationTestBase {

    private TestRealm setupRealmWithGroupPropagation() {
        var r = newRealmWithScimAndLdapAndConfig(cfg ->
            cfg.putSingle("propagation-group", "true"));
        enableScimEventListener(r.realm());
        return r;
    }

    @Test
    void adminCreatedGroupFiresScimPost() {
        stubScimGroupCreateOk();
        var r = setupRealmWithGroupPropagation();

        createGroup(r.realm(), "admins");

        awaitGroupPostFor("admins");
    }

    @Test
    void adminUpdatedGroupFiresScimPut() {
        stubScimGroupCreateOk();
        stubScimGroupUpdateOk();
        var r = setupRealmWithGroupPropagation();

        String groupId = createGroup(r.realm(), "admins");
        awaitGroupPostFor("admins");

        var rep = r.realm().groups().group(groupId).toRepresentation();
        rep.setName("admins-renamed");
        r.realm().groups().group(groupId).update(rep);

        await().atMost(20, SECONDS).untilAsserted(() -> {
            int puts = wireMock.countRequestsMatching(
                putRequestedFor(urlPathMatching("/Groups/.*")).build()
            ).getCount();
            assertTrue(puts >= 1,
                "expected SCIM PUT after group update, got " + puts);
        });
    }

    @Test
    void adminDeletedGroupFiresScimDelete() {
        stubScimGroupCreateOk();
        stubScimGroupDeleteOk();
        var r = setupRealmWithGroupPropagation();

        String groupId = createGroup(r.realm(), "admins");
        awaitGroupPostFor("admins");

        r.realm().groups().group(groupId).remove();

        await().atMost(20, SECONDS).untilAsserted(() -> {
            int deletes = wireMock.countRequestsMatching(
                deleteRequestedFor(urlPathMatching("/Groups/.*")).build()
            ).getCount();
            assertTrue(deletes >= 1,
                "expected SCIM DELETE after group remove, got " + deletes);
        });
    }

    @Test
    void addingUserToGroupFiresScimGroupReplace() {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        stubScimGroupCreateOk();
        stubScimGroupUpdateOk();
        var r = setupRealmWithGroupPropagation();

        String userId = createAdminUser(r.realm(), "groupie", "groupie@test.local");
        awaitUserPostFor("groupie");

        String groupId = createGroup(r.realm(), "admins");
        awaitGroupPostFor("admins");

        // Adding alice to admins fires GROUP_MEMBERSHIP/CREATE, which
        // ScimEventListenerProvider expands into BOTH a group replace
        // (PUT with the current member list) and a user replace.
        r.realm().users().get(userId).joinGroup(groupId);

        await().atMost(20, SECONDS).untilAsserted(() -> {
            int groupPuts = wireMock.countRequestsMatching(
                putRequestedFor(urlPathMatching("/Groups/.*")).build()
            ).getCount();
            assertTrue(groupPuts >= 1,
                "expected SCIM PUT /Groups/* after membership add, got " + groupPuts);
        });
    }

    @Test
    void membershipChangeOnPlainGroupSkipsUserReplace() {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        stubScimGroupCreateOk();
        stubScimGroupUpdateOk();
        stubScimGroupPatchOk();
        var r = setupRealmWithGroupPropagation();

        String userId = createAdminUser(r.realm(), "plain-mem", "plain-mem@test.local");
        awaitUserPostFor("plain-mem");
        String groupId = createGroup(r.realm(), "plain-group"); // confers no scim-marked role
        awaitGroupPostFor("plain-group");

        int userPutsBefore = wireMock.countRequestsMatching(
            putRequestedFor(urlPathMatching("/Users/.*")).build()).getCount();

        r.realm().users().get(userId).joinGroup(groupId);

        // The group write still fires; wait for it, then confirm no user PUT was added.
        await().atMost(20, SECONDS).untilAsserted(() -> assertTrue(
            wireMock.countRequestsMatching(putRequestedFor(urlPathMatching("/Groups/.*")).build())
                .getCount() >= 1,
            "expected a SCIM group write after membership add"));
        sleepQuietly(2);

        int userPutsAfter = wireMock.countRequestsMatching(
            putRequestedFor(urlPathMatching("/Users/.*")).build()).getCount();
        assertEquals(userPutsBefore, userPutsAfter,
            "a plain group confers no scim-marked role, so no user replace should fire; "
                + "was " + userPutsBefore + ", now " + userPutsAfter);
    }

    @Test
    void membershipChangeOnScimRoleGroupFiresUserReplace() {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        stubScimGroupCreateOk();
        stubScimGroupUpdateOk();
        var r = setupRealmWithGroupPropagation();

        String userId = createAdminUser(r.realm(), "role-mem", "role-mem@test.local");
        awaitUserPostFor("role-mem");
        String groupId = createGroup(r.realm(), "role-group");
        awaitGroupPostFor("role-group");

        // Mark a realm role scim=true and map it to the group.
        var role = new org.keycloak.representations.idm.RoleRepresentation();
        role.setName("scim-marked");
        role.setAttributes(java.util.Map.of("scim", java.util.List.of("true")));
        r.realm().roles().create(role);
        var roleRep = r.realm().roles().get("scim-marked").toRepresentation();
        r.realm().groups().group(groupId).roles().realmLevel().add(java.util.List.of(roleRep));

        int userPutsBefore = wireMock.countRequestsMatching(
            putRequestedFor(urlPathMatching("/Users/.*")).build()).getCount();

        r.realm().users().get(userId).joinGroup(groupId);

        await().atMost(20, SECONDS).untilAsserted(() -> assertTrue(
            wireMock.countRequestsMatching(putRequestedFor(urlPathMatching("/Users/.*")).build())
                .getCount() > userPutsBefore,
            "group confers a scim-marked role, so a user replace should fire"));
    }

    private TestRealm setupRealmWithGroupPatchOp() {
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("propagation-group", "true");
            cfg.putSingle("group-patchOp", "true");
        });
        enableScimEventListener(r.realm());
        return r;
    }

    @Test
    void groupMembershipAddWithPatchOpSendsDeltaAdd() {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        stubScimGroupCreateOk();
        stubScimGroupPatchOk();
        var r = setupRealmWithGroupPatchOp();

        String userId = createAdminUser(r.realm(), "groupie", "groupie@test.local");
        awaitUserPostFor("groupie");
        String groupId = createGroup(r.realm(), "admins");
        awaitGroupPostFor("admins");

        // With group-patchOp=true, GROUP_MEMBERSHIP/CREATE dispatches a
        // single-member delta PATCH (op=add) rather than a full-list PUT.
        r.realm().users().get(userId).joinGroup(groupId);

        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(patchRequestedFor(urlPathMatching("/Groups/.*"))
                .withRequestBody(containing("\"op\":\"add\""))
                .withRequestBody(containing("members"))));
    }

    @Test
    void groupMembershipRemoveWithPatchOpSendsDeltaRemove() {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        stubScimGroupCreateOk();
        stubScimGroupPatchOk();
        var r = setupRealmWithGroupPatchOp();

        String userId = createAdminUser(r.realm(), "groupie", "groupie@test.local");
        awaitUserPostFor("groupie");
        String groupId = createGroup(r.realm(), "admins");
        awaitGroupPostFor("admins");

        r.realm().users().get(userId).joinGroup(groupId);
        await().atMost(20, SECONDS).until(() ->
            wireMock.countRequestsMatching(
                patchRequestedFor(urlPathMatching("/Groups/.*"))
                    .withRequestBody(containing("\"op\":\"add\"")).build()
            ).getCount() >= 1);

        // Removing fires GROUP_MEMBERSHIP/DELETE → delta PATCH op=remove with
        // the RFC 7644 filter path targeting just this member.
        r.realm().users().get(userId).leaveGroup(groupId);

        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(patchRequestedFor(urlPathMatching("/Groups/.*"))
                .withRequestBody(containing("\"op\":\"remove\""))
                .withRequestBody(containing("members[value eq"))));
    }

    @Test
    void removingUserFromGroupFiresScimGroupReplace() {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        stubScimGroupCreateOk();
        stubScimGroupUpdateOk();
        var r = setupRealmWithGroupPropagation();

        String userId = createAdminUser(r.realm(), "groupie", "groupie@test.local");
        awaitUserPostFor("groupie");
        String groupId = createGroup(r.realm(), "admins");
        awaitGroupPostFor("admins");

        // Add, then count the resulting PUTs as a baseline.
        r.realm().users().get(userId).joinGroup(groupId);
        await().atMost(20, SECONDS).until(() ->
            wireMock.countRequestsMatching(
                putRequestedFor(urlPathMatching("/Groups/.*")).build()
            ).getCount() >= 1);
        int afterJoin = wireMock.countRequestsMatching(
            putRequestedFor(urlPathMatching("/Groups/.*")).build()
        ).getCount();

        // Remove. Expect another PUT (group replace with empty members).
        r.realm().users().get(userId).leaveGroup(groupId);

        await().atMost(20, SECONDS).untilAsserted(() -> {
            int afterLeave = wireMock.countRequestsMatching(
                putRequestedFor(urlPathMatching("/Groups/.*")).build()
            ).getCount();
            assertTrue(afterLeave > afterJoin,
                "expected another SCIM PUT after membership remove (" + afterJoin
                    + " before, " + afterLeave + " after)");
        });
    }
}
