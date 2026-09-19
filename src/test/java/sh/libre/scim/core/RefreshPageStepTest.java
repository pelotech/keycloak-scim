package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.keycloak.storage.user.SynchronizationResult;

/**
 * Row-loop rules of the refresh page step: the cursor, the exhausted flag, the
 * three stop reasons, and the guard that keeps a raw failure inside the page.
 */
class RefreshPageStepTest {

    private static final Function<RefreshPageStep.UserRow, RefreshOutcome> PUSHED =
        row -> RefreshOutcome.CONTINUE;
    private static final Function<RefreshPageStep.UserRow, RefreshOutcome> THROTTLED =
        row -> RefreshOutcome.THROTTLED;
    private static final BooleanSupplier WITHIN_BUDGET = () -> false;
    private static final BooleanSupplier OVER_BUDGET = () -> true;

    private static List<RefreshPageStep.UserRow> rows(String... usernames) {
        return Arrays.stream(usernames).map(u -> new RefreshPageStep.UserRow("id-" + u, u)).toList();
    }

    private static PageOutcome<String> process(String after, int size, List<RefreshPageStep.UserRow> rows,
            Function<RefreshPageStep.UserRow, RefreshOutcome> handle, BooleanSupplier overBudget) {
        return RefreshPageStep.processRows(after, size, rows, new SynchronizationResult(),
            new RefreshPageStep.ThrottleStreak(), handle, overBudget);
    }

    @Test
    void anEmptyPageIsExhaustedAndKeepsTheCursor() {
        var outcome = process("m", 2, rows(), PUSHED, WITHIN_BUDGET);

        assertThat(outcome.exhausted()).isTrue();
        assertThat(outcome.progressed()).isTrue();
        assertThat(outcome.next()).isEqualTo("m");
        assertThat(outcome.stopReason()).isEqualTo(StopReason.NONE);
    }

    @Test
    void aCompletedShortPageIsExhaustedWithTheCursorAtItsLastUsername() {
        var outcome = process(null, 3, rows("a", "b"), PUSHED, WITHIN_BUDGET);

        assertThat(outcome.exhausted()).isTrue();
        assertThat(outcome.next()).isEqualTo("b");
    }

    @Test
    void aCompletedFullPageIsNotExhausted() {
        var outcome = process(null, 2, rows("a", "b"), PUSHED, WITHIN_BUDGET);

        assertThat(outcome.exhausted()).isFalse();
        assertThat(outcome.progressed()).isTrue();
        assertThat(outcome.next()).isEqualTo("b");
    }

    @Test
    void aPageContinuesFromTheCursorItWasGiven() {
        var outcome = process("b", 2, rows("c", "d"), PUSHED, WITHIN_BUDGET);

        assertThat(outcome.next()).isEqualTo("d");
        assertThat(outcome.progressed()).isTrue();
    }

    @Test
    void aBudgetCutPageIsNotExhaustedEvenWhenShort() {
        var outcome = process(null, 3, rows("a", "b"), PUSHED, OVER_BUDGET);

        assertThat(outcome.stopReason()).isEqualTo(StopReason.PAGE_BUDGET);
        assertThat(outcome.exhausted()).isFalse();
        assertThat(outcome.next()).isEqualTo("a");
    }

    @Test
    void aPolicyStopIsNotExhausted() {
        var outcome = process(null, 3, rows("a", "b"), row -> RefreshOutcome.STOP, WITHIN_BUDGET);

        assertThat(outcome.stopReason()).isEqualTo(StopReason.POLICY);
        assertThat(outcome.exhausted()).isFalse();
        assertThat(outcome.next()).isEqualTo("a");
    }

    @Test
    void aPolicyStopOutranksTheBudgetStop() {
        var outcome = process(null, 3, rows("a", "b"), row -> RefreshOutcome.STOP, OVER_BUDGET);

        assertThat(outcome.stopReason()).isEqualTo(StopReason.POLICY);
    }

    @Test
    void aPageOverBudgetStillHandlesOneRow() {
        var handled = new ArrayList<String>();

        var outcome = process(null, 2, rows("a", "b"),
            row -> { handled.add(row.username()); return RefreshOutcome.CONTINUE; }, OVER_BUDGET);

        assertThat(handled).containsExactly("a");
        assertThat(outcome.progressed()).isTrue();
        assertThat(outcome.next()).isEqualTo("a");
    }

    @Test
    void aMissingUserIsNotPushed() {
        var pushed = new ArrayList<String>();
        var counters = new SynchronizationResult();

        var outcome = RefreshPageStep.refreshLoaded("id-a", (String) null, counters,
            u -> { pushed.add(u); return RefreshOutcome.STOP; });

        assertThat(outcome).isEqualTo(RefreshOutcome.CONTINUE);
        assertThat(pushed).isEmpty();
        assertThat(counters.getFailed()).isZero();
    }

    @Test
    void aLoadedUserIsPushedAndItsOutcomeReturned() {
        var outcome = RefreshPageStep.refreshLoaded("id-a", "user", new SynchronizationResult(),
            u -> RefreshOutcome.STOP);

        assertThat(outcome).isEqualTo(RefreshOutcome.STOP);
    }

    @Test
    void rowsHandledWithoutAPushStillMoveTheCursor() {
        var counters = new SynchronizationResult();

        var outcome = RefreshPageStep.processRows(null, 2, rows("a", "b"), counters,
            new RefreshPageStep.ThrottleStreak(), PUSHED, WITHIN_BUDGET);

        assertThat(outcome.next()).isEqualTo("b");
        assertThat(counters.getUpdated()).isZero();
    }

