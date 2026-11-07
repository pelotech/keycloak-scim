package sh.libre.scim.event;

import org.junit.jupiter.api.Test;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RoleModel;

import sh.libre.scim.core.ScimDispatcher;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    void confersScimRole_throughAParentGroup() {
        var marked = role("true");
        var parent = mock(GroupModel.class);
        when(parent.getRoleMappingsStream()).thenAnswer(i -> Stream.of(marked));
        var child = mock(GroupModel.class);
        when(child.getRoleMappingsStream()).thenAnswer(i -> Stream.empty());
        when(child.getParent()).thenReturn(parent);

        assertTrue(listener.groupConfersScimRole(child));
    }

    @Test
    void confersScimRole_throughAComposite() {
        var marked = role("true");
        var composite = role(null);
        when(composite.isComposite()).thenReturn(true);
        when(composite.getCompositesStream()).thenAnswer(i -> Stream.of(marked));
        var group = mock(GroupModel.class);
        when(group.getRoleMappingsStream()).thenAnswer(i -> Stream.of(composite));

        assertTrue(listener.groupConfersScimRole(group));
    }

    private static AdminEvent event(ResourceType type, OperationType op, String path) {
        var e = new AdminEvent();
        e.setResourceType(type);
        e.setOperationType(op);
        e.setResourcePath(path);
        return e;
    }

    private static ScimDispatcher routed(AdminEvent e) {
        var routing = new ScimEventListenerProvider(mock(KeycloakSession.class, RETURNS_DEEP_STUBS));
        var dispatcher = mock(ScimDispatcher.class);
        routing.dispatcher = dispatcher;
        routing.onEvent(e, false);
        return dispatcher;
    }

    @Test
    void aRoleGrantedToAGroupRequestsARoleRefreshAndSendsNoUser() {
        var d = routed(event(ResourceType.REALM_ROLE_MAPPING, OperationType.CREATE,
            "groups/g1/role-mappings/realm"));
        verify(d).requestRoleRefresh();
        verify(d, never()).run(any(), any());
    }

    @Test
    void aClientRoleGrantedToAGroupRequestsARoleRefresh() {
        var d = routed(event(ResourceType.CLIENT_ROLE_MAPPING, OperationType.CREATE,
            "groups/g1/role-mappings/clients/c1"));
        verify(d).requestRoleRefresh();
    }

    @Test
    void aClientRoleGrantedToAUserSendsThatUser() {
        var d = routed(event(ResourceType.CLIENT_ROLE_MAPPING, OperationType.CREATE,
            "users/u1/role-mappings/clients/c1"));
        verify(d).run(eq(ScimDispatcher.SCOPE_USER), any());
        verify(d, never()).requestRoleRefresh();
    }

    @Test
    void aRealmRoleChangeRequestsARoleRefresh() {
        var d = routed(event(ResourceType.REALM_ROLE, OperationType.UPDATE, "roles-by-id/r1"));
        verify(d).requestRoleRefresh();
    }

    @Test
    void aClientRoleChangeRequestsARoleRefresh() {
        var d = routed(event(ResourceType.CLIENT_ROLE, OperationType.DELETE, "clients/c1/roles/x"));
        verify(d).requestRoleRefresh();
    }

    @Test
    void aClientDeleteRequestsARoleRefresh() {
        var d = routed(event(ResourceType.CLIENT, OperationType.DELETE, "clients/c1"));
        verify(d).requestRoleRefresh();
    }

    @Test
    void aClientUpdateDoesNothing() {
        var d = routed(event(ResourceType.CLIENT, OperationType.UPDATE, "clients/c1"));
        verifyNoInteractions(d);
    }

    @Test
    void aMoveUnderANewParentRequestsARoleRefreshOnly() {
        var d = routed(event(ResourceType.GROUP, OperationType.UPDATE, "groups/parent-1/children"));
        verify(d).requestRoleRefresh();
        verify(d, never()).run(any(), any());
    }

    @Test
    void aMoveToTheTopLevelRequestsARoleRefreshOnly() {
        var d = routed(event(ResourceType.GROUP, OperationType.UPDATE, "groups"));
        verify(d).requestRoleRefresh();
        verify(d, never()).run(any(), any());
    }

    @Test
    void aGroupRenameUpdatesTheGroupAndRequestsNoRoleRefresh() {
        var d = routed(event(ResourceType.GROUP, OperationType.UPDATE, "groups/g1"));
        verify(d).run(eq(ScimDispatcher.SCOPE_GROUP), any());
        verify(d, never()).requestRoleRefresh();
    }

    @Test
    void aGroupDeleteOnADefaultGroupsPathDoesNothing() {
        var d = routed(event(ResourceType.GROUP, OperationType.DELETE, "default-groups/g1"));
        verifyNoInteractions(d);
    }

    @Test
    void aGroupUpdateOnAPermissionsPathDoesNothing() {
        var d = routed(event(ResourceType.GROUP, OperationType.UPDATE, "groups/g1/management/permissions"));
        verifyNoInteractions(d);
    }

    @Test
    void aGroupDeleteDeletesTheGroupAndRequestsARoleRefresh() {
        var d = routed(event(ResourceType.GROUP, OperationType.DELETE, "groups/g1"));
        verify(d).run(eq(ScimDispatcher.SCOPE_GROUP), any());
        verify(d).requestRoleRefresh();
    }

    @Test
    void aGroupIdWithADotIsAccepted() {
        var d = routed(event(ResourceType.GROUP, OperationType.DELETE, "groups/abc.def"));
        verify(d).run(eq(ScimDispatcher.SCOPE_GROUP), any());
        verify(d).requestRoleRefresh();
    }

    @Test
    void aTopLevelGroupCreateIsUnchanged() {
        var d = routed(event(ResourceType.GROUP, OperationType.CREATE, "groups/g1"));
        verify(d).run(eq(ScimDispatcher.SCOPE_GROUP), any());
        verify(d, never()).requestRoleRefresh();
    }

    @Test
    void theBareGroupsPathWithCreateDoesNothing() {
        var d = routed(event(ResourceType.GROUP, OperationType.CREATE, "groups"));
        verifyNoInteractions(d);
    }
}
