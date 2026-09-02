package sh.libre.scim.integration;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.info.ServerInfoRepresentation;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test: a real Keycloak container starts with the shaded plugin JAR
 * mounted into /opt/keycloak/providers/, and the SCIM storage SPI and the
 * LDAP storage mapper factory both register without errors.
 *
 * Container startup takes ~30s. The Keycloak image (~500MB) is pulled on
 * first run and cached locally afterwards.
 *
 * To run: ./gradlew integrationTest
 */
@Testcontainers
class KeycloakPluginLoadsIT {

    private static final File PLUGIN_JAR = new File(
        System.getProperty(
            "keycloak.plugin.jar",
            "build/docker/keycloak-scim.jar"
        )
    );

    @Container
    static final KeycloakContainer keycloak =
        new KeycloakContainer(System.getProperty("keycloak.image", "quay.io/keycloak/keycloak:25.0.6"))
            .withProviderLibsFrom(List.of(PLUGIN_JAR));

    @Test
    void keycloakStartsWithPluginJarLoaded() {
        assertTrue(keycloak.isRunning(), "Keycloak container should be running");
        assertNotNull(keycloak.getAuthServerUrl(), "auth server URL should be exposed");
    }

    @Test
    void scimStorageProviderFactoryIsRegistered() {
        var admin = AdminClients.forContainer(keycloak);
        ServerInfoRepresentation info = admin.serverInfo().getInfo();

        var userStorageProviders = info.getComponentTypes()
            .get("org.keycloak.storage.UserStorageProvider");
        assertNotNull(userStorageProviders, "UserStorageProvider component type list should be present");
        assertTrue(
            userStorageProviders.stream().anyMatch(c -> "scim".equals(c.getId())),
            "SCIM UserStorageProvider factory ('scim') should be registered"
        );
    }

    @Test
    void scimLdapStorageMapperFactoryIsRegistered() {
        var admin = AdminClients.forContainer(keycloak);
        ServerInfoRepresentation info = admin.serverInfo().getInfo();

        var ldapMappers = info.getComponentTypes()
            .get("org.keycloak.storage.ldap.mappers.LDAPStorageMapper");
        assertNotNull(ldapMappers, "LDAPStorageMapper component type list should be present");
        assertTrue(
            ldapMappers.stream().anyMatch(c -> "scim-ldap-sync".equals(c.getId())),
            "SCIM LDAP mapper factory ('scim-ldap-sync') should be registered"
        );
    }

    @Test
    void scimReconcileResourceIsReachable() throws Exception {
        // RealmResourceProviders aren't surfaced via serverInfo, so probe the
        // route directly. POST with a bogus componentId: our handler returns
        // 404 with a JSON error body, which distinguishes "route registered,
        // component not found" from "route doesn't exist at all" (a plain
        // Keycloak 404 HTML page). The route requires an admin token, and the
        // master admin holds manage-users through the admin realm role.
        var admin = AdminClients.forContainer(keycloak);
        var response = postToReconcile("nope", admin.tokenManager().getAccessTokenString());

        org.junit.jupiter.api.Assertions.assertEquals(404, response.statusCode(),
            "expected 404 for unknown componentId");
        org.junit.jupiter.api.Assertions.assertTrue(response.body().contains("no SCIM provider component"),
            "expected our handler's JSON error body, got: " + response.body());
    }

    @Test
    void scimReconcileResourceRejectsUnauthenticatedCallers() throws Exception {
        var response = postToReconcile("nope", null);

        org.junit.jupiter.api.Assertions.assertEquals(401, response.statusCode(),
            "expected 401 without a bearer token, got: " + response.body());
    }

    private static java.net.http.HttpResponse<String> postToReconcile(
            String componentId, String token) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
            keycloak.getAuthServerUrl() + "/realms/master/scim-reconcile/" + componentId))
            .POST(java.net.http.HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return java.net.http.HttpClient.newHttpClient()
            .send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }
}
