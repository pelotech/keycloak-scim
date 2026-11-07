package sh.libre.scim.core;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;

import sh.libre.scim.storage.ScimStorageProviderFactory;

/**
 * Runs at most one role refresh of each component at a time on this node,
 * a short delay after the last request. Several requests in a row become one
 * refresh. A request during a refresh causes one more refresh after it.
 *
 * <p>The state is in memory. A request that a stopped node never ran waits for
 * the next periodic sync. See the effective roles design.
 */
public final class RoleRefreshScheduler {

    private static final Logger LOGGER = Logger.getLogger(RoleRefreshScheduler.class);

    /** JVM system property: seconds between a request and its refresh. */
    static final String DELAY_PROPERTY = "scim.roles.refreshDelaySeconds";
    static final long DEFAULT_DELAY_SECONDS = 60;

    /** A component with no entry is idle. */
    enum State { SCHEDULED, RUNNING, RUNNING_AGAIN }

    @FunctionalInterface
    interface Runner {
        RoleRefreshResult run(String realmId, String componentId, BooleanSupplier shutdown);
    }

    @FunctionalInterface
    interface Delayer {
        void schedule(Runnable task, Duration delay);
    }

    private static volatile RoleRefreshScheduler current;

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final Runner runner;
    private final Delayer delayer;
    private final Duration delay;
    private final ScheduledExecutorService ownedExecutor;
    private volatile boolean shutdown;

    RoleRefreshScheduler(Runner runner, Delayer delayer, Duration delay, ScheduledExecutorService ownedExecutor) {
        this.runner = runner;
        this.delayer = delayer;
        this.delay = delay;
        this.ownedExecutor = ownedExecutor;
    }

    /** Starts this node's scheduler. Called once, from the event listener factory. */
    public static synchronized void start(KeycloakSessionFactory sessionFactory) {
        if (current != null) {
            return;
        }
        var executor = new ScheduledThreadPoolExecutor(1, r -> {
            var thread = new Thread(r, "scim-role-refresh");
            thread.setDaemon(true);
            return thread;
        });
        // Runs that wait for their delay are dropped at shutdown.
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        var delay = Duration.ofSeconds(Long.getLong(DELAY_PROPERTY, DEFAULT_DELAY_SECONDS));
        current = new RoleRefreshScheduler(
            (realmId, componentId, isShutdown) -> runForComponent(sessionFactory, realmId, componentId, isShutdown),
            (task, d) -> executor.schedule(task, d.toMillis(), TimeUnit.MILLISECONDS),
            delay,
            executor);
    }

    /** Stops this node's scheduler. A running refresh stops at its next user. */
    public static synchronized void stop() {
        var scheduler = current;
        current = null;
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    // package-private: tests install a scheduler with fake parts
    static synchronized void install(RoleRefreshScheduler scheduler) {
        current = scheduler;
    }

    /** This node's scheduler, or null before start and after stop. */
    public static RoleRefreshScheduler current() {
        return current;
    }

    /** Asks for one role refresh of a component. */
    public void request(String realmId, String componentId) {
        if (shutdown) {
            return;
        }
        boolean[] schedule = {false};
        states.compute(componentId, (id, state) -> {
            if (state == null) {
                schedule[0] = true;
                return State.SCHEDULED;
            }
            return state == State.SCHEDULED ? State.SCHEDULED : State.RUNNING_AGAIN;
        });
        if (schedule[0]) {
            scheduleRun(realmId, componentId);
        }
    }

    void shutdown() {
        shutdown = true;
        if (ownedExecutor != null) {
            // Not shutdownNow: an interrupt can land inside a JDBC or HTTP
            // call and break the lease release. The running refresh sees the
            // flag through its lost check and stops at the next user. See
            // SyncLease.stopHeartbeat for the same choice.
            ownedExecutor.shutdown();
        }
    }

    // package-private for tests; null means idle
    State state(String componentId) {
        return states.get(componentId);
    }

    private void scheduleRun(String realmId, String componentId) {
        if (shutdown) {
            states.remove(componentId);
            return;
        }
        try {
            delayer.schedule(() -> run(realmId, componentId), delay);
        } catch (RejectedExecutionException e) {
            states.remove(componentId);
        }
    }

    private void run(String realmId, String componentId) {
        if (shutdown) {
            states.remove(componentId);
            return;
        }
        // Moves to RUNNING before anything is read, so a request after this
        // point causes one more refresh instead of being absorbed.
        if (!states.replace(componentId, State.SCHEDULED, State.RUNNING)) {
            return;
        }
        RoleRefreshResult result = RoleRefreshResult.STOPPED;
        try {
            result = runner.run(realmId, componentId, () -> shutdown);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "SCIM role refresh failed for component %s", componentId);
        } finally {
            // In finally, so even an Error leaves the component able to
            // refresh again. Without it, the state would stay RUNNING.
            finish(realmId, componentId, result);
        }
    }

    private void finish(String realmId, String componentId, RoleRefreshResult result) {
        boolean[] schedule = {false};
        states.compute(componentId, (id, state) -> {
            boolean newerRequest = state == State.RUNNING_AGAIN;
            boolean again = result == RoleRefreshResult.REFUSED
                || (newerRequest && result != RoleRefreshResult.SKIPPED);
            if (again && !shutdown) {
                schedule[0] = true;
                return State.SCHEDULED;
            }
            return null;
        });
        if (schedule[0]) {
            scheduleRun(realmId, componentId);
        }
    }

    /** Reads the component again, then runs the refresh if it is still on. */
    private static RoleRefreshResult runForComponent(KeycloakSessionFactory sessionFactory, String realmId,
                                                     String componentId, BooleanSupplier isShutdown) {
        ComponentModel model = KeycloakModelUtils.runJobInTransactionWithResult(sessionFactory, session -> {
            var realm = session.realms().getRealm(realmId);
            if (realm == null) {
                return null;
            }
            var component = realm.getComponent(componentId);
            return refreshable(component) ? component : null;
        });
        if (model == null) {
            return RoleRefreshResult.SKIPPED;
        }
        return ScimSync.runRoleRefresh(sessionFactory, realmId, model, isShutdown);
    }

    /** Whether a component still exists and is on for user propagation. */
    // package-private for tests
    static boolean refreshable(ComponentModel component) {
        return component != null
            && ScimStorageProviderFactory.ID.equals(component.getProviderId())
            && component.get("enabled", true)
            && component.get("propagation-user", false);
    }
}
