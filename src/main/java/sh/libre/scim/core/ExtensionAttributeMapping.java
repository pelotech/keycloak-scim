package sh.libre.scim.core;

/** One parsed extension-attribute mapping row. */
public record ExtensionAttributeMapping(
        String keycloakAttr,
        String schemaUrn,
        String attributeName,
        ExtensionAttributeType type,
        boolean multivalued) {

    /** Fully-qualified SCIM path, e.g. {@code urn:...:User:department}. */
    public String fullyQualifiedPath() {
        return schemaUrn + ":" + attributeName;
    }
}
