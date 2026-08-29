package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ComponentRepresentation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * delete-mode=deactivate on the event paths: admin user deletion becomes
 * GET + PUT active:false (or a single PATCH with user-patchOp=true), never
 * DELETE; a 404 counts as already deprovisioned; a Case-B resurrection purges
 * the stale tombstone so a later mode flip cannot DELETE a live user's
 * resource. Also covers sync-import: inactive unmatched remote users are
 * treated as our tombstones and exempt from DELETE_REMOTE and CREATE_LOCAL.
 */
class ScimDeleteModeDeactivateIT extends IntegrationTestBase {

    /** Fresh realm with delete-mode=deactivate and the scim event listener enabled. */
    private TestRealm deactivateRealm() {
        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("delete-mode", "deactivate"));
        enableScimEventListener(r.realm());
        return r;
    }

    @Test
    void adminDelete_sendsGetPlusPutActiveFalse_neverDelete() {
        String ext = "ext-deact-1";
        stubScimUserCreateOk(ext);
        stubScimUserGet(ext, true);
        stubScimUserUpdateOk();
        // armed so a leaked DELETE gets counted below instead of 404ing into retries
        stubScimUserDeleteOk();

        var r = deactivateRealm();
        String userId = createAdminUser(r.realm(), "deact1", "deact1@test.local");
        awaitUserPostFor("deact1");

        r.realm().users().get(userId).remove();

        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(putRequestedFor(urlPathEqualTo("/Users/" + ext))
                .withRequestBody(matchingJsonPath("$.active", equalTo("false")))));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/Users/" + ext)));
        assertEquals(0, userDeleteCount(), "deactivate mode must never DELETE /Users");
    }

    @Test
    void getShowsInactive_skipsThePut() {
        String ext = "ext-deact-2";
        stubScimUserCreateOk(ext);
        stubScimUserGet(ext, false);
        stubScimUserUpdateOk();
        stubScimUserDeleteOk();

        var r = deactivateRealm();
        String userId = createAdminUser(r.realm(), "deact2", "deact2@test.local");
        awaitUserPostFor("deact2");

        r.realm().users().get(userId).remove();

        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(getRequestedFor(urlPathEqualTo("/Users/" + ext))));
        // No completion signal for the absent PUT; settle briefly.
        sleepQuietly(2);
        assertEquals(0, userPutCountFor(ext),
            "downstream already inactive: the PUT must be skipped");
        assertEquals(0, userDeleteCount(), "deactivate mode must never DELETE /Users");
    }

    @Test
    void get404_treatedAsAlreadyDeprovisioned() {
        String ext = "ext-deact-3";
        stubScimUserCreateOk(ext);
        stubScimUserGet404(ext);
        stubScimUserUpdateOk();
        stubScimUserDeleteOk();

        var r = deactivateRealm();
        String userId = createAdminUser(r.realm(), "deact3", "deact3@test.local");
        awaitUserPostFor("deact3");

        r.realm().users().get(userId).remove();

        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(getRequestedFor(urlPathEqualTo("/Users/" + ext))));
        // No completion signal for the absent PUT; settle briefly.
        sleepQuietly(2);
        assertEquals(0, userPutCountFor(ext),
            "404 downstream means already deprovisioned: no PUT expected");
        assertEquals(0, userDeleteCount(), "deactivate mode must never DELETE /Users");
    }

    @Test
    void caseB_resurrection_purgesTombstone_soModeFlipCannotDelete() throws Exception {
        String ext = "ext-deact-4";
        stubScimUserCreateOk(ext);
        stubScimUserGet(ext, true);
        stubScimUserUpdateOk();
        stubScimUserDeleteOk();

        var r = deactivateRealm();
        String userId = createAdminUser(r.realm(), "deact4", "deact4@test.local");
        awaitUserPostFor("deact4");

        // Deprovision: the mapping becomes a DEACTIVATED_AT tombstone.
        r.realm().users().get(userId).remove();
        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(putRequestedFor(urlPathEqualTo("/Users/" + ext))
                .withRequestBody(matchingJsonPath("$.active", equalTo("false")))));

        // Case-B resurrection: same username under a new Keycloak id. The create
        // response's external id matches the flagged row, which purges the tombstone.
        createAdminUser(r.realm(), "deact4", "deact4@test.local");
        await().atMost(20, SECONDS).until(() -> perUserPostCount() >= 2);
        // The purge commits in an async transaction after the POST lands at
        // WireMock; let it finish before flipping the mode.
        sleepQuietly(3);

        // Flip the component to delete-mode=delete.
        ComponentRepresentation scim = scimComponent(r.realm());
        scim.getConfig().putSingle("delete-mode", "delete");
        r.realm().components().component(scim.getId()).update(scim);

        // A surviving tombstone would look like an orphan to the reconciler (its
        // KC id resolves to no local user) and get its SCIM resource DELETEd.
        // With it purged, only the live mapping remains; that user has no
        // federation link and is out of reconciler scope, so no traffic.
        int deletesBefore = userDeleteCount();
        postReconcile(r.name(), scim.getId(), 0);
        sleepQuietly(3);
        assertEquals(deletesBefore, userDeleteCount(),
            "purged tombstone must not produce a DELETE after flipping back to delete mode");
    }

    @Test
    void patchOpPosture_deactivatesWithSinglePatch() {
        String ext = "ext-deact-5";
        stubScimUserCreateOk(ext);
        wireMock.stubFor(patch(urlPathEqualTo("/Users/" + ext))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "%s",
                      "userName": "placeholder",
                      "displayName": "placeholder",
                      "active": false,
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""".formatted(ext))));
        // Armed so a leaked PUT/DELETE gets counted below instead of 404ing into
        // retries. The GET stays unstubbed: a leaked GET would 404 and trip the
        // zero-GET assertion.
        stubScimUserUpdateOk();
        stubScimUserDeleteOk();

        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("delete-mode", "deactivate");
            cfg.putSingle("user-patchOp", "true");
        });
        enableScimEventListener(r.realm());

        String userId = createAdminUser(r.realm(), "deact5", "deact5@test.local");
        awaitUserPostFor("deact5");

        r.realm().users().get(userId).remove();

        // The SDK's PatchBuilder serializes value("false") as the JSON string
        // "false", not a boolean, so assert on op/path containment rather than
        // a typed JSONPath match.
        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(patchRequestedFor(urlPathEqualTo("/Users/" + ext))
                .withRequestBody(containing("\"active\""))
                .withRequestBody(containing("replace"))));

        // The PATCH is the last call in the flow; settle before the negative checks.
        sleepQuietly(2);
        assertEquals(0, wireMock.countRequestsMatching(
                getRequestedFor(urlPathEqualTo("/Users/" + ext)).build()).getCount(),
            "patchOp posture must deactivate in one round trip: no GET expected");
        assertEquals(0, userPutCountFor(ext),
            "patchOp posture must deactivate via PATCH, never PUT");
        assertEquals(0, userDeleteCount(), "deactivate mode must never DELETE /Users");
    }

    @Test
    void syncImport_skipsInactiveUnmatchedRemotes() {
        // An inactive remote user with no local match is one of our tombstones
        // (deactivated users stay in the consumer's /Users list), so both
        // sync-import actions must leave it alone: no DELETE_REMOTE, no
        // CREATE_LOCAL resurrection.
        wireMock.stubFor(get(urlPathEqualTo("/Users"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "schemas": ["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
                      "totalResults": 1,
                      "itemsPerPage": 1,
                      "startIndex": 1,
                      "Resources": [{
                        "id": "ext-ghost-1",
                        "userName": "ghost-user",
                        "displayName": "Ghost",
                        "active": false,
                        "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                      }]
                    }""")));
        // Armed so a leaked DELETE_REMOTE gets counted instead of 404ing.
        stubScimUserDeleteOk();

        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("delete-mode", "deactivate");
            cfg.putSingle("sync-import", "true");
            cfg.putSingle("sync-import-action", "DELETE_REMOTE");
        });
        String scimId = scimComponent(r.realm()).getId();

        r.realm().userStorage().syncUsers(scimId, "triggerFullSync");
        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(getRequestedFor(urlPathEqualTo("/Users"))));
        sleepQuietly(2);
        assertEquals(0, userDeleteCount(),
            "inactive unmatched remote is a tombstone: DELETE_REMOTE must be suppressed");
        assertTrue(r.realm().users().search("ghost-user").isEmpty(),
            "inactive unmatched remote must not be imported locally");

        // Same tombstone scoping with the other import action.
        ComponentRepresentation scim = scimComponent(r.realm());
        scim.getConfig().putSingle("sync-import-action", "CREATE_LOCAL");
        r.realm().components().component(scim.getId()).update(scim);
        r.realm().userStorage().syncUsers(scimId, "triggerFullSync");
        await().atMost(20, SECONDS).until(() -> wireMock.countRequestsMatching(
            getRequestedFor(urlPathEqualTo("/Users")).build()).getCount() >= 2);
        sleepQuietly(2);
        assertTrue(r.realm().users().search("ghost-user").isEmpty(),
            "CREATE_LOCAL must not resurrect a deactivated remote user locally");
        assertEquals(0, userDeleteCount(),
            "deactivate mode must never DELETE /Users during sync-import");
    }
}
