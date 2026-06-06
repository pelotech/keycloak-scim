package sh.libre.scim.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import de.captaingoldfish.scim.sdk.client.http.BasicAuth;
import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.resources.base.ScimObjectNode;

import org.keycloak.component.ComponentModel;

import com.google.common.net.HttpHeaders;

/**
 * Owns the outbound-auth concern for a {@link ScimClient}: the HTTP header maps
 * handed to the SCIM SDK request builder, and the token-refresh-on-401/403 retry
 * for {@code CLIENT_CREDENTIALS} mode.
 *
 * <p>{@link #headers()} returns the live map instance (NOT a copy): it is shared
 * by reference with the SDK request builder via {@code ScimClient.genScimClientConfig},
 * and {@link #refreshAuthHeader()} mutates that same instance so a re-minted token
 * is picked up on the retry. Returning a defensive copy here would silently break
 * token refresh.
 */
class ScimAuthHeaders {

    private final Map<String, String> defaultHeaders = new HashMap<>();
    private final Map<String, String> expectedResponseHeaders = new HashMap<>();
    final OAuthClientCredentialsTokenSource tokenSource;

    ScimAuthHeaders(ComponentModel model) {
        this(model, buildTokenSourceFromModel(model));
    }

    // package-private for tests: inject an explicit token source (stub minter)
    // so unit tests avoid real HTTP.
    ScimAuthHeaders(ComponentModel model, OAuthClientCredentialsTokenSource tokenSource) {
        this.tokenSource = tokenSource;

        if (tokenSource != null) {
            defaultHeaders.put(HttpHeaders.AUTHORIZATION, tokenSource.currentAuthorizationHeader());
        } else {
            switch (model.get("auth-mode")) {
                case "BEARER":
                    defaultHeaders.put(HttpHeaders.AUTHORIZATION,
                        BearerAuthentication(model.get("auth-pass")));
                    break;
                case "BASIC_AUTH":
                    defaultHeaders.put(HttpHeaders.AUTHORIZATION,
                        BasicAuthentication(model.get("auth-user"), model.get("auth-pass")));
                    break;
            }
        }

        defaultHeaders.put(HttpHeaders.CONTENT_TYPE, model.get("content-type"));
    }

    private static OAuthClientCredentialsTokenSource buildTokenSourceFromModel(ComponentModel model) {
        if ("CLIENT_CREDENTIALS".equals(model.get("auth-mode"))) {
            return new OAuthClientCredentialsTokenSource(
                model.getId(),
                OAuthConfig.from(model),
                new OAuthClientCredentialsTokenSource.HttpTokenMinter(model.getId()));
        }
        return null;
    }

    Map<String, String> headers() {
        return defaultHeaders;
    }

    Map<String, String> expectedResponseHeaders() {
        return expectedResponseHeaders;
    }

    protected String BasicAuthentication(String username, String password) {
        return BasicAuth.builder()
            .username(username)
            .password(password)
            .build()
            .getAuthorizationHeaderValue();
    }

    protected String BearerAuthentication(String token) {
        return "Bearer " + token;
    }

    private void refreshAuthHeader() {
        assert tokenSource != null;
        defaultHeaders.put(HttpHeaders.AUTHORIZATION, tokenSource.currentAuthorizationHeader());
    }

    <S extends ScimObjectNode> ServerResponse<S> sendWithAuthRefresh(Supplier<ServerResponse<S>> op) {
        if (tokenSource == null) {
            return op.get();
        }
        refreshAuthHeader();
        ServerResponse<S> r = op.get();
        int status = r.getHttpStatus();
        if (status == 401 || status == 403) {
            tokenSource.invalidate();
            refreshAuthHeader();
            r = op.get();
        }
        return r;
    }
}
