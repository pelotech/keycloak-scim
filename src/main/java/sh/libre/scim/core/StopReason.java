package sh.libre.scim.core;

/** Why a unit of batch-sync work ended early, if it did. */
// package-private: shared by the paged user path and the unpaged group path
enum StopReason {
    /** Ran to completion. */
    NONE,
    /** {@code sync-on-error} decided the run should stop. */
    POLICY,
    /** A page used up its wall-clock budget. */
    PAGE_BUDGET
}
