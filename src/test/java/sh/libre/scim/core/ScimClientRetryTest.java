package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
}
