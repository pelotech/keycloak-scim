package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end propagation scenarios for users — LDAP federation paths
 * (lazy import, change sync, full sync, sink failure) and the admin-REST
 * event-listener path (create/update/delete, username-source). Plus the
 * deletion-reconciler integration tests (endpoint and scheduled timer).
 *
 * <p>Group propagation lives in {@code ScimGroupPropagationIT}; resilience
 * concerns (auth modes, retry behavior) live in {@code ScimResilienceIT}.
 *
 * <p>Each test creates a fresh realm so realmId/componentId-scoped state
 * (mappings, federation links) does not leak between scenarios.
 */
class ScimPropagationFromLdapIT extends IntegrationTestBase {

    @Test
    void lazyImportTriggersScimPost() {
        stubScimUserCreateOk();
        var r = newRealmWithScimAndLdap();

        r.realm().users().search("alice", 0, 10);

        awaitUserPostFor("alice");
    }

    @Test
    void lazyImportIsIdempotent() {
        stubScimUserCreateOk();
        var r = newRealmWithScimAndLdap();

        r.realm().users().search("alice", 0, 10);
        awaitUserPostFor("alice");

        // Second search after the user is already imported should NOT
        // produce another POST — ScimClient.create skips when a mapping exists.
        r.realm().users().search("alice", 0, 10);

        // Give any stray async propagation a window to misbehave, then assert
        // total count is still 1.
        sleepQuietly(2);
        int posts = wireMock.countRequestsMatching(
            postRequestedFor(urlPathEqualTo("/Users")).build()
        ).getCount();
        assertEquals(1, posts, "second lazy import must not create a duplicate SCIM resource");
    }

    @Test
    void ldapModificationTriggersScimPutViaChangedUsersSync() throws Exception {
        // Design-doc scenario 2: modify a user in LDAP, run triggerChangedUsersSync,
        // expect a SCIM PUT via the isCreate=false code path.
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        var r = newRealmWithScimAndLdap();

        // Initial import: POST path.
        r.realm().users().search("alice", 0, 10);
        awaitUserPostFor("alice");

        try {
            // Bump alice's modifyTimestamp so changedUsersSync picks her up.
            modifyLdapAttribute("uid=alice,ou=users,dc=test,dc=local", "sn", "Anderson-Modified");

            r.realm().userStorage().syncUsers(r.ldapId(), "triggerChangedUsersSync");

            await().atMost(20, SECONDS).untilAsserted(() -> {
                int puts = wireMock.countRequestsMatching(
                    putRequestedFor(urlPathMatching("/Users/.*")).build()
                ).getCount();
                assertTrue(puts >= 1,
                    "expected at least one SCIM PUT after LDAP modify + changedUsersSync, got " + puts);
            });
        } finally {
            // Restore alice's sn so later tests see the original state.
            modifyLdapAttribute("uid=alice,ou=users,dc=test,dc=local", "sn", "Anderson");
        }
    }

    @Test
    void ldapSyncAloneDoesNotPropagateDeletion() throws Exception {
        // Baseline of the upstream gap (Keycloak #35235): triggerFullSync
        // alone does not propagate LDAP deletions — Keycloak doesn't remove
        // the local UserModel, no admin USER/DELETE event fires, and the
        // event-listener path sees nothing. This pins the gap's existence;
        // the reconciler closes it.
        stubScimUserCreateOk();
        stubScimUserDeleteOk();
        var r = newRealmWithScimAndLdap();

        r.realm().users().search("alice", 0, 10);
        awaitUserPostFor("alice");

        deleteLdapEntry("uid=alice,ou=users,dc=test,dc=local");
        try {
            r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
            sleepQuietly(3);
            int deletes = wireMock.countRequestsMatching(
                deleteRequestedFor(urlPathMatching("/Users/.*")).build()
            ).getCount();
            assertEquals(0, deletes,
                "baseline: LDAP sync alone must not propagate deletion "
                + "(upstream Keycloak #35235). The reconciler closes this.");
        } finally {
            reAddAlice();
        }
    }

    @Test
    void reconcilerDeletesScimResourcesForMissingLdapUsers() throws Exception {
        // Closes the scenario-4 gap via the reconciler endpoint.
        stubScimUserCreateOk();
        stubScimUserDeleteOk();
        var r = newRealmWithScimAndLdap();

        // Lazy-import alice; this stamps ldap-federation-last-seen and creates
        // her SCIM mapping via onImportUserFromLDAP.
        r.realm().users().search("alice", 0, 10);
        awaitUserPostFor("alice");

        deleteLdapEntry("uid=alice,ou=users,dc=test,dc=local");
        try {
            // Re-run the admin search: Keycloak's federation enumeration drops
            // alice (she's no longer in LDAP). Our SCIM mapping persists,
            // which is exactly the gap condition the reconciler targets —
            // a local ScimResource row with no corresponding federation user.
            r.realm().users().search("alice", 0, 10);

            var scimComponentId = r.realm().components()
                .query(null, "org.keycloak.storage.UserStorageProvider")
                .stream()
                .filter(c -> "scim".equals(c.getProviderId()))
                .findFirst()
                .orElseThrow()
                .getId();

            var resp = postReconcile(r.name(), scimComponentId, 0);
            assertEquals(200, resp.statusCode(),
                "reconciler endpoint should succeed; body was: " + resp.body());
            assertTrue(resp.body().contains("\"deleted\":1"),
                "expected deleted=1 in reconciler response, got: " + resp.body());

            await().atMost(20, SECONDS).untilAsserted(() -> {
                int deletes = wireMock.countRequestsMatching(
                    deleteRequestedFor(urlPathMatching("/Users/.*")).build()
                ).getCount();
                assertTrue(deletes >= 1,
                    "expected at least one SCIM DELETE from reconciler, got " + deletes);
            });
        } finally {
            reAddAlice();
        }
    }

