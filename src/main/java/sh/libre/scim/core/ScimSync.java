package sh.libre.scim.core;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.storage.user.SynchronizationResult;

import sh.libre.scim.core.exceptions.ScimPropagationException;
import sh.libre.scim.storage.ScimStorageProviderFactory;

/**
 * Runs a user-storage sync for one SCIM provider component. Import and refresh
 * are selected independently and import runs first for each resource type.
 * User refresh commits one page per transaction; import and group refresh each
 * run in a single transaction. Every part of a sync retries a call three times,
 * rather than the ten an interactive call gets. The pages use their own client,
 * with shorter HTTP timeouts.
 *
 * <p>Keycloak wraps the whole sync in a transaction of its own and cancels it
 * at the global timeout. A run longer than that still pushes every user and
 * keeps every mapping the pages committed, but Keycloak reports the sync as
 * failed. Paging does not change that.
 *
 * <p>A run holds a lease on its component while it runs, so one sync of a
 * component happens at a time across the cluster. A run that loses its lease
 * finishes the transaction in flight, skips the stages it has not started, and
 * counts each skipped stage as a failure. The rules are in {@link SyncLease}.
 */
public final class ScimSync {

    private static final Logger LOGGER = Logger.getLogger(ScimSync.class);
    private static final ScimTracingBridge TRACING = ScimTracingBridge.create();

    /** Above this many groups, warn that the single-transaction group refresh may time out. */
    static final long GROUP_COUNT_WARNING = 500;

    /** One stage of a sync. It counts into {@code counters}, not into the run. */
    @FunctionalInterface
    private interface Stage {
        void run(ScimClient client, KeycloakSession session, SynchronizationResult counters);
    }

    private ScimSync() {}

    /**
     * Test aid only. When this system property is set, a run takes its lease
     * and then behaves as a crashed holder: no heartbeat, no self-fence and no
     * release. An integration test uses it to prove that a stale lease is
     * taken by the next run.
     */
    static final String SIMULATE_CRASH_PROPERTY = "scim.sync.lease.simulateCrash";

    public static SynchronizationResult run(KeycloakSessionFactory sessionFactory, String realmId,
                                            ComponentModel model) {
        return run(sessionFactory, realmId, model, new JpaLeaseStore(sessionFactory), Clock.systemUTC());
    }

    // package-private so a test can pass a fake store
    static SynchronizationResult run(KeycloakSessionFactory sessionFactory, String realmId,
                                     ComponentModel model, LeaseStore store, Clock clock) {
        var result = new SynchronizationResult();
        boolean doImport = model.get("sync-import", false);
        boolean doRefresh = model.get("sync-refresh", false);
        if (!doImport && !doRefresh) {
            // Both halves are off by default, so a sync triggered from the admin
            // console or the user-storage REST endpoint would otherwise return an
            // empty result and look like it worked.
            LOGGER.infof("Sync requested for component %s but sync-import and sync-refresh are both "
                + "disabled; nothing to do", model.getId());
            return result;
        }
        boolean users = "true".equals(model.get("propagation-user"));
        boolean groups = "true".equals(model.get("propagation-group"));
        if (!users && !groups) {
            // No stage would run, so there is nothing to hold a lease for.
            return result;
        }

        var lease = new SyncLease(store, model.getId(), clock);
        boolean simulateCrash = Boolean.getBoolean(SIMULATE_CRASH_PROPERTY);
        if (simulateCrash) {
            LOGGER.warnf("%s is set: this run keeps its lease, so later syncs of component %s are refused "
                + "for %d s, and a run longer than that can overlap the next one. Test aid only; unset it in "
                + "production.", SIMULATE_CRASH_PROPERTY, model.getId(), SyncLease.STALE_THRESHOLD.toSeconds());
        }
        try {
            if (lease.acquire() == SyncLease.Decision.REFUSE) {
                return SynchronizationResult.ignored();
            }
        } catch (RuntimeException e) {
            // A run without a lease would recreate the overlap the lease removes.
            LOGGER.errorf(e, "SCIM sync of component %s could not take its lease", model.getId());
            result.increaseFailed();
            return result;
        }
        // See SIMULATE_CRASH_PROPERTY.
        BooleanSupplier lost = simulateCrash ? () -> false : lease::lost;
        try {
            if (!simulateCrash) {
                // Inside the try, so a throw here still releases.
                lease.startHeartbeat();
            }
            runStages(sessionFactory, realmId, model, clock, result, lease::reportProgress, lost,
                doImport, doRefresh, users, groups);
        } finally {
            if (!simulateCrash) {
                lease.release();
            }
        }
        return result;
    }

