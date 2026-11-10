package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A 404 on update makes the plugin create the user again. The server gives the
 * new user a new remote id. Later updates must go to that new id, and must not
 * create the user a third time.
 */
class ScimRecreateRemoteIdIT extends IntegrationTestBase {

    private int userPostCount() {
        return wireMock.countRequestsMatching(
            postRequestedFor(urlPathEqualTo("/Users")).build()).getCount();
    }

    private void touchUser(TestRealm r, String userId, String firstName) {
        var rep = new UserRepresentation();
        rep.setFirstName(firstName);
        r.realm().users().get(userId).update(rep);
    }

    @Test
    void laterUpdatesGoToTheNewRemoteId() {
        String idA = "recreate-a";
        String idB = "recreate-b";
        stubScimUserCreateOk(idA);

        var r = newRealmWithScimAndLdap();
        enableScimEventListener(r.realm());
        String userId = createAdminUser(r.realm(), "recreate1", "recreate1@test.local");
        awaitUserPostFor("recreate1");
        assertEquals(1, userPostCount());

        // The server forgot user A. A new create returns B. B accepts updates.
        wireMock.stubFor(put(urlPathEqualTo("/Users/" + idA))
            .willReturn(aResponse().withStatus(404)));
        stubScimUserCreateOk(idB);
        wireMock.stubFor(put(urlPathEqualTo("/Users/" + idB))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "%s",
                      "userName": "placeholder",
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""".formatted(idB))));

        touchUser(r, userId, "First");
        await().atMost(20, SECONDS).until(() -> userPostCount() == 2);
        assertEquals(1, userPutCountFor(idA), "the first update went to the old id");

        touchUser(r, userId, "Second");
        await().atMost(20, SECONDS).untilAsserted(() ->
            assertEquals(1, userPutCountFor(idB), "the second update must go to the new id"));
        assertEquals(1, userPutCountFor(idA), "the old id must get no more updates");
        assertEquals(2, userPostCount(), "no second re-create");
    }
}
