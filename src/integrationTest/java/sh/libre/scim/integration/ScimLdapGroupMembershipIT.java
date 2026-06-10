package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end propagation of LDAP-federated group memberships. When an LDAP
 * user is imported (here via a full federation sync), our
 * {@code ScimLdapStorageMapper.onImportUserFromLDAP} iterates the imported
 * user's Keycloak groups (materialized by the attached group-ldap-mapper)
 * and, with {@code propagation-group=true}, calls
 * {@code ScimClient.ensureGroupMembership} for each. That ensures the SCIM
 * group exists (POST /Groups on first sight) and adds the member via a
 * single-member delta PATCH (op=add) when {@code group-patchOp=true}.
 *
 * <p>The LDIF seed places {@code alice} and {@code bob} in {@code cn=engineers}
 * (a {@code groupOfNames}), so one full sync imports both into the same group.
 *
 * <p>Unlike the admin-REST event-listener path exercised in
 * {@code ScimGroupPropagationIT}, the federated-import path is driven by the
 * mapper, so the {@code scim} event listener is not required here.
 */
class ScimLdapGroupMembershipIT extends IntegrationTestBase {

    @Test
    void federatedUserGroupMembershipIsProvisionedAndAdded() {
        stubScimUserCreateOk();
        stubScimGroupCreateOk();
        stubScimGroupPatchOk();

        var r = newRealmWithScimAndLdapGroups(cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("propagation-group", "true");
            cfg.putSingle("group-patchOp", "true");
        });

        syncUntilMembershipsAdded(r);
    }

    @Test
    void groupProvisionedOnceAcrossMultipleMembers() {
        stubScimUserCreateOk();
        stubScimGroupCreateOk();
        stubScimGroupPatchOk();

        var r = newRealmWithScimAndLdapGroups(cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("propagation-group", "true");
            cfg.putSingle("group-patchOp", "true");
        });

        syncUntilMembershipsAdded(r);

        // The group is provisioned exactly once: the first ensureGroupMembership
        // creates it (POST /Groups), and every subsequent ensure short-circuits
        // on the now-existing local group mapping rather than POSTing again. We
        // assert this only after both member PATCHes are observed, so we are not
        // racing a still-in-flight second provisioning.
        int groupPosts = wireMock.countRequestsMatching(
            postRequestedFor(urlPathEqualTo("/Groups")).build()
        ).getCount();
        assertEquals(1, groupPosts,
            "engineers group must be provisioned exactly once across both members' "
                + "membership propagation, got " + groupPosts);
    }

    /**
     * Drives the federated import to a deterministic end state: both users
     * provisioned, the group provisioned, and both memberships added.
     *
     * <p>Why two syncs rather than one: a member-add ({@code patchGroupMembership})
     * can only resolve the member once that user's SCIM mapping exists, and the
     * user-create ({@code SCOPE_USER}) and membership ({@code SCOPE_GROUP})
     * dispatches are independent post-commit async tasks. Within a single sync
     * the membership task can run before the user mapping is written and skip
     * (the documented lazy-import lag that "converges on the next sync"). On a
     * fast/warm machine the race usually resolves within one sync; on a cold,
     * resource-constrained CI runner it does not, and there is no further
     * trigger. So we wait for the user and group mappings to exist (their POSTs
     * land), then re-sync once — by then the memberships resolve deterministically
     * — instead of racing convergence inside a single sync.
     */
    private void syncUntilMembershipsAdded(TestRealm r) {
        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");

        // Both users must be provisioned (POST /Users) — that is what creates
        // the SCIM user mappings the membership PATCHes resolve against.
        await().atMost(30, SECONDS).until(() ->
            wireMock.countRequestsMatching(
                postRequestedFor(urlPathEqualTo("/Users")).build()
            ).getCount() >= 2);

        // And the group must be provisioned (POST /Groups), creating the group
        // mapping the member PATCHes target.
        await().atMost(30, SECONDS).until(() ->
            wireMock.countRequestsMatching(
                postRequestedFor(urlPathEqualTo("/Groups")).build()
            ).getCount() >= 1);

        // Re-sync: user mappings now exist, so each imported user's membership
        // resolves to a single-member add PATCH. The group create short-circuits
        // on the existing mapping, so this does not add a second POST /Groups.
        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");

        // Both seeded members (alice, bob) added via single-member delta PATCHes.
        awaitMemberAddPatchCount(2);
    }
}
