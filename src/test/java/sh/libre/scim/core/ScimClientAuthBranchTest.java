package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.common.net.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;

class ScimClientAuthBranchTest {

    @BeforeEach
    void setUp() {
        OAuthClientCredentialsTokenSource.resetCacheForTests();
    }

    @Test
    void clientCredentials_seedsBearerAuthHeaderFromTokenSource() {
        var model = componentModelFor("CLIENT_CREDENTIALS");
        // Pre-seed the JVM-wide CACHE so construction does not attempt real HTTP.
        var preSeeded = new OAuthClientCredentialsTokenSource(
            model.getId(),
            OAuthConfig.from(model),
            cfg -> new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.test", 300));
        // Calling currentAuthorizationHeader() populates the cache; ScimClient's own
        // OAuthClientCredentialsTokenSource will hit it instead of HttpTokenMinter.
        preSeeded.currentAuthorizationHeader();

        var client = new ScimClient(model, mock(KeycloakSession.class));

        assertThat(client.defaultHeaders.get(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer eyJ.test");
        assertThat(client.tokenSource).isNotNull();
    }

    @Test
    void bearer_doesNotConstructTokenSource() {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "BEARER");
        config.putSingle("auth-pass", "static-token");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId("comp-bearer");

        var client = new ScimClient(model, mock(KeycloakSession.class));

        assertThat(client.defaultHeaders.get(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer static-token");
        assertThat(client.tokenSource).isNull();
    }

    static ComponentModel componentModelFor(String authMode) {
        var m = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", authMode);
        config.putSingle("oauth-client-id", "id");
        config.putSingle("oauth-client-secret", "secret");
        config.putSingle("oauth-token-endpoint", "https://kc.example/realms/main/protocol/openid-connect/token");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        m.setConfig(config);
        m.setId("comp-1");
        return m;
    }
}
