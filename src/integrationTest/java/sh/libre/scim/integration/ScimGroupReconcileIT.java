package sh.libre.scim.integration;

import java.net.http.HttpResponse;

import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for the reconciler's group phase under the
 * <b>member-presence</b> design: a mapped federated group that currently has
 * zero members (or whose local model is gone) is DELETEd in the SCIM sink, while
 * a group that still has members is kept.
 *
 * <p>There is NO group-attribute write and no staleness threshold in this
 * design. Staleness is achieved <i>naturally</i>: when the backing LDAP group is
 * deleted or renamed, a full federation sync drains the old Keycloak
 * {@code GroupModel} to zero members (the renamed group's members move to a
 * brand-new group). The reconciler's group phase then classifies the
 * now-memberless group as a DELETE candidate. Reconcile is driven via the HTTP
 * endpoint ({@code postReconcile}); the background scheduler is left out of the
 * way so the only reconcile is the one the test triggers.
 *
 * <p>Because nothing writes a group attribute, the reconcile transaction cannot
 * collide with an in-flight async {@code GROUP_ATTRIBUTE} write — there is no
 * {@code StaleStateException} and therefore no 500-retry wrapper: a plain
 * {@code postReconcile} suffices.
 *
 * <p><b>Deterministic sequencing.</b> Each scenario provisions {@code engineers}
 * and waits for the member-add PATCHes to land (which proves the import settled
 * and the group is provisioned with both members). Only then does it mutate LDAP
 * (delete / rename), full-sync to drain the old group, optionally await the new
 * group's POST (rename), and finally reconcile. No aging step exists.
 */
class ScimGroupReconcileIT extends IntegrationTestBase {

    private static final String ENGINEERS_DN = "cn=engineers,ou=groups,dc=test,dc=local";
    private static final String ENGINEERS_RENAMED_DN = "cn=engineers-team,ou=groups,dc=test,dc=local";

    /**
     * Deterministic SCIM ids the stubbed sink assigns each group, keyed by
     * displayName (see {@link #stubScimGroupCreateReturning}). 36 chars to fit
     * the SCIM_RESOURCE.EXTERNAL_ID VARCHAR(36) column. The reconciler's group
     * DELETE targets {@code /Groups/<this id>}, so distinct ids let the rename
     * scenario distinguish the old group's DELETE from the new group's.
     */
    private static final String SCIM_ID_ENGINEERS      = "aaaaaaaa-0000-0000-0000-00000000eng1";
    private static final String SCIM_ID_ENGINEERS_TEAM = "bbbbbbbb-0000-0000-0000-0000000team2";

    /**
     * Threshold is irrelevant to the member-presence group phase (it only gates
     * the user phase), but {@code postReconcile} requires a value. Use the normal
     * value the reconciler ITs use so freshly-imported users stay fresh.
     */
    private static final long RECONCILE_THRESHOLD_HOURS = 48;

    /**
     * Provisions the federated {@code engineers} group with both seeded members
     * (alice, bob) deterministically, using the two-sync pattern from
     * {@code ScimLdapGroupMembershipIT}. Returns after the member-add PATCHes are
     * observed — which proves the import has settled (both users provisioned, the
     * group provisioned via POST /Groups, and both members added). After this it
     * is safe to mutate LDAP and re-sync.
     */
    private void provisionEngineers(TestRealm r) {
        // First provisioning phase: drive a full sync until BOTH seeded users and
        // the group have been POSTed to the sink. A single sync can under-deliver:
        // Keycloak's LDAP import can hit a transient BindException mid-enumeration
        // under cumulative ephemeral-port pressure and return having imported only
        // partial data (the per-entry failure is swallowed, so syncUsers does not
        // throw). A longer await cannot recover a short sync — only a re-sync can —
        // so we re-sync until the side effects land rather than waiting on one
        // import. The happy path syncs once and breaks immediately; extra syncs
        // happen only on shortfall, bounded with a cooldown so we don't storm the
        // LDAP pool. This mirrors the member-add loop below.
        for (int attempt = 0; attempt < 4; attempt++) {
            triggerFullSync(r);
            try {
                await().atMost(30, SECONDS).until(() ->
                    wireMock.countRequestsMatching(
                        postRequestedFor(urlPathEqualTo("/Users")).build()).getCount() >= 2
                    && wireMock.countRequestsMatching(
                        postRequestedFor(urlPathEqualTo("/Groups")).build()).getCount() >= 1);
                break;
            } catch (org.awaitility.core.ConditionTimeoutException e) {
                if (attempt == 3) {
                    throw e;
                }
                sleepQuietly(5);
            }
        }

        // Re-sync: user mappings now exist, so each imported user's membership
        // resolves to a single-member add PATCH (the group create short-circuits on
        // the existing mapping, so no second POST /Groups). Under cumulative LDAP
        // ephemeral-port pressure across the suite, a single re-sync's member
        // resolves can intermittently fail (BindException); we re-sync a couple of
        // bounded times with a cooldown until both member-adds land, rather than
        // racing a single import. Both member-adds observed => the import settled
        // and the group holds both members.
        for (int attempt = 0; attempt < 4; attempt++) {
            triggerFullSync(r);
            try {
                await().atMost(30, SECONDS).until(() -> memberAddPatchCount() >= 2);
                return;
            } catch (org.awaitility.core.ConditionTimeoutException e) {
                if (attempt == 3) {
                    throw e;
                }
                sleepQuietly(5);
            }
        }
    }

