package sh.libre.scim.storage;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScimStorageProviderFactoryTest {

    // ---------------------------------------------------------------------------
    // Task 12 — config schema
    // ---------------------------------------------------------------------------

    @Test
    void authModeOptions_includesClientCredentials() {
        var factory = new ScimStorageProviderFactory();
        var authMode = factory.getConfigProperties().stream()
            .filter(p -> "auth-mode".equals(p.getName()))
            .findFirst().orElseThrow();
        assertThat(authMode.getOptions()).contains("CLIENT_CREDENTIALS");
    }

    @Test
    void configProperties_includeOauthFields() {
        var factory = new ScimStorageProviderFactory();
        var names = factory.getConfigProperties().stream()
            .map(p -> p.getName())
            .toList();
        assertThat(names).contains(
            "oauth-client-id",
            "oauth-client-secret",
            "oauth-token-endpoint",
            "oauth-scope");
    }

    // ---------------------------------------------------------------------------
    // Task 13 — validation
    // ---------------------------------------------------------------------------

    /** ComponentModel with the given auth-mode and extras; reconciler-enabled=false suppresses ReconcilerConfigValidator. */
    private static ComponentModel componentWithAuthMode(String authMode, Map<String, String> extra) {
        var m = new ComponentModel();
        m.put("auth-mode", authMode);
        m.put("reconciler-enabled", "false");
        extra.forEach(m::put);
        return m;
    }

    /** Mock realm returning an empty getComponentsStream(). */
    private static RealmModel mockRealm() {
        var realm = mock(RealmModel.class);
        when(realm.getComponentsStream()).thenReturn(Stream.empty());
        return realm;
    }

    @Test
    void clientCredentials_missingClientId_rejected() {
        var factory = new ScimStorageProviderFactory();
        var model = componentWithAuthMode("CLIENT_CREDENTIALS", Map.of(
            "oauth-client-secret", "s",
            "oauth-token-endpoint", "https://kc/token"));
        assertThatThrownBy(() ->
                factory.validateConfiguration(mock(KeycloakSession.class), mockRealm(), model))
            .isInstanceOf(ComponentValidationException.class)
            .hasMessageContaining("oauth-client-id");
    }

    @Test
    void clientCredentials_missingClientSecret_rejected() {
        var factory = new ScimStorageProviderFactory();
        var model = componentWithAuthMode("CLIENT_CREDENTIALS", Map.of(
            "oauth-client-id", "id",
            "oauth-token-endpoint", "https://kc/token"));
        assertThatThrownBy(() ->
                factory.validateConfiguration(mock(KeycloakSession.class), mockRealm(), model))
            .isInstanceOf(ComponentValidationException.class)
            .hasMessageContaining("oauth-client-secret");
    }

    @Test
    void clientCredentials_missingTokenEndpoint_rejected() {
        var factory = new ScimStorageProviderFactory();
        var model = componentWithAuthMode("CLIENT_CREDENTIALS", Map.of(
            "oauth-client-id", "id",
            "oauth-client-secret", "s"));
        assertThatThrownBy(() ->
                factory.validateConfiguration(mock(KeycloakSession.class), mockRealm(), model))
            .isInstanceOf(ComponentValidationException.class)
            .hasMessageContaining("oauth-token-endpoint");
    }

    @Test
    void clientCredentials_malformedTokenEndpoint_rejected() {
        var factory = new ScimStorageProviderFactory();
        var model = componentWithAuthMode("CLIENT_CREDENTIALS", Map.of(
            "oauth-client-id", "id",
            "oauth-client-secret", "s",
            "oauth-token-endpoint", "not-a-url"));
        assertThatThrownBy(() ->
                factory.validateConfiguration(mock(KeycloakSession.class), mockRealm(), model))
            .isInstanceOf(ComponentValidationException.class)
            .hasMessageContaining("oauth-token-endpoint");
    }

    @Test
    void clientCredentials_allFieldsPresent_accepted() {
        var factory = new ScimStorageProviderFactory();
        var model = componentWithAuthMode("CLIENT_CREDENTIALS", Map.of(
            "oauth-client-id", "id",
            "oauth-client-secret", "s",
            "oauth-token-endpoint", "https://kc/token"));
        assertThatCode(() ->
                factory.validateConfiguration(mock(KeycloakSession.class), mockRealm(), model))
            .doesNotThrowAnyException();
    }

    @Test
    void clientCredentials_uppercaseScheme_accepted() {
        var factory = new ScimStorageProviderFactory();
        var model = componentWithAuthMode("CLIENT_CREDENTIALS", Map.of(
            "oauth-client-id", "id",
            "oauth-client-secret", "s",
            "oauth-token-endpoint", "HTTPS://kc/token"));
        assertThatCode(() ->
                factory.validateConfiguration(mock(KeycloakSession.class), mockRealm(), model))
            .doesNotThrowAnyException();
    }

    @Test
    void clientCredentials_noHost_rejected() {
        var factory = new ScimStorageProviderFactory();
        var model = componentWithAuthMode("CLIENT_CREDENTIALS", Map.of(
            "oauth-client-id", "id",
            "oauth-client-secret", "s",
            "oauth-token-endpoint", "https:///path"));
        assertThatThrownBy(() ->
                factory.validateConfiguration(mock(KeycloakSession.class), mockRealm(), model))
            .isInstanceOf(ComponentValidationException.class)
            .hasMessageContaining("oauth-token-endpoint");
    }

    @Test
    void legacyAuthMode_withJunkOauthFields_unaffected() {
        var factory = new ScimStorageProviderFactory();
        var model = componentWithAuthMode("BEARER", Map.of(
            "auth-pass", "t",
            // oauth-* fields are ignored when auth-mode != CLIENT_CREDENTIALS
            "oauth-token-endpoint", "not-a-url",
            "oauth-client-id", ""));
        assertThatCode(() ->
                factory.validateConfiguration(mock(KeycloakSession.class), mockRealm(), model))
            .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------
    // Task 4.2 — sync-on-error config property
    // ---------------------------------------------------------------------------

    @Test
    void syncOnErrorProperty_presentWithDefaultAuto() {
        var factory = new ScimStorageProviderFactory();
        var syncOnError = factory.getConfigProperties().stream()
            .filter(p -> "sync-on-error".equals(p.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("sync-on-error property not found"));
        assertThat(syncOnError.getDefaultValue()).isEqualTo("auto");
        assertThat(syncOnError.getOptions()).contains("auto", "continue", "stop");
    }

    @Test
    void legacyAuthModes_unchanged() {
        var factory = new ScimStorageProviderFactory();
        var none = componentWithAuthMode("NONE", Map.of());
        var bearer = componentWithAuthMode("BEARER", Map.of("auth-pass", "t"));
        var basic = componentWithAuthMode("BASIC_AUTH", Map.of("auth-user", "u", "auth-pass", "p"));

        assertThatCode(() ->
                factory.validateConfiguration(mock(KeycloakSession.class), mockRealm(), none))
            .doesNotThrowAnyException();
        assertThatCode(() ->
                factory.validateConfiguration(mock(KeycloakSession.class), mockRealm(), bearer))
            .doesNotThrowAnyException();
        assertThatCode(() ->
                factory.validateConfiguration(mock(KeycloakSession.class), mockRealm(), basic))
            .doesNotThrowAnyException();
    }
}
