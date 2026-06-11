package sh.libre.scim.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.InOrder;

class EnsureGroupMembershipTest {

    private ScimClient newClient(boolean groupPatchOp, GroupModel group) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        config.putSingle("group-patchOp", Boolean.toString(groupPatchOp));
        model.setConfig(config);
        model.setId("comp-grp");

        var session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        var groups = mock(GroupProvider.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(session.groups()).thenReturn(groups);
        when(groups.getGroupById(realm, "grp-1")).thenReturn(group);

        // ScimClient ctor does not touch the session; the spy stubs the public methods under test
        return new ScimClient(model, session);
    }

    @Test
    void groupPatchOpOn_ensuresGroupThenAddsMember() {
        var group = mock(GroupModel.class);
        var client = spy(newClient(true, group));
        doNothing().when(client).provisionGroupForMembership(any(), eq(group));
        doReturn(true).when(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));

        boolean applied = client.ensureGroupMembership(GroupAdapter::new, "grp-1", "user-1");

        assertTrue(applied); // propagated -> caller records it in the propagated-group set
        InOrder order = inOrder(client);
        order.verify(client).provisionGroupForMembership(any(), eq(group));
        order.verify(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));
    }

    @Test
    void groupPatchOpOff_skipsEnsureCreate_stillAddsMember() {
        var group = mock(GroupModel.class);
        var client = spy(newClient(false, group));
        doReturn(true).when(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));

        client.ensureGroupMembership(GroupAdapter::new, "grp-1", "user-1");

        verify(client, never()).provisionGroupForMembership(any(), eq(group));
        verify(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));
    }

    @Test
    void missingLocalGroup_isSkipped() {
        var client = spy(newClient(true, null));

        boolean applied = client.ensureGroupMembership(GroupAdapter::new, "grp-1", "user-1");

        assertFalse(applied); // not propagated -> caller leaves it unrecorded and retries
        verify(client, never()).provisionGroupForMembership(any(), any());
        verify(client, never()).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));
    }

    @Test
    void groupPatchOpOff_federatedGroup_skipsReplace() {
        // group-patchOp=false routes membership through a full `replace`, which
        // enumerates members and can re-import a federated group's members
        // (re-import loop). For a federated (non-local-storage) group it must
        // skip the replace entirely.
        var group = mock(GroupModel.class);
        when(group.getId()).thenReturn("f:ldap-component:cn=engineers,ou=groups");
        var client = spy(newClient(false, group));
        doNothing().when(client).replace(any(), any());

        boolean applied = client.patchGroupMembership(GroupAdapter::new, "grp-1", "user-1", false);

        assertTrue(applied);
        verify(client, never()).replace(any(), any());
    }

    @Test
    void groupPatchOpOff_localGroup_stillReplaces() {
        // A local group's members are already local, so `replace` is safe — keep it.
        var group = mock(GroupModel.class);
        when(group.getId()).thenReturn("grp-1");
        var client = spy(newClient(false, group));
        doNothing().when(client).replace(any(), any());

        client.patchGroupMembership(GroupAdapter::new, "grp-1", "user-1", false);

        verify(client).replace(any(), any());
    }
}
