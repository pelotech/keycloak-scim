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
        // As replaceBody does, the adapter takes the row's remote id.
        doAnswer(i -> {
            Adapter<?, ?> adapter = i.getArgument(0);
            ScimResource row = i.getArgument(1);
            adapter.setExternalId(row.getExternalId());
            return true;
        }).when(client).replaceApplied(any(), any());
        doReturn(true).when(client).createApplied(any());
        doNothing().when(client).writeBodyHash(any(), anyString(), any(), anyString());
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
        var counters = new SynchronizationResult();

        client.refreshOne(UserAdapter::new, user, counters, SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).replaceApplied(any(), any());
        verify(client, never()).writeBodyHash(any(), anyString(), any(), anyString());
        assertThat(counters.getUpdated()).isZero();
        assertThat(counters.getFailed()).isZero();
    }

    @Test
    void roleRefreshSendsAChangedUserAndStoresTheHash() {
        var client = client();
        var expectedHash = currentHash();
        var m = mapping("0".repeat(64));

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client).replaceApplied(any(), eq(m));
        verify(client).writeBodyHash(m, "ext-1", "0".repeat(64), expectedHash);
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
        var counters = new SynchronizationResult();

        client.refreshOne(UserAdapter::new, user, counters, SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).createApplied(any());
        verify(client, never()).writeBodyHash(any(), anyString(), any(), anyString());
        assertThat(counters.getUpdated()).isZero();
        assertThat(counters.getFailed()).isZero();
    }

    @Test
    void aPeriodicRefreshSendsAnUnchangedUserAndStoresTheHash() {
        var client = client();
        var before = currentHash();
        var m = mapping(before);

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), false);

        verify(client).replaceApplied(any(), eq(m));
        verify(client).writeBodyHash(eq(m), eq("ext-1"), eq(before), anyString());
    }

    @Test
    void aFailedReplaceStoresNoHash() {
        var client = client();
        mapping(null);
        doReturn(false).when(client).replaceApplied(any(), any());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).writeBodyHash(any(), anyString(), any(), anyString());
    }

    /**
     * A 404 re-create gives the adapter a new remote id, and the page
     * transaction removes the old row. The hash write must skip it.
     */
    @Test
    void aReCreateStoresNoHash() {
        var client = client();
        var m = mapping(null);
        doAnswer(i -> {
            Adapter<?, ?> adapter = i.getArgument(0);
            adapter.setExternalId(m.getExternalId());
            // A 404 re-create gives the adapter the server's new id.
            adapter.replaceExternalId("ext-2");
            return true;
        }).when(client).replaceApplied(any(), any());

        client.refreshOne(UserAdapter::new, user, new SynchronizationResult(),
            SyncErrorPolicy.fromConfig(null), true);

        verify(client, never()).writeBodyHash(any(), anyString(), any(), anyString());
    }

    @Test
    void anEventReplaceClearsTheHash() {
        var client = spy(new ScimClient(model, session));
        doReturn(true).when(client).replaceBody(any(), any());
        doNothing().when(client).clearBodyHash(any());

        client.replace(UserAdapter::new, user);

        verify(client).clearBodyHash(any());
    }

    @Test
    void aFailedEventReplaceKeepsTheHash() {
        var client = spy(new ScimClient(model, session));
        doReturn(false).when(client).replaceBody(any(), any());
        doNothing().when(client).clearBodyHash(any());

        client.replace(UserAdapter::new, user);

        verify(client, never()).clearBodyHash(any());
    }

    @Test
    void aReactivationThroughCreateClearsTheHash() {
        var client = spy(new ScimClient(model, session));
        var tombstone = mapping(null);
        tombstone.setDeactivatedAt(1L);
        doReturn(true).when(client).replaceApplied(any());
        doNothing().when(client).clearBodyHash(any());
        var adapter = new UserAdapter(session, "comp-1");
        adapter.apply(user);

        client.createApplied(adapter);

        verify(client).clearBodyHash(adapter);
    }

    @Test
    void clearBodyHashSetsANonHashValueOnEveryRow() {
        var client = new ScimClient(model, session);
        var first = new ScimResource();
        first.setBodyHash("a".repeat(64));
        var second = new ScimResource();
        when(findById.getResultList()).thenReturn(List.of(first, second));
        var adapter = new UserAdapter(session, "comp-1");
        adapter.apply(user);

        client.clearBodyHash(adapter);

        assertThat(first.getBodyHash()).isNotNull().doesNotMatch("[0-9a-f]{64}");
        assertThat(second.getBodyHash()).isNotNull().doesNotMatch("[0-9a-f]{64}");
        assertThat(first.getBodyHash()).isNotEqualTo(second.getBodyHash());
    }

    @Test
    void clearBodyHashDoesNothingForANonUser() {
        var client = new ScimClient(model, session);
        var row = new ScimResource();
        row.setBodyHash("a".repeat(64));
        when(findById.getResultList()).thenReturn(List.of(row));
        var adapter = mock(Adapter.class);
        when(adapter.getType()).thenReturn("Group");

        client.clearBodyHash(adapter);

        assertThat(row.getBodyHash()).isEqualTo("a".repeat(64));
        verify(adapter, never()).query(anyString(), anyString());
    }
}
