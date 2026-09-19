package sh.libre.scim.core;

import org.jboss.logging.Logger;
import org.keycloak.storage.user.SynchronizationResult;

/**
 * Drives a {@link PageStep} page by page. It passes each outcome's cursor to
 * the next page and merges counters after each page. It stops on a policy stop,
 * on a throttle streak, or when the step reports its source exhausted. It
 * infers nothing about position or completion itself, because only the step
 * knows how its source behaves.
 */
final class PagedSyncRunner {

    private static final Logger LOGGER = Logger.getLogger(PagedSyncRunner.class);

    private PagedSyncRunner() {}

    static <C> void run(PageStep<C> step, int pageSize, SynchronizationResult syncRes) {
        C cursor = step.initialCursor();
        while (true) {
            PageOutcome<C> outcome;
            try {
                outcome = step.run(cursor, pageSize);
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "Paged sync aborted: the page after cursor %s failed", cursor);
                throw e;
            }
            // Merge after the page returns, because a page that throws keeps
            // nothing and must not add counts for work it rolled back.
            syncRes.add(outcome.counters());
            if (outcome.stopReason() == StopReason.POLICY) {
                LOGGER.errorf("Paged sync stopped by sync-on-error at cursor %s", outcome.next());
                return;
            }
            // A separate message, because the operator must see that the
            // endpoint throttled the run rather than that a push failed.
            if (outcome.stopReason() == StopReason.THROTTLE_STREAK) {
                LOGGER.errorf("Paged sync stopped by a throttle streak at cursor %s", outcome.next());
                return;
            }
            if (outcome.exhausted()) {
                return;
            }
            if (!outcome.progressed()) {
                throw new IllegalStateException("Paged sync made no progress after cursor " + cursor);
            }
            cursor = outcome.next();
        }
    }
}
