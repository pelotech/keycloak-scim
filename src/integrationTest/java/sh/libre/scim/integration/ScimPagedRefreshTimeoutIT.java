package sh.libre.scim.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;

/**
 * A sync-refresh run longer than the transaction timeout fails at Keycloak's
 * own commit. Each page committed on its own before that, so every user was
 * pushed and kept its mapping.
 *
 * <p>This class starts a second Keycloak container. The shared one runs with
 * the default transaction timeout, which no test run can outlast.
 */
class ScimPagedRefreshTimeoutIT extends IntegrationTestBase {

    /**
     * The timeout applies to every transaction in the container, including the
     * ones that start it and migrate its schema. Thirty seconds leaves those
     * room and still sits well below the length of the run under test.
     */
    private static final int TRANSACTION_TIMEOUT_SECONDS = 30;

    /** Twenty users behind a 2-second endpoint take about 40 seconds. */
    private static final int USERS = 20;

    private static final int CREATE_DELAY_MILLIS = 2000;

    /** Four users per page take about 8 seconds, so no page nears its own budget. */
    private static final String PAGE_SIZE = "4";

    private static KeycloakContainer shortTimeoutKeycloak;
    private static Keycloak shortTimeoutAdmin;

    @BeforeAll
    static void startShortTimeoutKeycloak() {
        shortTimeoutKeycloak = new KeycloakContainer(KEYCLOAK_IMAGE)
            .withProviderLibsFrom(List.of(PLUGIN_JAR))
            .withNetwork(network)
            .withEnv("JAVA_OPTS_APPEND",
                "-Dquarkus.transaction-manager.default-transaction-timeout="
                    + TRANSACTION_TIMEOUT_SECONDS + "s");
        shortTimeoutKeycloak.start();
        shortTimeoutAdmin = AdminClients.forContainer(shortTimeoutKeycloak);
    }

    @AfterAll
    static void stopShortTimeoutKeycloak() {
        if (shortTimeoutAdmin != null) {
            shortTimeoutAdmin.close();
        }
        if (shortTimeoutKeycloak != null) {
            shortTimeoutKeycloak.stop();
        }
    }

    /** A create that holds the page open, so the run outlasts the timeout. */
    private void stubSlowUserCreate() {
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse()
                .withStatus(201)
                .withFixedDelay(CREATE_DELAY_MILLIS)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "%s",
                      "userName": "placeholder",
                      "displayName": "placeholder",
                      "active": true,
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""".formatted(UUID.randomUUID()))));
    }

    private int userPutCount() {
        return wireMock.countRequestsMatching(
            putRequestedFor(urlPathMatching("/Users/.*")).build()).getCount();
    }

    /** A realm on the short-timeout container, with the SCIM provider and no directory. */
    private RealmResource newShortTimeoutRealm() {
        String realmName = "it-" + UUID.randomUUID().toString().substring(0, 8);
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(realmName);
        realmRep.setEnabled(true);
        shortTimeoutAdmin.realms().create(realmRep);
        RealmResource realm = shortTimeoutAdmin.realm(realmName);
        addScimStorageProvider(realm, cfg -> {
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("sync-page-size", PAGE_SIZE);
        });
        return realm;
    }

    @Test
    void committedPagesSurviveARunLongerThanTheTransactionTimeout() {
        stubSlowUserCreate();
        RealmResource realm = newShortTimeoutRealm();
        for (int i = 0; i < USERS; i++) {
            createAdminUser(realm, "slow-" + i, "slow-" + i + "@test.local");
        }
        String componentId = scimComponent(realm).getId();

        // Keycloak wraps the sync in a transaction of its own. The run outlasts
        // the timeout, so the reaper cancels it and the commit fails.
        assertThrows(WebApplicationException.class, () ->
            realm.userStorage().syncUsers(componentId, "triggerFullSync"));

        // Each page committed before that, so every user reached the endpoint.
        await().atMost(60, SECONDS).untilAsserted(() -> assertEquals(USERS, perUserPostCount()));

        // The second run has no delay, so it fits inside the timeout. The
        // mappings of the failed run survive, so these users are replaced.
        wireMock.resetAll();
        stubScimUserCreateOk();
        stubScimUserUpdateOk();

        var second = realm.userStorage().syncUsers(componentId, "triggerFullSync");

        assertEquals(USERS, second.getUpdated());
        assertEquals(0, second.getFailed());
        assertEquals(0, perUserPostCount(), "a mapped user is replaced, not created again");
        assertEquals(USERS, userPutCount());
    }
}
