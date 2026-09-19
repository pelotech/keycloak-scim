package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;

class ScimClientRetryTest {

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503, 504})
    void retryableStatuses(int status) {
        assertThat(ScimClient.isRetryableStatus(status)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 400, 401, 403, 404, 409})
    void nonRetryableStatuses(int status) {
        assertThat(ScimClient.isRetryableStatus(status)).isFalse();
    }

    @Test
    void defaultRetryConfigKeepsTenAttempts() {
        assertThat(ScimClient.defaultRetryConfig().getMaxAttempts()).isEqualTo(10);
    }

    @Test
    void batchSyncRetryConfigUsesFourAttempts() {
        assertThat(ScimClient.batchSyncRetryConfig().getMaxAttempts()).isEqualTo(4);
    }

    @Test
    void batchSyncIntervalStartsAtHalfASecondAndIsCappedAtFive() {
        var interval = ScimClient.batchSyncInterval();
        assertThat(interval.apply(1)).isEqualTo(500L);
        assertThat(interval.apply(2)).isEqualTo(750L);
        assertThat(interval.apply(20)).isEqualTo(5000L);
    }

    @Test
    void forBatchSyncBuildsAClientWithTheBatchPolicy() {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId("comp-batch");

        var client = ScimClient.forBatchSync(model, mock(KeycloakSession.class));
        try {
            assertThat(client.registry.getDefaultConfig().getMaxAttempts()).isEqualTo(4);
        } finally {
            client.close();
        }
    }
}
