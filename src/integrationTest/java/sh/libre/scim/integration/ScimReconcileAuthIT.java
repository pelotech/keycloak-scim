package sh.libre.scim.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authorization on the /scim-reconcile/* routes. Keycloak does not
 * authenticate RealmResourceProvider routes, so the provider does it itself:
 * every route needs a bearer token issued by the realm in the request path,
 * belonging to a user who holds that realm's manage-users admin role.
 *
 * <p>The reconcile route runs a real deprovisioning pass, so an open route
 * would let any caller who guessed a component id delete a realm's users from
 * the downstream SCIM service.
 */
class ScimReconcileAuthIT extends IntegrationTestBase {

    private static final String CLIENT_WITHOUT_ROLE = "it-no-admin-role";

    // ---------- reconcile route ----------

    @Test
    void reconcile_withoutToken_is401() throws Exception {
        var r = newRealmWithScimAndLdap();
        String componentId = scimComponent(r.realm()).getId();

        var response = postReconcile(r.name(), componentId, 48, null);

        assertEquals(401, response.statusCode(),
            "no Authorization header must not reach the reconciler; body was: " + response.body());
        assertTrue(response.body().contains("\"error\""),
            "expected our JSON error body, got: " + response.body());
    }

    @Test
    void reconcile_withGarbageToken_is401() throws Exception {
        var r = newRealmWithScimAndLdap();
        String componentId = scimComponent(r.realm()).getId();

        var response = postReconcile(r.name(), componentId, 48, "not-a-jwt");

        assertEquals(401, response.statusCode(),
            "an unverifiable token must be rejected; body was: " + response.body());
    }

    @Test
    void reconcile_withTokenFromAnotherRealm_is401() throws Exception {
        var r = newRealmWithScimAndLdap();
        String componentId = scimComponent(r.realm()).getId();

        // The master admin can administer this realm through the admin REST
        // API, but its token is issued by the master realm. Keycloak verifies
        // a bearer token against the realm of the current request, so it does
        // not authenticate here.
        var response = postReconcile(r.name(), componentId, 48, masterAdminToken());

        assertEquals(401, response.statusCode(),
            "a token from another realm must not authenticate; body was: " + response.body());
    }

    @Test
    void reconcile_withTokenLackingTheRole_is403() throws Exception {
        var r = newRealmWithScimAndLdap();
        String componentId = scimComponent(r.realm()).getId();
        createServiceAccountClient(r.name(), CLIENT_WITHOUT_ROLE, false);

        var response = postReconcile(
            r.name(), componentId, 48, serviceAccountToken(r.name(), CLIENT_WITHOUT_ROLE));

        assertEquals(403, response.statusCode(),
            "an authenticated caller without manage-users must be refused; body was: "
                + response.body());
        assertTrue(response.body().contains("manage-users"),
            "expected the error body to name the missing role, got: " + response.body());
    }

    @Test
    void reconcile_withTokenHoldingTheRole_is200() throws Exception {
        var r = newRealmWithScimAndLdap();
        String componentId = scimComponent(r.realm()).getId();

        // reconcileToken provisions a realm-local service account holding
        // realm-management manage-users.
        var response = postReconcile(r.name(), componentId, 48);

        assertEquals(200, response.statusCode(),
            "an authorized caller should get the normal response; body was: " + response.body());
        assertTrue(response.body().contains("\"deleted\":")
                && response.body().contains("\"groupsDeleted\":")
                && response.body().contains("\"userDeleteMode\":"),
            "expected the normal reconcile JSON body, got: " + response.body());
    }

    @Test
    void reconcile_unknownComponent_stillNeedsAuthFirst() throws Exception {
        var r = newRealmWithScimAndLdap();

        // The 404 path must not double as an unauthenticated probe for which
        // component ids exist.
        var unauthenticated = postReconcile(r.name(), "no-such-component", 48, null);
        assertEquals(401, unauthenticated.statusCode(),
            "auth is checked before component lookup; body was: " + unauthenticated.body());

        var authorized = postReconcile(r.name(), "no-such-component", 48, reconcileToken(r.name()));
        assertEquals(404, authorized.statusCode(),
            "an authorized caller still gets 404 for an unknown component; body was: "
                + authorized.body());
    }

    // ---------- metrics routes ----------

    @Test
    void metrics_withoutToken_is401() throws Exception {
        var response = sendToMetrics("GET", "metrics", "master", null);

        assertEquals(401, response.statusCode(),
            "GET /metrics must require a token; body was: " + response.body());
    }

    @Test
    void metricsReset_withoutToken_is401() throws Exception {
        var response = sendToMetrics("POST", "metrics/reset", "master", null);

        assertEquals(401, response.statusCode(),
            "POST /metrics/reset must require a token; body was: " + response.body());
    }

    @Test
    void metrics_withRealmLocalTokenLackingTheRole_is403() throws Exception {
        var r = newRealmWithScimAndLdap();
        createServiceAccountClient(r.name(), CLIENT_WITHOUT_ROLE, false);

        var response = sendToMetrics(
            "GET", "metrics", r.name(), serviceAccountToken(r.name(), CLIENT_WITHOUT_ROLE));

        assertEquals(403, response.statusCode(),
            "GET /metrics must require manage-users; body was: " + response.body());
    }

    /**
     * The master realm has no realm-management client, so the provider falls
     * back to the master admin client for the role lookup. The container's
     * admin user reaches manage-users there through the admin realm role.
     */
    @Test
    void metrics_withMasterAdminToken_is200() throws Exception {
        var response = sendToMetrics("GET", "metrics", "master", masterAdminToken());

        assertEquals(200, response.statusCode(),
            "the master admin should be authorized on the master realm; body was: "
                + response.body());
    }

    @Test
    void metricsReset_withMasterAdminToken_is204() throws Exception {
        var response = sendToMetrics("POST", "metrics/reset", "master", masterAdminToken());

        assertEquals(204, response.statusCode(),
            "the master admin should be authorized on the master realm; body was: "
                + response.body());
    }

    private HttpResponse<String> sendToMetrics(
            String method, String path, String realmName, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
            keycloak.getAuthServerUrl() + "/realms/" + realmName + "/scim-reconcile/" + path));
        if ("POST".equals(method)) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.GET();
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient()
            .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
