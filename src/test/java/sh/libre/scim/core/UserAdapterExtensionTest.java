package sh.libre.scim.core;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserAdapterExtensionTest {

    private static final String COMPONENT_ID = "component-id";
    private static final String ENT = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";
    private static final String CUSTOM = "urn:example:custom:2.0:User";

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock JpaConnectionProvider jpaConnectionProvider;
    @Mock EntityManager entityManager;

    private ComponentModel componentWithMappings(List<String> rows) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.put("user-extension-mappings", rows);
        model.setConfig(config);
        model.setId(COMPONENT_ID);
        return model;
    }

    @BeforeEach
    void setUp() {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-id");
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpaConnectionProvider);
        when(jpaConnectionProvider.getEntityManager()).thenReturn(entityManager);
    }

    @Test
    void toScim_attachesEnterpriseAndCustomExtensions() {
        var model = componentWithMappings(List.of(
            "kcDept   = " + ENT + ":department",
            "kcLabels = " + CUSTOM + ":labels ; multi",
            "kcActive = " + CUSTOM + ":active ; type=boolean"));
        when(realm.getComponent(COMPONENT_ID)).thenReturn(model);

        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn("u1");
        when(user.getUsername()).thenReturn("alice");
        when(user.isEnabled()).thenReturn(true);
        when(user.getGroupsStream()).thenReturn(java.util.stream.Stream.empty());
        when(user.getRoleMappingsStream()).thenReturn(java.util.stream.Stream.empty());
        when(user.getFirstAttribute("kcDept")).thenReturn("Eng");
        when(user.getFirstAttribute("kcActive")).thenReturn("true");
        when(user.getAttributes()).thenReturn(Map.of("kcLabels", List.of("/a", "/b")));

        var adapter = new UserAdapter(session, COMPONENT_ID);
        adapter.apply(user);
        var scim = adapter.toSCIM(false);

        assertThat(scim.getEnterpriseUser()).isPresent();
        assertThat(scim.getEnterpriseUser().get().getDepartment()).contains("Eng");
        assertThat(scim.getSchemas()).contains(ENT, CUSTOM);
        JsonNode ext = scim.get(CUSTOM);
        assertThat(ext.get("active").booleanValue()).isTrue();
        assertThat(ext.get("labels").isArray()).isTrue();
        assertThat(ext.get("labels")).extracting(JsonNode::asText).containsExactly("/a", "/b");
    }

    @Test
    void toPatchBuilder_emitsTypedExtensionReplaceOps() throws Exception {
        var model = componentWithMappings(List.of(
            "kcDept   = " + ENT + ":department",
            "kcLabels = " + CUSTOM + ":labels ; multi",
            "kcActive = " + CUSTOM + ":active ; type=boolean"));
        when(realm.getComponent(COMPONENT_ID)).thenReturn(model);

        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn("u1");
        when(user.getUsername()).thenReturn("alice");
        when(user.isEnabled()).thenReturn(true);
        when(user.getGroupsStream()).thenReturn(java.util.stream.Stream.empty());
        when(user.getRoleMappingsStream()).thenReturn(java.util.stream.Stream.empty());
        when(user.getFirstAttribute("kcDept")).thenReturn("Eng");
        when(user.getFirstAttribute("kcActive")).thenReturn("true");
        when(user.getAttributes()).thenReturn(Map.of("kcLabels", List.of("/a", "/b")));

        var adapter = new UserAdapter(session, COMPONENT_ID);
        adapter.apply(user);

        var requestBuilder = new de.captaingoldfish.scim.sdk.client.ScimRequestBuilder(
            "https://example.test/scim/v2",
            de.captaingoldfish.scim.sdk.client.ScimClientConfig.builder().build());
        var patchBuilder = adapter.toPatchBuilder(requestBuilder, "Users/u1");

        // getResource() serializes all operations to a SCIM PatchOpRequest JSON document.
        JsonNode patch = new com.fasterxml.jackson.databind.ObjectMapper().readTree(patchBuilder.getResource());
        JsonNode ops = patch.get("Operations");
        assertThat(ops).isNotNull();

        // Index operations by their "path" for assertion.
        java.util.Map<String, JsonNode> byPath = new java.util.HashMap<>();
        for (JsonNode op : ops) {
            if (op.hasNonNull("path")) {
                byPath.put(op.get("path").asText(), op);
            }
        }

        // The SDK serializes every PATCH op's "value" as a JSON array per RFC 7644
        // (e.g. {"path":"...:active","op":"replace","value":[true]}). We therefore
        // assert the JSON *type of the array element(s)* to prove typing is preserved.

        // Enterprise department REPLACE op present (value wrapped as ["Eng"]).
        assertThat(byPath).containsKey(ENT + ":department");
        assertThat(byPath.get(ENT + ":department").get("op").asText()).isEqualToIgnoringCase("replace");

        // Custom boolean op: value is [true] — a one-element array whose element is a
        // JSON boolean true (NOT the string "true").
        JsonNode activeOp = byPath.get(CUSTOM + ":active");
        assertThat(activeOp).isNotNull();
        JsonNode activeValue = activeOp.get("value");
        assertThat(activeValue.isArray()).isTrue();
        assertThat(activeValue).hasSize(1);
        assertThat(activeValue.get(0).isBoolean()).isTrue();
        assertThat(activeValue.get(0).booleanValue()).isTrue();

        // Custom multivalued op: value is a JSON array carrying both elements.
        JsonNode labelsOp = byPath.get(CUSTOM + ":labels");
        assertThat(labelsOp).isNotNull();
        JsonNode labelsValue = labelsOp.get("value");
        assertThat(labelsValue.isArray()).isTrue();
        assertThat(labelsValue).extracting(JsonNode::asText).containsExactly("/a", "/b");
    }

    @Test
    void toScim_noMappingsLeavesUserUnchanged() {
        var model = componentWithMappings(List.of());
        when(realm.getComponent(COMPONENT_ID)).thenReturn(model);

        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn("u1");
        when(user.getUsername()).thenReturn("alice");
        when(user.isEnabled()).thenReturn(true);
        when(user.getGroupsStream()).thenReturn(java.util.stream.Stream.empty());
        when(user.getRoleMappingsStream()).thenReturn(java.util.stream.Stream.empty());

        var adapter = new UserAdapter(session, COMPONENT_ID);
        adapter.apply(user);
        var scim = adapter.toSCIM(false);

        assertThat(scim.getEnterpriseUser()).isEmpty();
        assertThat(scim.getSchemas()).doesNotContain(CUSTOM);
    }
}