    @Test
    void theBudgetIsExceededOnlyPastItsLimit() {
        var start = Instant.parse("2026-01-01T00:00:00Z");
        var budget = Duration.ofSeconds(45);

        assertThat(RefreshPageStep.overBudget(start, start.plusSeconds(45), budget)).isFalse();
        assertThat(RefreshPageStep.overBudget(start, start.plusSeconds(46), budget)).isTrue();
    }

    @Test
    void aFullPageOfThrottledUsersStopsTheRun() {
        var outcome = process(null, 3, rows("a", "b", "c"), THROTTLED, WITHIN_BUDGET);

        assertThat(outcome.stopReason()).isEqualTo(StopReason.THROTTLE_STREAK);
        assertThat(outcome.exhausted()).isFalse();
        assertThat(outcome.next()).isEqualTo("c");
    }

    @Test
    void aThrottleStreakShorterThanThePageDoesNotStopTheRun() {
        var outcome = process(null, 3, rows("a", "b"), THROTTLED, WITHIN_BUDGET);

        assertThat(outcome.stopReason()).isEqualTo(StopReason.NONE);
        assertThat(outcome.exhausted()).isTrue();
    }

    @Test
    void theThrottleStreakCarriesAcrossPages() {
        var streak = new RefreshPageStep.ThrottleStreak();

        var first = RefreshPageStep.processRows(null, 2, rows("a", "b"), new SynchronizationResult(),
            streak, THROTTLED, WITHIN_BUDGET);
        var second = RefreshPageStep.processRows("b", 2, rows("c", "d"), new SynchronizationResult(),
            streak, THROTTLED, WITHIN_BUDGET);

        assertThat(first.stopReason()).isEqualTo(StopReason.THROTTLE_STREAK);
        assertThat(second.stopReason()).isEqualTo(StopReason.THROTTLE_STREAK);
    }

    @Test
    void anyOutcomeOtherThanAThrottleResetsTheStreak() {
        var streak = new RefreshPageStep.ThrottleStreak();

        assertThat(streak.record(RefreshOutcome.THROTTLED, 2)).isFalse();
        assertThat(streak.record(RefreshOutcome.CONTINUE, 2)).isFalse();
        assertThat(streak.count()).isZero();
        // The next throttle is the first in a row again, so it does not stop.
        assertThat(streak.record(RefreshOutcome.THROTTLED, 2)).isFalse();
        assertThat(streak.count()).isEqualTo(1);
    }

    @Test
    void aPushResetsTheStreakThatTheNextPageInherits() {
        var streak = new RefreshPageStep.ThrottleStreak();

        // Each page throttles its first user and pushes its second one.
        var first = RefreshPageStep.processRows(null, 2, rows("a", "b"), new SynchronizationResult(),
            streak, pushOn("b"), WITHIN_BUDGET);
        var second = RefreshPageStep.processRows("b", 2, rows("c", "d"), new SynchronizationResult(),
            streak, pushOn("d"), WITHIN_BUDGET);

        assertThat(first.stopReason()).isEqualTo(StopReason.NONE);
        assertThat(second.stopReason()).isEqualTo(StopReason.NONE);
    }

    @Test
    void aPolicyStopOutranksTheThrottleStreak() {
        var outcome = process(null, 1, rows("a"), row -> RefreshOutcome.STOP, WITHIN_BUDGET);

        assertThat(outcome.stopReason()).isEqualTo(StopReason.POLICY);
    }

    @Test
    void aThrottleStreakOutranksTheBudgetStop() {
        var outcome = process(null, 1, rows("a"), THROTTLED, OVER_BUDGET);

        assertThat(outcome.stopReason()).isEqualTo(StopReason.THROTTLE_STREAK);
    }

    @Test
    void aRawRuntimeFailureIsCountedAndStopsNothing() {
        var counters = new SynchronizationResult();

        var outcome = RefreshPageStep.refreshLoaded("id-a", "user", counters, u -> {
            throw new ConcurrentModificationException("racing map access");
        });

        assertThat(outcome).isEqualTo(RefreshOutcome.CONTINUE);
        assertThat(counters.getFailed()).isEqualTo(1);
    }

    @Test
    void aRawRuntimeFailureLeavesThePageRunningAndResetsTheThrottleStreak() {
        var counters = new SynchronizationResult();
        var streak = new RefreshPageStep.ThrottleStreak();
        var handled = new ArrayList<String>();

        var outcome = RefreshPageStep.processRows(null, 2, rows("a", "b"), counters, streak,
            row -> {
                handled.add(row.username());
                return RefreshPageStep.refreshLoaded(row.id(), row.username(), counters, u -> {
                    if ("a".equals(u)) {
                        throw new ConcurrentModificationException("racing map access");
                    }
                    return RefreshOutcome.THROTTLED;
                });
            },
            WITHIN_BUDGET);

        assertThat(handled).containsExactly("a", "b");
        assertThat(outcome.stopReason()).isEqualTo(StopReason.NONE);
        assertThat(outcome.next()).isEqualTo("b");
        assertThat(counters.getFailed()).isEqualTo(1);
    }

    /** Throttles every row except the named one, which reports a push. */
    private static Function<RefreshPageStep.UserRow, RefreshOutcome> pushOn(String username) {
        return row -> username.equals(row.username()) ? RefreshOutcome.CONTINUE : RefreshOutcome.THROTTLED;
    }
}