    @Test
    void scheduledReconcilerFiresOnItsOwn() throws Exception {
        // Configures the reconciler on the SCIM component with short but
        // validator-compliant timings and lets the scheduled timer drive
        // the deletion — no HTTP endpoint call. The orphan-mapping path
        // doesn't depend on the threshold; the timer just needs to fire.
        stubScimUserCreateOk();
        stubScimUserDeleteOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("reconciler-enabled", "true");
            cfg.putSingle("reconciler-interval-seconds", "2");
            cfg.putSingle("reconciler-stale-threshold-seconds", "4");
        });

        r.realm().users().search("alice", 0, 10);
        awaitUserPostFor("alice");

        deleteLdapEntry("uid=alice,ou=users,dc=test,dc=local");
        try {
            r.realm().users().search("alice", 0, 10);

            await().atMost(15, SECONDS).untilAsserted(() -> {
                int deletes = wireMock.countRequestsMatching(
                    deleteRequestedFor(urlPathMatching("/Users/.*")).build()
                ).getCount();
                assertTrue(deletes >= 1,
                    "expected at least one SCIM DELETE from the scheduled reconciler, got " + deletes);
            });
        } finally {
            reAddAlice();
        }
    }

    @Test
    void fullSyncPropagatesAllLdapUsers() {
        stubScimUserCreateOk();
        var r = newRealmWithScimAndLdap();

        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");

        awaitUserPostFor("alice");
        awaitUserPostFor("bob");
    }

    @Test
    void adminCreatedUserFiresScimPostViaEventListener() {
        stubScimUserCreateOk();
        var r = newRealmWithScimAndLdap();
        enableScimEventListener(r.realm());

        createAdminUser(r.realm(), "charlie", "charlie@test.local");

        awaitUserPostFor("charlie");
    }

    @Test
    void adminUpdateOfUserFiresScimReplaceViaEventListener() {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        var r = newRealmWithScimAndLdap();
        enableScimEventListener(r.realm());

        String userId = createAdminUser(r.realm(), "dana", "dana@test.local");
        awaitUserPostFor("dana");

        var updated = r.realm().users().get(userId).toRepresentation();
        updated.setLastName("Updated");
        r.realm().users().get(userId).update(updated);

        await().atMost(20, SECONDS).untilAsserted(() -> {
            int puts = wireMock.countRequestsMatching(
                putRequestedFor(urlPathMatching("/Users/.*")).build()
            ).getCount();
            assertTrue(puts >= 1, "expected SCIM PUT after admin update, got " + puts);
        });
    }

    @Test
    void usernameSourceEmailEmitsEmailAsScimUserName() {
        stubScimUserCreateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg ->
            cfg.putSingle("username-source", "email")
        );
        enableScimEventListener(r.realm());

        createAdminUser(r.realm(), "erin", "erin@test.local");

        await().atMost(20, SECONDS).untilAsserted(() -> {
            var posts = wireMock.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().startsWith("/Users")
                    && "POST".equals(e.getRequest().getMethod().getName()))
                .toList();
            assertTrue(posts.size() >= 1, "expected a SCIM POST");
            String body = posts.get(0).getRequest().getBodyAsString();
            assertTrue(body.contains("\"userName\":\"erin@test.local\""),
                "userName must be email with username-source=email, body was: " + body);
        });
    }

    @Test
    void adminDeleteOfScimBackedUserFiresScimDelete() {
        // Verifies the fix to ScimEventListenerProvider's DELETE handler:
        // it no longer NPEs trying to re-fetch the deleted user.
        stubScimUserCreateOk();
        stubScimUserDeleteOk();
        var r = newRealmWithScimAndLdap();
        enableScimEventListener(r.realm());

        String userId = createAdminUser(r.realm(), "frank", "frank@test.local");
        awaitUserPostFor("frank");

        r.realm().users().get(userId).remove();

        await().atMost(20, SECONDS).untilAsserted(() -> {
            int deletes = wireMock.countRequestsMatching(
                deleteRequestedFor(urlPathMatching("/Users/.*")).build()
            ).getCount();
            assertTrue(deletes >= 1,
                "expected at least one SCIM DELETE after admin user delete, got " + deletes);
        });
    }

    @Test
    void scimSinkFailureDoesNotBlockLdapImport() {
        // No POST stub — WireMock will return 404 for /Users.
        // ScimDispatcher.runOne catches exceptions from the SCIM call, so the
        // LDAP import path must still produce a local Keycloak UserModel.
        var r = newRealmWithScimAndLdap();

        var found = r.realm().users().search("alice", 0, 10);
        assertEquals(1, found.size(), "alice must be imported locally even when SCIM sink is unavailable");
        assertEquals("alice", found.get(0).getUsername());
    }
}
