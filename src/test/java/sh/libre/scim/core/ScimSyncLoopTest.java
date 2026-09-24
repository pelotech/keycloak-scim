package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.captaingoldfish.scim.sdk.common.resources.User;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
            .when(client).createApplied(any());

        client.refreshResources(twoResourceFactory(first, second), new SynchronizationResult());

        verify(client, times(1)).createApplied(any());
    }

    /** AUTO policy: permanent failure on resource 1 → skip, resource 2 still attempted. */
    @Test
    @SuppressWarnings("unchecked")
    void autoPolicy_permanentFailure_continuesRun() {
        var client = spy(newClient()); // default sync-on-error=auto

        TestModel first = mock(TestModel.class);
        TestModel second = mock(TestModel.class);

        doThrow(new InconsistentScimMappingException("bad mapping"))
            .doReturn(true)
            .when(client).createApplied(any());
        var syncRes = new SynchronizationResult();

        client.refreshResources(twoResourceFactory(first, second), syncRes);

        verify(client, times(2)).createApplied(any());
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
            .doReturn(true)
            .when(client).createApplied(any());

        client.refreshResources(twoResourceFactory(first, second), new SynchronizationResult());

        verify(client, times(2)).createApplied(any());
    }

    /** CONTINUE policy: transient failure on resource 1 → skip, resource 2 still attempted. */
    @Test
    @SuppressWarnings("unchecked")
    void continuePolicy_transientFailure_continuesRun() {
        var client = spy(newClient("continue"));

        TestModel first = mock(TestModel.class);
        TestModel second = mock(TestModel.class);

        doThrow(new InvalidResponseFromScimEndpointException(503, "down"))
            .doReturn(true)
            .when(client).createApplied(any());

        client.refreshResources(twoResourceFactory(first, second), new SynchronizationResult());

        verify(client, times(2)).createApplied(any());
    }

    /** STOP policy: any failure on resource 1 → stop (resource 2 not attempted). */
    @Test
    @SuppressWarnings("unchecked")
    void stopPolicy_permanentFailure_stopsRun() {
        var client = spy(newClient("stop"));

        TestModel first = mock(TestModel.class);
        TestModel second = mock(TestModel.class);

        doThrow(new InconsistentScimMappingException("bad mapping"))
            .when(client).createApplied(any());

        client.refreshResources(twoResourceFactory(first, second), new SynchronizationResult());

        verify(client, times(1)).createApplied(any());
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
            .doReturn(true)
            .when(client).createApplied(any());

        client.refreshResources(twoResourceFactory(first, second), new SynchronizationResult());

        verify(client, times(2)).createApplied(any());
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
        doReturn(true).when(client).createApplied(any());
        var syncRes = new SynchronizationResult();

        client.refreshResources(factory, syncRes);

        assertThat(syncRes.getUpdated()).isZero();
        assertThat(syncRes.getFailed()).isZero();
        verify(client, never()).createApplied(any());
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
        doReturn(true).when(client).replaceApplied(any(), any());
        var syncRes = new SynchronizationResult();

        client.refreshResources(factory, syncRes);

        assertThat(syncRes.getUpdated()).isZero();
        assertThat(syncRes.getFailed()).isZero();
        verify(client, never()).replaceApplied(any(), any());
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
        doReturn(true).when(client).createApplied(any());
        var syncRes = new SynchronizationResult();

        client.refreshResources(factory, syncRes);

        verify(client, times(1)).createApplied(any());
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
        doReturn(true).when(client).createApplied(any());

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
            .when(client).createApplied(any());
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
            .when(client).createApplied(any());
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
            .when(client).createApplied(any());

        var outcome = client.refreshOne(
            oneResourceFactory(false), mock(TestModel.class), new SynchronizationResult(), SyncErrorPolicy.AUTO);

        assertThat(outcome).isEqualTo(RefreshOutcome.STOP);
    }

    /**
     * The CONTINUE policy does not hide the throttle. The streak counter must
     * still see it, because a throttled run makes no progress under any policy.
     */
    @Test
    void refreshOne_throttledFailureUnderContinuePolicy_returnsThrottled() {
        var client = spy(newClient("continue"));
        doThrow(new InvalidResponseFromScimEndpointException(429, "slow down"))
            .when(client).createApplied(any());

        var outcome = client.refreshOne(
            oneResourceFactory(false), mock(TestModel.class), new SynchronizationResult(),
            SyncErrorPolicy.CONTINUE);

        assertThat(outcome).isEqualTo(RefreshOutcome.THROTTLED);
    }

    // -----------------------------------------------------------------------
    // refreshOne counts a push that did not happen
    // -----------------------------------------------------------------------

    /**
     * An adapter for the unmapped path. The create call finds the row that
     * {@code existing} holds, so it needs no endpoint.
     */
    @SuppressWarnings("unchecked")
    private Adapter<TestModel, User> unmappedAdapter(List<ScimResource> existing) {
        Adapter<TestModel, User> a = mock(Adapter.class);
        a.skip = false;
        when(a.getType()).thenReturn("User");
        when(a.getId()).thenReturn("u1");
        when(a.skipRefresh()).thenReturn(false);
        when(a.getMapping()).thenReturn(null);
        TypedQuery<ScimResource> query = mock(TypedQuery.class);
        when(query.getResultList()).thenReturn(existing);
        when(a.query("findById", "u1")).thenReturn(query);
        return a;
    }

    /** Counts how many adapters the sync path builds for one resource. */
    private AdapterFactory<TestModel, User, Adapter<TestModel, User>> sharedFactory(
            Adapter<TestModel, User> adapter, AtomicInteger built) {
        return (session, componentId) -> {
            built.incrementAndGet();
            return adapter;
        };
    }

    /**
     * An adapter for the mapped path that breaks before the request goes out.
     * The replace call logs that fault, raises nothing, and pushes nothing.
     */
    @SuppressWarnings("unchecked")
    private Adapter<TestModel, User> mappedAdapterThatCannotPush() {
        Adapter<TestModel, User> a = mock(Adapter.class);
        a.skip = false;
        when(a.getType()).thenReturn("User");
        when(a.getId()).thenReturn("u1");
        when(a.skipRefresh()).thenReturn(false);
        when(a.getMapping()).thenReturn(new ScimResource());
        when(a.getSCIMEndpoint()).thenThrow(new IllegalStateException("the endpoint name broke"));
        return a;
    }

    /**
     * The replace call logs a fault of its own and returns. The resource never
     * reached the endpoint, so the run must not report it as updated.
     */
    @Test
    void refreshOne_replaceThatPushedNothing_countsFailed() {
        var client = newClient();
        var adapter = mappedAdapterThatCannotPush();
        var syncRes = new SynchronizationResult();

        var outcome = client.refreshOne(
            (session, componentId) -> adapter, mock(TestModel.class), syncRes, SyncErrorPolicy.AUTO);

        assertThat(outcome).isEqualTo(RefreshOutcome.CONTINUE);
        assertThat(syncRes.getUpdated()).isZero();
        assertThat(syncRes.getFailed()).isEqualTo(1);
    }

    /**
     * The operator asked the run to stop on any failure. A push that pushed
     * nothing is a failure, so it must stop the run like any other.
     */
    @Test
    void refreshOne_pushedNothingUnderStopPolicy_returnsStop() {
        var client = newClient("stop");
        var adapter = mappedAdapterThatCannotPush();
        var syncRes = new SynchronizationResult();

        var outcome = client.refreshOne(
            (session, componentId) -> adapter, mock(TestModel.class), syncRes, SyncErrorPolicy.STOP);

        assertThat(outcome).isEqualTo(RefreshOutcome.STOP);
        assertThat(syncRes.getFailed()).isEqualTo(1);
    }

    /** The CONTINUE policy goes on to the next resource, as it does for any failure. */
    @Test
    void refreshOne_pushedNothingUnderContinuePolicy_returnsContinue() {
        var client = newClient("continue");
        var adapter = mappedAdapterThatCannotPush();

        var outcome = client.refreshOne((session, componentId) -> adapter, mock(TestModel.class),
            new SynchronizationResult(), SyncErrorPolicy.CONTINUE);

        assertThat(outcome).isEqualTo(RefreshOutcome.CONTINUE);
    }

    /**
     * The create call finds a mapping that the refresh did not see and returns
     * without a push. Nothing went to the endpoint, so nothing was updated.
     */
    @Test
    void refreshOne_createThatPushedNothing_countsFailed() {
        var client = newClient();
        var adapter = unmappedAdapter(List.of(new ScimResource()));
        var syncRes = new SynchronizationResult();

        var outcome = client.refreshOne(
            (session, componentId) -> adapter, mock(TestModel.class), syncRes, SyncErrorPolicy.AUTO);

        assertThat(outcome).isEqualTo(RefreshOutcome.CONTINUE);
        assertThat(syncRes.getUpdated()).isZero();
        assertThat(syncRes.getFailed()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // refreshOne applies the model once
    // -----------------------------------------------------------------------

    /**
     * Applying the model walks role mappings and reads the component config,
     * so a second pass halves how many users fit in a page.
     */
    @Test
    void refreshOne_createBranch_appliesTheModelOnceAndBuildsOneAdapter() {
        var client = newClient();
        var adapter = unmappedAdapter(List.of(new ScimResource()));
        var built = new AtomicInteger();

        client.refreshOne(sharedFactory(adapter, built), mock(TestModel.class),
            new SynchronizationResult(), SyncErrorPolicy.AUTO);

        verify(adapter, times(1)).apply(any(TestModel.class));
        assertThat(built.get()).isEqualTo(1);
    }

    /**
     * A steady-state sync replaces every user, so this is the branch where the
     * second apply cost the page most of its budget.
     */
    @Test
    void refreshOne_replaceBranch_appliesTheModelOnceAndBuildsOneAdapter() {
        var client = newClient();
        var adapter = mappedAdapterThatCannotPush();
        var built = new AtomicInteger();

        client.refreshOne(sharedFactory(adapter, built), mock(TestModel.class),
            new SynchronizationResult(), SyncErrorPolicy.AUTO);

        verify(adapter, times(1)).apply(any(TestModel.class));
        assertThat(built.get()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // refreshOne reads the mapping once
    // -----------------------------------------------------------------------

    /**
     * A page of users has a wall clock budget, so every read per user takes
     * page capacity. The refresh reads the mapping row to pick the branch, and
     * the replace it calls must reuse that row.
     */
    @Test
    void refreshOne_replaceBranch_readsTheMappingOnce() {
        var client = newClient();
        var adapter = mappedAdapterThatCannotPush();

        client.refreshOne((session, componentId) -> adapter, mock(TestModel.class),
            new SynchronizationResult(), SyncErrorPolicy.AUTO);

        verify(adapter, times(1)).getMapping();
        verify(adapter, never()).query(eq("findById"), any());
    }

    // -----------------------------------------------------------------------
    // the applied push methods refuse an excluded resource
    // -----------------------------------------------------------------------

    /** Pushing an excluded resource cannot be undone at the endpoint, so it must fail loudly. */
    @Test
    @SuppressWarnings("unchecked")
    void createApplied_excludedResource_throws() {
        var client = newClient();
        Adapter<TestModel, User> adapter = mock(Adapter.class);
        adapter.skip = true;
        when(adapter.getId()).thenReturn("u1");

        assertThatThrownBy(() -> client.createApplied(adapter))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaceApplied_excludedResource_throws() {
        var client = newClient();
        Adapter<TestModel, User> adapter = mock(Adapter.class);
        adapter.skip = true;
        when(adapter.getId()).thenReturn("u1");

        assertThatThrownBy(() -> client.replaceApplied(adapter))
            .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The STOP policy outranks the throttle report. A caller that continued on
     * THROTTLED would ignore the operator's choice to stop on any failure.
     */
    @Test
    void refreshOne_throttledFailureUnderStopPolicy_returnsStop() {
        var client = spy(newClient("stop"));
        doThrow(new InvalidResponseFromScimEndpointException(429, "slow down"))
            .when(client).createApplied(any());

        var outcome = client.refreshOne(
            oneResourceFactory(false), mock(TestModel.class), new SynchronizationResult(), SyncErrorPolicy.STOP);

        assertThat(outcome).isEqualTo(RefreshOutcome.STOP);
    }
}
