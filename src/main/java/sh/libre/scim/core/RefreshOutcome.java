package sh.libre.scim.core;

/** What one resource of a batch refresh tells its caller to do next. */
// package-private: shared by the paged user path and the unpaged group path
enum RefreshOutcome {
    /** The resource was pushed, skipped, or failed in some other way. */
    CONTINUE,
    /** The push failed and the endpoint reported that it throttles the caller. */
    THROTTLED,
    /** {@code sync-on-error} decided the run should stop. */
    STOP
}
