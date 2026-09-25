package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SyncLeaseTest {

    private static final Duration STALE = SyncLease.STALE_THRESHOLD;
    private static final long NOW = 1_000_000_000L;

    @Test
    void noHolderIsTaken() {
        assertThat(SyncLease.decide(null, null, NOW)).isEqualTo(SyncLease.Decision.TAKE);
    }

    @Test
    void aLiveHolderIsRefused() {
        long renewed = NOW - STALE.toMillis() / 2;
        assertThat(SyncLease.decide("other", renewed, NOW)).isEqualTo(SyncLease.Decision.REFUSE);
    }

    @Test
    void aStaleHolderIsTaken() {
        long renewed = NOW - STALE.toMillis() - 1;
        assertThat(SyncLease.decide("other", renewed, NOW)).isEqualTo(SyncLease.Decision.TAKE);
    }

    /** The comparison is strict: an age equal to the threshold is not past it. */
    @Test
    void anAgeExactlyAtTheThresholdIsRefused() {
        long renewed = NOW - STALE.toMillis();
        assertThat(SyncLease.decide("other", renewed, NOW)).isEqualTo(SyncLease.Decision.REFUSE);
    }

    /** A holder with no renewal time is treated as stale; the row is malformed. */
    @Test
    void aHolderWithNoRenewalTimeIsTaken() {
        assertThat(SyncLease.decide("other", null, NOW)).isEqualTo(SyncLease.Decision.TAKE);
    }
}
