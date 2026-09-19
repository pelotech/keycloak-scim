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

    /** Replays scripted outcomes and records the cursor each page was given. */
    private static final class ScriptedStep implements PageStep<String> {
        private final Deque<PageOutcome<String>> outcomes;
        final List<String> cursorsSeen = new ArrayList<>();

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
