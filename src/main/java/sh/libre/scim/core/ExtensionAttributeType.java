package sh.libre.scim.core;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Supported SCIM extension attribute types and their coercion from the raw
 * (always-string) Keycloak attribute value into a JSON node of the right type.
 * Complex and binary are intentionally unsupported (see spec Out of scope).
 */
public enum ExtensionAttributeType {
    STRING,
    BOOLEAN,
    INTEGER,
    DECIMAL,
    DATETIME,
    REFERENCE;

    /** Parse a {@code type=} token; null/blank defaults to STRING. Throws on unknown. */
    public static ExtensionAttributeType fromToken(String token) {
        if (token == null || token.isBlank()) {
            return STRING;
        }
        for (var t : values()) {
            if (t.name().equalsIgnoreCase(token.trim())) {
                return t;
            }
        }
        throw new IllegalArgumentException("unsupported type '" + token + "'");
    }

    /**
     * Coerce a raw string into a JSON node of this type.
     *
     * <p>Throws {@link IllegalArgumentException} on bad input, including {@code null}.
     * STRING and REFERENCE are preserved verbatim (no trimming) so that a SCIM string
     * attribute may legitimately contain leading/trailing whitespace. BOOLEAN, INTEGER,
     * DECIMAL, and DATETIME are trimmed before parsing.
     */
    public JsonNode coerce(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        switch (this) {
            case STRING:
            case REFERENCE:
                return TextNode.valueOf(raw);
            case BOOLEAN:
                String trimmedBool = raw.trim();
                if ("true".equalsIgnoreCase(trimmedBool)) return BooleanNode.TRUE;
                if ("false".equalsIgnoreCase(trimmedBool)) return BooleanNode.FALSE;
                throw new IllegalArgumentException("not a boolean: '" + raw + "'");
            case INTEGER:
                try {
                    return LongNode.valueOf(Long.parseLong(raw.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("not an integer: '" + raw + "'", e);
                }
            case DECIMAL:
                try {
                    return DecimalNode.valueOf(new BigDecimal(raw.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("not a decimal: '" + raw + "'", e);
                }
            case DATETIME:
                String trimmedDt = raw.trim();
                try {
                    OffsetDateTime.parse(trimmedDt);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("not an ISO-8601 dateTime: '" + raw + "'", e);
                }
                return TextNode.valueOf(trimmedDt);
            default:
                throw new IllegalStateException("unreachable");
        }
    }
}
