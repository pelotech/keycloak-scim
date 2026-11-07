package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSessionFactory;

class RoleRefreshSchedulerTest {

    private final Deque<Runnable> scheduled = new ArrayDeque<>();
    private final Deque<RoleRefreshResult> results = new ArrayDeque<>();
    private final List<String> runs = new ArrayList<>();
    private Runnable duringRun = () -> {};

    private final RoleRefreshScheduler scheduler = new RoleRefreshScheduler(
        (realmId, componentId, shutdown) -> {
            runs.add(componentId);
            duringRun.run();
            return results.isEmpty() ? RoleRefreshResult.FINISHED : results.pop();
        },
        (task, delay) -> scheduled.add(task),
        Duration.ofSeconds(60),
        null);

    private void runNext() {
        scheduled.pop().run();
    }

    private static ComponentModel component(String providerId, String enabled, String propagationUser) {
        var m = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        if (enabled != null) {
            config.putSingle("enabled", enabled);
        }
        if (propagationUser != null) {
            config.putSingle("propagation-user", propagationUser);
        }
        m.setConfig(config);
        m.setProviderId(providerId);
        return m;
    }

    @Test
    void aRequestSchedulesOneRun() {
        scheduler.request("r", "c");

        assertThat(scheduled).hasSize(1);
        assertThat(scheduler.state("c")).isEqualTo(RoleRefreshScheduler.State.SCHEDULED);
    }

    @Test
    void aSecondRequestBeforeTheRunSchedulesNothing() {
        scheduler.request("r", "c");
        scheduler.request("r", "c");

        assertThat(scheduled).hasSize(1);
    }

    @Test
    void aFinishedRunGoesIdle() {
        scheduler.request("r", "c");
        runNext();

        assertThat(runs).containsExactly("c");
        assertThat(scheduler.state("c")).isNull();
        assertThat(scheduled).isEmpty();
    }

    @Test
    void aRequestDuringARunSchedulesOneMore() {
        scheduler.request("r", "c");
        duringRun = () -> {
            scheduler.request("r", "c");
            scheduler.request("r", "c");
        };
        runNext();

        assertThat(scheduled).hasSize(1);
        assertThat(scheduler.state("c")).isEqualTo(RoleRefreshScheduler.State.SCHEDULED);
    }

    @Test
    void aRefusedRunTriesAgain() {
        results.add(RoleRefreshResult.REFUSED);
        scheduler.request("r", "c");
        runNext();

        assertThat(scheduled).hasSize(1);
        assertThat(scheduler.state("c")).isEqualTo(RoleRefreshScheduler.State.SCHEDULED);
    }

    @Test
    void aStoppedRunDoesNotRetry() {
        results.add(RoleRefreshResult.STOPPED);
        scheduler.request("r", "c");
        runNext();

        assertThat(scheduled).isEmpty();
        assertThat(scheduler.state("c")).isNull();
    }

    @Test
    void aStoppedRunWithANewerRequestRunsAgain() {
        results.add(RoleRefreshResult.STOPPED);
        scheduler.request("r", "c");
        duringRun = () -> scheduler.request("r", "c");
        runNext();

        assertThat(scheduled).hasSize(1);
    }

    @Test
    void aSkippedRunGoesIdleEvenWithANewerRequest() {
        results.add(RoleRefreshResult.SKIPPED);
        scheduler.request("r", "c");
        duringRun = () -> scheduler.request("r", "c");
        runNext();

        assertThat(scheduled).isEmpty();
        assertThat(scheduler.state("c")).isNull();
    }

    @Test
    void aRunnerThatThrowsCountsAsStopped() {
        var throwing = new RoleRefreshScheduler(
            (realmId, componentId, shutdown) -> { throw new IllegalStateException("boom"); },
            (task, delay) -> scheduled.add(task), Duration.ofSeconds(60), null);
        throwing.request("r", "c");
        runNext();

        assertThat(throwing.state("c")).isNull();
        assertThat(scheduled).isEmpty();
    }

    @Test
    void componentsAreIndependent() {
        scheduler.request("r", "a");
        scheduler.request("r", "b");

        assertThat(scheduled).hasSize(2);
    }

    @Test
    void onlyAnEnabledScimComponentWithUserPropagationIsRefreshable() {
        assertThat(RoleRefreshScheduler.refreshable(component("scim", null, "true"))).isTrue();
        assertThat(RoleRefreshScheduler.refreshable(component("scim", "false", "true"))).isFalse();
        assertThat(RoleRefreshScheduler.refreshable(component("scim", "true", "false"))).isFalse();
        assertThat(RoleRefreshScheduler.refreshable(component("scim", "true", null))).isFalse();
        assertThat(RoleRefreshScheduler.refreshable(component("ldap", "true", "true"))).isFalse();
        assertThat(RoleRefreshScheduler.refreshable(null)).isFalse();
    }

    @Test
    void aRunnerThatThrowsAnErrorStillFreesTheComponent() {
        var erroring = new RoleRefreshScheduler(
            (realmId, componentId, shutdown) -> { throw new StackOverflowError(); },
            (task, delay) -> scheduled.add(task), Duration.ofSeconds(60), null);
        erroring.request("r", "c");
        try {
            runNext();
        } catch (StackOverflowError expected) {
            // The error still reaches the executor.
        }

        assertThat(erroring.state("c")).isNull();
    }

    @Test
    void afterShutdownRequestsAreIgnoredAndNothingIsRescheduled() {
        results.add(RoleRefreshResult.REFUSED);
        scheduler.request("r", "c");
        scheduler.shutdown();
        runNext();
        scheduler.request("r", "d");

        assertThat(scheduled).isEmpty();
        assertThat(scheduler.state("c")).isNull();
        assertThat(scheduler.state("d")).isNull();
    }

    @Test
    void aSecondStartKeepsTheSameInstanceAndStopClearsIt() {
        var factory = mock(KeycloakSessionFactory.class);
        try {
            RoleRefreshScheduler.start(factory);
            var first = RoleRefreshScheduler.current();
            RoleRefreshScheduler.start(factory);

            assertThat(first).isNotNull();
            assertThat(RoleRefreshScheduler.current()).isSameAs(first);

            RoleRefreshScheduler.stop();

            assertThat(RoleRefreshScheduler.current()).isNull();
            first.request("r", "c");
            assertThat(first.state("c")).isNull();
        } finally {
            RoleRefreshScheduler.stop();
        }
    }

    @Test
    void aFailureToReadTheComponentIsRefusedSoItRetries() {
        // A mock factory gives a null session, so the read throws.
        var factory = mock(KeycloakSessionFactory.class);

        var result = RoleRefreshScheduler.runForComponent(factory, "r", "c", () -> false);

        assertThat(result).isEqualTo(RoleRefreshResult.REFUSED);
    }
}
