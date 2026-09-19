package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;

import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.resources.User;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

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
    void syncPageRetryConfigUsesFourAttempts() {
        assertThat(ScimClient.syncPageRetryConfig().getMaxAttempts()).isEqualTo(4);
    }

    /**
     * Asserts the actual waits a sync-page retry performs, not the interval
     * function's shape in isolation. With four attempts there are only three
     * waits (500, 750, 1125ms); the cap at 5s never applies at this attempt
     * count, so an assertion against it would pass even if someone quietly
     * changed the backoff to grow unbounded again.
     */
    @Test
    void syncPageIntervalWaitsSumToTheExpectedBudget() {
        var config = ScimClient.syncPageRetryConfig();
        var interval = ScimClient.syncPageInterval();

        long totalWaitMillis = 0;
        for (int attempt = 1; attempt < config.getMaxAttempts(); attempt++) {
            totalWaitMillis += interval.apply(attempt);
        }

        assertThat(totalWaitMillis).isEqualTo(2375L);
    }

    /**
     * Pins the attempt count and the retry-on-result predicate together: a
     * retry built from the sync-page config must call the supplier exactly
     * four times when every response is a retryable 503. The interval
     * function is swapped for a 1ms one so the test doesn't actually wait
     * out the real backoff; that shape is covered separately above.
     */
    @Test
    @SuppressWarnings("unchecked")
    void syncPageRetryInvokesSupplierExactlyFourTimesOn503() {
        var fastConfig = RetryConfig.from(ScimClient.syncPageRetryConfig())
            .intervalFunction(IntervalFunction.of(1))
            .build();
        var retry = Retry.of("sync-page-test", fastConfig);
        var calls = new AtomicInteger();

        ServerResponse<User> response = mock(ServerResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(response.getHttpStatus()).thenReturn(503);

        retry.executeSupplier(() -> {
            calls.incrementAndGet();
            return response;
        });

        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void forSyncPageBuildsAClientWithTheSyncPagePolicy() {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId("comp-page");

        var client = ScimClient.forSyncPage(model, mock(KeycloakSession.class));
        try {
            assertThat(client.registry.getDefaultConfig().getMaxAttempts()).isEqualTo(4);
        } finally {
            client.close();
        }
    }

    private static ComponentModel componentModel(String id) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId(id);
        return model;
    }

    @Test
    void defaultClientUsesThirtySecondHttpTimeouts() {
        var client = new ScimClient(componentModel("comp-default"), mock(KeycloakSession.class));
        try {
            var scimClientConfig = client.genScimClientConfig();
            assertThat(scimClientConfig.getConnectTimeout()).isEqualTo(30);
            assertThat(scimClientConfig.getRequestTimeout()).isEqualTo(30);
            assertThat(scimClientConfig.getSocketTimeout()).isEqualTo(30);
        } finally {
            client.close();
        }
    }

    @Test
    void forSyncPageClientUsesFiveSecondHttpTimeouts() {
        var client = ScimClient.forSyncPage(componentModel("comp-page-timeouts"), mock(KeycloakSession.class));
        try {
            var scimClientConfig = client.genScimClientConfig();
            assertThat(scimClientConfig.getConnectTimeout()).isEqualTo(5);
            assertThat(scimClientConfig.getRequestTimeout()).isEqualTo(5);
            assertThat(scimClientConfig.getSocketTimeout()).isEqualTo(5);
        } finally {
            client.close();
        }
    }
}
