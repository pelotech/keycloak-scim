package sh.libre.scim.event;

import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RoleModel;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScimEventListenerProviderTest {

    private final ScimEventListenerProvider listener =
        new ScimEventListenerProvider(mock(KeycloakSession.class));

    private static RoleModel role(String scimAttr) {
        var r = mock(RoleModel.class);
        when(r.getFirstAttribute("scim")).thenReturn(scimAttr);
        return r;
    }

    @Test
    void confersScimRole_whenAnyMappedRoleIsMarked() {
        var plain = role(null);
        var marked = role("true");
        var group = mock(GroupModel.class);
        when(group.getRoleMappingsStream()).thenReturn(Stream.of(plain, marked));

        assertTrue(listener.groupConfersScimRole(group));
    }

    @Test
    void doesNotConfer_whenNoMappedRoleIsMarked() {
        var plain = role(null);
        var other = role("false");
        var group = mock(GroupModel.class);
        when(group.getRoleMappingsStream()).thenReturn(Stream.of(plain, other));

        assertFalse(listener.groupConfersScimRole(group));
    }

    @Test
    void doesNotConfer_whenGroupHasNoRoles() {
        var group = mock(GroupModel.class);
        when(group.getRoleMappingsStream()).thenReturn(Stream.empty());

        assertFalse(listener.groupConfersScimRole(group));
    }
}
