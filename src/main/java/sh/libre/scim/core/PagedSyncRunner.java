package sh.libre.scim.core;

import java.util.Objects;

import org.jboss.logging.Logger;
import org.keycloak.storage.user.SynchronizationResult;

/**
 * Drives a {@link PageStep} page by page. It passes each outcome's cursor to
 * the next page and merges counters after each page. It stops on a policy stop,
 * on a throttle streak, on a failed page transaction, or when the step reports
 * its source exhausted. It fails
 * the run when a page reports no progress and gives no reason for it. It infers
 * nothing about position or completion itself, because only the step knows how
 * its source behaves.
 */
final class PagedSyncRunner {

    private static final Logger LOGGER = Logger.getLogger(PagedSyncRunner.class);

    private PagedSyncRunner() {}

    /**
     * Runs pages until the step stops the run or exhausts its source.
     *
     * @param step supplies the pages and does all of the work
     * @param pageSize how many resources one page may process; at least 1
     * @param syncRes the run's counters; each page's counters are added to it
     * @throws IllegalArgumentException if {@code pageSize} is below 1
     * @throws IllegalStateException if a page reports no progress and no reason
     */
    static <C> void run(PageStep<C> step, int pageSize, SynchronizationResult syncRes) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("Paged sync needs a page size of at least 1, got " + pageSize);
        }
        C cursor = step.initialCursor();
        while (true) {
            PageOutcome<C> outcome;
            try {
                outcome = step.run(cursor, pageSize);
            } catch (RuntimeException e) {
                // Log before the rethrow, because only the runner holds the
                // cursor. The caller cannot say where the run stopped.
                LOGGER.errorf(e, "Paged sync aborted: the page after cursor %s failed", cursor);
                throw e;
            }
            // Merge after the page returns. A page that throws keeps no
            // committed rows, so it must add no counts.
            syncRes.add(outcome.counters());
            if (endsTheRun(outcome)) {
                return;
            }
            if (outcome.exhausted()) {
                return;
            }
            if (!outcome.progressed()) {
                LOGGER.errorf("Paged sync aborted: the page after cursor %s made no progress", cursor);
                throw new IllegalStateException("Paged sync made no progress after cursor " + cursor);
            }
            if (Objects.equals(outcome.next(), cursor)) {
                // Every step in this design moves its cursor when it progresses.
                // The pair below is a broken step, and it loops with no other sign.
                LOGGER.warnf("Paged sync page claimed progress but returned cursor %s again", cursor);
            }
            cursor = outcome.next();
        }
    }

    /**
     * Decides whether this outcome ends the run, and logs the reason when it
     * does. The switch covers every {@link StopReason}, so a new reason must be
     * decided here before the code compiles again.
     */
    private static <C> boolean endsTheRun(PageOutcome<C> outcome) {
        return switch (outcome.stopReason()) {
            case NONE -> false;
            case PAGE_BUDGET -> {
                // A step that fetches before it starts the transaction can spend
                // the whole budget on the fetch and process nothing. Stop the
                // run and let the next sync start this page again.
                if (!outcome.progressed()) {
                    LOGGER.warnf("Paged sync stopped: the page budget ran out before any progress at cursor %s",
                        outcome.next());
                    yield true;
                }
                yield false;
            }
            case POLICY -> {
                LOGGER.errorf("Paged sync stopped by sync-on-error at cursor %s", outcome.next());
                yield true;
            }
            // A separate message, because the operator must see that the
            // endpoint throttled the run rather than that a push failed.
            case THROTTLE_STREAK -> {
                LOGGER.errorf("Paged sync stopped by a throttle streak at cursor %s", outcome.next());
                yield true;
            }
            // The page kept nothing, so another page would only widen the gap
            // between the endpoint and the database.
            case TRANSACTION_FAILED -> {
                LOGGER.errorf("Paged sync stopped: the page transaction at cursor %s cannot commit",
                    outcome.next());
                yield true;
            }
        };
    }
}
