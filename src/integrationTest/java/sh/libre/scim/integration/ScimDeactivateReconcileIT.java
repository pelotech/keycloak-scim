package sh.libre.scim.integration;

import java.util.Set;
import java.util.stream.Collectors;

import javax.naming.NamingException;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ComponentRepresentation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * delete-mode=deactivate on the reconciler paths: a threshold-stale mapped user
 * is deactivated once (GET + PUT active:false, never DELETE), repeat passes are
 * zero-HTTP for flagged mappings, sync-refresh skips them, and a normal LDAP
 * full sync of an untouched entry re-links (same entryUUID) and reactivates the
 * remote resource while clearing the flag.
 */
class ScimDeactivateReconcileIT extends IntegrationTestBase {

    /**
     * Stubs POST /Users for the given userName so each user's create response
     * carries a distinct external id, letting later GET/PUT traffic be
     * correlated per user. Must not coexist with a catch-all POST /Users stub,
     * which would swallow creates under an uncorrelatable id.
     */
    private void stubScimUserCreateFor(String userName, String extId) {
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .withRequestBody(matchingJsonPath("$.userName", equalTo(userName)))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "%s",
                      "userName": "%s",
                      "displayName": "placeholder",
                      "active": true,
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""".formatted(extId, userName))));
    }

    /**
     * Stubs GET on any /Users/{id} with a valid active:true User. Has to be
     * active:true, or deactivateUser short-circuits on "remote already inactive"
     * and the PUT under test never fires. The placeholder id in the body is
     * harmless: the PUT URL comes from the mapping's external id, not this
     * response.
     */
    private void stubScimUserGetAnyActiveTrue() {
        wireMock.stubFor(get(urlPathMatching("/Users/.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "placeholder-id",
                      "userName": "placeholder",
                      "displayName": "placeholder",
                      "active": true,
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""")));
    }

    /** Count of PUTs to /Users/{extId} whose body carries the given active value. */
    private int activePutCount(String extId, boolean active) {
        return wireMock.countRequestsMatching(
            putRequestedFor(urlPathEqualTo("/Users/" + extId))
                .withRequestBody(matchingJsonPath("$.active", equalTo(Boolean.toString(active))))
                .build()).getCount();
    }

    /** Total requests (any method) to any /Users* URL. */
    private int totalUsersTraffic() {
        return wireMock.findAll(anyRequestedFor(urlPathMatching("/Users.*"))).size();
    }

    /** Distinct /Users/{id} URLs that have received a PUT with active:false. */
    private Set<String> activeFalsePutUrls() {
        return wireMock.findAll(
                putRequestedFor(urlPathMatching("/Users/.*"))
                    .withRequestBody(matchingJsonPath("$.active", equalTo("false"))))
            .stream()
            .map(req -> req.getUrl())
            .collect(Collectors.toSet());
    }

    private void deleteLdapEntryQuietly(String dn) {
        try {
            deleteLdapEntry(dn);
        } catch (NamingException ignored) {
            // entry may not exist (partial seed); nothing to restore
        }
    }

    @Test
    void reconcile_deactivatesOnce_refreshHolds_reimportReactivates() throws Exception {
        // ---- Phase 1 stubs: deterministic external ids per seeded user ----
        stubScimUserCreateFor("alice", "ext-alice");
        stubScimUserCreateFor("bob", "ext-bob");
        stubScimUserGet("ext-alice", true);
        stubScimUserGet("ext-bob", true);
        stubScimUserUpdateOk();
        // armed so a leaked DELETE gets counted below instead of 404ing into retries
        stubScimUserDeleteOk();

        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("delete-mode", "deactivate"));
        String scimComponentId = scimComponent(r.realm()).getId();

        try {
            // ---- Phase 1: deactivate once, then zero traffic ----
            triggerFullSync(r);
            awaitUserPostFor("alice");
            awaitUserPostFor("bob");

            // Remove alice's LDAP entry; bob's stays untouched (he re-links in
            // phase 3 under his original entryUUID).
            deleteLdapEntry(ldapUserDn("alice"));

            // threshold 0 makes every mapped federated user stale, so both
            // deactivate: GET then PUT active:false each.
            var resp = postReconcile(r.name(), scimComponentId, 0);
            assertEquals(200, resp.statusCode(),
                "reconcile should succeed; body was: " + resp.body());
            await().atMost(30, SECONDS).untilAsserted(() ->
                wireMock.verify(putRequestedFor(urlPathEqualTo("/Users/ext-alice"))
                    .withRequestBody(matchingJsonPath("$.active", equalTo("false")))));
            await().atMost(30, SECONDS).untilAsserted(() ->
                wireMock.verify(putRequestedFor(urlPathEqualTo("/Users/ext-bob"))
                    .withRequestBody(matchingJsonPath("$.active", equalTo("false")))));

            // A second reconcile pass skips the flagged mappings locally: no HTTP at all.
            int before = totalUsersTraffic();
            postReconcile(r.name(), scimComponentId, 0);
            // No completion signal for absence; settle briefly.
            sleepQuietly(3);
            assertEquals(before, totalUsersTraffic(),
                "second reconcile pass must be zero-HTTP for flagged mappings");

            // ---- Phase 2: sync-refresh does not resurrect ----
            int alicePutsAfterP1 = userPutCountFor("ext-alice");
            int bobPutsAfterP1 = userPutCountFor("ext-bob");

            ComponentRepresentation scim = scimComponent(r.realm());
            scim.getConfig().putSingle("sync-refresh", "true");
            r.realm().components().component(scim.getId()).update(scim);

            // Sync the SCIM component (not the LDAP one): sync-refresh PUTs
            // every mapped user except flagged ones, which it skips.
            r.realm().userStorage().syncUsers(scimComponentId, "triggerFullSync");
            sleepQuietly(3);
            assertEquals(0, activePutCount("ext-alice", true),
                "sync-refresh must not resurrect deactivated users");
            assertEquals(0, activePutCount("ext-bob", true),
                "sync-refresh must not resurrect deactivated users");
            assertEquals(alicePutsAfterP1, userPutCountFor("ext-alice"),
                "sync-refresh must skip flagged mappings entirely (no PUT of any kind)");
            assertEquals(bobPutsAfterP1, userPutCountFor("ext-bob"),
                "sync-refresh must skip flagged mappings entirely (no PUT of any kind)");

            // ---- Phase 3: Case-A re-import reactivates bob ----
            // Bob's LDAP entry was never touched, so a full sync re-links his
            // local user (same entryUUID) and fires onImportUserFromLDAP with
            // isCreate=false, which goes through replace, not create():
            // active:true is pushed to the same external id and DEACTIVATED_AT
            // is cleared.
            triggerFullSync(r);
            await().atMost(30, SECONDS).untilAsserted(() ->
                wireMock.verify(putRequestedFor(urlPathEqualTo("/Users/ext-bob"))
                    .withRequestBody(matchingJsonPath("$.active", equalTo("true")))));

            // Alice's LDAP entry is still gone: no mapper fires for her, so no
            // reactivation.
            assertEquals(0, activePutCount("ext-alice", true),
                "alice's LDAP entry is gone; she must not be reactivated");

            // A 1-hour-threshold reconcile now keeps bob (last-seen witness just
            // refreshed, flag cleared) and still skips alice (flagged, no HTTP).
            int bobFalsePutsBefore = activePutCount("ext-bob", false);
            int aliceTrafficBefore = wireMock.findAll(
                anyRequestedFor(urlPathMatching("/Users/ext-alice.*"))).size();
            postReconcile(r.name(), scimComponentId, 1);
            sleepQuietly(3);
            assertEquals(bobFalsePutsBefore, activePutCount("ext-bob", false),
                "bob is fresh again (flag cleared, witness refreshed): "
                    + "no new deactivation PUT after reactivation");
            assertEquals(aliceTrafficBefore, wireMock.findAll(
                    anyRequestedFor(urlPathMatching("/Users/ext-alice.*"))).size(),
                "alice's mapping is still flagged: reconcile must skip her locally with zero HTTP");

            assertEquals(0, userDeleteCount(), "deactivate mode must never DELETE /Users");
        } finally {
            // Restore alice for other classes sharing the LDAP seed. This cannot
            // retrigger Case A for her: the re-created entry has a new entryUUID,
            // so Keycloak's LDAP_ID-mismatch branch fires no mapper.
            reAddAlice();
        }
    }

    /**
     * Deactivate/reactivate in a bulk-enabled realm. This does not exercise the
     * bulk-lane routing: re-imported linked users take the isCreate=false
     * replace path and never enter bulkCreateUsers, which only serves freshly
     * created local users.
     */
    @Test
    void bulkRealm_deactivateAndReactivate_composes() throws Exception {
        stubScimBulkOk();
        stubScimUserGetAnyActiveTrue();
        stubScimUserUpdateOk();
        // armed so a leaked DELETE gets counted below instead of 404ing into retries
        stubScimUserDeleteOk();

        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("delete-mode", "deactivate");
            cfg.putSingle("bulk-enabled", "true");
        });
        String scimComponentId = scimComponent(r.realm()).getId();

        seedLdapUsers("bulkdeact", 3);
        try {
            triggerFullSync(r);
            awaitBulkPostCountStable();

            // The full sync maps every entry under ou=users: the 3 bulkdeact
            // users plus the seeded alice and bob, so 5 deactivations, not 3.
            int mapped = 5;

            // threshold 0: every mapped user is stale, so all deactivate.
            var resp = postReconcile(r.name(), scimComponentId, 0);
            assertEquals(200, resp.statusCode(),
                "reconcile should succeed; body was: " + resp.body());
            await().atMost(30, SECONDS).untilAsserted(() -> {
                Set<String> urls = activeFalsePutUrls();
                assertEquals(mapped, urls.size(),
                    "expected one distinct active:false PUT URL per mapped user; got " + urls);
            });
            Set<String> deactivatedUrls = activeFalsePutUrls();

            // Re-import: the LDAP entries are untouched, so each user re-links
            // and the isCreate=false replace reactivates it at its original
            // external id.
            triggerFullSync(r);
            for (String url : deactivatedUrls) {
                await().atMost(30, SECONDS).untilAsserted(() -> {
                    int count = wireMock.countRequestsMatching(
                        putRequestedFor(urlEqualTo(url))
                            .withRequestBody(matchingJsonPath("$.active", equalTo("true")))
                            .build()).getCount();
                    assertTrue(count >= 1,
                        "expected a reactivation PUT (active:true) to " + url + " after re-import");
                });
            }

            assertEquals(0, userDeleteCount(), "deactivate mode must never DELETE /Users");
        } finally {
            // The LDAP container is shared across the class; remove the bulkdeact
            // entries so the other test's per-userName POST stubs never see them.
            for (int i = 0; i < 3; i++) {
                deleteLdapEntryQuietly(ldapUserDn("bulkdeact" + i));
            }
        }
    }
}