    /**
     * Triggers a full federation sync, tolerating the transient HTTP 400 /
     * {@code BindException: Cannot assign requested address} that Keycloak can
     * surface when its LDAP connection pool briefly cannot open a socket (an
     * environmental hiccup under ephemeral-port pressure, not a data error).
     * Retries with a generous backoff window.
     */
    private void triggerFullSync(TestRealm r) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
                return;
            } catch (RuntimeException e) {
                last = e;
                sleepQuietly(5);
            }
        }
        throw last;
    }

    /** A SCIM mapping for a Group only exists once its component id is known. */
    private String scimComponentId(TestRealm r) {
        return r.realm().components()
            .query(null, "org.keycloak.storage.UserStorageProvider")
            .stream()
            .filter(c -> "scim".equals(c.getProviderId()))
            .findFirst()
            .orElseThrow()
            .getId();
    }

    private TestRealm newGroupReconcileRealm() {
        return newRealmWithScimAndLdapGroups(cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("propagation-group", "true");
            cfg.putSingle("group-patchOp", "true");
            // Reconcile is driven via the HTTP endpoint (postReconcile), like
            // ScimPropagationFromLdapIT#reconcilerDeletesScimResourcesForMissingLdapUsers,
            // so the background scheduler is intentionally left out of the way: an
            // additional timer firing on its own would only add scim-dispatch /
            // LDAP-connection churn with no extra coverage. We still enable the
            // reconciler so the group phase is wired; postReconcile overrides the
            // threshold per call.
            cfg.putSingle("reconciler-enabled", "true");
            cfg.putSingle("reconciler-interval-seconds", "3600");
            cfg.putSingle("reconciler-stale-threshold-seconds", "172800");
        });
    }

    /**
     * Awaits (via the admin API) that the named top-level group has drained to
     * zero members. A federated group's member edges persist between syncs; they
     * only disappear once the backing LDAP group is deleted/renamed and a full
     * sync drains them. Gating reconcile on the drain removes any timing race
     * between the sync's member removal and the reconcile's member-presence read.
     */
    private void awaitGroupHasNoMembers(TestRealm r, String groupName) {
        await().atMost(30, SECONDS).untilAsserted(() -> {
            var g = findGroupByName(r.realm(), groupName);
            assertNotNull(g, groupName + " must still exist (drained, not removed)");
            int members = r.realm().groups().group(g.getId()).members().size();
            assertEquals(0, members,
                groupName + " must be drained to 0 members after the LDAP change + full sync");
        });
    }

    /**
     * Stubs {@code POST /Groups} so that a group whose request body carries the
     * given {@code displayName} gets a deterministic, distinct SCIM {@code id}
     * back from the sink. The shared {@code stubScimGroupCreateOk} returns a
     * single constant UUID for every POST, so two groups (engineers and the
     * renamed engineers-team) would be indistinguishable by SCIM id — and the
     * reconciler's DELETE targets {@code /Groups/<scimId>}. Scoping the stub by
     * displayName gives each group its own id so the DELETE assertion is precise.
     *
     * <p>The matcher uses the displayName WITH its closing quote
     * ({@code "displayName":"engineers"}) so it does not also match
     * {@code engineers-team}; the engineers-team stub is registered at higher
     * priority to win when both could match.
     */
    private void stubScimGroupCreateReturning(String displayName, String scimId, int priority) {
        wireMock.stubFor(
            post(urlPathEqualTo("/Groups"))
                .atPriority(priority)
                .withRequestBody(containing("\"displayName\":\"" + displayName + "\""))
                .willReturn(aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/scim+json")
                    .withBody("""
                        {
                          "id": "%s",
                          "displayName": "%s",
                          "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"]
                        }""".formatted(scimId, displayName))));
    }

    private int groupDeleteCount() {
        return wireMock.countRequestsMatching(
            deleteRequestedFor(urlPathMatching("/Groups/.*")).build()).getCount();
    }

    // ---------- Scenario 1: a deleted LDAP group is reconciled (DELETEd) ----------

    @Test
    void deletedLdapGroupIsReconciled() throws Exception {
        stubScimUserCreateOk();
        // engineers gets a deterministic, known SCIM id so we can assert the
        // DELETE targets exactly /Groups/<that id>.
        stubScimGroupCreateReturning("engineers", SCIM_ID_ENGINEERS, 1);
        stubScimGroupPatchOk();
        stubScimGroupDeleteOk();
        stubScimUserDeleteOk();

        var r = newGroupReconcileRealm();
        String componentId = scimComponentId(r);

        provisionEngineers(r);

        var engineers = findGroupByName(r.realm(), "engineers");
        assertNotNull(engineers, "engineers must be materialized after provisioning");

        // Delete the backing LDAP group, then full-sync. Keycloak keeps the local
        // engineers GroupModel but drains it to 0 members.
        deleteLdapEntry(ENGINEERS_DN);
        boolean restored = false;
        try {
            triggerFullSync(r);
            awaitGroupHasNoMembers(r, "engineers");

            var resp = postReconcile(r.name(), componentId, RECONCILE_THRESHOLD_HOURS);
            assertEquals(200, resp.statusCode(),
                "reconcile should succeed; body was: " + resp.body());

            // The now-memberless engineers group is DELETEd at its SCIM id.
            await().atMost(30, SECONDS).untilAsserted(() ->
                wireMock.verify(deleteRequestedFor(urlPathEqualTo("/Groups/" + SCIM_ID_ENGINEERS))));
        } finally {
            // Restore the seed for any later test sharing the LDAP container.
            reAddEngineersLdapGroup();
            restored = true;
        }
        assertTrue(restored, "LDAP seed restored");
    }

    // ---------- Scenario 2: LDAP rename = delete-old + provision-new ----------

    @Test
    void renamedLdapGroupDeletesOldProvisionsNew() throws Exception {
        stubScimUserCreateOk();
        // Each group gets its own deterministic SCIM id. The engineers-team stub
        // is registered at higher priority so it wins for the renamed group's
        // POST (whose body contains "engineers-team"); the engineers stub matches
        // only the exact "engineers" displayName (closing quote).
        stubScimGroupCreateReturning("engineers-team", SCIM_ID_ENGINEERS_TEAM, 1);
        stubScimGroupCreateReturning("engineers", SCIM_ID_ENGINEERS, 2);
        stubScimGroupPatchOk();
        stubScimGroupDeleteOk();
        stubScimUserDeleteOk();

        var r = newGroupReconcileRealm();
        String componentId = scimComponentId(r);

        provisionEngineers(r);

        var engineers = findGroupByName(r.realm(), "engineers");
        assertNotNull(engineers, "engineers must be materialized after provisioning");
        String engineersKcId = engineers.getId();

        boolean restored = false;
        try {
            // Rename the LDAP group cn engineers -> engineers-team (modrdn preserves
            // entry identity). Keycloak's group-ldap-mapper materializes a BRAND-NEW
            // GroupModel for the new cn and leaves the old engineers GroupModel in
            // place, draining its members (they move to the new group). Full sync
            // provisions the new group fresh (a new POST /Groups, distinct SCIM id).
            renameLdapEntry(ENGINEERS_DN, ENGINEERS_RENAMED_DN);

            // Two syncs (the deterministic-provisioning pattern): the first
            // materializes the new GroupModel and writes the user mappings; the
            // second resolves the new group's POST /Groups (with members). We
            // gate on engineers-team's POST landing — that proves it provisioned
            // fresh with its own SCIM id.
            triggerFullSync(r);
            await().atMost(30, SECONDS).untilAsserted(() ->
                assertNotNull(findGroupByName(r.realm(), "engineers-team"),
                    "a new GroupModel for the renamed cn must materialize after sync"));
            triggerFullSync(r);
            await().atMost(30, SECONDS).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlPathEqualTo("/Groups"))
                    .withRequestBody(containing("\"displayName\":\"engineers-team\""))));

            var team = findGroupByName(r.realm(), "engineers-team");
            assertNotNull(team, "engineers-team must exist after rename + sync");
            assertTrue(!engineersKcId.equals(team.getId()),
                "the renamed cn must yield a NEW GroupModel (distinct Keycloak id)");

            // The OLD engineers group's members have moved to engineers-team; wait
            // for the drain so the member-presence read is deterministic.
            awaitGroupHasNoMembers(r, "engineers");

            var resp = postReconcile(r.name(), componentId, RECONCILE_THRESHOLD_HOURS);
            assertEquals(200, resp.statusCode(),
                "reconcile should succeed; body was: " + resp.body());

            // The OLD (now memberless) engineers group is DELETEd at its SCIM id.
            await().atMost(30, SECONDS).untilAsserted(() ->
                wireMock.verify(deleteRequestedFor(urlPathEqualTo("/Groups/" + SCIM_ID_ENGINEERS))));

            // The NEW engineers-team group was POSTed and is NOT deleted (it has
            // members). Asserting the ABSENCE of a DELETE has no deterministic
            // completion signal (a delete would arrive async, separate from the
            // reconcile HTTP response), so we settle briefly first — the best
            // available gate, matching the peer reconciler ITs' 3s convention.
            sleepQuietly(3);
            wireMock.verify(postRequestedFor(urlPathEqualTo("/Groups"))
                .withRequestBody(containing("\"displayName\":\"engineers-team\"")));
            int teamDeletes = wireMock.countRequestsMatching(
                deleteRequestedFor(urlPathEqualTo("/Groups/" + SCIM_ID_ENGINEERS_TEAM)).build())
                .getCount();
            assertEquals(0, teamDeletes,
                "engineers-team (still has members) must NOT be deleted");
        } finally {
            // Restore the seed for any later test sharing the LDAP container.
            renameLdapEntry(ENGINEERS_RENAMED_DN, ENGINEERS_DN);
            restored = true;
        }
        assertTrue(restored, "LDAP seed restored");
    }

    // ---------- Scenario 3: a live (member-holding) group is NOT deleted ----------

    @Test
    void liveGroupIsNotDeleted() throws Exception {
        stubScimUserCreateOk();
        stubScimGroupCreateOk();
        stubScimGroupPatchOk();
        stubScimGroupDeleteOk();
        stubScimUserDeleteOk();

        var r = newGroupReconcileRealm();
        String componentId = scimComponentId(r);

        // Provision engineers and keep its members (no LDAP change). This guards
        // that a stable, still-federated group with members is not wrongly deleted.
        provisionEngineers(r);

        var engineers = findGroupByName(r.realm(), "engineers");
        assertNotNull(engineers, "engineers must be materialized after provisioning");
        assertTrue(r.realm().groups().group(engineers.getId()).members().size() >= 1,
            "engineers must still hold its members before reconcile");

        var resp = postReconcile(r.name(), componentId, RECONCILE_THRESHOLD_HOURS);
        assertEquals(200, resp.statusCode(),
            "reconcile should succeed; body was: " + resp.body());

        // Give a stray DELETE a window to misbehave, then assert none fired.
        // No deterministic signal exists for "a delete that should NOT happen"
        // (it would arrive async, separate from the reconcile response), so the
        // brief settle is the best available gate — 3s matches the peer
        // reconciler ITs' convention.
        sleepQuietly(3);
        assertEquals(0, groupDeleteCount(),
            "a live (member-holding) federated group must NOT be deleted; "
                + "got " + groupDeleteCount() + " SCIM DELETE(s) to /Groups/*");
    }

    // ---------- LDAP helpers ----------

    /** LDAP modrdn: renames an entry's RDN in place, preserving its identity. */
    private void renameLdapEntry(String oldDn, String newDn) throws NamingException {
        var ctx = new InitialDirContext(newLdapEnv());
        try {
            ctx.rename(oldDn, newDn);
        } finally {
            ctx.close();
        }
    }

    /**
     * Re-creates the seeded {@code engineers} group (with its members) after a
     * scenario deletes it, so later tests sharing the LDAP container see the seed.
     */
    private void reAddEngineersLdapGroup() throws NamingException {
        var ctx = new InitialDirContext(newLdapEnv());
        try {
            var attrs = new javax.naming.directory.BasicAttributes();
            var oc = new javax.naming.directory.BasicAttribute("objectClass");
            oc.add("groupOfNames");
            oc.add("top");
            attrs.put(oc);
            attrs.put("cn", "engineers");
            var members = new javax.naming.directory.BasicAttribute("member");
            members.add("uid=alice,ou=users,dc=test,dc=local");
            members.add("uid=bob,ou=users,dc=test,dc=local");
            attrs.put(members);
            ctx.createSubcontext(ENGINEERS_DN, attrs);
        } finally {
            ctx.close();
        }
    }
}
