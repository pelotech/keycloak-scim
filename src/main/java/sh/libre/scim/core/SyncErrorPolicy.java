package sh.libre.scim.core;

import sh.libre.scim.core.exceptions.ScimPropagationException;

/**
 * Whether a per-resource failure in a batch sync skips the record or aborts the
 * run. Configured via the {@code sync-on-error} component property.
 *
 * <p>{@code auto} is category-aware: a permanent failure (bad mapping, malformed
 * data) skips the offending record; a transient failure (endpoint down, 5xx)
 * stops the run, since every remaining record would fail the same way. A 429
 * throttling response skips the record and the run continues, because a
 * throttling endpoint is working.
 */
public enum SyncErrorPolicy {
    AUTO, CONTINUE, STOP;

    /** Whether the batch run should abort after this failure. */
    public boolean shouldStopRun(ScimPropagationException e) {
        return switch (this) {
            case AUTO -> e.isTransient() && !e.isThrottled();
            case CONTINUE -> false;
            case STOP -> true;
        };
    }

    /** Unknown or {@code null} values default to {@link #AUTO}. */
    public static SyncErrorPolicy fromConfig(String value) {
        if (value == null) return AUTO;
        return switch (value) {
            case "continue" -> CONTINUE;
            case "stop" -> STOP;
            default -> AUTO;
        };
    }
}
