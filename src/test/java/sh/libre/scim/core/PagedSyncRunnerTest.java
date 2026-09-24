package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.keycloak.storage.user.SynchronizationResult;

/**
 * Loop behaviour of the paged sync runner: cursor hand-off, counter merging,
 * the stop reasons, and the no-progress guard.
 */
class PagedSyncRunnerTest {

    /** Replays scripted outcomes and records what each page was asked for. */
    private static final class ScriptedStep implements PageStep<String> {
        private final Deque<PageOutcome<String>> outcomes;
        final List<String> cursorsSeen = new ArrayList<>();
        final List<Integer> sizesSeen = new ArrayList<>();

        ScriptedStep(List<PageOutcome<String>> outcomes) {
            this.outcomes = new ArrayDeque<>(outcomes);
        }

        @Override
        public String initialCursor() {
            return null;
        }

        @Override
        public PageOutcome<String> run(String cursor, int size) {
            cursorsSeen.add(cursor);
            sizesSeen.add(size);
            if (outcomes.isEmpty()) {
                throw new AssertionError("runner asked for a page past the end of the script");
            }
            return outcomes.poll();
        }
    }

    private static SynchronizationResult updated(int n) {
        var r = new SynchronizationResult();
        r.setUpdated(n);
        return r;
    }

    private static PageOutcome<String> page(String next, int updated) {
        return new PageOutcome<>(next, true, false, updated(updated), StopReason.NONE);
    }

    private static PageOutcome<String> lastPage(String next, int updated) {
        return new PageOutcome<>(next, true, true, updated(updated), StopReason.NONE);
    }

    @Test
    void handsEachOutcomesCursorToTheNextPageAndMergesEveryPage() {
        var step = new ScriptedStep(List.of(page("b", 2), page("d", 2), lastPage("e", 1)));
        var result = new SynchronizationResult();

        PagedSyncRunner.run(step, 2, result);

        assertThat(step.cursorsSeen).containsExactly(null, "b", "d");
        assertThat(result.getUpdated()).isEqualTo(5);
    }

    @Test
    void stopsWhenTheStepReportsExhausted() {
        var step = new ScriptedStep(List.of(lastPage(null, 0)));

        PagedSyncRunner.run(step, 2, new SynchronizationResult());

        assertThat(step.cursorsSeen).containsExactly((String) null);
    }

    @Test
    void stopsOnAPolicyStopAndStillMergesThatPage() {
        var failed = new SynchronizationResult();
        failed.setFailed(1);
        var step = new ScriptedStep(List.of(
            page("b", 2),
            new PageOutcome<>("c", true, false, failed, StopReason.POLICY)));
        var result = new SynchronizationResult();

        PagedSyncRunner.run(step, 2, result);

        assertThat(step.cursorsSeen).containsExactly(null, "b");
        assertThat(result.getUpdated()).isEqualTo(2);
        assertThat(result.getFailed()).isEqualTo(1);
    }

    /**
     * A throttle streak ends the run like a policy stop does. The operator keeps
     * every page that committed before the streak.
     */
    @Test
    void stopsOnAThrottleStreakAndStillMergesThatPage() {
        var failed = new SynchronizationResult();
        failed.setFailed(2);
        var step = new ScriptedStep(List.of(
            page("b", 3),
            new PageOutcome<>("c", true, false, failed, StopReason.THROTTLE_STREAK)));
        var result = new SynchronizationResult();

        PagedSyncRunner.run(step, 2, result);

        assertThat(step.cursorsSeen).containsExactly(null, "b");
        assertThat(result.getUpdated()).isEqualTo(3);
        assertThat(result.getFailed()).isEqualTo(2);
    }

    /**
     * A page whose transaction can no longer commit ends the run. The page
     * itself keeps nothing, so the run must not open another one.
     */
    @Test
    void stopsWhenAPageReportsAFailedTransaction() {
        var step = new ScriptedStep(List.of(
            page("b", 3),
            new PageOutcome<>("c", true, false, new SynchronizationResult(),
                StopReason.TRANSACTION_FAILED)));
        var result = new SynchronizationResult();

        PagedSyncRunner.run(step, 2, result);

        assertThat(step.cursorsSeen).containsExactly(null, "b");
        assertThat(result.getUpdated()).isEqualTo(3);
    }

    /**
     * A page that cannot commit keeps none of its pushes, so its updated count
     * describes work the database rolled back. Its failures still happened, and
     * the result is the only place besides the log where they show.
     */
    @Test
    void keepsOnlyTheFailuresOfAPageThatCannotCommit() {
        var rolledBack = new SynchronizationResult();
        rolledBack.setUpdated(2);
        rolledBack.setFailed(1);
        var step = new ScriptedStep(List.of(
            page("b", 3),
            new PageOutcome<>("c", true, false, rolledBack, StopReason.TRANSACTION_FAILED)));
        var result = new SynchronizationResult();

        PagedSyncRunner.run(step, 2, result);

        assertThat(step.cursorsSeen).containsExactly(null, "b");
        assertThat(result.getUpdated()).isEqualTo(3);
        assertThat(result.getFailed()).isEqualTo(1);
    }

