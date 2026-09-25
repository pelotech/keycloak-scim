package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SyncLeaseTest {

    private static final Duration STALE = SyncLease.STALE_THRESHOLD;
    private static final long NOW = 1_000_000_000L;

    private final FakeLeaseStore store = new FakeLeaseStore();
    private final MutableClock clock = new MutableClock(NOW);

    /** A clock the tests move by hand. Volatile, because a heartbeat thread may read it while a test advances it. */
    static final class MutableClock extends Clock {
        private volatile long millis;
        MutableClock(long millis) { this.millis = millis; }
        void advance(Duration d) { millis += d.toMillis(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
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
        assertThat(store.rows.get("comp-1").acquiredAt).isEqualTo(NOW);
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
        var ensureFailure = new IllegalStateException("no privilege");
        store.ensureFailure = ensureFailure;
        assertThatThrownBy(() -> lease().acquire())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no privilege")
            .hasCause(ensureFailure);
    }

    /** Proves the lenient catch stops at the ensure step: a lock failure is not swallowed. */
    @Test
    void aLockStepFailurePropagatesAndWritesNothing() {
        store.ensureRow("comp-1");
        store.lockFailure = new IllegalStateException("row lock timed out");
        assertThatThrownBy(() -> lease().acquire())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("row lock timed out");
        assertThat(store.rows.get("comp-1").holder).isNull();
        assertThat(store.rows.get("comp-1").acquiredAt).isNull();
        assertThat(store.rows.get("comp-1").renewedAt).isNull();
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

    /** The recorded success is what stops a run from self-fencing after the first threshold. */
    @Test
    void aTickRenewsAndRecordsTheSuccess() {
        var lease = lease();
        lease.acquire();
        clock.advance(SyncLease.HEARTBEAT_INTERVAL);
        lease.tick();
        assertThat(store.rows.get("comp-1").renewedAt).isEqualTo(clock.millis());
        assertThat(lease.lost()).isFalse();
        // Past the threshold since acquire, exactly at it since the tick. Strict, so not past.
        clock.advance(SyncLease.SELF_FENCE_THRESHOLD);
        assertThat(lease.lost()).isFalse();
    }

    @Test
    void aTickThatMatchesNothingMarksTheLeaseLost() {
        var lease = lease();
        lease.acquire();
        store.rows.get("comp-1").holder = "other";
        clock.advance(SyncLease.HEARTBEAT_INTERVAL);
        lease.tick();
        assertThat(lease.lost()).isTrue();
    }

    /** Once lost, the run has nothing to renew, so a tick must not touch the store. */
    @Test
    void aLostLeaseTicksWithoutAStoreCall() {
        var lease = lease();
        lease.acquire();
        store.rows.get("comp-1").holder = "other";
        clock.advance(SyncLease.HEARTBEAT_INTERVAL);
        lease.tick();
        assertThat(lease.lost()).isTrue();
        int callsAtLoss = store.renewCalls;
        clock.advance(SyncLease.HEARTBEAT_INTERVAL);
        lease.tick();
        assertThat(store.renewCalls).isEqualTo(callsAtLoss);
    }

    @Test
    void aTickThatThrowsIsNotALoss() {
        var lease = lease();
        lease.acquire();
        store.renewFailure = new IllegalStateException("db away");
        clock.advance(SyncLease.HEARTBEAT_INTERVAL);
        lease.tick();
        assertThat(lease.lost()).isFalse();
    }

    @Test
    void aTickSurvivesAThrowAndRenewsNextTime() {
        var lease = lease();
        lease.acquire();
        store.renewFailure = new IllegalStateException("db away");
        clock.advance(SyncLease.HEARTBEAT_INTERVAL);
        lease.tick();
        store.renewFailure = null;
        clock.advance(SyncLease.HEARTBEAT_INTERVAL);
        lease.tick();
        assertThat(store.rows.get("comp-1").renewedAt).isEqualTo(clock.millis());
        assertThat(store.renewCalls).isEqualTo(2);
    }

    /** The holder cannot see a matched-nothing update if it cannot reach the database. */
    @Test
    void selfFenceTripsWhenTheLastSuccessIsOld() {
        var lease = lease();
        lease.acquire();
        store.renewFailure = new IllegalStateException("db away");
        clock.advance(SyncLease.SELF_FENCE_THRESHOLD.plusMillis(1));
        lease.tick();
        assertThat(lease.lost()).isTrue();
    }

    @Test
    void selfFenceDoesNotTripAtExactlyTheThreshold() {
        var lease = lease();
        lease.acquire();
        clock.advance(SyncLease.SELF_FENCE_THRESHOLD);
        assertThat(lease.lost()).isFalse();
    }

    /**
     * A run that never took the lease has nothing to hold. The clock is near
     * zero on purpose: a never-set renewal reads as epoch zero, and at the
     * usual test time that age alone trips the self-fence. A few milliseconds
     * cannot, so only the never-took rule can make this assertion hold.
     */
    @Test
    void aRefusedRunIsLost() {
        long nearZero = 1_000L;
        seed("other", nearZero);
        var lease = new SyncLease(store, "comp-1", new MutableClock(nearZero));
        assertThat(lease.acquire()).isEqualTo(SyncLease.Decision.REFUSE);
        assertThat(lease.lost()).isTrue();
    }

    @Test
    void renewalStopsAfterTheProgressWindow() {
        var lease = lease();
        lease.acquire();
        clock.advance(SyncLease.PROGRESS_WINDOW.plusMillis(1));
        lease.tick();
        assertThat(store.renewCalls).isZero();
    }

    /** The gate is strict, like the thresholds: an age equal to the window is not past it. */
    @Test
    void anAgeExactlyAtTheProgressWindowStillRenews() {
        var lease = lease();
        lease.acquire();
        clock.advance(SyncLease.PROGRESS_WINDOW);
        lease.tick();
        assertThat(store.renewCalls).isEqualTo(1);
    }

    @Test
    void progressReopensRenewal() {
        var lease = lease();
        lease.acquire();
        clock.advance(SyncLease.PROGRESS_WINDOW.plusMillis(1));
        lease.reportProgress();
        lease.tick();
        assertThat(store.renewCalls).isEqualTo(1);
    }

    /** A driver can throw an Error. The tick must survive that too, or the schedule dies. */
    @Test
    void aTickSurvivesAnError() {
        var lease = lease();
        lease.acquire();
        store.renewFailure = new StackOverflowError("stack");
        clock.advance(SyncLease.HEARTBEAT_INTERVAL);
        lease.tick();
        assertThat(lease.lost()).isFalse();
        store.renewFailure = null;
        clock.advance(SyncLease.HEARTBEAT_INTERVAL);
        lease.tick();
        assertThat(store.rows.get("comp-1").renewedAt).isEqualTo(clock.millis());
    }

    @Test
    void stopIsSafeBeforeStartAndTwice() {
        var lease = lease();
        lease.stopHeartbeat();
        lease.startHeartbeat();
        lease.stopHeartbeat();
        lease.stopHeartbeat();
    }
}
