package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.resources.User;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;

import sh.libre.scim.core.exceptions.InconsistentScimMappingException;
import sh.libre.scim.core.exceptions.InvalidResponseFromScimEndpointException;

/** ScimClient throws the exception taxonomy on final failure instead of swallowing. */
class ScimClientThrowTest {

    private ScimClient newClient() {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId("comp-throw");
        return new ScimClient(model, mock(KeycloakSession.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_rejectedWith503_throwsTransient() {
        var client = newClient();

        Adapter<?, User> adapter = mock(Adapter.class);
        when(adapter.getId()).thenReturn("user-1");

        ServerResponse<User> response = mock(ServerResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(response.getHttpStatus()).thenReturn(503);
        when(response.getResponseBody()).thenReturn("unavailable");

        var ex = catchThrowableOfType(
            () -> client.handleCreateResponse(adapter, response),
            InvalidResponseFromScimEndpointException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.isTransient()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_rejectedWith400_throwsPermanent() {
        var client = newClient();

        Adapter<?, User> adapter = mock(Adapter.class);
        when(adapter.getId()).thenReturn("user-1");

        ServerResponse<User> response = mock(ServerResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(response.getHttpStatus()).thenReturn(400);
        when(response.getResponseBody()).thenReturn("bad request");

        var ex = catchThrowableOfType(
            () -> client.handleCreateResponse(adapter, response),
            InvalidResponseFromScimEndpointException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.isTransient()).isFalse();
    }

    /**
     * replace with no local→external mapping: findById throws {@link NoResultException},
     * which surfaces as a permanent {@link InconsistentScimMappingException}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void replace_missingMapping_throwsInconsistentMapping() {
        var client = newClient();

        Adapter<TestModel, User> adapter = mock(Adapter.class);
        adapter.skip = false;
        when(adapter.getId()).thenReturn("user-1");
        when(adapter.getType()).thenReturn("User");

        TypedQuery<?> query = mock(TypedQuery.class);
        when(query.getSingleResult()).thenThrow(new NoResultException("no mapping"));
        when(adapter.query("findById", "user-1")).thenReturn((TypedQuery) query);

        AdapterFactory<TestModel, User, Adapter<TestModel, User>> factory =
            (session, componentId) -> adapter;

        assertThatThrownBy(() -> client.replace(factory, mock(TestModel.class)))
            .isInstanceOf(InconsistentScimMappingException.class);
    }

    /**
     * A JAX-RS {@link jakarta.ws.rs.ProcessingException} escaping after retry exhaustion
     * must be classified as a transient {@link InvalidResponseFromScimEndpointException},
     * not surface as a bare RuntimeException.
     */
    @Test
    void classifyTransport_processingException_becomesTransientEndpointError() {
        var processing = new jakarta.ws.rs.ProcessingException("connection refused");

        var ex = catchThrowableOfType(
            () -> { throw ScimClient.classifyCrudFailure("create user-1", processing); },
            InvalidResponseFromScimEndpointException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.isTransient()).isTrue();
        assertThat(ex.getCause()).isSameAs(processing);
    }

    /** A RuntimeException wrapping a SCIM SDK {@code ResponseException} is also classified as transport. */
    @Test
    void classifyTransport_wrappedResponseException_becomesTransient() {
        var wrapped = new RuntimeException(
            new de.captaingoldfish.scim.sdk.common.exceptions.ResponseException("boom", 500, null) {});

        var ex = catchThrowableOfType(
            () -> { throw ScimClient.classifyCrudFailure("delete user-1", wrapped); },
            InvalidResponseFromScimEndpointException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.isTransient()).isTrue();
    }

    /** An already-classified {@link ScimPropagationException} passes through unchanged — not re-wrapped as transport. */
    @Test
    void classifyTransport_alreadyClassified_passesThrough() {
        var classified = new InvalidResponseFromScimEndpointException(400, "bad");

        var out = catchThrowableOfType(
            () -> { throw ScimClient.classifyCrudFailure("create x", classified); },
            InvalidResponseFromScimEndpointException.class);

        assertThat(out).isSameAs(classified);
        assertThat(out.isTransient()).isFalse();
    }

    /** delete with no mapping is a no-op: {@link NoResultException} is swallowed, nothing to delete remotely. */
    @Test
    @SuppressWarnings("unchecked")
    void delete_missingMapping_doesNotThrow() {
        var client = newClient();

        Adapter<TestModel, User> adapter = mock(Adapter.class);
        when(adapter.getType()).thenReturn("User");
        when(adapter.getId()).thenReturn("user-1");

        TypedQuery<?> query = mock(TypedQuery.class);
        when(query.getSingleResult()).thenThrow(new NoResultException("no mapping"));
        when(adapter.query("findById", "user-1")).thenReturn((TypedQuery) query);

        AdapterFactory<TestModel, User, Adapter<TestModel, User>> factory =
            (session, componentId) -> adapter;

        client.delete(factory, "user-1");
    }

    /** Marker model type so the generic AdapterFactory signature is satisfiable. */
    interface TestModel extends org.keycloak.models.RoleMapperModel {}
}
