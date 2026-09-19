package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.storage.user.SynchronizationResult;

/**
 * Row-loop rules of the refresh page step: the cursor, the exhausted flag, the
 * stop reasons, the guard that keeps an isolated fault inside the page, and the
 * guard that ends a page whose transaction can no longer commit.
 */
class RefreshPageStepTest {

    private static final Function<RefreshPageStep.UserRow, RefreshOutcome> PUSHED =
        row -> RefreshOutcome.CONTINUE;
    private static final Function<RefreshPageStep.UserRow, RefreshOutcome> THROTTLED =
        row -> RefreshOutcome.THROTTLED;
    private static final BooleanSupplier WITHIN_BUDGET = () -> false;
    private static final BooleanSupplier OVER_BUDGET = () -> true;
    /** The page transaction can still commit. */
    private static final BooleanSupplier HEALTHY = () -> false;
    /** The page transaction is marked rollback-only. */
    private static final BooleanSupplier POISONED = () -> true;

    private static List<RefreshPageStep.UserRow> rows(String... usernames) {
        return Arrays.stream(usernames).map(u -> new RefreshPageStep.UserRow("id-" + u, u)).toList();
    }

    private static RefreshPageStep.Page page(String after, int size, String... usernames) {
        return new RefreshPageStep.Page(after, size, rows(usernames));
    }

    private static RefreshPageStep.PageProgress process(RefreshPageStep.Page page,
            Function<RefreshPageStep.UserRow, RefreshOutcome> handle, BooleanSupplier overBudget) {
        return RefreshPageStep.processRows(page, new RefreshPageStep.ThrottleStreak(), handle,
            HEALTHY, overBudget);
    }

    @Test
    void anEmptyPageIsExhaustedAndKeepsTheCursor() {
        var progress = process(page("m", 2), PUSHED, WITHIN_BUDGET);

        assertThat(progress.exhausted()).isTrue();
        assertThat(progress.progressed()).isTrue();
        assertThat(progress.cursor()).isEqualTo("m");
        assertThat(progress.stopReason()).isEqualTo(StopReason.NONE);
    }

    @Test
    void aCompletedShortPageIsExhaustedWithTheCursorAtItsLastUsername() {
        var progress = process(page(null, 3, "a", "b"), PUSHED, WITHIN_BUDGET);

        assertThat(progress.exhausted()).isTrue();
        assertThat(progress.cursor()).isEqualTo("b");
    }

    @Test
    void aCompletedFullPageIsNotExhausted() {
        var progress = process(page(null, 2, "a", "b"), PUSHED, WITHIN_BUDGET);

        assertThat(progress.exhausted()).isFalse();
        assertThat(progress.progressed()).isTrue();
        assertThat(progress.cursor()).isEqualTo("b");
    }

    @Test
    void aPageContinuesFromTheCursorItWasGiven() {
        var progress = process(page("b", 2, "c", "d"), PUSHED, WITHIN_BUDGET);

        assertThat(progress.cursor()).isEqualTo("d");
        assertThat(progress.progressed()).isTrue();
    }

    @Test
    void aBudgetCutPageIsNotExhaustedEvenWhenShort() {
        var progress = process(page(null, 3, "a", "b"), PUSHED, OVER_BUDGET);

        assertThat(progress.stopReason()).isEqualTo(StopReason.PAGE_BUDGET);
        assertThat(progress.exhausted()).isFalse();
        assertThat(progress.cursor()).isEqualTo("a");
    }

    @Test
    void aPolicyStopIsNotExhausted() {
        var progress = process(page(null, 3, "a", "b"), row -> RefreshOutcome.STOP, WITHIN_BUDGET);

        assertThat(progress.stopReason()).isEqualTo(StopReason.POLICY);
        assertThat(progress.exhausted()).isFalse();
        assertThat(progress.cursor()).isEqualTo("a");
    }

    @Test
    void aPolicyStopOutranksTheBudgetStop() {
        var progress = process(page(null, 3, "a", "b"), row -> RefreshOutcome.STOP, OVER_BUDGET);

        assertThat(progress.stopReason()).isEqualTo(StopReason.POLICY);
    }

    @Test
    void aPageOverBudgetStillHandlesOneRow() {
        var handled = new ArrayList<String>();

        var progress = process(page(null, 2, "a", "b"),
            row -> { handled.add(row.username()); return RefreshOutcome.CONTINUE; }, OVER_BUDGET);

        assertThat(handled).containsExactly("a");
        assertThat(progress.progressed()).isTrue();
        assertThat(progress.cursor()).isEqualTo("a");
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
        var progress = process(page(null, 2, "a", "b"), PUSHED, WITHIN_BUDGET);

        assertThat(progress.cursor()).isEqualTo("b");
    }

