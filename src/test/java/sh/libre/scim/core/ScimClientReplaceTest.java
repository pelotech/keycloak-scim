package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;

/** Replace-path decisions: PATCH or PUT, and when a failed replace re-creates. */
class ScimClientReplaceTest {

    private static ComponentModel model(String groupPatchOp, String userPatchOp) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        if (groupPatchOp != null) {
            config.putSingle("group-patchOp", groupPatchOp);
        }
        if (userPatchOp != null) {
            config.putSingle("user-patchOp", userPatchOp);
        }
        model.setConfig(config);
        return model;
    }

    @Test
    void groupsUsePatchByDefault() {
        assertThat(ScimClient.usesPatch(model(null, null), "Group")).isTrue();
    }

    @Test
    void groupsUsePutWhenGroupPatchOpIsFalse() {
        assertThat(ScimClient.usesPatch(model("false", null), "Group")).isFalse();
    }

    @Test
    void usersUsePutByDefault() {
        assertThat(ScimClient.usesPatch(model(null, null), "User")).isFalse();
    }

    @Test
    void usersUsePatchWhenUserPatchOpIsTrue() {
        assertThat(ScimClient.usesPatch(model(null, "true"), "User")).isTrue();
    }

    /** The type is compared by value, so a type string built at run time also matches. */
    @Test
    void typeIsComparedByValue() {
        var type = new String("User");
        assertThat(ScimClient.usesPatch(model(null, "true"), type)).isTrue();
    }

    @Test
    void notFoundReCreates() {
        assertThat(ScimClient.recreatesAfterFailedReplace(404)).isTrue();
    }

    /** A 400 means the server rejected the body, not that the resource is missing. */
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 409, 500})
    void otherFailuresDoNotReCreate(int status) {
        assertThat(ScimClient.recreatesAfterFailedReplace(status)).isFalse();
    }

    /** RFC 7643 defines active as a boolean, so the deactivate PATCH sends false, not "false". */
    @Test
    void deactivatePatchSendsActiveAsJsonBoolean() throws Exception {
        var body = new ObjectMapper().readTree(ScimClient.deactivatePatch().toString());
        var ops = body.get("Operations");
        assertThat(ops).hasSize(1);
        assertThat(ops.get(0).get("path").asText()).isEqualTo("active");
        assertThat(ops.get(0).get("op").asText()).isEqualTo("replace");
        assertThat(ops.get(0).get("value").isBoolean()).isTrue();
        assertThat(ops.get(0).get("value").booleanValue()).isFalse();
    }
}
