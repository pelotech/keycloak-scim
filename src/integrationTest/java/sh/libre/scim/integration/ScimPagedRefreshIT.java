package sh.libre.scim.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import javax.naming.NamingException;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.SynchronizationResultRepresentation;

/**
 * End-to-end cover for sync-refresh, which pages through the local users by
 * username and commits one page per transaction.
 *
 * <p>Each test gets a fresh realm. Nothing enables the SCIM event listener, so
 * creating a user through the admin API does not push it. Only the sync under
 * test pushes. The sync call on the SCIM component runs to the end and returns
 * its result, so an assertion can follow it directly.
 *
 * <p>The directory container is shared by every test in this class, so a test
 * that seeds entries removes them in a finally block.
 */
class ScimPagedRefreshIT extends IntegrationTestBase {

    private SynchronizationResultRepresentation syncScim(TestRealm r) {
        return r.realm().userStorage().syncUsers(scimComponent(r.realm()).getId(), "triggerFullSync");
    }

    private int postsFor(String userName) {
        return wireMock.countRequestsMatching(postRequestedFor(urlPathEqualTo("/Users"))
            .withRequestBody(matchingJsonPath("$.userName", equalTo(userName))).build()).getCount();
    }

    private int putsFor(String userName) {
        return wireMock.countRequestsMatching(putRequestedFor(urlPathMatching("/Users/.*"))
            .withRequestBody(matchingJsonPath("$.userName", equalTo(userName))).build()).getCount();
    }

