package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end propagation scenarios for groups: create, update, delete, and
 * membership changes flow from Keycloak admin REST events through
 * {@code ScimEventListenerProvider} to the SCIM sink.
 *
 * <p>All tests configure the SCIM provider with {@code propagation-group=true}
 * and enable the {@code scim} event listener on the realm. The event listener
 * fires the group adapter on GROUP_CREATE / GROUP_UPDATE / GROUP_DELETE
 * events, and on GROUP_MEMBERSHIP it dispatches both a group replace (PUT
 * with current membership) and a user replace.
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
