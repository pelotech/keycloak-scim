package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.resources.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.storage.user.SynchronizationResult;
import sh.libre.scim.jpa.ScimResource;

/** The delete-mode gate: deactivate applies to Users only, delete stays the default. */
class ScimClientDeactivateTest {

    private static ComponentModel model(String deleteMode) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        if (deleteMode != null) {
            config.putSingle("delete-mode", deleteMode);
        }
        model.setConfig(config);
        model.setId("comp-deactivate");
        return model;
    }

    @Test
    void deactivateModeAndUserType_deactivates() {
        assertThat(ScimClient.shouldDeactivate(model("deactivate"), "User")).isTrue();
    }

    @Test
    void deactivateModeAndGroupType_stillDeletes() {
        assertThat(ScimClient.shouldDeactivate(model("deactivate"), "Group")).isFalse();
    }

    @Test
    void deleteMode_deletes() {
        assertThat(ScimClient.shouldDeactivate(model("delete"), "User")).isFalse();
    }

    @Test
    void absentMode_defaultsToDelete() {
        assertThat(ScimClient.shouldDeactivate(model(null), "User")).isFalse();
    }

    private static ScimClient newClient(String deleteMode) {
        return new ScimClient(model(deleteMode), mock(KeycloakSession.class));
    }

    /** Marker model type so the generic AdapterFactory signature is satisfiable. */
    interface TestModel extends org.keycloak.models.RoleMapperModel {}

    @SuppressWarnings("unchecked")
    private static Adapter<TestModel, User> userAdapterWithMapping(ScimResource mapping) {
        Adapter<TestModel, User> adapter = mock(Adapter.class);
        when(adapter.getType()).thenReturn("User");
        when(adapter.getId()).thenReturn("user-1");
        TypedQuery<ScimResource> query = mock(TypedQuery.class);
        if (mapping == null) {
            when(query.getSingleResult()).thenThrow(new NoResultException("no mapping"));
        } else {
            when(query.getSingleResult()).thenReturn(mapping);
        }
        when(adapter.query("findById", "user-1")).thenReturn(query);
        return adapter;
    }

    /**
     * delete() on an already-flagged mapping is a local no-op: no HTTP, no exception,
     * flag untouched. The endpoint here is unreachable, so any HTTP attempt would
     * throw and fail the test.
     */
    @Test
    void deactivate_alreadyFlagged_localNoOp() {
        var client = newClient("deactivate");
        var mapping = new ScimResource();
        mapping.setDeactivatedAt(1721000000000L);
        var adapter = userAdapterWithMapping(mapping);

        client.delete((session, componentId) -> adapter, "user-1");

        assertThat(mapping.getDeactivatedAt()).isEqualTo(1721000000000L);
    }

    /** No mapping: nothing was ever propagated; deactivate no-ops like delete does. */
    @Test
    void deactivate_missingMapping_doesNotThrow() {
        var client = newClient("deactivate");
        var adapter = userAdapterWithMapping(null);

        client.delete((session, componentId) -> adapter, "user-1");
    }

    @SuppressWarnings("unchecked")
    private static Adapter<TestModel, User> userAdapterWithMappingList(List<ScimResource> rows) {
        Adapter<TestModel, User> adapter = mock(Adapter.class);
        adapter.skip = false;
        when(adapter.getType()).thenReturn("User");
        when(adapter.getId()).thenReturn("user-1");
        TypedQuery<ScimResource> query = mock(TypedQuery.class);
        when(query.getResultList()).thenReturn(rows);
        when(adapter.query("findById", "user-1")).thenReturn(query);
        return adapter;
    }

    /**
     * Case A: create against a DEACTIVATED_AT-flagged mapping (same KC id) falls
     * through to replace, which pushes active from isEnabled() to the same remote id.
     */
    @Test
    void create_flaggedMapping_fallsThroughToReplace() {
        var client = spy(newClient("deactivate"));
        var tombstone = new ScimResource();
        tombstone.setDeactivatedAt(1721000000000L);
        var adapter = userAdapterWithMappingList(List.of(tombstone));
        AdapterFactory<TestModel, User, Adapter<TestModel, User>> factory =
            (session, componentId) -> adapter;
        var kcModel = mock(TestModel.class);
        doNothing().when(client).replace(any(), any());

        client.create(factory, kcModel);

        verify(client).replace(factory, kcModel);
    }

    /** A live (unflagged) mapping keeps the existing short-circuit: no replace, no POST. */
    @Test
    void create_liveMapping_shortCircuits() {
        var client = spy(newClient("deactivate"));
        var adapter = userAdapterWithMappingList(List.of(new ScimResource()));
        AdapterFactory<TestModel, User, Adapter<TestModel, User>> factory =
            (session, componentId) -> adapter;

        client.create(factory, mock(TestModel.class));

        verify(client, never()).replace(any(), any());
    }

    /**
     * Refresh skips flagged mappings; otherwise it would flip the remote back to
     * active:true and fight the reconciler.
     */
    @Test
    @SuppressWarnings("unchecked")
    void refresh_flaggedMapping_skipped() {
        var client = spy(newClient("deactivate"));
        var tombstone = new ScimResource();
        tombstone.setDeactivatedAt(1721000000000L);
        Adapter<TestModel, User> adapter = mock(Adapter.class);
        when(adapter.getType()).thenReturn("User");
        when(adapter.getId()).thenReturn("user-1");
        when(adapter.skipRefresh()).thenReturn(false);
        var kcModel = mock(TestModel.class);
        when(adapter.getResourceStream()).thenReturn(Stream.of(kcModel));
        when(adapter.getMapping()).thenReturn(tombstone);
        AdapterFactory<TestModel, User, Adapter<TestModel, User>> factory =
            (session, componentId) -> adapter;
        doNothing().when(client).replace(any(), any());
        doNothing().when(client).create(any(), any());
        var syncRes = new SynchronizationResult();

        client.refreshResources(factory, syncRes);

        verify(client, never()).replace(any(), any());
        verify(client, never()).create(any(), any());
        assertThat(syncRes.getUpdated()).isZero();
    }

    /** Control: a live mapping still refreshes via replace. */
    @Test
    @SuppressWarnings("unchecked")
    void refresh_liveMapping_replaces() {
        var client = spy(newClient("deactivate"));
        Adapter<TestModel, User> adapter = mock(Adapter.class);
        when(adapter.getType()).thenReturn("User");
        when(adapter.getId()).thenReturn("user-1");
        when(adapter.skipRefresh()).thenReturn(false);
        var kcModel = mock(TestModel.class);
        when(adapter.getResourceStream()).thenReturn(Stream.of(kcModel));
        when(adapter.getMapping()).thenReturn(new ScimResource());
        AdapterFactory<TestModel, User, Adapter<TestModel, User>> factory =
            (session, componentId) -> adapter;
        doNothing().when(client).replace(any(), any());

        client.refreshResources(factory, new SynchronizationResult());

        verify(client).replace(any(), any());
    }

    /**
     * Case B: a successful create purges tombstone rows with the same external id.
     * Keyed by external id, not KC id: a resurrected account gets a new KC id, so
     * only the external id links the old row to the new one.
     */
    @Test
    @SuppressWarnings("unchecked")
    void createResponse_purgesTombstonesByExternalId() {
        var session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-1");
        var em = mock(EntityManager.class);
        var jpaProvider = mock(JpaConnectionProvider.class);
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpaProvider);
        when(jpaProvider.getEntityManager()).thenReturn(em);
        var purge = mock(Query.class);
        when(em.createNamedQuery("deleteDeactivatedByExternalId")).thenReturn(purge);
        when(purge.setParameter(anyString(), any())).thenReturn(purge);
        when(purge.executeUpdate()).thenReturn(1);

        var client = new ScimClient(model("deactivate"), session);
        Adapter<TestModel, User> adapter = mock(Adapter.class);
        when(adapter.getType()).thenReturn("User");
        when(adapter.getId()).thenReturn("kc-new-id");
        when(adapter.getExternalId()).thenReturn("ext-stable-id");
        ServerResponse<User> response = mock(ServerResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getResource()).thenReturn(new User());

        client.handleCreateResponse(adapter, response);

        verify(purge).setParameter("id", "ext-stable-id");
        verify(purge).setParameter("type", "User");
        verify(purge).setParameter("realmId", "realm-1");
        verify(purge).setParameter("componentId", "comp-deactivate");
        verify(purge).executeUpdate();
    }

    private static User userWithActive(Boolean active) {
        var user = new User();
        user.setActive(active);
        return user;
    }

    /** A server holding its own suspension state can return active:false for a user we pushed as active. */
    @Test
    void activeDisagreement_pushedActiveReturnedInactive_warns() {
        assertThat(ScimClient.activeStateDisagrees(true, userWithActive(false))).isTrue();
    }

    @Test
    void activeDisagreement_matching_doesNotWarn() {
        assertThat(ScimClient.activeStateDisagrees(true, userWithActive(true))).isFalse();
    }

    /** Not every endpoint echoes active, so an absent field counts as agreement. */
    @Test
    void activeDisagreement_absentInResponse_doesNotWarn() {
        assertThat(ScimClient.activeStateDisagrees(true, new User())).isFalse();
    }

    @Test
    void activeDisagreement_nothingPushed_doesNotWarn() {
        assertThat(ScimClient.activeStateDisagrees(null, userWithActive(false))).isFalse();
    }
}
