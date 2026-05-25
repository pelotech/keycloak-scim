package sh.libre.scim.integration;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the CLIENT_CREDENTIALS auth mode.
 *
 * <p>The scaffold sets up a real Keycloak service-account client in the
 * test realm. The SCIM provider component is configured to use that
 * client's credentials to obtain tokens from Keycloak's own token
 * endpoint. WireMock acts as the SCIM sink and captures outbound
 * requests including the Authorization header.
 *
 * <p>Task 18: scaffold + sanity test ({@link #harnessLoadsAndComponentConfiguresCleanly}).
 */
class ScimOidcAuthIT extends IntegrationTestBase {

    // Stable identifiers for the service-account client created in each test's realm.
    private static final String SA_CLIENT_ID = "scim-pusher";
    private static final String SA_CLIENT_SECRET = "client-secret-value";

    // Per-test state set up in @BeforeEach.
    private String realmName;
    private RealmResource realm;
    private String scimComponentId;

    @BeforeEach
    void setUpRealmAndComponent() {
        realmName = "oidc-" + UUID.randomUUID().toString().substring(0, 8);
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(realmName);
        realmRep.setEnabled(true);
        admin.realms().create(realmRep);
        realm = admin.realm(realmName);

        createServiceAccountClient(realm);

        // oauth-token-endpoint must be the URL the plugin (running inside
        // the Keycloak container) uses to reach the token endpoint. Since
        // the plugin runs inside the same JVM as Keycloak, it can address
        // the server via localhost on Keycloak's internal HTTP port (8080).
        String internalTokenEndpoint = "http://localhost:8080/realms/"
            + realmName + "/protocol/openid-connect/token";

        scimComponentId = addScimClientCredentialsProvider(realm, internalTokenEndpoint);
        enableScimEventListener(realm);
    }

    /**
     * Sanity check: the scaffold's realm, service-account client, and
     * SCIM provider component all configure cleanly.
     */
    @Test
    void harnessLoadsAndComponentConfiguresCleanly() {
        // SCIM provider component has CLIENT_CREDENTIALS auth-mode and correct client-id.
        var componentRep = realm.components().component(scimComponentId).toRepresentation();
        assertThat(componentRep.getConfig().getFirst("auth-mode")).isEqualTo("CLIENT_CREDENTIALS");
        assertThat(componentRep.getConfig().getFirst("oauth-client-id")).isEqualTo(SA_CLIENT_ID);

        // Service-account client exists in the realm with the right config.
        List<ClientRepresentation> clients = realm.clients().findByClientId(SA_CLIENT_ID);
        assertThat(clients).hasSize(1);
        ClientRepresentation saClient = clients.get(0);
        assertThat(saClient.isServiceAccountsEnabled()).isTrue();
        assertThat(saClient.getClientAuthenticatorType()).isEqualTo("client-secret");
    }

    // ---------- helpers ----------

    /**
     * Reconfigures the SCIM provider component's {@code oauth-token-endpoint}
     * to point at a WireMock stub instead of Keycloak. The factory's
     * {@code onUpdate} hook automatically invalidates the token cache.
     */
    protected void useWireMockTokenEndpoint(String stubPath) {
        var componentResource = realm.components().component(scimComponentId);
        var rep = componentResource.toRepresentation();
        rep.getConfig().putSingle("oauth-token-endpoint", wireMock.baseUrl() + stubPath);
        componentResource.update(rep);
    }

    /**
     * Restores the SCIM provider component's {@code oauth-token-endpoint}
     * back to the real Keycloak endpoint.
     */
    protected void useKeycloakTokenEndpoint() {
        String internalTokenEndpoint = "http://localhost:8080/realms/"
            + realmName + "/protocol/openid-connect/token";
        var componentResource = realm.components().component(scimComponentId);
        var rep = componentResource.toRepresentation();
        rep.getConfig().putSingle("oauth-token-endpoint", internalTokenEndpoint);
        componentResource.update(rep);
    }

    /**
     * Creates a service-account (client_credentials) client in the given realm.
     */
    private void createServiceAccountClient(RealmResource targetRealm) {
        var client = new ClientRepresentation();
        client.setClientId(SA_CLIENT_ID);
        client.setSecret(SA_CLIENT_SECRET);
        client.setServiceAccountsEnabled(true);
        client.setClientAuthenticatorType("client-secret");
        client.setPublicClient(false);
        client.setStandardFlowEnabled(false);
        client.setDirectAccessGrantsEnabled(false);
        client.setEnabled(true);
        try (Response r = targetRealm.clients().create(client)) {
            if (r.getStatus() >= 400) {
                throw new IllegalStateException(
                    "service-account client create failed: " + r.getStatus());
            }
        }
    }

    /**
     * Creates a SCIM storage provider component configured with CLIENT_CREDENTIALS
     * auth mode and returns its component ID (extracted from the Location header).
     */
    private String addScimClientCredentialsProvider(RealmResource targetRealm,
                                                    String tokenEndpoint) {
        var scim = new ComponentRepresentation();
        scim.setName("test-scim-cc");
        scim.setProviderType("org.keycloak.storage.UserStorageProvider");
        scim.setProviderId("scim");
        var cfg = new MultivaluedHashMap<String, String>();
        cfg.putSingle("endpoint",
            "http://host.testcontainers.internal:" + wireMock.port());
        cfg.putSingle("auth-mode", "CLIENT_CREDENTIALS");
        cfg.putSingle("oauth-client-id", SA_CLIENT_ID);
        cfg.putSingle("oauth-client-secret", SA_CLIENT_SECRET);
        cfg.putSingle("oauth-token-endpoint", tokenEndpoint);
        cfg.putSingle("content-type", "application/scim+json");
        cfg.putSingle("propagation-user", "true");
        cfg.putSingle("propagation-group", "false");
        cfg.putSingle("enabled", "true");
        scim.setConfig(cfg);
        try (Response r = targetRealm.components().add(scim)) {
            if (r.getStatus() >= 400) {
                throw new IllegalStateException(
                    "SCIM provider create (CLIENT_CREDENTIALS) failed: " + r.getStatus());
            }
            String location = r.getLocation().getPath();
            return location.substring(location.lastIndexOf('/') + 1);
        }
    }
}
