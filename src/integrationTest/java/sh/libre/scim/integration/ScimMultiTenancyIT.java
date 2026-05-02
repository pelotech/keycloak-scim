package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-tenancy + multi-provider scenarios. Verifies the dispatcher fans out
 * correctly to multiple SCIM providers in the same realm, and that
 * realm-scoped state (event listener, mappings, components) doesn't bleed
 * between realms.
 *
 * <p>Both scenarios use distinct WireMock URL paths to distinguish which
 * "destination" each request landed at, avoiding the need for separate
 * WireMock servers.
 */
class ScimMultiTenancyIT extends IntegrationTestBase {

    @Test
    void multipleScimProvidersAllReceiveTheUser() {
        // Two SCIM provider components in one realm, pointing at distinct
        // WireMock URL paths. Verify a single admin user create produces a
        // POST to BOTH endpoints — i.e., the dispatcher fans out across all
        // matching components, not just the first.
        wireMock.stubFor(post(urlPathEqualTo("/scim-a/Users"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody(scimUserBody())));
        wireMock.stubFor(post(urlPathEqualTo("/scim-b/Users"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody(scimUserBody())));

        String realmName = "tenancy-" + UUID.randomUUID().toString().substring(0, 8);
        var rep = new RealmRepresentation();
        rep.setRealm(realmName);
        rep.setEnabled(true);
        admin.realms().create(rep);
        var realm = admin.realm(realmName);

        addScimProviderAt(realm, "scim-a", "/scim-a");
        addScimProviderAt(realm, "scim-b", "/scim-b");
        enableScimEventListener(realm);

        createAdminUser(realm, "alice-mt", "alice-mt@test.local");

        // Both endpoints should see the POST.
        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(postRequestedFor(urlPathEqualTo("/scim-a/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("alice-mt")))));
        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(postRequestedFor(urlPathEqualTo("/scim-b/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("alice-mt")))));
    }

    @Test
    void realmsAreIsolated() {
        // Two realms, each with its own SCIM provider pointing at a distinct
        // path. A user create in realm-1 must produce a POST only at
        // realm-1's endpoint, not realm-2's.
        wireMock.stubFor(post(urlPathEqualTo("/realm-one/Users"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody(scimUserBody())));
        wireMock.stubFor(post(urlPathEqualTo("/realm-two/Users"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody(scimUserBody())));

        String r1Name = "iso1-" + UUID.randomUUID().toString().substring(0, 6);
        String r2Name = "iso2-" + UUID.randomUUID().toString().substring(0, 6);
        for (String name : new String[]{r1Name, r2Name}) {
            var rep = new RealmRepresentation();
            rep.setRealm(name);
            rep.setEnabled(true);
            admin.realms().create(rep);
        }
        var r1 = admin.realm(r1Name);
        var r2 = admin.realm(r2Name);

        addScimProviderAt(r1, "scim-r1", "/realm-one");
        addScimProviderAt(r2, "scim-r2", "/realm-two");
        enableScimEventListener(r1);
        enableScimEventListener(r2);

        createAdminUser(r1, "in-realm-one", "in-realm-one@test.local");

        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(postRequestedFor(urlPathEqualTo("/realm-one/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("in-realm-one")))));

        // Realm 2's endpoint must NOT have been called. Wait briefly to give
        // any (incorrect) async dispatch a chance to misbehave.
        sleepQuietly(2);
        int leakedToR2 = wireMock.countRequestsMatching(
            postRequestedFor(urlPathEqualTo("/realm-two/Users")).build()).getCount();
        assertEquals(0, leakedToR2,
            "realm-two's SCIM endpoint must not receive a POST for a realm-one user");
    }

    @Test
    void runtimeReconcilerConfigChangeReschedules() throws Exception {
        // Verifies the onUpdate hook reschedules the reconciler timer when
        // the operator flips reconciler-enabled or changes the interval at
        // runtime — not just at component-create time. Without this, an
        // operator would have to delete + recreate the component (or
        // restart Keycloak) to apply config changes.
        stubScimUserCreateOk();
        stubScimUserDeleteOk();

        // Start with reconciler disabled.
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("reconciler-enabled", "false");
        });

        // Lazy-import alice so a mapping exists for the reconciler to find.
        r.realm().users().search("alice", 0, 10);
        awaitUserPostFor("alice");

        deleteLdapEntry("uid=alice,ou=users,dc=test,dc=local");
        try {
            r.realm().users().search("alice", 0, 10);

            // With reconciler-enabled=false, we expect zero deletions even
            // after a long wait. Confirm by sleeping then counting.
            sleepQuietly(4);
            int beforeUpdate = wireMock.countRequestsMatching(
                com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(
                    com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("/Users/.*")
                ).build()).getCount();
            assertEquals(0, beforeUpdate,
                "with reconciler disabled, no DELETEs should have fired yet");

            // Update component: enable reconciler with short interval.
            var components = r.realm().components()
                .query(null, "org.keycloak.storage.UserStorageProvider");
            var scimComp = components.stream()
                .filter(c -> "scim".equals(c.getProviderId()))
                .findFirst().orElseThrow();
            scimComp.getConfig().putSingle("reconciler-enabled", "true");
            scimComp.getConfig().putSingle("reconciler-interval-seconds", "2");
            scimComp.getConfig().putSingle("reconciler-stale-threshold-seconds", "4");
            r.realm().components().component(scimComp.getId()).update(scimComp);

            // The onUpdate hook should reschedule. Wait for the timer to
            // fire and produce at least one DELETE.
            await().atMost(15, SECONDS).untilAsserted(() -> {
                int after = wireMock.countRequestsMatching(
                    com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(
                        com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("/Users/.*")
                    ).build()).getCount();
                assertTrue(after >= 1,
                    "expected DELETEs after enabling reconciler at runtime, got " + after);
            });
        } finally {
            reAddAlice();
        }
    }

    // ---------- helpers ----------

    private void addScimProviderAt(org.keycloak.admin.client.resource.RealmResource realm,
                                   String name, String pathPrefix) {
        var scim = new ComponentRepresentation();
        scim.setName(name);
        scim.setProviderType("org.keycloak.storage.UserStorageProvider");
        scim.setProviderId("scim");
        var cfg = new MultivaluedHashMap<String, String>();
        cfg.putSingle("endpoint", "http://host.testcontainers.internal:" + wireMock.port() + pathPrefix);
        cfg.putSingle("auth-mode", "NONE");
        cfg.putSingle("content-type", "application/scim+json");
        cfg.putSingle("propagation-user", "true");
        cfg.putSingle("propagation-group", "false");
        cfg.putSingle("enabled", "true");
        scim.setConfig(cfg);
        try (var resp = realm.components().add(scim)) {
            if (resp.getStatus() >= 400) {
                throw new IllegalStateException("SCIM provider create failed: " + resp.getStatus());
            }
        }
    }

    private static String scimUserBody() {
        return """
            {
              "id": "%s",
              "userName": "placeholder",
              "displayName": "placeholder",
              "active": true,
              "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
            }""".formatted(UUID.randomUUID());
    }
}
