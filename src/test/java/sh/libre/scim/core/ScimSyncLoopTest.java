package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
import sh.libre.scim.jpa.ScimResource;

/**
 * Sync batch loop skip/stop behaviour driven by SyncErrorPolicy.
 *
 * <p>Under {@code sync-on-error=auto}: a transient failure other than throttling
 * stops the run; a permanent failure, or a 429 throttling response, skips the
 * record and the run continues. Under {@code sync-on-error=continue} a transient
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
        var syncRes = new SynchronizationResult();

        client.refreshResources(twoResourceFactory(first, second), syncRes);

        verify(client, times(2)).create(any(), any());
        assertThat(syncRes.getFailed()).isEqualTo(1);
    }

    /** AUTO policy: 429 throttled failure on resource 1 → skip, resource 2 still attempted. */
    @Test
    @SuppressWarnings("unchecked")
    void autoPolicy_throttleFailure_continuesRun() {
        var client = spy(newClient()); // default sync-on-error=auto

        TestModel first = mock(TestModel.class);
        TestModel second = mock(TestModel.class);

        doThrow(new InvalidResponseFromScimEndpointException(429, "slow down"))
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

    // -----------------------------------------------------------------------
    // refreshOne must not count a skipped resource as updated
    // -----------------------------------------------------------------------

    /** A user excluded by scim-skip or propagation-role is neither pushed nor counted. */
    @Test
    @SuppressWarnings("unchecked")
    void skippedResource_isNotPushedOrCountedAsUpdated() {
        var client = spy(newClient());
        TestModel only = mock(TestModel.class);
        AdapterFactory<TestModel, User, Adapter<TestModel, User>> factory = (session, componentId) -> {
            Adapter<TestModel, User> a = mock(Adapter.class);
            a.skip = true;
            when(a.getType()).thenReturn("User");
            when(a.skipRefresh()).thenReturn(false);
            when(a.getMapping()).thenReturn(null);
            when(a.getResourceStream()).thenReturn(Stream.of(only));
            return a;
        };
        doNothing().when(client).create(any(), any());
        var syncRes = new SynchronizationResult();

        client.refreshResources(factory, syncRes);

        assertThat(syncRes.getUpdated()).isZero();
        assertThat(syncRes.getFailed()).isZero();
        verify(client, never()).create(any(), any());
    }

    /**
     * A mapped user excluded by scim-skip or propagation-role is not replaced
     * either. The skip check in refreshOne returns before getMapping() is
     * consulted, so this stubs a mapping precisely to guard against that check
     * ever moving below the mapping branch.
     */
    @Test
    @SuppressWarnings("unchecked")
    void skippedMappedResource_isNotReplacedOrCountedAsUpdated() {
        var client = spy(newClient());
        TestModel only = mock(TestModel.class);
        AdapterFactory<TestModel, User, Adapter<TestModel, User>> factory = (session, componentId) -> {
            Adapter<TestModel, User> a = mock(Adapter.class);
            a.skip = true;
            when(a.getType()).thenReturn("User");
            when(a.skipRefresh()).thenReturn(false);
            when(a.getMapping()).thenReturn(new ScimResource());
            when(a.getResourceStream()).thenReturn(Stream.of(only));
            return a;
        };
        doNothing().when(client).replace(any(), any());
        var syncRes = new SynchronizationResult();

        client.refreshResources(factory, syncRes);

        assertThat(syncRes.getUpdated()).isZero();
        assertThat(syncRes.getFailed()).isZero();
        verify(client, never()).replace(any(), any());
    }

    /**
     * A skipped resource must not stop the loop or swallow the next one: the
     * first of two resources is excluded, and the second is still pushed and
     * counted. This is the contract the paged refresh runner depends on.
     */
    @Test
    @SuppressWarnings("unchecked")
    void skippedResource_doesNotBlockSubsequentPush() {
        var client = spy(newClient());
        TestModel skipped = mock(TestModel.class);
        TestModel pushed = mock(TestModel.class);
        AdapterFactory<TestModel, User, Adapter<TestModel, User>> factory = (session, componentId) -> {
            Adapter<TestModel, User> a = mock(Adapter.class);
            when(a.getType()).thenReturn("User");
            when(a.skipRefresh()).thenReturn(false);
            when(a.getMapping()).thenReturn(null);
            when(a.getResourceStream()).thenReturn(Stream.of(skipped, pushed));
            // refreshOne calls apply(resource) before checking adapter.skip, so
            // derive skip from the resource actually applied rather than from
            // which factory.create() call this is — the factory is also invoked
            // for bookkeeping (getType/getResourceStream) before either resource
            // is processed.
            doAnswer(inv -> {
                a.skip = inv.getArgument(0) == skipped;
                return null;
            }).when(a).apply(any(TestModel.class));
            return a;
        };
        doNothing().when(client).create(any(), any());
        var syncRes = new SynchronizationResult();

        client.refreshResources(factory, syncRes);

        verify(client, times(1)).create(any(), any());
        assertThat(syncRes.getUpdated()).isEqualTo(1);
        assertThat(syncRes.getFailed()).isZero();
    }

    // -----------------------------------------------------------------------
    // refreshOne reports the per-resource outcome
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private AdapterFactory<TestModel, User, Adapter<TestModel, User>> oneResourceFactory(boolean skip) {
        return (session, componentId) -> {
            Adapter<TestModel, User> a = mock(Adapter.class);
            a.skip = skip;
            when(a.getType()).thenReturn("User");
            when(a.skipRefresh()).thenReturn(false);
            when(a.getMapping()).thenReturn(null);
            return a;
        };
    }

    /** A push that works lets the caller go on to the next resource. */
    @Test
    void refreshOne_pushedResource_returnsContinue() {
        var client = spy(newClient());
        doNothing().when(client).create(any(), any());

        var outcome = client.refreshOne(
            oneResourceFactory(false), mock(TestModel.class), new SynchronizationResult(), SyncErrorPolicy.AUTO);

        assertThat(outcome).isEqualTo(RefreshOutcome.CONTINUE);
    }

    /** An excluded resource is not a throttle and not a stop. */
    @Test
    void refreshOne_skippedResource_returnsContinue() {
        var client = spy(newClient());

        var outcome = client.refreshOne(
            oneResourceFactory(true), mock(TestModel.class), new SynchronizationResult(), SyncErrorPolicy.AUTO);

        assertThat(outcome).isEqualTo(RefreshOutcome.CONTINUE);
    }

    /** A 429 is reported apart from other failures, so a caller can count streaks. */
    @Test
    void refreshOne_throttledFailure_returnsThrottled() {
        var client = spy(newClient());
        doThrow(new InvalidResponseFromScimEndpointException(429, "slow down"))
            .when(client).create(any(), any());
        var syncRes = new SynchronizationResult();

        var outcome = client.refreshOne(
            oneResourceFactory(false), mock(TestModel.class), syncRes, SyncErrorPolicy.AUTO);

        assertThat(outcome).isEqualTo(RefreshOutcome.THROTTLED);
        assertThat(syncRes.getFailed()).isEqualTo(1);
    }

    /** A permanent failure under AUTO is counted, and the caller goes on. */
    @Test
    void refreshOne_permanentFailure_returnsContinue() {
        var client = spy(newClient());
        doThrow(new InconsistentScimMappingException("bad mapping"))
            .when(client).create(any(), any());
        var syncRes = new SynchronizationResult();

        var outcome = client.refreshOne(
            oneResourceFactory(false), mock(TestModel.class), syncRes, SyncErrorPolicy.AUTO);

        assertThat(outcome).isEqualTo(RefreshOutcome.CONTINUE);
        assertThat(syncRes.getFailed()).isEqualTo(1);
    }

    /** A transient failure under AUTO tells the caller to stop the run. */
    @Test
    void refreshOne_policyStop_returnsStop() {
        var client = spy(newClient());
        doThrow(new InvalidResponseFromScimEndpointException(503, "down"))
            .when(client).create(any(), any());

        var outcome = client.refreshOne(
            oneResourceFactory(false), mock(TestModel.class), new SynchronizationResult(), SyncErrorPolicy.AUTO);

        assertThat(outcome).isEqualTo(RefreshOutcome.STOP);
    }

    /**
     * The STOP policy outranks the throttle report. A caller that continued on
     * THROTTLED would ignore the operator's choice to stop on any failure.
     */
    @Test
    void refreshOne_throttledFailureUnderStopPolicy_returnsStop() {
        var client = spy(newClient("stop"));
        doThrow(new InvalidResponseFromScimEndpointException(429, "slow down"))
            .when(client).create(any(), any());

        var outcome = client.refreshOne(
            oneResourceFactory(false), mock(TestModel.class), new SynchronizationResult(), SyncErrorPolicy.STOP);

        assertThat(outcome).isEqualTo(RefreshOutcome.STOP);
    }
}
