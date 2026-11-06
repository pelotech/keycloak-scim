package sh.libre.scim.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.captaingoldfish.scim.sdk.client.ScimClientConfig;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserAdapterTest {

    private static final String COMPONENT_ID = "component-id";

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock ComponentModel component;
    @Mock JpaConnectionProvider jpaConnectionProvider;
    @Mock EntityManager entityManager;

    private UserAdapter adapter;

    @BeforeEach
    void setUp() {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-id");
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpaConnectionProvider);
        when(jpaConnectionProvider.getEntityManager()).thenReturn(entityManager);

        adapter = new UserAdapter(session, COMPONENT_ID);
        adapter.setActive(true);
        adapter.setRoles(new String[]{});
    }

    @Test
    void toScimUsesUsernameByDefault() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("username-source")).thenReturn(null);

        adapter.setUsername("alice");
        adapter.setEmail("alice@example.com");

        var user = adapter.toSCIM(false);
        assertEquals("alice", user.getUserName().orElse(null));
    }

    @Test
    void toScimUsesEmailWhenConfigured() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("username-source")).thenReturn("email");

        adapter.setUsername("alice");
        adapter.setEmail("alice@example.com");

        var user = adapter.toSCIM(false);
        assertEquals("alice@example.com", user.getUserName().orElse(null));
    }

    @Test
    void toScimFallsBackToUsernameWhenEmailSourceConfiguredButEmailMissing() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("username-source")).thenReturn("email");

        adapter.setUsername("alice");
        // no email set

        var user = adapter.toSCIM(false);
        assertEquals("alice", user.getUserName().orElse(null));
    }

    @Test
    void toScimFallsBackToUsernameWhenComponentMissing() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(null);

        adapter.setUsername("alice");
        adapter.setEmail("alice@example.com");

        var user = adapter.toSCIM(false);
        assertEquals("alice", user.getUserName().orElse(null));
    }

    @Test
    void toScimEmailHasWorkTypeAndPrimaryFlag() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);

        adapter.setUsername("alice");
        adapter.setEmail("alice@example.com");

        var user = adapter.toSCIM(false);
        var emails = user.getEmails();

        assertEquals(1, emails.size());
        var email = emails.get(0);
        assertEquals("alice@example.com", email.getValue().orElse(null));
        assertEquals("work", email.getType().orElse(null));
        assertTrue(email.isPrimary());
    }

    @Test
    void toScimEmitsNoEmailEntryWhenEmailMissing() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);

        adapter.setUsername("alice");
        // no email

        var user = adapter.toSCIM(false);
        assertTrue(user.getEmails().isEmpty());
    }

    @Test
    void propagationRoleBlankDoesNotSkip() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("propagation-role")).thenReturn("");

        assertFalse(adapter.skippedByPropagationRole(mock(UserModel.class)));
    }

    @Test
    void propagationRoleUnsetDoesNotSkip() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("propagation-role")).thenReturn(null);

        assertFalse(adapter.skippedByPropagationRole(mock(UserModel.class)));
    }

    @Test
    void propagationRoleUserHasItDoesNotSkip() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("propagation-role")).thenReturn("scim-push");
        var role = mock(RoleModel.class);
        when(realm.getRole("scim-push")).thenReturn(role);
        var user = mock(UserModel.class);
        when(user.hasRole(role)).thenReturn(true);

        assertFalse(adapter.skippedByPropagationRole(user));
    }

    @Test
    void propagationRoleUserLacksItSkips() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("propagation-role")).thenReturn("scim-push");
        var role = mock(RoleModel.class);
        when(realm.getRole("scim-push")).thenReturn(role);
        var user = mock(UserModel.class);
        when(user.hasRole(role)).thenReturn(false);

        assertTrue(adapter.skippedByPropagationRole(user));
    }

    @Test
    void propagationRoleNotFoundFailsClosedAndSkips() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("propagation-role")).thenReturn("typo-role");
        when(realm.getRole("typo-role")).thenReturn(null);

        assertTrue(adapter.skippedByPropagationRole(mock(UserModel.class)));
    }

    /** Index the PATCH body's operations by path. */
    private Map<String, JsonNode> patchOpsByPath() throws Exception {
        var requestBuilder = new ScimRequestBuilder(
            "https://scim.example/scim/v2", ScimClientConfig.builder().build());
        var body = adapter.toPatchBuilder(requestBuilder, "Users/ext-1").getResource();
        var byPath = new HashMap<String, JsonNode>();
        for (JsonNode op : new ObjectMapper().readTree(body).get("Operations")) {
            byPath.put(op.get("path").asText(), op);
        }
        return byPath;
    }

    private static JsonNode value(JsonNode op) {
        assertNotNull(op);
        return op.get("value");
    }

    @Test
    void patchSendsActiveAsJsonBoolean() throws Exception {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        adapter.setUsername("alice");

        // RFC 7643: active is a single boolean, not "true" and not [true].
        var active = value(patchOpsByPath().get("active"));
        assertTrue(active.isBoolean(), "active must be a JSON boolean, was: " + active);
        assertTrue(active.booleanValue());
    }

    @Test
    void patchCoversTheSameAttributesAsPut() throws Exception {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        adapter.setId("kc-1");
        adapter.setUsername("alice");
        adapter.setDisplayName("Alice Liddell");
        adapter.setGivenName("Alice");
        adapter.setFamilyName("Liddell");
        adapter.setEmail("alice@example.com");

        var ops = patchOpsByPath();
        assertEquals("kc-1", value(ops.get("externalId")).asText());
        assertEquals("alice", value(ops.get("userName")).asText());
        assertEquals("Alice Liddell", value(ops.get("displayName")).asText());
        assertEquals("Alice", value(ops.get("name.givenName")).asText());
        assertEquals("Liddell", value(ops.get("name.familyName")).asText());
        var emails = value(ops.get("emails"));
        assertEquals(1, emails.size());
        var email = emails.get(0);
        assertEquals("alice@example.com", email.get("value").asText());
        assertEquals("work", email.get("type").asText());
        assertTrue(email.get("primary").asBoolean());
        for (var op : ops.values()) {
            assertEquals("replace", op.get("op").asText());
        }
    }

    @Test
    void patchUserNameFollowsUsernameSource() throws Exception {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("username-source")).thenReturn("email");
        adapter.setUsername("alice");
        adapter.setEmail("alice@example.com");

        assertEquals("alice@example.com", value(patchOpsByPath().get("userName")).asText());
    }

    @Test
    void patchLeavesOutAttributesThatHaveNoValue() throws Exception {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        adapter.setUsername("alice");

        var ops = patchOpsByPath();
        assertFalse(ops.containsKey("name.givenName"));
        assertFalse(ops.containsKey("name.familyName"));
        assertFalse(ops.containsKey("emails"));
    }

    private static RoleModel role(String name, boolean marked, RoleModel... composites) {
        var r = mock(RoleModel.class);
        when(r.getName()).thenReturn(name);
        when(r.getFirstAttribute("scim")).thenReturn(marked ? "true" : null);
        when(r.isComposite()).thenReturn(composites.length > 0);
        when(r.getCompositesStream()).thenAnswer(i -> Stream.of(composites));
        return r;
    }

    /**
     * Roles reach SCIM from composites, parent groups, and client roles. A
     * realm role and a client role with the same name appear once. Unmarked
     * roles stay out. The list is sorted.
     */
    @Test
    void applySendsEffectiveMarkedRoles() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.getConfig()).thenReturn(new MultivaluedHashMap<>());
        var direct = role("direct", true);
        var plain = role("plain", false);
        var viaComposite = role("via-composite", true);
        var composite = role("composite", false, viaComposite);
        var fromParent = role("from-parent", true);
        var sharedRealm = role("shared", true);
        var sharedClient = role("shared", true);
        var parent = mock(GroupModel.class);
        when(parent.getRoleMappingsStream()).thenAnswer(i -> Stream.of(fromParent));
        var child = mock(GroupModel.class);
        when(child.getParentId()).thenReturn("parent-id");
        when(child.getParent()).thenReturn(parent);
        when(child.getRoleMappingsStream()).thenAnswer(i -> Stream.of(sharedClient));
        var user = mock(UserModel.class);
        when(user.getId()).thenReturn("u1");
        when(user.getUsername()).thenReturn("alice");
        when(user.isEnabled()).thenReturn(true);
        when(user.getRoleMappingsStream()).thenAnswer(i -> Stream.of(direct, plain, composite, sharedRealm));
        when(user.getGroupsStream()).thenAnswer(i -> Stream.of(child));

        var fresh = new UserAdapter(session, COMPONENT_ID);
        fresh.apply(user);

        assertArrayEquals(new String[] {"direct", "from-parent", "shared", "via-composite"}, fresh.getRoles());
    }
}
