package sh.libre.scim.core;

/** How one role refresh of a component ended. {@link RoleRefreshScheduler} acts on it. */
enum RoleRefreshResult {
    /** The refresh visited every user. Some users may have failed. */
    FINISHED,
    /** The lease was held by another run, or could not be taken. Try again later. */
    REFUSED,
    /** The refresh started and did not visit every user. */
    STOPPED,
    /** The realm or component is gone or turned off, so nothing ran. */
    SKIPPED
}
