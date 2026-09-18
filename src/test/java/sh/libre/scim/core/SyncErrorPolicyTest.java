package sh.libre.scim.core;

import org.junit.jupiter.api.Test;
import sh.libre.scim.core.exceptions.InconsistentScimMappingException;
import sh.libre.scim.core.exceptions.InvalidResponseFromScimEndpointException;
import sh.libre.scim.core.exceptions.ScimPropagationException;

import static org.assertj.core.api.Assertions.assertThat;

class SyncErrorPolicyTest {

    private static ScimPropagationException transient_() {
        return new InvalidResponseFromScimEndpointException(503, "x");
    }

    private static ScimPropagationException permanent() {
        return new InconsistentScimMappingException("m");
    }

    private static ScimPropagationException throttled() {
        return new InvalidResponseFromScimEndpointException(429, "slow down");
    }

    @Test
    void autoStopsOnTransientNotThrottled() {
        assertThat(SyncErrorPolicy.AUTO.shouldStopRun(transient_())).isTrue();
        assertThat(SyncErrorPolicy.AUTO.shouldStopRun(permanent())).isFalse();
    }

    @Test
    void continueNeverStops() {
        assertThat(SyncErrorPolicy.CONTINUE.shouldStopRun(transient_())).isFalse();
        assertThat(SyncErrorPolicy.CONTINUE.shouldStopRun(permanent())).isFalse();
    }

    @Test
    void stopAlwaysStops() {
        assertThat(SyncErrorPolicy.STOP.shouldStopRun(transient_())).isTrue();
        assertThat(SyncErrorPolicy.STOP.shouldStopRun(permanent())).isTrue();
    }

    @Test
    void fromConfigDefaultsToAuto() {
        assertThat(SyncErrorPolicy.fromConfig(null)).isEqualTo(SyncErrorPolicy.AUTO);
        assertThat(SyncErrorPolicy.fromConfig("stop")).isEqualTo(SyncErrorPolicy.STOP);
        assertThat(SyncErrorPolicy.fromConfig("continue")).isEqualTo(SyncErrorPolicy.CONTINUE);
        assertThat(SyncErrorPolicy.fromConfig("auto")).isEqualTo(SyncErrorPolicy.AUTO);
    }

    @Test
    void autoContinuesOnThrottle() {
        assertThat(SyncErrorPolicy.AUTO.shouldStopRun(throttled())).isFalse();
    }

    @Test
    void continueContinuesOnThrottle() {
        assertThat(SyncErrorPolicy.CONTINUE.shouldStopRun(throttled())).isFalse();
    }

    @Test
    void autoStopsOnTransportFailure() {
        var transportFailure = InvalidResponseFromScimEndpointException.transport("refused", new RuntimeException());
        assertThat(SyncErrorPolicy.AUTO.shouldStopRun(transportFailure)).isTrue();
    }
}
