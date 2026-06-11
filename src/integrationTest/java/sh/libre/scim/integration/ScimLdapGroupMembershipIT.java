package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Restore the seeded {@code engineers} membership ({@code alice} + {@code bob})
     * before each test. The OpenLDAP container is shared across the class's test
     * methods and is not reset between them, and {@code removingUserFromLdapGroupEmitsRemovePatch}
     * drops alice from the group — without this, every test running after it would
     * see alice missing from engineers and never converge.
     */
    @org.junit.jupiter.api.BeforeEach
    void restoreSeededGroupMembers() throws Exception {
        setLdapAttribute("cn=engineers,ou=groups,dc=test,dc=local", "member",
            "uid=alice,ou=users,dc=test,dc=local",
            "uid=bob,ou=users,dc=test,dc=local");
    }

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
    void concurrentFirstProvisioningPostsGroupExactlyOnce() {
        stubScimUserCreateOk();
        stubScimGroupCreateOk(); // NON-deduping server: always 201 (the adversarial case)
        stubScimGroupPatchOk();

        var r = newRealmWithScimAndLdapGroups(cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("propagation-group", "true");
            cfg.putSingle("group-patchOp", "true");
        });

        syncUntilMembershipsAdded(r);

        // Direct verification of atomic first-provisioning AND its load-bearing
        // mechanism — the nested, immediately-committed mapping write. alice and
        // bob are imported concurrently and both first-provision engineers. The
        // server stub returns 201 to EVERY POST (a non-deduping server), so SCIM
        // imposes no dedup of its own: the only thing that can hold the count to
        // exactly 1 is the in-process provisioning lock combined with the winner
        // persisting its mapping in a NESTED transaction that COMMITS before the
        // lock is released — so the second worker, in its own transaction, reads
        // the committed mapping and skips its POST. If the persist were in the
        // worker's outer (not-yet-committed) transaction, the second worker would
        // miss it and POST again -> count 2 (or a duplicate-PK rollback). Exactly
        // 1 proves the nested commit is visible to the other worker before unlock.
        int groupPosts = wireMock.countRequestsMatching(
            postRequestedFor(urlPathEqualTo("/Groups")).build()
        ).getCount();
        assertTrue(groupPosts == 1,
            "engineers must be provisioned with EXACTLY one POST /Groups under concurrent "
                + "first-provisioning (atomic provisioning + nested-commit visibility), got "
                + groupPosts);
    }

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

        // 1. Converge: alice + bob propagated as members of engineers.
        syncUntilMembershipsAdded(r);
        assertTrue(memberRemovePatchCount() == 0,
            "no removals expected before dropping a member, got " + memberRemovePatchCount());

        // 2. Drop alice from the LDAP group (REPLACE member -> bob only; keeps bob,
        //    so the groupOfNames stays non-empty).
        modifyLdapAttribute("cn=engineers,ou=groups,dc=test,dc=local",
            "member", "uid=bob,ou=users,dc=test,dc=local");

        // 3. Re-sync until alice's removal is observed. Her next import sees engineers
        //    gone from her current groups while her stored set still records it -> REMOVE PATCH.
        long deadline = System.currentTimeMillis() + 120_000;
        while (memberRemovePatchCount() < 1 && System.currentTimeMillis() < deadline) {
            r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
            sleepQuietly(5);
        }
        assertTrue(memberRemovePatchCount() >= 1,
            "expected >=1 member-remove PATCH after dropping alice, got " + memberRemovePatchCount());

        // 4. Loop-safety: a re-import regression produces ~thousands of member PATCHes
        //    from a SINGLE sync (measured 1,388 for 2 members); bounded re-assertion is
        //    ~1-2 per member. Measure the delta of one more isolated sync (independent of
        //    how many convergence resyncs ran above).
        int before = memberAddPatchCount() + memberRemovePatchCount();
        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
        sleepQuietly(10);
        int delta = (memberAddPatchCount() + memberRemovePatchCount()) - before;
        assertTrue(delta < 20,
            "a single sync must add only bounded member PATCHes (loop regression -> thousands), got " + delta);
    }

    @Test
    void unchangedResyncSendsNoRedundantMemberPatches() throws Exception {
        stubScimUserCreateOk();
        stubScimGroupCreateOk();
        stubScimGroupPatchOk();

        var r = newRealmWithScimAndLdapGroups(cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("propagation-group", "true");
            cfg.putSingle("group-patchOp", "true");
        });

        // Converge: both members propagated.
        syncUntilMembershipsAdded(r);
        int addsAfterConverge = memberAddPatchCount();

        // A full sync with NO LDAP changes still re-fires the import hook for every
        // unchanged user, but additions are delta-driven against the propagated-group
        // set, so it must send ZERO new member PATCHes (Follow-up A: no redundant
        // per-sync re-assertion).
        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
        sleepQuietly(10);

        assertTrue(memberAddPatchCount() == addsAfterConverge,
            "an unchanged full sync must add no member PATCHes; was " + addsAfterConverge
                + ", now " + memberAddPatchCount());
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
        // First sync provisions the user + group SCIM mappings (POST /Users,
        // POST /Groups). The member-add PATCHes resolve the user mapping, so
        // they can only succeed once those mappings are committed.
        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
        await().atMost(60, SECONDS).until(() ->
            wireMock.countRequestsMatching(
                postRequestedFor(urlPathEqualTo("/Users")).build()).getCount() >= 2);
        await().atMost(60, SECONDS).until(() ->
            wireMock.countRequestsMatching(
                postRequestedFor(urlPathEqualTo("/Groups")).build()).getCount() >= 1);

        // Member-add propagation is async and eventually-consistent: a PATCH
        // skips if it runs before the user's mapping is committed (the
        // documented lazy-import lag, "converges on the next sync"). So re-sync
        // until both memberships are actually observed (or a generous deadline) —
        // nudging convergence rather than assuming N syncs suffice.
        //
        // Additions are delta-driven, so each member's ADD fires exactly ONCE
        // (a skip records nothing and retries; a success is recorded and never
        // re-sent). Reaching `>= 2` requires BOTH members to converge — there is
        // no re-assertion padding the count — so re-sync until both land or a
        // generous deadline. Re-import is idempotent: first-time group
        // provisioning is atomic (exactly one POST /Groups even under concurrent
        // members) and an already-propagated member is skipped, so no redundant
        // PATCHes fire across the resync loop.
        long deadline = System.currentTimeMillis() + 120_000;
        while (memberAddPatchCount() < 2 && System.currentTimeMillis() < deadline) {
            r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
            sleepQuietly(5);
        }
        assertTrue(memberAddPatchCount() >= 2,
            "expected >=2 member-add PATCH(es) after resync loop, got " + memberAddPatchCount());
    }
}
