package sh.libre.scim.core;

import org.keycloak.storage.user.SynchronizationResult;

/**
 * What one page of a batch sync reports back to {@link PagedSyncRunner}.
 *
 * @param next       cursor the following page starts from
 * @param progressed whether the page moved through its source at all
 * @param exhausted  whether the source has no further pages
 * @param counters   this page's counts, merged only after the page commits
 * @param stopReason why the page ended early, or {@link StopReason#NONE}
 */
record PageOutcome<C>(C next, boolean progressed, boolean exhausted,
                      SynchronizationResult counters, StopReason stopReason) {}
