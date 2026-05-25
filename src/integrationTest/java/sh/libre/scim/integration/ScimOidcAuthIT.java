package sh.libre.scim.integration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
 * <p>Task 20: token cache reused across subsequent events ({@link #cachedAcrossSubsequentEvents}).
 * <p>Task 21: bulk-import workers share the token cache ({@link #cachedAcrossAsyncWorkers}).
 * <p>Task 22: token re-minted after expires_in elapses ({@link #expiryTriggersRefresh}).
 */
class ScimOidcAuthIT extends IntegrationTestBase {

    // Stable identifiers for the service-account client created in each test's realm.
    private static final String SA_CLIENT_ID = "scim-pusher";
    private static final String SA_CLIENT_SECRET = "test-fixture-secret";

    // Per-test state set up in @BeforeEach.
    private String realmName;
    private RealmResource realm;
    private String scimComponentId;

    // Cached JWKS for JWT verification — lazily loaded, keyed to the realm.
    private JWKSet cachedJwkSet;

    @BeforeEach
    void setUpRealmAndComponent() {
        realmName = "oidc-" + UUID.randomUUID().toString().substring(0, 8);
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(realmName);
        realmRep.setEnabled(true);
        admin.realms().create(realmRep);
        realm = admin.realm(realmName);

        createServiceAccountClient(realm);

        scimComponentId = addScimClientCredentialsProvider(realm, internalTokenEndpoint());
        enableScimEventListener(realm);
        cachedJwkSet = null;
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
        var claims = verifyJwtAgainstRealmJwks(jwt);

        // Verify key claims that a downstream receiver would check.
        assertThat(claims.getStringClaim("azp")).isEqualTo(SA_CLIENT_ID);
        // The issuer in the JWT is derived from the URL the plugin used when
        // calling the token endpoint (http://localhost:8080/realms/{realm}).
        // We assert structural correctness rather than a hard-coded base URL,
        // since Keycloak in dev mode reflects the Host header as the issuer.
        assertThat(claims.getIssuer()).endsWith("/realms/" + realmName);
        // Require at least 10s of remaining lifetime — catches any accidental
        // misconfiguration of the token lifespan (Keycloak default is 5 min).
        assertThat(claims.getExpirationTime())
            .isAfter(new Date(System.currentTimeMillis() + 10_000));
    }

    /**
     * Task 20: Token cache is reused across subsequent SCIM events.
     *
     * <p>With a WireMock token endpoint returning a long-lived token,
     * two sequential admin-user creates must share the same bearer —
     * exactly one token POST, two SCIM POSTs carrying identical auth headers.
     */
    @Test
    void cachedAcrossSubsequentEvents() {
        String stubPath = "/oauth/token-cached";
        wireMock.stubFor(post(urlEqualTo(stubPath)).willReturn(okJson(
            "{\"access_token\":\"eyJ.cached\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        useWireMockTokenEndpoint(stubPath);

        // Stub the SCIM sink.
        stubScimUserCreateOk();

        createAdminUser(realm, "bob-1", "bob1@test.local");
        awaitUserPostFor("bob-1");
        createAdminUser(realm, "bob-2", "bob2@test.local");
        awaitUserPostFor("bob-2");

        // Exactly one /token POST; both /Users POSTs share the cached bearer.
        wireMock.verify(1, postRequestedFor(urlEqualTo(stubPath)));
        wireMock.verify(2, postRequestedFor(urlPathMatching("/Users.*"))
            .withHeader("Authorization", equalTo("Bearer eyJ.cached")));
    }

    /**
     * Task 21: Concurrent async workers from LDAP full sync share the token cache.
     *
     * <p>With N=2 LDAP users (the seed file has alice + bob), triggering a full
     * sync fans out via {@code ScimDispatcher.runAsync}. Despite concurrent dispatch,
     * only one token POST should happen because the per-componentId lock in
     * {@code OAuthClientCredentialsTokenSource} serializes mints.
     */
    @Test
    void cachedAcrossAsyncWorkers() throws Exception {
        String stubPath = "/oauth/token-async";
        wireMock.stubFor(post(urlEqualTo(stubPath)).willReturn(okJson(
            "{\"access_token\":\"eyJ.async\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        useWireMockTokenEndpoint(stubPath);

        // Stub the SCIM sink.
        stubScimUserCreateOk();

        // Add LDAP federation (seed has alice + bob = 2 users).
        String ldapId = addLdapFederation(realm);
        addLdapAttributeMapper(realm, ldapId, "email", "email", "mail");
        addLdapAttributeMapper(realm, ldapId, "firstName", "firstName", "givenName");
        addLdapAttributeMapper(realm, ldapId, "lastName", "lastName", "sn");
        attachScimMapper(realm, ldapId);

        // Trigger full sync; the async dispatcher fans out one task per user.
        realm.userStorage().syncUsers(ldapId, "triggerFullSync");

        // Wait for both SCIM POSTs.
        awaitScimPostCount(2);

        // One token mint despite two concurrent SCIM POSTs.
        wireMock.verify(1, postRequestedFor(urlEqualTo(stubPath)));
        wireMock.verify(2, postRequestedFor(urlPathMatching("/Users.*"))
            .withHeader("Authorization", equalTo("Bearer eyJ.async")));
    }

    /**
     * Task 22: Token is re-minted after {@code expires_in} elapses.
     *
     * <p>With {@code expires_in=1}, the skew logic computes
     * {@code refreshAt = now + max(0, 1 - 30) = now}, so every call to
     * {@code currentAuthorizationHeader()} mints a fresh token (the cache never
     * hits). Contrast with the cached case ({@link #cachedAcrossSubsequentEvents}):
     * {@code expires_in=300} yields exactly 1 token POST for 2 user creates.
     * Here we assert at-least 2 — one per user-create event — proving re-mint
     * behaviour. (The actual count can be higher because {@code ScimClient} calls
     * {@code currentAuthorizationHeader()} both in its constructor and in
     * {@code sendWithAuthRefresh}, so each event may trigger more than one mint.)
     */
    @Test
    void expiryTriggersRefresh() throws Exception {
        String stubPath = "/oauth/token-fast-expiry";
        wireMock.stubFor(post(urlEqualTo(stubPath)).willReturn(okJson(
            "{\"access_token\":\"eyJ.first\",\"token_type\":\"Bearer\",\"expires_in\":1}")));
        useWireMockTokenEndpoint(stubPath);

        // Stub the SCIM sink.
        stubScimUserCreateOk();

        createAdminUser(realm, "exp-user-1", "exp1@test.local");
        awaitUserPostFor("exp-user-1");

        // Small jitter to advance the wall clock past the minted instant;
        // refreshAt = now-at-mint, so any subsequent now is >= refreshAt.
        Thread.sleep(100);

        createAdminUser(realm, "exp-user-2", "exp2@test.local");
        awaitUserPostFor("exp-user-2");

        // At least 2 token POSTs (one per event minimum), confirming re-mint
        // on every call — not the cached 1-for-all behaviour.
        int tokenPosts = wireMock.findAll(postRequestedFor(urlEqualTo(stubPath))).size();
        assertThat(tokenPosts).isGreaterThanOrEqualTo(2);
    }

    // ---------- helpers ----------

    /**
     * Returns the Keycloak token endpoint URL as seen from inside the Keycloak
     * container. Port 8080 is Keycloak's internal HTTP port (not a mapped one).
     */
    private String internalTokenEndpoint() {
        return "http://localhost:8080/realms/" + realmName + "/protocol/openid-connect/token";
    }

    /**
     * Reconfigures the SCIM provider component's {@code oauth-token-endpoint}
     * to point at a WireMock stub instead of Keycloak. The factory's
     * {@code onUpdate} hook automatically invalidates the token cache.
     *
     * <p>Uses {@code host.testcontainers.internal} because Keycloak runs inside
     * Docker and must reach WireMock on the host via that alias.
     */
    protected void useWireMockTokenEndpoint(String stubPath) {
        var componentResource = realm.components().component(scimComponentId);
        var rep = componentResource.toRepresentation();
        rep.getConfig().putSingle("oauth-token-endpoint",
            "http://host.testcontainers.internal:" + wireMock.port() + stubPath);
        componentResource.update(rep);
    }

    /**
     * Restores the SCIM provider component's {@code oauth-token-endpoint}
     * back to the real Keycloak endpoint.
     */
    protected void useKeycloakTokenEndpoint() {
        var componentResource = realm.components().component(scimComponentId);
        var rep = componentResource.toRepresentation();
        rep.getConfig().putSingle("oauth-token-endpoint", internalTokenEndpoint());
        componentResource.update(rep);
    }

    /**
     * Strips the "Bearer " prefix from an Authorization header value.
     */
    private String stripBearer(String authorizationHeader) {
        assertThat(authorizationHeader).startsWith("Bearer ");
        return authorizationHeader.substring("Bearer ".length());
    }

    /**
     * Verifies a JWT's signature against the realm's JWKS endpoint and returns
     * the validated claims set. Fetches JWKS once per test (lazy + cached).
     */
    private JWTClaimsSet verifyJwtAgainstRealmJwks(String jwt) throws Exception {
        if (cachedJwkSet == null) {
            String externalBaseUrl = keycloak.getAuthServerUrl();
            URL jwksUrl = java.net.URI.create(
                externalBaseUrl + "/realms/" + realmName + "/protocol/openid-connect/certs"
            ).toURL();
            cachedJwkSet = JWKSet.load(jwksUrl);
        }
        var parsedJwt = SignedJWT.parse(jwt);
        JWSAlgorithm alg = parsedJwt.getHeader().getAlgorithm();
        var keySource = new ImmutableJWKSet<SecurityContext>(cachedJwkSet);
        var keySelector = new JWSVerificationKeySelector<SecurityContext>(alg, keySource);
        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector(keySelector);
        return processor.process(jwt, null);
    }

    /**
     * Polls WireMock until at least {@code expectedCount} POSTs to /Users have been recorded.
     */
    private void awaitScimPostCount(int expectedCount) {
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            int count = wireMock.countRequestsMatching(
                postRequestedFor(urlPathMatching("/Users.*")).build()
            ).getCount();
            assertThat(count).isGreaterThanOrEqualTo(expectedCount);
        });
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
