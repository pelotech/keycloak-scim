package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import sh.libre.scim.core.OAuthClientCredentialsTokenSource.TokenEndpointException;

class OAuthClientCredentialsTokenSourceRetryTest {

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503, 504})
    void httpErrorWithRetryableStatusIsRetryable(int status) {
        assertThat(OAuthClientCredentialsTokenSource.isRetryableMintFailure(
            TokenEndpointException.http(status, "boom"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 401, 403, 404, 409})
    void httpErrorWithNonRetryableStatusIsNotRetryable(int status) {
        assertThat(OAuthClientCredentialsTokenSource.isRetryableMintFailure(
            TokenEndpointException.http(status, "config error"))).isFalse();
    }

    @Test
    void transportFaultIsRetryable() {
        assertThat(OAuthClientCredentialsTokenSource.isRetryableMintFailure(
            TokenEndpointException.transport("connection refused", new IOException()))).isTrue();
    }

    @Test
    void malformedResponseIsNotRetryable() {
        // A 2xx with a junk body throws IllegalStateException from parsing —
        // not transient, must not be retried.
        assertThat(OAuthClientCredentialsTokenSource.isRetryableMintFailure(
            new IllegalStateException("token response missing access_token"))).isFalse();
    }

    @Test
    void unrelatedThrowableIsNotRetryable() {
        assertThat(OAuthClientCredentialsTokenSource.isRetryableMintFailure(
            new RuntimeException("something else"))).isFalse();
    }
}
