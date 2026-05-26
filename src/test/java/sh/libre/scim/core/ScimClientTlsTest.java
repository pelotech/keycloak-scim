package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScimClientTlsTest {

    private static final String PROP = "scim.tls.insecureHostnameVerification";

    @AfterEach
    void cleanup() {
        System.clearProperty(PROP);
    }

    @Test
    void tlsHostnameVerificationDisabled_defaultsFalse() {
        System.clearProperty(PROP);
        assertThat(ScimClient.tlsHostnameVerificationDisabled()).isFalse();
    }

    @Test
    void tlsHostnameVerificationDisabled_trueWhenPropertySet() {
        System.setProperty(PROP, "true");
        assertThat(ScimClient.tlsHostnameVerificationDisabled()).isTrue();
    }

    @Test
    void tlsHostnameVerificationDisabled_falseWhenPropertyExplicitlyFalse() {
        System.setProperty(PROP, "false");
        assertThat(ScimClient.tlsHostnameVerificationDisabled()).isFalse();
    }
}