    @Test
    void theBudgetIsExceededOnlyPastItsLimit() {
        var start = Instant.parse("2026-01-01T00:00:00Z");
        var budget = Duration.ofSeconds(45);

        assertThat(RefreshPageStep.overBudget(start, start.plusSeconds(45), budget)).isFalse();
        assertThat(RefreshPageStep.overBudget(start, start.plusSeconds(46), budget)).isTrue();
    }

    // --- the budget is a page-level cut, so it is read only when rows remain ---

    @Test
    void theBudgetIsNotReadAfterTheLastRow() {
        var checks = new AtomicInteger();

        var progress = process(page(null, 3, "a", "b"), PUSHED,
            () -> { checks.incrementAndGet(); return false; });

        // One check, between the two rows. None after the last row.
        assertThat(checks).hasValue(1);
        assertThat(progress.exhausted()).isTrue();
    }

    @Test
    void aShortFinalPagePastTheBudgetIsStillExhausted() {
        var progress = process(page(null, 3, "a"), PUSHED, OVER_BUDGET);

        // Another transaction would only fetch nothing, so the page must not
        // ask for one.
        assertThat(progress.stopReason()).isEqualTo(StopReason.NONE);
        assertThat(progress.exhausted()).isTrue();
    }

    @Test
    void aFullPagePastTheBudgetOnItsLastRowJustEnds() {
        var progress = process(page(null, 1, "a"), PUSHED, OVER_BUDGET);

        assertThat(progress.stopReason()).isEqualTo(StopReason.NONE);
        assertThat(progress.exhausted()).isFalse();
        assertThat(progress.cursor()).isEqualTo("a");
    }

    // --- the throttle streak ---

    @Test
    void aFullPageOfThrottledUsersStopsTheRun() {
        var progress = process(page(null, 3, "a", "b", "c"), THROTTLED, WITHIN_BUDGET);

        assertThat(progress.stopReason()).isEqualTo(StopReason.THROTTLE_STREAK);
        assertThat(progress.exhausted()).isFalse();
        assertThat(progress.cursor()).isEqualTo("c");
    }

    @Test
    void aThrottleStreakShorterThanThePageDoesNotStopTheRun() {
        var progress = process(page(null, 3, "a", "b"), THROTTLED, WITHIN_BUDGET);

        assertThat(progress.stopReason()).isEqualTo(StopReason.NONE);
        assertThat(progress.exhausted()).isTrue();
    }

    @Test
    void theThrottleStreakCarriesAcrossPages() {
        var streak = new RefreshPageStep.ThrottleStreak();

        // Two throttles on a short first page. Below the threshold of three.
        var first = RefreshPageStep.processRows(page(null, 3, "a", "b"), streak, THROTTLED,
            HEALTHY, WITHIN_BUDGET);
        // One throttle on the second page. It only reaches three with the carry.
        var second = RefreshPageStep.processRows(page("b", 3, "c"), streak, THROTTLED,
            HEALTHY, WITHIN_BUDGET);

        assertThat(first.stopReason()).isEqualTo(StopReason.NONE);
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
        // A stop outcome also breaks the streak, so the two can never collide.
        assertThat(streak.record(RefreshOutcome.STOP, 2)).isFalse();
        assertThat(streak.count()).isZero();
    }

    @Test
    void aPushResetsTheStreakThatTheNextPageInherits() {
        var streak = new RefreshPageStep.ThrottleStreak();

        // Each page throttles its first user and pushes its second one.
        var first = RefreshPageStep.processRows(page(null, 2, "a", "b"), streak, pushOn("b"),
            HEALTHY, WITHIN_BUDGET);
        var second = RefreshPageStep.processRows(page("b", 2, "c", "d"), streak, pushOn("d"),
            HEALTHY, WITHIN_BUDGET);

        assertThat(first.stopReason()).isEqualTo(StopReason.NONE);
        assertThat(second.stopReason()).isEqualTo(StopReason.NONE);
    }

    @Test
    void aThrottleStreakOutranksTheBudgetStop() {
        var progress = process(page(null, 1, "a"), THROTTLED, OVER_BUDGET);

        assertThat(progress.stopReason()).isEqualTo(StopReason.THROTTLE_STREAK);
    }

    // --- an isolated fault stays inside the page ---

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
        var handled = new ArrayList<String>();

