package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ScimBodyHashTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void keyOrderDoesNotChangeTheHash() throws Exception {
        var a = JSON.readTree("{\"userName\":\"a\",\"name\":{\"givenName\":\"A\",\"familyName\":\"B\"}}");
        var b = JSON.readTree("{\"name\":{\"familyName\":\"B\",\"givenName\":\"A\"},\"userName\":\"a\"}");

        assertThat(ScimBodyHash.of(a)).isEqualTo(ScimBodyHash.of(b));
    }

    @Test
    void idAndMetaAreIgnored() throws Exception {
        var plain = JSON.readTree("{\"userName\":\"a\"}");
        var withIds = JSON.readTree("{\"userName\":\"a\",\"id\":\"remote-1\",\"meta\":{\"location\":\"x\"}}");

        assertThat(ScimBodyHash.of(withIds)).isEqualTo(ScimBodyHash.of(plain));
    }

    @Test
    void aChangedRoleChangesTheHash() throws Exception {
        var before = JSON.readTree("{\"roles\":[{\"value\":\"a\"}]}");
        var after = JSON.readTree("{\"roles\":[{\"value\":\"a\"},{\"value\":\"b\"}]}");

        assertThat(ScimBodyHash.of(after)).isNotEqualTo(ScimBodyHash.of(before));
    }

    @Test
    void theHashIsSixtyFourHexCharacters() throws Exception {
        assertThat(ScimBodyHash.of(JSON.readTree("{}"))).matches("[0-9a-f]{64}");
    }

    @Test
    void theInputIsNotChanged() throws Exception {
        var body = JSON.readTree("{\"id\":\"remote-1\",\"userName\":\"a\"}");

        ScimBodyHash.of(body);

        assertThat(body.has("id")).isTrue();
    }
}
