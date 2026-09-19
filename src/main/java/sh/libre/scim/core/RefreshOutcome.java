package sh.libre.scim.core;

/**
 * What one resource of a batch refresh tells its caller to do next. This is the
 * per-resource outcome; {@link StopReason} is the per-page one.
 */
// package-private: shared by the paged user path and the unpaged group path
enum RefreshOutcome {
    /** The resource was pushed, skipped, or failed in some other way. */
    CONTINUE,
    /**
     * The push failed and the endpoint reported that it throttles the caller.
     * A page counts these in a row and reports {@link StopReason#THROTTLE_STREAK}
     * when the count reaches a full page.
     */
    THROTTLED,
    /**
     * {@code sync-on-error} decided the run should stop. A page turns this into
     * {@link StopReason#POLICY}.
     */
    STOP
}
