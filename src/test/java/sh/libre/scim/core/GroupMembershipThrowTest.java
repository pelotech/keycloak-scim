package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.captaingoldfish.scim.sdk.client.builder.PatchBuilder;
import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import sh.libre.scim.core.exceptions.InvalidResponseFromScimEndpointException;
import sh.libre.scim.jpa.ScimResource;

/**
 * {@code patchGroupMembership} throws on hard failure (non-2xx or transport),
 * but preserves the {@code NoResultException}→{@code !isAdd} lazy-import-lag signal.
 */
class GroupMembershipThrowTest {

    private ComponentModel model() {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        config.putSingle("group-patchOp", "true");
        model.setConfig(config);
        model.setId("comp-grp");
        return model;
    }

    private KeycloakSession session() {
        var session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        return session;
    }

    /** Adapter mock wired so {@code patchGroupMembership} reaches the PATCH send. */
    @SuppressWarnings("unchecked")
    private GroupAdapter adapterSending(ServerResponse<Group> response) {
        var adapter = mock(GroupAdapter.class);
        adapter.skip = false;
        when(adapter.getType()).thenReturn("Group");
        when(adapter.getSCIMEndpoint()).thenReturn("Groups");
        when(adapter.getExternalId()).thenReturn("grp-ext-1");
        when(adapter.getId()).thenReturn("grp-1");

        var groupMapping = mock(ScimResource.class);
        TypedQuery<ScimResource> groupQuery = mock(TypedQuery.class);
        when(groupQuery.getSingleResult()).thenReturn(groupMapping);
        // Non-empty result list: provisionGroupForMembership short-circuits
        // (group already provisioned), so ensureGroupMembership proceeds to PATCH.
        when(groupQuery.getResultList()).thenReturn(java.util.List.of(groupMapping));
        when(adapter.query("findById", "grp-1")).thenReturn(groupQuery);

        var userMapping = mock(ScimResource.class);
        when(userMapping.getExternalId()).thenReturn("user-ext-9");
        TypedQuery<ScimResource> userQuery = mock(TypedQuery.class);
        when(userQuery.getSingleResult()).thenReturn(userMapping);
        when(adapter.query("findById", "user-1", "User")).thenReturn(userQuery);

        PatchBuilder<Group> patchBuilder = mock(PatchBuilder.class);
        when(adapter.toMembershipPatchBuilder(any(), anyString(), anyString(), anyBoolean()))
            .thenReturn(patchBuilder);
        when(patchBuilder.sendRequest()).thenReturn(response);

        return adapter;
    }

    @Test
    @SuppressWarnings("unchecked")
    void hardHttpFailure_500_throwsTransient() {
        ServerResponse<Group> response = mock(ServerResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(response.getHttpStatus()).thenReturn(500);
        when(response.getResponseBody()).thenReturn("server error");

        var adapter = adapterSending(response);
        AdapterFactory<GroupModel, Group, GroupAdapter> factory = (s, c) -> adapter;
        var client = new ScimClient(model(), session());

        var ex = catchThrowableOfType(
            () -> client.patchGroupMembership(factory, "grp-1", "user-1", true),
            InvalidResponseFromScimEndpointException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.isTransient()).isTrue();
        // The non-2xx throw must NOT be re-classified by the transport catch
        // (which would force httpStatus=0). httpStatus() must stay 500.
        assertThat(ex.httpStatus()).isEqualTo(500);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lazyLagAdd_noMapping_returnsFalseUnchanged() {
        var adapter = mock(GroupAdapter.class);
        when(adapter.getType()).thenReturn("Group");
        TypedQuery<ScimResource> q = mock(TypedQuery.class);
        when(q.getSingleResult()).thenThrow(new NoResultException("no mapping"));
        when(adapter.query("findById", "grp-1")).thenReturn(q);
        AdapterFactory<GroupModel, Group, GroupAdapter> factory = (s, c) -> adapter;
        var client = new ScimClient(model(), session());

        boolean applied = client.patchGroupMembership(factory, "grp-1", "user-1", true);

        assertThat(applied).isFalse(); // ADD not applied -> retries next import
    }

    @Test
    @SuppressWarnings("unchecked")
    void lazyLagRemove_noMapping_returnsTrueUnchanged() {
        var adapter = mock(GroupAdapter.class);
        when(adapter.getType()).thenReturn("Group");
        TypedQuery<ScimResource> q = mock(TypedQuery.class);
        when(q.getSingleResult()).thenThrow(new NoResultException("no mapping"));
        when(adapter.query("findById", "grp-1")).thenReturn(q);
        AdapterFactory<GroupModel, Group, GroupAdapter> factory = (s, c) -> adapter;
        var client = new ScimClient(model(), session());

        boolean applied = client.patchGroupMembership(factory, "grp-1", "user-1", false);

        assertThat(applied).isTrue(); // REMOVE with nothing to remove -> applied
    }

    @Test
    @SuppressWarnings("unchecked")
    void transportFailure_throwsTransientViaClassifier() {
        var adapter = mock(GroupAdapter.class);
        when(adapter.getType()).thenReturn("Group");
        when(adapter.getSCIMEndpoint()).thenReturn("Groups");
        when(adapter.getExternalId()).thenReturn("grp-ext-1");

        var groupMapping = mock(ScimResource.class);
        TypedQuery<ScimResource> groupQuery = mock(TypedQuery.class);
        when(groupQuery.getSingleResult()).thenReturn(groupMapping);
        when(adapter.query("findById", "grp-1")).thenReturn(groupQuery);

        var userMapping = mock(ScimResource.class);
        when(userMapping.getExternalId()).thenReturn("user-ext-9");
        TypedQuery<ScimResource> userQuery = mock(TypedQuery.class);
        when(userQuery.getSingleResult()).thenReturn(userMapping);
        when(adapter.query("findById", "user-1", "User")).thenReturn(userQuery);

        PatchBuilder<Group> patchBuilder = mock(PatchBuilder.class);
        when(adapter.toMembershipPatchBuilder(any(), anyString(), anyString(), anyBoolean()))
            .thenReturn(patchBuilder);
        when(patchBuilder.sendRequest())
            .thenThrow(new jakarta.ws.rs.ProcessingException("connection refused"));

        AdapterFactory<GroupModel, Group, GroupAdapter> factory = (s, c) -> adapter;
        var client = new ScimClient(model(), session());

        var ex = catchThrowableOfType(
            () -> client.patchGroupMembership(factory, "grp-1", "user-1", true),
            InvalidResponseFromScimEndpointException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.isTransient()).isTrue();
        assertThat(ex.httpStatus()).isEqualTo(0); // transport classification
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensureGroupMembership_propagatesThrowOnHardFailure() {
        ServerResponse<Group> response = mock(ServerResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(response.getHttpStatus()).thenReturn(500);
        when(response.getResponseBody()).thenReturn("server error");

        var adapter = adapterSending(response);
        AdapterFactory<GroupModel, Group, GroupAdapter> factory = (s, c) -> adapter;

        var model = model();
        var session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        var groups = mock(GroupProvider.class);
        var group = mock(GroupModel.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(session.groups()).thenReturn(groups);
        when(groups.getGroupById(realm, "grp-1")).thenReturn(group);

        var client = new ScimClient(model, session);

        assertThatThrownBy(() -> client.ensureGroupMembership(factory, "grp-1", "user-1"))
            .isInstanceOf(InvalidResponseFromScimEndpointException.class);
    }
}
