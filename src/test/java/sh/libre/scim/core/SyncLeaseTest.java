package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SyncLeaseTest {

    private static final Duration STALE = SyncLease.STALE_THRESHOLD;
    private static final long NOW = 1_000_000_000L;

    private final FakeLeaseStore store = new FakeLeaseStore();
    private final MutableClock clock = new MutableClock(NOW);

    /** A clock the tests move by hand. */
    static final class MutableClock extends java.time.Clock {
        private long millis;
        MutableClock(long millis) { this.millis = millis; }
        void advance(Duration d) { millis += d.toMillis(); }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    private SyncLease lease() {
        return new SyncLease(store, "comp-1", clock);
    }

    private void seed(String holder, long renewedAt) {
        store.ensureRow("comp-1");
        store.inOneTransaction(l -> { l.take("comp-1", holder, renewedAt); return null; });
    }

    @Test
    void acquireOnAnEmptyStoreTakesTheLease() {
        var lease = lease();
        assertThat(lease.acquire()).isEqualTo(SyncLease.Decision.TAKE);
        assertThat(store.rows.get("comp-1").holder).isEqualTo(lease.token());
        assertThat(store.rows.get("comp-1").renewedAt).isEqualTo(NOW);
    }

    @Test
    void acquireAgainstALiveHolderRefusesAndWritesNothing() {
        seed("other", NOW - 1000);
        assertThat(lease().acquire()).isEqualTo(SyncLease.Decision.REFUSE);
        assertThat(store.rows.get("comp-1").holder).isEqualTo("other");
    }

    @Test
    void acquireAgainstAStaleHolderTakesOver() {
        seed("other", NOW - STALE.toMillis() - 1);
        var lease = lease();
        assertThat(lease.acquire()).isEqualTo(SyncLease.Decision.TAKE);
        assertThat(store.rows.get("comp-1").holder).isEqualTo(lease.token());
    }

    /** The row exists, so the ensure step fails on a duplicate. That is the common case and not fatal. */
    @Test
    void aFailedEnsureStepStillAcquiresWhenTheRowExists() {
        store.ensureRow("comp-1");
        assertThat(lease().acquire()).isEqualTo(SyncLease.Decision.TAKE);
    }

    /** A live lease is refused whoever holds it, including this run. */
    @Test
    void aRunDoesNotTakeItsOwnLiveLeaseTwice() {
        var lease = lease();
        assertThat(lease.acquire()).isEqualTo(SyncLease.Decision.TAKE);
        clock.advance(Duration.ofSeconds(1));
        assertThat(lease.acquire()).isEqualTo(SyncLease.Decision.REFUSE);
        assertThat(store.rows.get("comp-1").holder).isEqualTo(lease.token());
    }

    @Test
    void aMissingRowAfterTheEnsureStepIsAFailure() {
        store.ensureFailure = new IllegalStateException("no privilege");
        assertThatThrownBy(() -> lease().acquire())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no privilege");
    }

    @Test
    void releaseClearsOwnHolderOnly() {
        var lease = lease();
        lease.acquire();
        store.rows.get("comp-1").holder = "other"; // taken over
        lease.release();
        assertThat(store.rows.get("comp-1").holder).isEqualTo("other");
    }

    @Test
    void releaseClearsTheHolderWhenStillOwned() {
        var lease = lease();
        lease.acquire();
        lease.release();
        assertThat(store.rows.get("comp-1").holder).isNull();
    }

    @Test
    void releaseSwallowsAStoreFailure() {
        var lease = lease();
        lease.acquire();
        store.releaseFailure = new IllegalStateException("db gone");
        lease.release(); // must not throw; the lease goes stale on its own
    }

    @Test
    void eachLeaseHasItsOwnToken() {
        assertThat(lease().token()).isNotEqualTo(lease().token());
    }

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
