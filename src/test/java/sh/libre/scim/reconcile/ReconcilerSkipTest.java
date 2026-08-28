package sh.libre.scim.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Reconciler tombstone skip: in deactivate mode an already-flagged mapping is
 * skipped locally (no HTTP); in delete mode the flag does not protect the row.
 */
class ReconcilerSkipTest {

    @Test
    void deactivateMode_flagged_skips() {
        assertThat(ReconcilerRunner.skipAlreadyDeactivated(true, 1721000000000L)).isTrue();
    }

    @Test
    void deactivateMode_unflagged_processes() {
        assertThat(ReconcilerRunner.skipAlreadyDeactivated(true, null)).isFalse();
    }

    @Test
    void deleteMode_flagged_processes() {
        assertThat(ReconcilerRunner.skipAlreadyDeactivated(false, 1721000000000L)).isFalse();
    }

    @Test
    void deleteMode_unflagged_processes() {
        assertThat(ReconcilerRunner.skipAlreadyDeactivated(false, null)).isFalse();
    }
}
