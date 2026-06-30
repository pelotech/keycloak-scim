package sh.libre.scim.core.exceptions;

/**
 * A non-2xx HTTP response (after retries) or a transport-level failure.
 * Transient iff the status is 429 or 5xx, or the failure is transport-level
 * (httpStatus == 0).
 */
public class InvalidResponseFromScimEndpointException extends ScimPropagationException {

    private final int httpStatus;

    public InvalidResponseFromScimEndpointException(int httpStatus, String message) {
        super("SCIM endpoint returned HTTP " + httpStatus + ": " + message);
        this.httpStatus = httpStatus;
    }

    private InvalidResponseFromScimEndpointException(String message, Throwable cause) {
        super("SCIM endpoint transport failure: " + message, cause);
        this.httpStatus = 0;
    }

    /** Transport-level failure (connection refused, timeout) — always transient. */
    public static InvalidResponseFromScimEndpointException transport(String message, Throwable cause) {
        return new InvalidResponseFromScimEndpointException(message, cause);
    }

    public int httpStatus() { return httpStatus; }

    @Override
    public boolean isTransient() {
        return httpStatus == 0 || httpStatus == 429 || httpStatus >= 500;
    }
}
