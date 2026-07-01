package sh.libre.scim.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.captaingoldfish.scim.sdk.common.resources.User;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.storage.user.SynchronizationResult;

import sh.libre.scim.core.exceptions.InconsistentScimMappingException;
import sh.libre.scim.core.exceptions.InvalidResponseFromScimEndpointException;

/**
 * Sync batch loop skip/stop behaviour driven by SyncErrorPolicy.
 *
 * <p>Under {@code sync-on-error=auto}: transient failure stops the run; permanent
 * failure skips and continues. Under {@code sync-on-error=continue} a transient
 * failure still continues.
 */
class ScimSyncLoopTest {

    interface TestModel extends org.keycloak.models.RoleMapperModel {}

    private ScimClient newClient() {
        return newClient(null);
    }

    private ScimClient newClient(String syncOnError) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        if (syncOnError != null) {
            config.putSingle("sync-on-error", syncOnError);
        }
        model.setConfig(config);
        model.setId("comp-loop");
        return new ScimClient(model, mock(KeycloakSession.class));
    }

    @SuppressWarnings("unchecked")
    private AdapterFactory<TestModel, User, Adapter<TestModel, User>> twoResourceFactory(
            TestModel first, TestModel second) {
        return (session, componentId) -> {
            Adapter<TestModel, User> a = mock(Adapter.class);
            when(a.getType()).thenReturn("User");
            when(a.skipRefresh()).thenReturn(false);
            when(a.getMapping()).thenReturn(null);
            when(a.getResourceStream()).thenReturn(Stream.of(first, second));
            return a;
        };
    }

    // -----------------------------------------------------------------------
    // Task 4.3 — policy-driven stop/continue (refreshResources)
    // -----------------------------------------------------------------------

    /** AUTO policy: transient failure on resource 1 → stop (resource 2 not attempted). */
    @Test
    @SuppressWarnings("unchecked")
    void autoPolicy_transientFailure_stopsRun() {
        var client = spy(newClient()); // default sync-on-error=auto

        TestModel first = mock(TestModel.class);
        TestModel second = mock(TestModel.class);

        doThrow(new InvalidResponseFromScimEndpointException(503, "down"))
            .when(client).create(any(), any());

        client.refreshResources(twoResourceFactory(first, second), new SynchronizationResult());

        verify(client, times(1)).create(any(), any());
    }

    /** AUTO policy: permanent failure on resource 1 → skip, resource 2 still attempted. */
    @Test
    @SuppressWarnings("unchecked")
    void autoPolicy_permanentFailure_continuesRun() {
        var client = spy(newClient()); // default sync-on-error=auto

        TestModel first = mock(TestModel.class);
        TestModel second = mock(TestModel.class);

        doThrow(new InconsistentScimMappingException("bad mapping"))
            .doNothing()
            .when(client).create(any(), any());

        client.refreshResources(twoResourceFactory(first, second), new SynchronizationResult());

        verify(client, times(2)).create(any(), any());
    }

    /** CONTINUE policy: transient failure on resource 1 → skip, resource 2 still attempted. */
    @Test
    @SuppressWarnings("unchecked")
    void continuePolicy_transientFailure_continuesRun() {
        var client = spy(newClient("continue"));

        TestModel first = mock(TestModel.class);
        TestModel second = mock(TestModel.class);

        doThrow(new InvalidResponseFromScimEndpointException(503, "down"))
            .doNothing()
            .when(client).create(any(), any());

        client.refreshResources(twoResourceFactory(first, second), new SynchronizationResult());

        verify(client, times(2)).create(any(), any());
    }

    /** STOP policy: any failure on resource 1 → stop (resource 2 not attempted). */
    @Test
    @SuppressWarnings("unchecked")
    void stopPolicy_permanentFailure_stopsRun() {
        var client = spy(newClient("stop"));

        TestModel first = mock(TestModel.class);
        TestModel second = mock(TestModel.class);

        doThrow(new InconsistentScimMappingException("bad mapping"))
            .when(client).create(any(), any());

        client.refreshResources(twoResourceFactory(first, second), new SynchronizationResult());

        verify(client, times(1)).create(any(), any());
    }

    // -----------------------------------------------------------------------
    // Chunk-2 seam (updated for Task 4.4): permanent exception → continues
    // -----------------------------------------------------------------------

    /**
     * Permanent exception under AUTO policy: skip and continue. Transient exception
     * under AUTO stops the run (covered by autoPolicy_transientFailure_stopsRun).
     */
    @Test
    @SuppressWarnings("unchecked")
    void refreshResources_permanentFailureOnFirst_stillAttemptsSecond() {
        var client = spy(newClient()); // default sync-on-error=auto

        TestModel first = mock(TestModel.class);
        TestModel second = mock(TestModel.class);

        doThrow(new InconsistentScimMappingException("no scim mapping"))
            .doNothing()
            .when(client).create(any(), any());

        client.refreshResources(twoResourceFactory(first, second), new SynchronizationResult());

        verify(client, times(2)).create(any(), any());
    }
}
