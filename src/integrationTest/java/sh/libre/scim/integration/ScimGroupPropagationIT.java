package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
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
    void groupFilterScopesSyncRefreshToMatchingGroupMembers() throws Exception {
        // group-filter is consulted only during sync-refresh: when triggered,
        // refreshResources iterates getResourceStream(), which under
        // group-filter returns only members of matching groups. Verify that
        // a sync of a SCIM provider configured with sync-refresh=true and
        // group-filter='admins' results in PUTs only for users in 'admins',
        // not other groups.
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        stubScimGroupCreateOk();
        stubScimGroupUpdateOk();

        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("propagation-group", "true");
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("group-filter", "admins");
        });
        enableScimEventListener(r.realm());

        // Pre-populate: two groups, two users, each in one group. Initial
        // CREATEs propagate via the event listener (so mappings exist).
        String adminsId = createGroup(r.realm(), "admins");
        String devsId = createGroup(r.realm(), "developers");
        String adminUserId = createAdminUser(r.realm(), "admin-mem", "admin-mem@test.local");
        String devUserId = createAdminUser(r.realm(), "dev-mem", "dev-mem@test.local");
        awaitUserPostFor("admin-mem");
        awaitUserPostFor("dev-mem");
        r.realm().users().get(adminUserId).joinGroup(adminsId);
        r.realm().users().get(devUserId).joinGroup(devsId);

        // Wait for the membership-driven PUTs from the event listener to
        // settle, then snapshot the count so we measure ONLY the sync's
        // contribution.
        sleepQuietly(2);
        int putsBeforeSync = wireMock.countRequestsMatching(
            putRequestedFor(urlPathMatching("/Users/.*")).build()).getCount();

        // Trigger the SCIM provider component's sync (NOT the LDAP
        // federation's). This is what calls ScimClient.sync ->
        // refreshResources -> getResourceStream, which is where
        // group-filter narrows the user iteration.
        var scimComponentId = r.realm().components()
            .query(null, "org.keycloak.storage.UserStorageProvider")
            .stream()
            .filter(c -> "scim".equals(c.getProviderId()))
            .findFirst().orElseThrow().getId();
        r.realm().userStorage().syncUsers(scimComponentId, "triggerFullSync");

        // Sync's refresh should PUT each matching-group member at least
        // once. With filter='admins', that's just admin-mem.
        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(putRequestedFor(urlPathMatching("/Users/.*"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("admin-mem")))));

        // Verify dev-mem was NOT refreshed by the sync. We can't trivially
        // assert "no PUT for dev-mem" because the earlier joinGroup may
        // have produced a PUT for them. Compare counts: total PUTs must
        // have grown (from the sync), but dev-mem-targeting PUTs should
        // NOT have grown beyond what the joinGroup already produced.
        // The cleanest signal: assert that admin-mem appears MORE than
        // dev-mem in the post-sync delta.
        int adminPutsTotal = wireMock.countRequestsMatching(
            putRequestedFor(urlPathMatching("/Users/.*"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("admin-mem"))).build()
        ).getCount();
        int devPutsTotal = wireMock.countRequestsMatching(
            putRequestedFor(urlPathMatching("/Users/.*"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("dev-mem"))).build()
        ).getCount();
        int putsAfterSync = wireMock.countRequestsMatching(
            putRequestedFor(urlPathMatching("/Users/.*")).build()).getCount();

        assertTrue(adminPutsTotal > devPutsTotal,
            "expected more PUTs for admin-mem (in matching group) than dev-mem"
                + " (in non-matching group); got admin=" + adminPutsTotal
                + " dev=" + devPutsTotal);
        assertTrue(putsAfterSync > putsBeforeSync,
            "expected sync to produce some refresh PUTs; before=" + putsBeforeSync
                + " after=" + putsAfterSync);
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
