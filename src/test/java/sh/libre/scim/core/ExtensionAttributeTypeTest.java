package sh.libre.scim.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionAttributeTypeTest {

    @Test
    void fromToken_defaultsToStringWhenNull() {
        assertThat(ExtensionAttributeType.fromToken(null)).isEqualTo(ExtensionAttributeType.STRING);
    }

    @Test
    void fromToken_isCaseInsensitive() {
        assertThat(ExtensionAttributeType.fromToken("Boolean")).isEqualTo(ExtensionAttributeType.BOOLEAN);
        assertThat(ExtensionAttributeType.fromToken("dateTime")).isEqualTo(ExtensionAttributeType.DATETIME);
    }

    @Test
    void fromToken_rejectsUnknown() {
        assertThatThrownBy(() -> ExtensionAttributeType.fromToken("complex"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("complex");
    }

    @Test
    void coerce_stringAndReference_produceTextNodes() {
        assertThat(ExtensionAttributeType.STRING.coerce("hi").asText()).isEqualTo("hi");
        assertThat(ExtensionAttributeType.REFERENCE.coerce("urn:x").isTextual()).isTrue();
    }

    @Test
    void coerce_booleanProducesBooleanNode() {
        JsonNode node = ExtensionAttributeType.BOOLEAN.coerce("TRUE");
        assertThat(node.isBoolean()).isTrue();
        assertThat(node.booleanValue()).isTrue();
    }

    @Test
    void coerce_booleanRejectsGarbage() {
        assertThatThrownBy(() -> ExtensionAttributeType.BOOLEAN.coerce("yes"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coerce_integerAndDecimalProduceNumberNodes() {
        assertThat(ExtensionAttributeType.INTEGER.coerce("42").isNumber()).isTrue();
        assertThat(ExtensionAttributeType.INTEGER.coerce("42").longValue()).isEqualTo(42L);
        assertThat(ExtensionAttributeType.DECIMAL.coerce("3.14").isNumber()).isTrue();
    }

    @Test
    void coerce_integerRejectsNonInteger() {
        assertThatThrownBy(() -> ExtensionAttributeType.INTEGER.coerce("3.14"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coerce_dateTimeValidatesIso8601AndKeepsString() {
        JsonNode node = ExtensionAttributeType.DATETIME.coerce("2021-01-01T00:00:00Z");
        assertThat(node.isTextual()).isTrue();
        assertThat(node.asText()).isEqualTo("2021-01-01T00:00:00Z");
    }

    @Test
    void coerce_dateTimeAcceptsNonZuluOffsetAndPreservesVerbatim() {
        JsonNode node = ExtensionAttributeType.DATETIME.coerce("2021-01-01T00:00:00+02:00");
        assertThat(node.asText()).isEqualTo("2021-01-01T00:00:00+02:00");
    }

    @Test
    void coerce_dateTimeRejectsBadFormat() {
        assertThatThrownBy(() -> ExtensionAttributeType.DATETIME.coerce("not-a-date"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromToken_defaultsToStringWhenBlank() {
        assertThat(ExtensionAttributeType.fromToken("")).isEqualTo(ExtensionAttributeType.STRING);
        assertThat(ExtensionAttributeType.fromToken("   ")).isEqualTo(ExtensionAttributeType.STRING);
    }

    @Test
    void coerce_booleanFalseBranch() {
        assertThat(ExtensionAttributeType.BOOLEAN.coerce("false").booleanValue()).isFalse();
        assertThat(ExtensionAttributeType.BOOLEAN.coerce("FALSE").booleanValue()).isFalse();
    }

    @Test
    void coerce_booleanTrimsWhitespace() {
        assertThat(ExtensionAttributeType.BOOLEAN.coerce(" true ").booleanValue()).isTrue();
    }

    @Test
    void coerce_dateTimeTrimsWhitespace() {
        JsonNode node = ExtensionAttributeType.DATETIME.coerce(" 2021-01-01T00:00:00Z ");
        assertThat(node.asText()).isEqualTo("2021-01-01T00:00:00Z");
    }

    @Test
    void coerce_stringPreservesWhitespaceVerbatim() {
        assertThat(ExtensionAttributeType.STRING.coerce("  hi  ").asText()).isEqualTo("  hi  ");
    }

    @Test
    void coerce_nullThrowsIllegalArgument() {
        assertThatThrownBy(() -> ExtensionAttributeType.STRING.coerce(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
