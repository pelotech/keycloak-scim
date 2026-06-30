package sh.libre.scim.core.exceptions;

/**
 * A failure to propagate a change to a SCIM service provider. Unchecked, because
 * SCIM calls flow through Consumer/BiConsumer/stream lambdas that can't declare
 * checked exceptions; the three handling sites (ScimDispatcher.runOne, the async
 * worker, the batch loops) catch it explicitly.
 */
public abstract class ScimPropagationException extends RuntimeException {

    protected ScimPropagationException(String message) {
        super(message);
    }

    protected ScimPropagationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Whether retrying the operation later might succeed (endpoint unreachable,
     * 5xx, 429) versus a permanent condition (bad mapping, malformed data, 4xx).
     * Drives both the critical-only rollback decision and the auto skip/stop one.
     */
    public abstract boolean isTransient();
}
