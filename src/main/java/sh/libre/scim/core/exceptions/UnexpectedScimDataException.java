package sh.libre.scim.core.exceptions;

/** The SCIM endpoint returned malformed or unexpected data. Permanent. */
public class UnexpectedScimDataException extends ScimPropagationException {
    public UnexpectedScimDataException(String message) { super(message); }
    public UnexpectedScimDataException(String message, Throwable cause) { super(message, cause); }

    @Override public boolean isTransient() { return false; }
}