    /**
     * The four stages, in order. Each is checked against the lease first, so
     * a run that lost it skips what it has not started. The stages see only
     * the progress reporter and the lost check, never the lease itself.
     */
    // package-private so a test can reach it with a fake lease
    static void runStages(KeycloakSessionFactory sessionFactory, String realmId, ComponentModel model, Clock clock,
                          SynchronizationResult result, Runnable progress, BooleanSupplier lost,
                          boolean doImport, boolean doRefresh, boolean users, boolean groups) {
        if (users) {
            if (doImport) {
                unlessLost(lost, "user import", result, () ->
                    runContained(model, "user import", result, () ->
                        runInTransaction(sessionFactory, realmId, model, "user import", result, progress,
                            (client, session, counters) ->
                                client.importResources(UserAdapter::new, counters, progress))));
            }
            if (doRefresh) {
                unlessLost(lost, "user refresh", result, () ->
                    runContained(model, "user refresh", result,
                        () -> refreshUsers(sessionFactory, realmId, model, clock, result, lost, progress)));
            }
        }
        if (groups) {
            if (doImport) {
                unlessLost(lost, "group import", result, () ->
                    runContained(model, "group import", result, () ->
                        runInTransaction(sessionFactory, realmId, model, "group import", result, progress,
                            (client, session, counters) ->
                                client.importResources(GroupAdapter::new, counters, progress))));
            }
            if (doRefresh) {
                // Groups are not paged. This stage has no per-resource guard and
                // no rollback check between groups, so one fault that poisons the
                // transaction discards every group mapping the stage wrote. The
                // group-count warning tells the operator when that exposure grows.
                unlessLost(lost, "group refresh", result, () ->
                    runContained(model, "group refresh", result, () ->
                        runInTransaction(sessionFactory, realmId, model, "group refresh", result, progress,
                            (client, session, counters) -> {
                                warnIfManyGroups(session, model);
                                client.refreshResources(GroupAdapter::new, counters, progress);
                            })));
            }
        }
    }

    /**
     * Skips a stage once the run has lost its lease. The skipped stage counts
     * as a failure, so the sync result shows an incomplete run.
     */
    // package-private so a test can reach it without a session factory
    static void unlessLost(BooleanSupplier lost, String stage, SynchronizationResult result, Runnable work) {
        if (lost.getAsBoolean()) {
            LOGGER.errorf("SCIM %s skipped: this run no longer holds the component's lease", stage);
            result.increaseFailed();
            return;
        }
        work.run();
    }

    /**
     * Refreshes the realm's users one page at a time. One step serves the whole
     * run, because the step counts throttled users across pages. The runner
     * gets the page size the step reads with, because that count is also the
     * throttle threshold.
     *
     * <p>A run that ends for any reason other than a finished population left
     * users unvisited. That counts as a failure, so the sync result shows it
     * and an operator does not have to read the log to find out.
     */
    private static void refreshUsers(KeycloakSessionFactory sessionFactory, String realmId,
                                     ComponentModel model, Clock clock, SynchronizationResult result,
                                     BooleanSupplier lost, Runnable progress) {
        progress.run();
        int pageSize = ScimStorageProviderFactory.positiveIntSetting(model,
            ScimStorageProviderFactory.SYNC_PAGE_SIZE,
            ScimStorageProviderFactory.DEFAULT_SYNC_PAGE_SIZE);
        var pageBudget = Duration.ofSeconds(ScimStorageProviderFactory.positiveIntSetting(model,
            ScimStorageProviderFactory.SYNC_PAGE_MAX_SECONDS,
            ScimStorageProviderFactory.DEFAULT_SYNC_PAGE_MAX_SECONDS));
        // The run's clock, so the page budget and the lease read the same time.
        var step = new RefreshPageStep(sessionFactory, realmId, model, pageBudget, clock, lost, progress);
        try (var ignored = TRACING.startSpan("scim.sync.refresh", "User", model.get("endpoint"))) {
            var outcome = PagedSyncRunner.run(step, pageSize, result);
            if (!outcome.completed()) {
                LOGGER.errorf("SCIM user refresh on component %s (%s) stopped at cursor %s because of %s; "
                    + "the users after that point were not visited",
                    model.getId(), model.getName(), outcome.cursor(), outcome.stopReason());
                result.increaseFailed();
            }
        }
        progress.run();
    }

