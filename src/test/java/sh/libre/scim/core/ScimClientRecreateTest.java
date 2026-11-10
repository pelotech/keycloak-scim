package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.resources.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.ArgumentCaptor;

import sh.libre.scim.core.exceptions.InvalidResponseFromScimEndpointException;
import sh.libre.scim.jpa.ScimResource;

/** The re-create step: a 404 on update, then a successful create with a new remote id. */
class ScimClientRecreateTest {

    private static final String COMPONENT_ID = "comp-recreate";

    private EntityManager em;
    private KeycloakSession session;
    private ScimClient client;
    private UserAdapter adapter;
    private ScimResource oldRow;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        var jpa = mock(JpaConnectionProvider.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-id");
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpa);
        when(jpa.getEntityManager()).thenReturn(em);

        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId(COMPONENT_ID);
        client = new ScimClient(model, session);

        oldRow = new ScimResource();
        oldRow.setId("kc-user-1");
        oldRow.setExternalId("old-id");
        oldRow.setRealmId("realm-id");
        oldRow.setComponentId(COMPONENT_ID);
        oldRow.setType("User");

        // The adapter holds the old remote id, as replaceBody leaves it.
        adapter = new UserAdapter(session, COMPONENT_ID);
        adapter.apply(oldRow);
    }

    @SuppressWarnings("unchecked")
    private static ServerResponse<User> createResponse(User created) {
        ServerResponse<User> response = mock(ServerResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getHttpStatus()).thenReturn(201);
        when(response.getResource()).thenReturn(created);
        return response;
    }

    private static User user(String id) {
        var user = new User();
        if (id != null) {
            user.setId(id);
        }
        user.setUserName("alice");
        user.setDisplayName("Alice");
        user.setActive(true);
        return user;
    }

    @Test
    void recreateKeepsTheNewRemoteId() {
        client.applyRecreate(adapter, oldRow, createResponse(user("new-id")));

        assertThat(adapter.getExternalId()).isEqualTo("new-id");
        verify(em).remove(oldRow);
        var saved = ArgumentCaptor.forClass(ScimResource.class);
        verify(em).persist(saved.capture());
        assertThat(saved.getValue().getExternalId()).isEqualTo("new-id");
        assertThat(saved.getValue().getId()).isEqualTo("kc-user-1");
        assertThat(saved.getValue().getType()).isEqualTo("User");
        assertThat(saved.getValue().getComponentId()).isEqualTo(COMPONENT_ID);
        assertThat(saved.getValue().getDeactivatedAt()).isNull();
    }

    @Test
    void recreateWithoutAnOldRowOnlySavesTheNewOne() {
        client.applyRecreate(adapter, null, createResponse(user("new-id")));

        verify(em, never()).remove(any());
        var saved = ArgumentCaptor.forClass(ScimResource.class);
        verify(em).persist(saved.capture());
        assertThat(saved.getValue().getExternalId()).isEqualTo("new-id");
    }

    @Test
    void recreateFailsWhenTheResponseHasNoId() {
        assertThatThrownBy(() -> client.applyRecreate(adapter, oldRow, createResponse(user(null))))
            .isInstanceOf(InvalidResponseFromScimEndpointException.class);

        verify(em, never()).remove(any());
        verify(em, never()).persist(any());
    }

    @Test
    void setExternalIdStaysSetOnce() {
        adapter.setExternalId("other-id");
        assertThat(adapter.getExternalId()).isEqualTo("old-id");

        var fresh = new UserAdapter(session, COMPONENT_ID);
        fresh.setExternalId("first");
        fresh.setExternalId("second");
        assertThat(fresh.getExternalId()).isEqualTo("first");
    }

    @Test
    void replaceExternalIdOverwrites() {
        adapter.replaceExternalId("new-id");
        assertThat(adapter.getExternalId()).isEqualTo("new-id");
    }
}
