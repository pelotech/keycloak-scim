package sh.libre.scim.core;

public record OAuthConfig(String tokenEndpoint, String clientId, String clientSecret, String scope) {
    public static OAuthConfig from(org.keycloak.component.ComponentModel model) {
        return new OAuthConfig(
            model.get("oauth-token-endpoint"),
            model.get("oauth-client-id"),
            model.get("oauth-client-secret"),
            model.get("oauth-scope")
        );
    }
}
