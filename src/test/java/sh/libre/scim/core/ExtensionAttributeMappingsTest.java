package sh.libre.scim.core;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import de.captaingoldfish.scim.sdk.common.resources.User;
import org.junit.jupiter.api.Test;
import org.keycloak.models.UserModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExtensionAttributeMappingsTest {

    private static final String ENT = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";
    private static final String CUSTOM = "urn:example:custom:2.0:User";

    @Test
    void parse_emptyAndBlankRowsYieldEmptyTable() {
        assertThat(ExtensionAttributeMappings.parse(List.of()).isEmpty()).isTrue();
        assertThat(ExtensionAttributeMappings.parse(List.of("   ", "")).isEmpty()).isTrue();
    }

    @Test
    void parse_simpleCustomRow() {
        var table = ExtensionAttributeMappings.parse(List.of("dept = " + CUSTOM + ":department"));
        var rows = table.rows();
        assertThat(rows).hasSize(1);
        var m = rows.get(0);
        assertThat(m.keycloakAttr()).isEqualTo("dept");
        assertThat(m.schemaUrn()).isEqualTo(CUSTOM);
        assertThat(m.attributeName()).isEqualTo("department");
        assertThat(m.type()).isEqualTo(ExtensionAttributeType.STRING);
        assertThat(m.multivalued()).isFalse();
    }

    @Test
    void parse_typeAndMultiModifiersOrderIndependent() {
        var a = ExtensionAttributeMappings.parse(List.of("h = " + CUSTOM + ":hire ; type=dateTime ; multi")).rows().get(0);
        assertThat(a.type()).isEqualTo(ExtensionAttributeType.DATETIME);
        assertThat(a.multivalued()).isTrue();

        var b = ExtensionAttributeMappings.parse(List.of("h = " + CUSTOM + ":hire ; multi ; type=dateTime")).rows().get(0);
        assertThat(b.type()).isEqualTo(ExtensionAttributeType.DATETIME);
        assertThat(b.multivalued()).isTrue();
    }

    @Test
    void parse_splitsOnLastColonOnly() {
        var m = ExtensionAttributeMappings.parse(List.of("o = " + CUSTOM + ":labels")).rows().get(0);
        assertThat(m.schemaUrn()).isEqualTo(CUSTOM);
        assertThat(m.attributeName()).isEqualTo("labels");
    }

    @Test
    void parse_rejectsRowWithoutEquals() {
        assertThatThrownBy(() -> ExtensionAttributeMappings.parse(List.of(CUSTOM + ":department")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_rejectsEmptyKeycloakAttr() {
        assertThatThrownBy(() -> ExtensionAttributeMappings.parse(List.of("  = " + CUSTOM + ":x")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_rejectsPathWithoutColon() {
        assertThatThrownBy(() -> ExtensionAttributeMappings.parse(List.of("x = noColonHere")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_rejectsEmptyAttributeSegment() {
        assertThatThrownBy(() -> ExtensionAttributeMappings.parse(List.of("x = " + CUSTOM + ":")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_rejectsUnknownType() {
        assertThatThrownBy(() -> ExtensionAttributeMappings.parse(List.of("x = " + CUSTOM + ":y ; type=complex")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_enterpriseFieldAccepted() {
        var m = ExtensionAttributeMappings.parse(List.of("d = " + ENT + ":department")).rows().get(0);
        assertThat(m.schemaUrn()).isEqualTo(ENT);
        assertThat(m.attributeName()).isEqualTo("department");
    }

    @Test
    void parse_enterpriseRejectsUnknownField() {
        assertThatThrownBy(() -> ExtensionAttributeMappings.parse(List.of("x = " + ENT + ":nickname")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_enterpriseRejectsNonStringType() {
        assertThatThrownBy(() -> ExtensionAttributeMappings.parse(List.of("x = " + ENT + ":department ; type=boolean")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_enterpriseRejectsMulti() {
        assertThatThrownBy(() -> ExtensionAttributeMappings.parse(List.of("x = " + ENT + ":department ; multi")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_rejectsDuplicateFullyQualifiedPath() {
        assertThatThrownBy(() -> ExtensionAttributeMappings.parse(List.of(
                "a = " + CUSTOM + ":dept",
                "b = " + CUSTOM + ":dept")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dept");
    }

    @Test
    void parse_rejectsEqualsInKeycloakAttrName() {
        // First-'=' split would silently truncate the attr name; the whitespace-in-path
        // guard turns this into a clear error.
        assertThatThrownBy(() -> ExtensionAttributeMappings.parse(List.of(
                "my=attr = " + CUSTOM + ":field")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_nullInputYieldsEmptyTable() {
        assertThat(ExtensionAttributeMappings.parse(null).isEmpty()).isTrue();
    }

    @Test
    void attach_enterpriseFieldsUseTypedObjectAndAddSchema() {
        var table = ExtensionAttributeMappings.parse(List.of(
            "kcDept = " + ENT + ":department",
            "kcOrg  = " + ENT + ":organization"));

        UserModel user = mock(UserModel.class);
        when(user.getFirstAttribute("kcDept")).thenReturn("Engineering");
        when(user.getFirstAttribute("kcOrg")).thenReturn("Acme");

        User scim = new User();
        table.attach(table.read(user), scim);

        assertThat(scim.getEnterpriseUser()).isPresent();
        assertThat(scim.getEnterpriseUser().get().getDepartment()).contains("Engineering");
        assertThat(scim.getEnterpriseUser().get().getOrganization()).contains("Acme");
        assertThat(scim.getSchemas()).contains(ENT);
    }

    @Test
    void attach_customSingleValuedEmitsTypedPrimitiveAndAddsSchema() {
        var table = ExtensionAttributeMappings.parse(List.of(
            "kcActive = " + CUSTOM + ":active ; type=boolean",
            "kcNote   = " + CUSTOM + ":note"));

        UserModel user = mock(UserModel.class);
        when(user.getFirstAttribute("kcActive")).thenReturn("true");
        when(user.getFirstAttribute("kcNote")).thenReturn("hello");

        User scim = new User();
        table.attach(table.read(user), scim);

        assertThat(scim.getSchemas()).contains(CUSTOM);
        var ext = scim.get(CUSTOM); // User is a Jackson ObjectNode
        assertThat(ext.get("active").isBoolean()).isTrue();
        assertThat(ext.get("active").booleanValue()).isTrue();
        assertThat(ext.get("note").asText()).isEqualTo("hello");
    }

    @Test
    void attach_customMultivaluedEmitsJsonArray() {
        var table = ExtensionAttributeMappings.parse(List.of("kcLabels = " + CUSTOM + ":labels ; multi"));

        UserModel user = mock(UserModel.class);
        when(user.getAttributes()).thenReturn(Map.of("kcLabels", List.of("a", "b")));

        User scim = new User();
        table.attach(table.read(user), scim);

        var arr = scim.get(CUSTOM).get("labels");
        assertThat(arr.isArray()).isTrue();
        assertThat(arr).extracting(JsonNode::asText).containsExactly("a", "b");
    }

    @Test
    void attach_skipsAbsentAttributesAndUncoercibleValues() {
        var table = ExtensionAttributeMappings.parse(List.of(
            "kcMissing = " + CUSTOM + ":missing",
            "kcBad     = " + CUSTOM + ":num ; type=integer"));

        UserModel user = mock(UserModel.class);
        when(user.getFirstAttribute("kcMissing")).thenReturn(null);
        when(user.getFirstAttribute("kcBad")).thenReturn("not-a-number");

        User scim = new User();
        table.attach(table.read(user), scim);

        assertThat(scim.getSchemas()).doesNotContain(CUSTOM);
    }

    @Test
    void attach_skipsBlankSingleValuedAttribute() {
        var table = ExtensionAttributeMappings.parse(List.of("kcDept = " + ENT + ":department"));

        UserModel user = mock(UserModel.class);
        when(user.getFirstAttribute("kcDept")).thenReturn("   ");

        User scim = new User();
        table.attach(table.read(user), scim);

        assertThat(scim.getEnterpriseUser()).isEmpty();
        assertThat(scim.getSchemas()).doesNotContain(ENT);
    }

    @Test
    void attach_multivaluedDropsOnlyBadElements() {
        var table = ExtensionAttributeMappings.parse(List.of("kcNums = " + CUSTOM + ":nums ; type=integer ; multi"));

        UserModel user = mock(UserModel.class);
        when(user.getAttributes()).thenReturn(Map.of("kcNums", List.of("1", "x", "3")));

        User scim = new User();
        table.attach(table.read(user), scim);

        var arr = scim.get(CUSTOM).get("nums");
        assertThat(arr).extracting(JsonNode::longValue).containsExactly(1L, 3L);
    }

    @Test
    void attach_multivaluedDropsBlankElements() {
        var table = ExtensionAttributeMappings.parse(List.of("kcVals = " + CUSTOM + ":vals ; multi"));

        UserModel user = mock(UserModel.class);
        when(user.getAttributes()).thenReturn(Map.of("kcVals", java.util.Arrays.asList("a", "  ", "b")));

        User scim = new User();
        table.attach(table.read(user), scim);

        var arr = scim.get(CUSTOM).get("vals");
        assertThat(arr).extracting(JsonNode::asText).containsExactly("a", "b");
    }

    @Test
    void attach_multipleCustomSchemasEachGetTheirOwnExtension() {
        var other = "urn:example:other:2.0:User";
        var table = ExtensionAttributeMappings.parse(List.of(
            "kcA = " + CUSTOM + ":alpha",
            "kcB = " + other + ":beta"));

        UserModel user = mock(UserModel.class);
        when(user.getFirstAttribute("kcA")).thenReturn("one");
        when(user.getFirstAttribute("kcB")).thenReturn("two");

        User scim = new User();
        table.attach(table.read(user), scim);

        assertThat(scim.getSchemas()).contains(CUSTOM, other);
        assertThat(scim.get(CUSTOM).get("alpha").asText()).isEqualTo("one");
        assertThat(scim.get(other).get("beta").asText()).isEqualTo("two");
    }

    @Test
    void fromConfig_downgradesParseErrorToEmpty() {
        // a malformed row would throw in parse(); fromConfig must not propagate
        var table = ExtensionAttributeMappings.fromConfig(List.of("no-equals-here"));
        assertThat(table.isEmpty()).isTrue();
    }

    @Test
    void patchValues_producesFullyQualifiedPathsWithCoercedValues() {
        var table = ExtensionAttributeMappings.parse(List.of(
            "kcDept   = " + ENT + ":department",
            "kcActive = " + CUSTOM + ":active ; type=boolean"));

        UserModel user = mock(UserModel.class);
        when(user.getFirstAttribute("kcDept")).thenReturn("Eng");
        when(user.getFirstAttribute("kcActive")).thenReturn("true");

        var ops = table.patchValues(table.read(user));
        assertThat(ops).extracting(ExtensionAttributeMappings.PatchValue::path)
            .containsExactlyInAnyOrder(ENT + ":department", CUSTOM + ":active");

        var byPath = ops.stream().collect(java.util.stream.Collectors.toMap(
            ExtensionAttributeMappings.PatchValue::path,
            ExtensionAttributeMappings.PatchValue::value));
        assertThat(byPath.get(ENT + ":department").isTextual()).isTrue();
        assertThat(byPath.get(CUSTOM + ":active").isBoolean()).isTrue();
        assertThat(byPath.get(CUSTOM + ":active").booleanValue()).isTrue();
    }

    @Test
    void fromConfig_nullInputYieldsEmptyTable() {
        assertThat(ExtensionAttributeMappings.fromConfig(null).isEmpty()).isTrue();
    }
}