    @Test
    void continuesFromTheCursorAfterABudgetStop() {
        var step = new ScriptedStep(List.of(
            new PageOutcome<>("b", true, false, updated(1), StopReason.PAGE_BUDGET),
            lastPage("c", 1)));

        PagedSyncRunner.run(step, 2, new SynchronizationResult());

        assertThat(step.cursorsSeen).containsExactly(null, "b");
    }

    @Test
    void failsWhenAPageMakesNoProgress() {
        var step = new ScriptedStep(List.of(
            new PageOutcome<>(null, false, false, new SynchronizationResult(), StopReason.NONE)));

        assertThatThrownBy(() -> PagedSyncRunner.run(step, 2, new SynchronizationResult()))
            .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The first page can stop the run. Every other stop test runs a normal page
     * first, so a runner that judged stops only from the second page would pass
     * them.
     */
    @Test
    void stopsOnAPolicyStopOnTheFirstPage() {
        var step = new ScriptedStep(List.of(
            new PageOutcome<>("b", true, false, updated(1), StopReason.POLICY),
            page("c", 1)));
        var result = new SynchronizationResult();

        PagedSyncRunner.run(step, 2, result);

        assertThat(step.cursorsSeen).containsExactly((String) null);
        assertThat(result.getUpdated()).isEqualTo(1);
    }

    /**
     * A budget stop with no progress ends the run without a failure. The next
     * sync starts the page again.
     */
    @Test
    void stopsWithoutFailingWhenTheBudgetRanOutBeforeAnyProgress() {
        var step = new ScriptedStep(List.of(
            new PageOutcome<>(null, false, false, new SynchronizationResult(), StopReason.PAGE_BUDGET)));

        PagedSyncRunner.run(step, 2, new SynchronizationResult());

        assertThat(step.cursorsSeen).containsExactly((String) null);
    }

    /** Work that committed before the abort must survive the abort. */
    @Test
    void mergesTheCountersOfEveryPageBeforeANoProgressAbort() {
        var step = new ScriptedStep(List.of(
            page("b", 2),
            new PageOutcome<>("b", false, false, updated(1), StopReason.NONE)));
        var result = new SynchronizationResult();

        assertThatThrownBy(() -> PagedSyncRunner.run(step, 2, result))
            .isInstanceOf(IllegalStateException.class);

        assertThat(result.getUpdated()).isEqualTo(3);
    }

    /** The cursor is the operator's only clue about where the run stalled. */
    @Test
    void theNoProgressFailureNamesTheCursor() {
        var step = new ScriptedStep(List.of(
            page("b", 1),
            new PageOutcome<>("b", false, false, new SynchronizationResult(), StopReason.NONE)));

        assertThatThrownBy(() -> PagedSyncRunner.run(step, 2, new SynchronizationResult()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("b");
    }

    @Test
    void asksTheStepForThePageSizeItWasGiven() {
        var step = new ScriptedStep(List.of(page("b", 1), lastPage("c", 1)));

        PagedSyncRunner.run(step, 7, new SynchronizationResult());

        assertThat(step.sizesSeen).containsExactly(7, 7);
    }

    @Test
    void rejectsAPageSizeBelowOne() {
        var step = new ScriptedStep(List.of());

        assertThatThrownBy(() -> PagedSyncRunner.run(step, 0, new SynchronizationResult()))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(step.cursorsSeen).isEmpty();
    }

    /**
     * A step that claims progress but returns the cursor it was given breaks the
     * contract. The runner warns and goes on, because only the step can tell
     * whether the source really moved.
     */
    @Test
    void goesOnWhenAStepRepeatsTheCursorItWasGiven() {
        var step = new ScriptedStep(List.of(page(null, 1), lastPage("c", 1)));

        PagedSyncRunner.run(step, 2, new SynchronizationResult());

        assertThat(step.cursorsSeen).containsExactly(null, null);
    }

    /** The runner dereferences the counters, so a page must always supply them. */
    @Test
    void aPageOutcomeRejectsNullCounters() {
        assertThatThrownBy(() -> new PageOutcome<>("b", true, false, null, StopReason.NONE))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void propagatesAPageFailure() {
        PageStep<String> step = new PageStep<>() {
            @Override
            public String initialCursor() {
                return null;
            }

            @Override
            public PageOutcome<String> run(String cursor, int size) {
                throw new IllegalArgumentException("page transaction failed");
            }
        };

        assertThatThrownBy(() -> PagedSyncRunner.run(step, 2, new SynchronizationResult()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("page transaction failed");
    }
}
