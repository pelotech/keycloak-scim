package sh.libre.scim.core.exceptions;

/** Missing or ambiguous local↔external SCIM mapping. Permanent. */
public class InconsistentScimMappingException extends ScimPropagationException {
    public InconsistentScimMappingException(String message) { super(message); }
    public InconsistentScimMappingException(String message, Throwable cause) { super(message, cause); }

    @Override public boolean isTransient() { return false; }
}