        var progress = RefreshPageStep.processRows(page(null, 2, "a", "b"),
            new RefreshPageStep.ThrottleStreak(),
            row -> {
                handled.add(row.username());
                return RefreshPageStep.refreshLoaded(row.id(), row.username(), counters, u -> {
                    if ("a".equals(u)) {
                        throw new ConcurrentModificationException("racing map access");
                    }
                    return RefreshOutcome.THROTTLED;
                });
            },
            HEALTHY, WITHIN_BUDGET);

        assertThat(handled).containsExactly("a", "b");
        assertThat(progress.stopReason()).isEqualTo(StopReason.NONE);
        assertThat(progress.cursor()).isEqualTo("b");
        assertThat(counters.getFailed()).isEqualTo(1);
    }

    // --- a page whose transaction can no longer commit ---

    @Test
    void aPoisonedTransactionEndsThePageAtOnce() {
        var handled = new ArrayList<String>();

        var progress = RefreshPageStep.processRows(page(null, 3, "a", "b", "c"),
            new RefreshPageStep.ThrottleStreak(),
            row -> { handled.add(row.username()); return RefreshOutcome.CONTINUE; },
            POISONED, WITHIN_BUDGET);

        assertThat(handled).containsExactly("a");
        assertThat(progress.stopReason()).isEqualTo(StopReason.TRANSACTION_FAILED);
        assertThat(progress.exhausted()).isFalse();
    }

    @Test
    void aSwallowedFailureThatPoisonsTheTransactionStopsThePage() {
        var counters = new SynchronizationResult();
        var poisoned = new AtomicBoolean();
        var handled = new ArrayList<String>();

        var progress = RefreshPageStep.processRows(page(null, 3, "a", "b", "c"),
            new RefreshPageStep.ThrottleStreak(),
            row -> {
                handled.add(row.username());
                return RefreshPageStep.refreshLoaded(row.id(), row.username(), counters, u -> {
                    // A duplicate mapping row marks the transaction rollback-only.
                    poisoned.set(true);
                    throw new IllegalStateException("constraint violation");
                });
            },
            poisoned::get, WITHIN_BUDGET);

        // The page must not push the other two users into work that cannot commit.
        assertThat(handled).containsExactly("a");
        assertThat(counters.getFailed()).isEqualTo(1);
        assertThat(progress.stopReason()).isEqualTo(StopReason.TRANSACTION_FAILED);
    }

    @Test
    void aFailedTransactionOutranksTheBudgetStop() {
        var progress = RefreshPageStep.processRows(page(null, 3, "a", "b"),
            new RefreshPageStep.ThrottleStreak(), PUSHED, POISONED, OVER_BUDGET);

        assertThat(progress.stopReason()).isEqualTo(StopReason.TRANSACTION_FAILED);
    }

    // --- page-level guards ---

    @Test
    void aPageWhoseRealmIsGoneEndsTheRunCleanly() {
        var outcome = RefreshPageStep.realmGone("m");

        assertThat(outcome.exhausted()).isTrue();
        assertThat(outcome.progressed()).isTrue();
        assertThat(outcome.next()).isEqualTo("m");
        assertThat(outcome.stopReason()).isEqualTo(StopReason.NONE);
        assertThat(outcome.counters().getAdded()).isZero();
        assertThat(outcome.counters().getUpdated()).isZero();
        assertThat(outcome.counters().getFailed()).isZero();
    }

    @Test
    void aFailureToCloseThePageClientStaysOutOfTheTransaction() {
        var client = mock(ScimClient.class);
        doThrow(new IllegalStateException("pool already shut down")).when(client).close();

        assertThatCode(() -> RefreshPageStep.closeQuietly(client)).doesNotThrowAnyException();
        verify(client).close();
    }

    @Test
    void aMissingConstructorArgumentFailsAtWiringTime() {
        var factory = mock(KeycloakSessionFactory.class);
        var model = new ComponentModel();

        assertThatThrownBy(() -> new RefreshPageStep(null, "realm", model,
            Duration.ofSeconds(45), Clock.systemUTC()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshPageStep(factory, null, model,
            Duration.ofSeconds(45), Clock.systemUTC()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshPageStep(factory, "realm", null,
            Duration.ofSeconds(45), Clock.systemUTC()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshPageStep(factory, "realm", model,
            null, Clock.systemUTC()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshPageStep(factory, "realm", model,
            Duration.ofSeconds(45), null))
            .isInstanceOf(NullPointerException.class);
    }

    /** Throttles every row except the named one, which reports a push. */
    private static Function<RefreshPageStep.UserRow, RefreshOutcome> pushOn(String username) {
        return row -> username.equals(row.username()) ? RefreshOutcome.CONTINUE : RefreshOutcome.THROTTLED;
    }
}
