package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.resources.User;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;

import sh.libre.scim.core.exceptions.InvalidResponseFromScimEndpointException;

class ScimClientCreateResponseTest {

    private ScimClient newClient() {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId("comp-create");
        return new ScimClient(model, mock(KeycloakSession.class));
    }

    /**
     * Regression: a rejected POST has no parsed resource. The old code fell through to
     * {@code adapter.apply(null)} and persisted a phantom mapping. Now the classified
     * taxonomy exception is thrown, so apply/saveMapping are never reached.
     */
    @Test
    @SuppressWarnings("unchecked")
    void unsuccessfulResponse_doesNotApplyOrSaveMapping() {
        var client = newClient();

        Adapter<?, User> adapter = mock(Adapter.class);
        when(adapter.getId()).thenReturn("user-1");

        ServerResponse<User> response = mock(ServerResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(response.getHttpStatus()).thenReturn(400);
        when(response.getResponseBody()).thenReturn("");
        when(response.getResource()).thenReturn(null);

        assertThatThrownBy(() -> client.handleCreateResponse(adapter, response))
            .isInstanceOf(InvalidResponseFromScimEndpointException.class);

        verify(adapter, never()).apply(any(User.class));
        verify(adapter, never()).saveMapping();
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulResponse_appliesResourceAndSavesMapping() {
        var client = newClient();

        Adapter<?, User> adapter = mock(Adapter.class);
        var created = new User();

        ServerResponse<User> response = mock(ServerResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getResource()).thenReturn(created);

        boolean applied = client.handleCreateResponse(adapter, response);

        assertThat(applied).isTrue();
        verify(adapter).apply(created);
        verify(adapter).saveMapping();
    }
}
