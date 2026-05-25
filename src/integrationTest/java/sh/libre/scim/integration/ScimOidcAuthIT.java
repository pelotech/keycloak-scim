package sh.libre.scim.integration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
 * <p>Task 19: JWT verification happy path ({@link #clientCredentialsHappyPath_jwtMintedAndVerifiable}).
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

    /**
     * Happy-path: Keycloak mints a real JWT for the service-account client,
     * the plugin forwards it as the Bearer header on the SCIM POST, and we
     * verify the JWT's signature against the realm's JWKS plus key claims.
     *
     * <p>This closes the live-Keycloak-token-endpoint gap: a JWT minted here
     * would be accepted by any downstream JWKS-verifying receiver configured
     * with the same realm's public keys.
     */
    @Test
    void clientCredentialsHappyPath_jwtMintedAndVerifiable() throws Exception {
        // Stub the SCIM /Users endpoint to accept creates.
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "%s",
                      "userName": "placeholder",
                      "displayName": "placeholder",
                      "active": true,
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""".formatted(UUID.randomUUID()))));

        // Trigger an admin user create — the SCIM event listener will fire
        // and the plugin will call the token endpoint, then POST to WireMock.
        createAdminUser(realm, "alice-jwt", "alice-jwt@test.local");

        // Wait until WireMock sees the SCIM POST for this user.
        awaitUserPostFor("alice-jwt");

        // Pull the Authorization header from the captured SCIM request.
        var scimRequests = wireMock.findAll(
            postRequestedFor(urlPathEqualTo("/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("alice-jwt"))));
        assertThat(scimRequests).isNotEmpty();
        String authHeader = scimRequests.get(0).getHeader("Authorization");
        assertThat(authHeader).startsWith("Bearer ");
        String jwt = authHeader.substring("Bearer ".length());

        // Fetch the realm's JWKS from the external (test-JVM-accessible) URL.
        // keycloak.getAuthServerUrl() is the externally-mapped URL the test JVM uses.
        String externalBaseUrl = keycloak.getAuthServerUrl();
        var jwksUrl = new URL(externalBaseUrl + "/realms/" + realmName
            + "/protocol/openid-connect/certs");
        var jwkSet = JWKSet.load(jwksUrl);

        // Verify the JWT's signature against the realm's public keys.
        var keySource = new ImmutableJWKSet<SecurityContext>(jwkSet);
        var keySelector = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector(keySelector);
        var claims = processor.process(jwt, null);

        // Verify key claims that a downstream receiver would check.
        assertThat(claims.getStringClaim("azp")).isEqualTo(SA_CLIENT_ID);
        // The issuer in the JWT is derived from the URL the plugin used when
        // calling the token endpoint (http://localhost:8080/realms/{realm}).
        // We assert structural correctness rather than a hard-coded base URL,
        // since Keycloak in dev mode reflects the Host header as the issuer.
        assertThat(claims.getIssuer()).endsWith("/realms/" + realmName);
        assertThat(claims.getExpirationTime()).isAfter(new Date());
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