    private void stubScimUserCreate503() {
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"detail\":\"service unavailable\"}")));
    }

    private void stubScimUserCreate429() {
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"detail\":\"slow down\"}")));
    }

    /** A 201 for one userName. Added after a catch-all, it takes precedence. */
    private void stubScimUserCreateOkFor(String userName) {
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .withRequestBody(matchingJsonPath("$.userName", equalTo(userName)))
            .willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "%s",
                      "userName": "%s",
                      "displayName": "placeholder",
                      "active": true,
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""".formatted(UUID.randomUUID(), userName))));
    }

    /**
     * A 201 whose resource id is longer than the 36 characters the mapping
     * column holds. The push reaches the endpoint, then the insert of the
     * mapping row fails and the page transaction can no longer commit. It is
     * the cheapest way to make a real page lose everything it did.
     */
    private void stubScimUserCreateOversizedId() {
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "%s-too-long-for-the-column",
                      "userName": "placeholder",
                      "displayName": "placeholder",
                      "active": true,
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""".formatted(UUID.randomUUID()))));
    }

    /** The list request the import starts with fails. Nothing else is affected. */
    private void stubScimUserList500() {
        wireMock.stubFor(get(urlPathEqualTo("/Users"))
            .willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"detail\":\"list failed\"}")));
    }

    /**
     * Waits until POST /Users traffic has been quiet for about three seconds. The
     * import worker logs its POST at WireMock before it commits the mapping; a
     * refresh that reaches the user before that commit would create it again.
     */
    private void awaitUserPostsSettle() {
        await().atMost(60, SECONDS).pollInterval(500, MILLISECONDS)
            .until(new Callable<Boolean>() {
                private int last = -1;
                private int stableReads = 0;

                @Override
                public Boolean call() {
                    int now = perUserPostCount();
                    if (now == last) {
                        stableReads++;
                    } else {
                        stableReads = 0;
                        last = now;
                    }
                    return stableReads >= 6;
                }
            });
    }

    private void deleteLdapEntriesQuietly(List<String> uids) {
        for (String uid : uids) {
            try {
                deleteLdapEntry(ldapUserDn(uid));
            } catch (NamingException ignored) {
                // already removed by the test
            }
        }
    }

    @Test
    void refreshPagesThroughEveryUser() {
        stubScimUserCreateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("sync-page-size", "2");
        });
        var names = List.of("page-a", "page-b", "page-c", "page-d", "page-e");
        for (String n : names) {
            createAdminUser(r.realm(), n, n + "@test.local");
        }

        var result = syncScim(r);

        for (String n : names) {
            assertEquals(1, postsFor(n), "expected exactly one create for " + n);
        }
        assertEquals(names.size(), result.getUpdated());
        assertEquals(0, result.getFailed());
    }

    @Test
    void aPageOfExcludedUsersDoesNotEndTheRun() {
        stubScimUserCreateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("sync-page-size", "2");
            cfg.putSingle("propagation-role", "scim-eligible");
        });
        var role = new RoleRepresentation();
        role.setName("scim-eligible");
        r.realm().roles().create(role);
        var excluded = List.of("skip-a", "skip-b", "skip-c", "skip-d");
        for (String n : excluded) {
            createAdminUser(r.realm(), n, n + "@test.local");
        }
        String eligibleId = createAdminUser(r.realm(), "zz-eligible", "zz-eligible@test.local");
        r.realm().users().get(eligibleId).roles().realmLevel()
            .add(List.of(r.realm().roles().get("scim-eligible").toRepresentation()));

        var result = syncScim(r);

        assertEquals(1, postsFor("zz-eligible"));
        for (String n : excluded) {
            assertEquals(0, postsFor(n), n + " is excluded by propagation-role");
        }
        assertEquals(1, result.getUpdated(), "excluded users must not be counted as updated");
        assertEquals(0, result.getFailed(), "a page of excluded users is not a failure");
    }

    @Test
    void anUncachedUserWhoseDirectoryEntryIsGoneIsNotPushed() throws Exception {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("sync-page-size", "2");
        });
        var uids = seedLdapUsers("rmnc", 4);
        try {
            triggerFullSync(r);
            for (String uid : uids) {
                awaitUserPostFor(uid);
            }
            awaitUserPostFor("alice");
            awaitUserPostFor("bob");
            awaitUserPostsSettle();
            String removed = uids.get(1);
            deleteLdapEntry(ldapUserDn(removed));
            r.realm().clearUserCache();
            wireMock.resetRequests();

            syncScim(r);

            assertEquals(0, putsFor(removed), "an uncached user with no directory entry is skipped");
            for (String uid : List.of(uids.get(0), uids.get(2), uids.get(3))) {
                assertEquals(1, putsFor(uid), "expected one refresh update for " + uid);
            }
        } finally {
            deleteLdapEntriesQuietly(uids);
        }
    }

    @Test
    void aCachedUserWhoseDirectoryEntryIsGoneIsPushedThenReconciled() throws Exception {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        stubScimUserDeleteOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("sync-refresh", "true"));
        var uids = seedLdapUsers("rmc", 2);
        try {
            triggerFullSync(r);
            for (String uid : uids) {
                awaitUserPostFor(uid);
            }
            awaitUserPostFor("alice");
            awaitUserPostFor("bob");
            awaitUserPostsSettle();
            String removed = uids.get(0);
            String removedId = r.realm().users().search(removed, true).get(0).getId();
            r.realm().clearUserCache();
            r.realm().users().get(removedId).toRepresentation(); // caches it while the entry still exists
            deleteLdapEntry(ldapUserDn(removed));
            wireMock.resetRequests();

            syncScim(r);
            assertEquals(1, putsFor(removed), "a cached user is pushed from its stored copy");

            r.realm().clearUserCache();
            var response = postReconcile(r.name(), scimComponent(r.realm()).getId(), 48);
            assertEquals(200, response.statusCode());
            assertEquals(1, userDeleteCount(), "the reconciler deprovisions the user once it is no longer cached");
        } finally {
            deleteLdapEntriesQuietly(uids);
        }
    }

    @Test
    void serviceAccountsAndDisabledUsersAreNotRefreshed() {
        stubScimUserCreateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("sync-refresh", "true"));
        createServiceAccountClient(r.name(), "it-sa-client", false);
        String disabledId = createAdminUser(r.realm(), "disabled-user", "disabled-user@test.local");
        var disabled = r.realm().users().get(disabledId).toRepresentation();
        disabled.setEnabled(false);
        r.realm().users().get(disabledId).update(disabled);
        createAdminUser(r.realm(), "enabled-user", "enabled-user@test.local");

        syncScim(r);

        assertEquals(1, postsFor("enabled-user"));
        assertEquals(0, postsFor("disabled-user"));
        assertEquals(0, postsFor("service-account-it-sa-client"),
            "service accounts are excluded from refresh");
    }

    @Test
    void refreshDoesNotImportDirectoryUsers() throws Exception {
        stubScimUserCreateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("sync-refresh", "true"));
        var uids = seedLdapUsers("noimp", 1);
        try {
            syncScim(r);
            assertEquals(0, postsFor(uids.get(0)), "refresh must not import a directory entry");

            triggerFullSync(r);
            awaitUserPostFor(uids.get(0));
        } finally {
            deleteLdapEntriesQuietly(uids);
        }
    }

    @Test
    void aStoppedRunKeepsTheMappingsItCommitted() {
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("sync-page-size", "2");
            cfg.putSingle("sync-on-error", "auto");
        });
        for (String n : List.of("keep-a", "keep-b", "keep-c", "keep-d")) {
            createAdminUser(r.realm(), n, n + "@test.local");
        }
        stubScimUserCreate503();
        stubScimUserCreateOkFor("keep-a");
        stubScimUserCreateOkFor("keep-b");

        var first = syncScim(r);

        assertEquals(2, first.getUpdated(), "the first page commits");
        assertEquals(2, first.getFailed(),
            "the failed push and the stopped run are both reported");
        assertEquals(0, postsFor("keep-d"), "nothing after the stop is attempted");

        wireMock.resetAll();
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        syncScim(r);

        assertEquals(1, putsFor("keep-a"), "keep-a was mapped by the first run, so it is replaced");
        assertEquals(1, putsFor("keep-b"));
        assertEquals(1, postsFor("keep-c"), "keep-c was never mapped, so it is created");
    }

    @Test
    void aThrottledPageEndsTheRunButOneThrottledUserDoesNot() {
        stubScimUserCreate429();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("sync-page-size", "2");
            cfg.putSingle("sync-on-error", "auto");
        });
        for (String n : List.of("thr-a", "thr-b", "thr-c", "thr-d")) {
            createAdminUser(r.realm(), n, n + "@test.local");
        }

        var result = syncScim(r);

        assertTrue(postsFor("thr-b") >= 1, "a throttled user does not stop the run on its own");
        assertEquals(0, postsFor("thr-c"), "a full page of throttled users in a row ends the run");
        assertEquals(0, result.getUpdated());
        assertEquals(3, result.getFailed(),
            "two throttled pushes and the stopped run are reported");
    }

    @Test
    void aFailedImportDoesNotStopTheUserRefresh() {
        stubScimUserCreateOk();
        stubScimUserList500();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("sync-import", "true");
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("sync-page-size", "2");
        });
        var names = List.of("cont-a", "cont-b", "cont-c");
        for (String n : names) {
            createAdminUser(r.realm(), n, n + "@test.local");
        }

        var result = syncScim(r);

        for (String n : names) {
            assertEquals(1, postsFor(n), "the refresh still pushed " + n);
        }
        assertEquals(names.size(), result.getUpdated());
        assertEquals(1, result.getFailed(), "the result reports the failed import");
    }

    @Test
    void aRolledBackPageReportsNothingItDiscarded() {
        stubScimUserCreateOversizedId();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("sync-page-size", "3");
        });
        for (String n : List.of("lost-a", "lost-b", "lost-c")) {
            createAdminUser(r.realm(), n, n + "@test.local");
        }

        var result = syncScim(r);

        assertTrue(perUserPostCount() >= 1, "the page reached the endpoint before it failed");
        assertEquals(0, result.getUpdated(),
            "a page that cannot commit must not report the users it pushed");
        assertTrue(result.getFailed() >= 1, "the lost page is reported as a failure");

        // The mapping rows went with the page, so the next run creates again.
        // This is what makes the discarded update count a lie.
        wireMock.resetAll();
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        syncScim(r);

        assertEquals(1, postsFor("lost-a"), "the rolled-back page kept no mapping for lost-a");
        assertEquals(0, putsFor("lost-a"));
    }

    @Test
    void rollbackStrategyDoesNotAffectASync() {
        // A sync no longer runs through the dispatcher, so rollback-strategy
        // cannot mark a page transaction rollback-only. Were it still able to,
        // the first failed push would end the page and the run.
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("sync-page-size", "2");
            cfg.putSingle("sync-on-error", "continue");
            cfg.putSingle("rollback-strategy", "always");
            cfg.putSingle("bulk-enabled", "false");
        });
        for (String n : List.of("roll-a", "roll-b", "roll-c", "roll-d")) {
            createAdminUser(r.realm(), n, n + "@test.local");
        }
        stubScimUserCreate503();
        stubScimUserCreateOkFor("roll-a");

        var first = syncScim(r);

        assertTrue(postsFor("roll-d") >= 1, "the run reached the last user");
        assertEquals(1, first.getUpdated());
        assertEquals(3, first.getFailed());

        wireMock.resetAll();
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        syncScim(r);

        assertEquals(1, putsFor("roll-a"),
            "the page kept its mapping although another user in it failed");
    }
}
