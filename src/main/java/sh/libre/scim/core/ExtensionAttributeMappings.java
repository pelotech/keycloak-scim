package sh.libre.scim.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;
import org.keycloak.models.UserModel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.captaingoldfish.scim.sdk.common.resources.EnterpriseUser;
import de.captaingoldfish.scim.sdk.common.resources.User;
import de.captaingoldfish.scim.sdk.common.utils.JsonHelper;

/**
 * Parses and holds the admin-configured extension-attribute mapping table,
 * reads values off a UserModel, and attaches them as SCIM extensions.
 * Resource-type-agnostic so GroupAdapter can reuse it.
 *
 * <p>{@link #parse(List)} throws {@link IllegalArgumentException} with a
 * specific message on any malformed row, so the same routine backs both
 * save-time validation (wrapped into ComponentValidationException by the
 * factory) and runtime construction (caught and downgraded to empty + warning
 * by {@link #fromConfig(List)}).
 */
public final class ExtensionAttributeMappings {

    private static final Logger LOGGER = Logger.getLogger(ExtensionAttributeMappings.class);

    public static final String ENTERPRISE_USER_URN =
        "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";

    /** The five string fields the SCIM SDK's EnterpriseUser exposes. */
    static final Set<String> ENTERPRISE_FIELDS =
        Set.of("employeeNumber", "costCenter", "organization", "division", "department");

    private final List<ExtensionAttributeMapping> rows;

    private ExtensionAttributeMappings(List<ExtensionAttributeMapping> rows) {
        this.rows = List.copyOf(rows);
    }