    /**
     * Runs one stage in one transaction and adds what it kept to {@code result}.
     *
     * <p>A failure inside the stage is logged and counted there rather than
     * escaping, because an escaping exception rolls the transaction back and
     * discards the mappings for everything already pushed. A persistence
     * failure can still mark the transaction rollback-only, in which case
     * Keycloak rolls it back when the session closes and throws nothing.
     *
     * <p>The stage therefore counts into its own result, and only a transaction
     * that committed contributes all of it. See {@link #mergeStage}.
     *
     * <p>Progress is reported before and after the stage, so a stage boundary
     * counts as a sign of life even when the stage examines nothing.
     */
    private static void runInTransaction(KeycloakSessionFactory sessionFactory, String realmId,
                                         ComponentModel model, String stage, SynchronizationResult result,
                                         Runnable progress, Stage work) {
        progress.run();
        var staged = new SynchronizationResult();
        var kept = new AtomicBoolean(false);
        try {
            KeycloakModelUtils.runJobInTransaction(sessionFactory, session -> {
                var realm = session.realms().getRealm(realmId);
                if (realm == null) {
                    // Nothing ran, so nothing was lost.
                    LOGGER.warnf("SCIM %s skipped on component %s: realm %s is gone",
                        stage, model.getId(), realmId);
                    kept.set(true);
                    return;
                }
                session.getContext().setRealm(realm);
                var client = ScimClient.forBatchSync(model, session);
                try {
                    work.run(client, session, staged);
                } catch (ScimPropagationException e) {
                    LOGGER.warnf(e, "SCIM %s failed on component %s (%s)", stage, model.getId(), model.getName());
                    staged.increaseFailed();
                } catch (RuntimeException e) {
                    LOGGER.errorf(e, "SCIM %s failed on component %s (%s)", stage, model.getId(), model.getName());
                    staged.increaseFailed();
                } finally {
                    ScimClient.closeQuietly(client);
                }
                kept.set(!session.getTransactionManager().getRollbackOnly());
            });
        } catch (RuntimeException e) {
            // The commit failed, so the stage kept nothing. Carry its failures
            // over and let the caller count the lost stage, which it does for
            // every exception that reaches it.
            keepFailures(result, staged);
            throw e;
        }
        if (!kept.get()) {
            LOGGER.warnf("SCIM %s on component %s was rolled back; nothing it pushed was recorded",
                stage, model.getId());
        }
        mergeStage(result, staged, kept.get());
        progress.run();
    }

    /**
     * Adds one stage's counters to the run.
     *
     * <p>A rolled-back stage kept nothing, so its added, updated and removed
     * counts describe work the database discarded. Reporting them would tell an
     * operator that users were written when they were not. Its failures did
     * happen, and the lost stage is one more.
     *
     * @param kept whether the stage's transaction committed
     */
    // package-private so a test can reach it without a session factory
    static void mergeStage(SynchronizationResult result, SynchronizationResult staged, boolean kept) {
        if (kept) {
            result.add(staged);
            return;
        }
        keepFailures(result, staged);
        result.increaseFailed();
    }

    /** Carries over a lost stage's failures and nothing else. */
    private static void keepFailures(SynchronizationResult result, SynchronizationResult staged) {
        result.setFailed(result.getFailed() + staged.getFailed());
    }

    /**
     * Runs one stage of the sync so that a failure escaping it is logged and
     * counted without stopping the other stages. It is the only handler for a
     * failed commit, and for anything thrown while resolving the realm or
     * building the client, because those happen outside the stage's own guard.
     */
    // package-private so a test can reach it without a session factory
    static void runContained(ComponentModel model, String stage, SynchronizationResult result, Runnable work) {
        try {
            work.run();
        } catch (ScimPropagationException e) {
            LOGGER.warnf(e, "SCIM %s failed on component %s (%s)", stage, model.getId(), model.getName());
            result.increaseFailed();
        } catch (RuntimeException e) {
            // Always with the stack trace. A duplicate stack costs less than a
            // missing one, and for three of the four stages this is the only
            // place the cause is printed at all.
            LOGGER.errorf(e, "SCIM %s failed on component %s (%s)", stage, model.getId(), model.getName());
            result.increaseFailed();
        }
    }

    // package-private so a test can reach it with a mock session
    static void warnIfManyGroups(KeycloakSession session, ComponentModel model) {
        try {
            long groups = session.groups().getGroupsCount(session.getContext().getRealm(), false);
            if (groups > GROUP_COUNT_WARNING) {
                LOGGER.warnf("Component %s is refreshing %d groups in a single transaction; with this many "
                    + "groups the refresh may exceed the transaction timeout", model.getId(), groups);
            }
        } catch (RuntimeException e) {
            // Only a warning; never let counting groups stop the refresh.
            LOGGER.debugf(e, "Could not count groups for component %s", model.getId());
        }
    }
}
