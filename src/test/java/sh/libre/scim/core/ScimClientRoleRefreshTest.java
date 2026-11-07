package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.user.SynchronizationResult;
import sh.libre.scim.jpa.ScimResource;

/** The role-refresh mode of refreshOne: hash check, no creates, hash write. */
class ScimClientRoleRefreshTest {

    private KeycloakSession session;
    private ComponentModel model;
    private EntityManager em;
    private TypedQuery<ScimResource> findById;
    private UserModel user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-1");
        model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId("comp-1");
        when(realm.getComponent("comp-1")).thenReturn(model);
        var jpa = mock(JpaConnectionProvider.class);
        em = mock(EntityManager.class);
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpa);
        when(jpa.getEntityManager()).thenReturn(em);
        findById = mock(TypedQuery.class);
        when(em.createNamedQuery(eq("findById"), eq(ScimResource.class))).thenReturn(findById);
        when(findById.setParameter(anyString(), any())).thenReturn(findById);
        user = mock(UserModel.class);
        when(user.getId()).thenReturn("u1");
        when(user.getUsername()).thenReturn("alice");
        when(user.isEnabled()).thenReturn(true);
        when(user.getRoleMappingsStream()).thenAnswer(i -> Stream.empty());
        when(user.getGroupsStream()).thenAnswer(i -> Stream.empty());
    }

    private ScimResource mapping(String bodyHash) {
        var m = new ScimResource();
        m.setId("u1");
        m.setRealmId("realm-1");
        m.setComponentId("comp-1");
        m.setType("User");
        m.setExternalId("ext-1");
        m.setBodyHash(bodyHash);
        when(findById.getResultList()).thenReturn(List.of(m));
        when(findById.getSingleResult()).thenReturn(m);
        return m;
    }

    private ScimClient client() {
        var client = spy(new ScimClient(model, session));
        doReturn(true).when(client).replaceApplied(any(), any());
        doReturn(true).when(client).createApplied(any());
        doNothing().when(client).writeBodyHash(any(), anyString(), anyString());
        return client;
    }

    /** The hash a refresh would compute for the user in setUp. */
    private String currentHash() {
        var adapter = new UserAdapter(session, "comp-1");
        adapter.apply(user);
        return ScimClient.bodyHashOf(adapter);
    }

    @Test
    void roleRefreshSkipsAnUnchangedUser() {
        var client = client();
        mapping(currentHash());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).replaceApplied(any(), any());
    }

    @Test
    void roleRefreshSendsAChangedUserAndStoresTheHash() {
        var client = client();
        var m = mapping("0".repeat(64));

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client).replaceApplied(any(), eq(m));
        verify(client).writeBodyHash(m, "ext-1", currentHash());
    }

    @Test
    void roleRefreshSendsAUserWithNoHash() {
        var client = client();
        mapping(null);

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client).replaceApplied(any(), any());
    }

    @Test
    void roleRefreshNeverCreates() {
        var client = client();
        when(findById.getResultList()).thenReturn(List.of());
        when(findById.getSingleResult()).thenThrow(new jakarta.persistence.NoResultException());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).createApplied(any());
    }

    @Test
    void aPeriodicRefreshSendsAnUnchangedUserAndStoresTheHash() {
        var client = client();
        var m = mapping(currentHash());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), false);

        verify(client).replaceApplied(any(), eq(m));
        verify(client).writeBodyHash(eq(m), eq("ext-1"), anyString());
    }

    @Test
    void aFailedReplaceStoresNoHash() {
        var client = client();
        mapping(null);
        doReturn(false).when(client).replaceApplied(any(), any());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).writeBodyHash(any(), anyString(), anyString());
    }

    /**
     * A 404 re-create changes the mapping row's external id inside the page
     * transaction. The re-create path edits the same managed row that
     * refreshOne passed in.
     */
    @Test
    void aReCreateStoresNoHash() {
        var client = client();
        var m = mapping(null);
        doAnswer(i -> {
            m.setExternalId("ext-2");
            return true;
        }).when(client).replaceApplied(any(), any());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).writeBodyHash(any(), anyString(), anyString());
    }
}
