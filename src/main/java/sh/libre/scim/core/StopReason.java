package sh.libre.scim.core;

/**
 * Why a unit of batch-sync work ended early, if it did. This is the per-page
 * reason; {@link RefreshOutcome} is the per-resource one.
 */
// package-private: shared by the paged user path and the unpaged group path
enum StopReason {
    /** Ran to completion. */
    NONE,
    /**
     * {@code sync-on-error} decided the run should stop. A page reports this
     * after a resource returns {@link RefreshOutcome#STOP}.
     */
    POLICY,
    /** A page used up its wall-clock budget. */
    PAGE_BUDGET,
    /**
     * The endpoint throttled a full page of resources in a row. A page counts
     * the resources that return {@link RefreshOutcome#THROTTLED}.
     *
     * <p>This stops the run even under {@code sync-on-error=continue}. It is a
     * guard against a run that achieves nothing, not an error policy. An
     * operator who asks the run to continue through failures still gets a
     * stopped run when the endpoint throttles a whole page, and the log says
     * why.
     */
    THROTTLE_STREAK,
    /**
     * The page transaction is marked rollback-only, so it can no longer commit.
     * A page reports this as soon as it sees the mark, to stop pushing more
     * resources to the endpoint that the database will then forget.
     *
     * <p>The commit that follows fails as well, so in practice the page throws
     * and the runner never sees this value. It exists so that the page stops
     * at the first row instead of at the last one.
     */
    TRANSACTION_FAILED
}
