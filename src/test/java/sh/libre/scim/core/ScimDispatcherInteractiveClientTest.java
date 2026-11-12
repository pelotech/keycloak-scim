package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;

/** The synchronous dispatch path runs inside a person's request, so it uses the interactive budget. */
class ScimDispatcherInteractiveClientTest {

    @Test
    void runOneHandsTheCallbackAnInteractiveClient() {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        model.setConfig(config);
        model.setId("comp-run-one");
        var dispatcher = new ScimDispatcher(mock(KeycloakSession.class));
        var received = new AtomicReference<ScimClient>();

        try {
            dispatcher.runOne(model, received::set);

            assertThat(received.get()).isNotNull();
            assertThat(received.get().registry.getDefaultConfig().getMaxAttempts()).isEqualTo(3);
            assertThat(received.get().genScimClientConfig().getSocketTimeout()).isEqualTo(10);
        } finally {
            dispatcher.close();
        }
    }
}
