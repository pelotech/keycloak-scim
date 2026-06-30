package sh.libre.scim.core;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

}