    public static ExtensionAttributeMappings parse(List<String> configRows) {
        List<ExtensionAttributeMapping> parsed = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        if (configRows != null) {
            for (String raw : configRows) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                ExtensionAttributeMapping m = parseRow(raw);
                if (!seenPaths.add(m.fullyQualifiedPath())) {
                    throw new IllegalArgumentException(
                        "duplicate SCIM path '" + m.fullyQualifiedPath() + "' in row '" + raw + "'");
                }
                parsed.add(m);
            }
        }
        return new ExtensionAttributeMappings(parsed);
    }

    private static ExtensionAttributeMapping parseRow(String raw) {
        int eq = raw.indexOf('=');
        if (eq < 0) {
            throw new IllegalArgumentException("mapping row missing '=': '" + raw + "'");
        }
        String kcAttr = raw.substring(0, eq).trim();
        if (kcAttr.isEmpty()) {
            throw new IllegalArgumentException("mapping row has empty Keycloak attribute: '" + raw + "'");
        }
        String rhs = raw.substring(eq + 1).trim();

        // Peel off ';'-delimited modifiers (type=..., multi) in any order.
        ExtensionAttributeType type = ExtensionAttributeType.STRING;
        boolean multi = false;
        String[] parts = rhs.split(";");
        String path = parts[0].trim();
        if (path.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                "SCIM path must not contain whitespace (check for a stray '=' in the Keycloak "
                + "attribute name) in row '" + raw + "'");
        }
        for (int i = 1; i < parts.length; i++) {
            String mod = parts[i].trim();
            if (mod.isEmpty()) {
                continue;
            }
            if (mod.equalsIgnoreCase("multi")) {
                multi = true;
            } else if (mod.toLowerCase().startsWith("type=")) {
                type = ExtensionAttributeType.fromToken(mod.substring("type=".length()).trim());
            } else {
                throw new IllegalArgumentException("unknown modifier '" + mod + "' in row '" + raw + "'");
            }
        }

        int lastColon = path.lastIndexOf(':');
        if (lastColon < 0) {
            throw new IllegalArgumentException("SCIM path has no ':' in row '" + raw + "'");
        }
        String schemaUrn = path.substring(0, lastColon).trim();
        String attributeName = path.substring(lastColon + 1).trim();
        if (schemaUrn.isEmpty()) {
            throw new IllegalArgumentException("empty schema URN in row '" + raw + "'");
        }
        if (attributeName.isEmpty()) {
            throw new IllegalArgumentException("empty attribute name in row '" + raw + "'");
        }

        if (ENTERPRISE_USER_URN.equals(schemaUrn)) {
            if (!ENTERPRISE_FIELDS.contains(attributeName)) {
                throw new IllegalArgumentException(
                    "unknown Enterprise User field '" + attributeName + "' in row '" + raw
                    + "' (supported: " + ENTERPRISE_FIELDS + ")");
            }
            if (type != ExtensionAttributeType.STRING) {
                throw new IllegalArgumentException(
                    "Enterprise User fields must be type=string in row '" + raw + "'");
            }
            if (multi) {
                throw new IllegalArgumentException(
                    "Enterprise User fields are single-valued; 'multi' not allowed in row '" + raw + "'");
            }
        }

        return new ExtensionAttributeMapping(kcAttr, schemaUrn, attributeName, type, multi);
    }

    /** Runtime entry: never throws on a bad row — logs and returns an empty table. */
    public static ExtensionAttributeMappings fromConfig(List<String> configRows) {
        try {
            return parse(configRows);
        } catch (IllegalArgumentException e) {
            LOGGER.warnf("Invalid user-extension-mappings, ignoring all extension mappings: %s", e.getMessage());
            return new ExtensionAttributeMappings(List.of());
        }
    }

    /** A single PATCH REPLACE target: the fully-qualified path and its coerced JSON value. */
    public record PatchValue(String path, JsonNode value) {}

    /**
     * Flatten the read values into per-attribute PATCH REPLACE descriptors.
     * For multivalued attributes the value is a JSON array; bad elements are
     * dropped (consistent with {@link #attach}). Attributes whose value(s) all
     * fail to coerce are omitted.
     */
    public List<PatchValue> patchValues(Map<ExtensionAttributeMapping, List<String>> values) {
        List<PatchValue> ops = new ArrayList<>();
        for (var entry : values.entrySet()) {
            var m = entry.getKey();
            List<String> raw = entry.getValue();
            if (m.multivalued()) {
                ArrayNode arr = JsonNodeFactory.instance.arrayNode();
                for (String r : raw) {
                    JsonNode c = tryCoerce(m, r);
                    if (c != null) arr.add(c);
                }
                if (!arr.isEmpty()) {
                    ops.add(new PatchValue(m.fullyQualifiedPath(), arr));
                }
            } else {
                JsonNode c = tryCoerce(m, raw.get(0));
                if (c != null) {
                    ops.add(new PatchValue(m.fullyQualifiedPath(), c));
                }
            }
        }
        return ops;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public List<ExtensionAttributeMapping> rows() {
        return rows;
    }

    /**
     * Read the raw (string) values for each mapping that is present on the user.
     * Single-valued reads via getFirstAttribute; multivalued via getAttributes.
     * Mappings with no value present are omitted from the result.
     */
    public Map<ExtensionAttributeMapping, List<String>> read(UserModel user) {
        Map<ExtensionAttributeMapping, List<String>> out = new LinkedHashMap<>();
        Map<String, List<String>> allAttrs = null; // lazy-loaded once, only if a multivalued row exists
        boolean attrsLoaded = false;
        for (var m : rows) {
            if (m.multivalued()) {
                if (!attrsLoaded) {
                    allAttrs = user.getAttributes();
                    attrsLoaded = true;
                }
                List<String> values = allAttrs == null ? null : allAttrs.get(m.keycloakAttr());
                if (values != null) {
                    List<String> nonBlank = values.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .toList();
                    if (!nonBlank.isEmpty()) {
                        out.put(m, nonBlank);
                    }
                }
            } else {
                String v = user.getFirstAttribute(m.keycloakAttr());
                if (v != null && !v.isBlank()) {
                    out.put(m, List.of(v));
                }
            }
        }
        return out;
    }

    /** Coerce the read values and attach them to the target User as SCIM extensions. */
    public void attach(Map<ExtensionAttributeMapping, List<String>> values, User target) {
        if (values.isEmpty()) {
            return;
        }
        EnterpriseUser enterprise = null;
        Map<String, ObjectNode> customNodes = new LinkedHashMap<>();

        for (var entry : values.entrySet()) {
            var m = entry.getKey();
            List<String> raw = entry.getValue();

            if (ENTERPRISE_USER_URN.equals(m.schemaUrn())) {
                if (enterprise == null) {
                    enterprise = target.getEnterpriseUser().orElseGet(EnterpriseUser::new);
                }
                setEnterpriseField(enterprise, m.attributeName(), raw.get(0));
                continue;
            }

            ObjectNode node = customNodes.computeIfAbsent(
                m.schemaUrn(), k -> JsonNodeFactory.instance.objectNode());
            if (m.multivalued()) {
                ArrayNode arr = JsonNodeFactory.instance.arrayNode();
                for (String r : raw) {
                    JsonNode coerced = tryCoerce(m, r);
                    if (coerced != null) {
                        arr.add(coerced);
                    }
                }
                if (!arr.isEmpty()) {
                    node.set(m.attributeName(), arr);
                }
            } else {
                JsonNode coerced = tryCoerce(m, raw.get(0));
                if (coerced != null) {
                    node.set(m.attributeName(), coerced);
                }
            }
        }

        if (enterprise != null) {
            // setEnterpriseUser manages the enterprise URN in the schemas set itself
            target.setEnterpriseUser(enterprise);
        }
        for (var e : customNodes.entrySet()) {
            ObjectNode node = e.getValue();
            if (node.isEmpty()) {
                continue;
            }
            target.addSchema(e.getKey());
            JsonHelper.addAttribute(target, e.getKey(), node);
        }
    }

    private JsonNode tryCoerce(ExtensionAttributeMapping m, String raw) {
        try {
            return m.type().coerce(raw);
        } catch (IllegalArgumentException e) {
            LOGGER.warnf("Skipping extension value for %s (%s): %s",
                m.fullyQualifiedPath(), m.type(), e.getMessage());
            return null;
        }
    }

    private static void setEnterpriseField(EnterpriseUser eu, String field, String value) {
        switch (field) {
            case "employeeNumber" -> eu.setEmployeeNumber(value);
            case "costCenter"     -> eu.setCostCenter(value);
            case "organization"   -> eu.setOrganization(value);
            case "division"       -> eu.setDivision(value);
            case "department"     -> eu.setDepartment(value);
            default -> throw new IllegalStateException("unvalidated enterprise field: " + field);
        }
    }
}
