package sh.libre.scim.core;

import java.util.Objects;

import org.keycloak.storage.user.SynchronizationResult;

/**
 * What one page of a batch sync reports back to {@link PagedSyncRunner}.
 *
 * <p>The runner accepts these combinations:
 * <ul>
 *   <li>{@code progressed} true and {@code exhausted} false: the run goes on
 *       from {@code next}.</li>
 *   <li>{@code exhausted} true: the run ends, whatever the other fields say.</li>
 *   <li>{@code progressed} false with {@link StopReason#PAGE_BUDGET}: the run
 *       ends and the next sync starts this page again.</li>
 *   <li>{@code progressed} false with {@link StopReason#NONE}: the runner
 *       fails the run, because the page would repeat for ever.</li>
 * </ul>
 *
 * <p>The runner merges {@code counters} with Keycloak's own merge, which sums
 * added, updated, removed and failed. It does not carry the {@code ignored}
 * flag, so a page cannot report an ignored run through this record.
 *
 * @param next       cursor the following page starts from
 * @param progressed whether the page moved through its source at all
 * @param exhausted  whether the source has no further pages
 * @param counters   this page's counts, merged only after the page commits
 * @param stopReason why the page ended early, or {@link StopReason#NONE}
 */
record PageOutcome<C>(C next, boolean progressed, boolean exhausted,
                      SynchronizationResult counters, StopReason stopReason) {

    PageOutcome {
        // The runner merges the counters for every page it gets back.
        Objects.requireNonNull(counters, "counters");
        Objects.requireNonNull(stopReason, "stopReason");
    }
}
