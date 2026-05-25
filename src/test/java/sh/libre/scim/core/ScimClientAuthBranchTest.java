package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.net.HttpHeaders;
import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.resources.User;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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

    @Test
    @SuppressWarnings("unchecked")
    void sendWithAuthRefresh_retriesOnceOn401() {
        OAuthClientCredentialsTokenSource.resetCacheForTests();
        var model = componentModelFor("CLIENT_CREDENTIALS");

        // Use a counting minter injected directly — no HTTP calls.
        var callCount = new AtomicInteger();
        var ts = new OAuthClientCredentialsTokenSource(
            model.getId(),
            OAuthConfig.from(model),
            cfg -> {
                int n = callCount.incrementAndGet();
                return n == 1
                    ? new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.first", 300)
                    : new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.second", 300);
            });

        // Use the test constructor to inject the stub token source directly.
        var client = new ScimClient(model, mock(KeycloakSession.class), ts);

        var attempts = new AtomicInteger();

        ServerResponse<User> r401 = mock(ServerResponse.class);
        when(r401.getHttpStatus()).thenReturn(401);
        ServerResponse<User> r201 = mock(ServerResponse.class);
        when(r201.getHttpStatus()).thenReturn(201);

        Supplier<ServerResponse<User>> op = () -> {
            int n = attempts.incrementAndGet();
            return n == 1 ? r401 : r201;
        };

        var result = client.sendWithAuthRefresh(op);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(result.getHttpStatus()).isEqualTo(201);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendWithAuthRefresh_retriesOnceOn403() {
        OAuthClientCredentialsTokenSource.resetCacheForTests();
        var model = componentModelFor("CLIENT_CREDENTIALS");

        var callCount = new AtomicInteger();
        var ts = new OAuthClientCredentialsTokenSource(
            model.getId(),
            OAuthConfig.from(model),
            cfg -> {
                int n = callCount.incrementAndGet();
                return n == 1
                    ? new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.first", 300)
                    : new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.second", 300);
            });

        var client = new ScimClient(model, mock(KeycloakSession.class), ts);

        var attempts = new AtomicInteger();

        ServerResponse<User> r403 = mock(ServerResponse.class);
        when(r403.getHttpStatus()).thenReturn(403);
        ServerResponse<User> r201 = mock(ServerResponse.class);
        when(r201.getHttpStatus()).thenReturn(201);

        Supplier<ServerResponse<User>> op = () -> {
            int n = attempts.incrementAndGet();
            return n == 1 ? r403 : r201;
        };

        var result = client.sendWithAuthRefresh(op);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(result.getHttpStatus()).isEqualTo(201);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendWithAuthRefresh_noTokenSource_doesNotRetry() {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "BEARER");
        config.putSingle("auth-pass", "tok");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId("comp-bearer2");

        var client = new ScimClient(model, mock(KeycloakSession.class));

        var attempts = new AtomicInteger();
        ServerResponse<User> r401 = mock(ServerResponse.class);
        when(r401.getHttpStatus()).thenReturn(401);

        Supplier<ServerResponse<User>> op = () -> {
            attempts.incrementAndGet();
            return r401;
        };

        var result = client.sendWithAuthRefresh(op);

        // Without a token source, no retry — exactly one invocation.
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(result.getHttpStatus()).isEqualTo(401);
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
