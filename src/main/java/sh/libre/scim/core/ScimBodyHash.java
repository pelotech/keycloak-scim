package sh.libre.scim.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The hash of a SCIM body that a refresh stores on the mapping row. Two bodies
 * that differ only in key order, in {@code id}, or in {@code meta} have the
 * same hash. {@code id} is the remote id, which the plugin does not decide.
 * Array order is kept.
 */
final class ScimBodyHash {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ScimBodyHash() {}

    /** SHA-256 of the canonical form of {@code body}, as 64 lowercase hex characters. */
    static String of(JsonNode body) {
        JsonNode canonical = canonical(body);
        if (canonical instanceof ObjectNode object) {
            object.remove("id");
            object.remove("meta");
        }
        try {
            byte[] bytes = JSON.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("could not hash a SCIM body", e);
        }
    }

    /**
     * A copy with object keys sorted at every level. Jackson's ObjectNode keeps
     * insertion order, so the sort is done here, not through a mapper setting.
     */
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                sorted.set(name, canonical(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> copy.add(canonical(element)));
            return copy;
        }
        return node;
    }
}
