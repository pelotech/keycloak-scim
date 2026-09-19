package sh.libre.scim.core;

import java.time.Clock;
import java.time.Duration;
import java.util.function.BiConsumer;

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
 * run in a single transaction. All of them use the batch-sync retry budget.
 *
 * <p>Keycloak wraps the whole sync in a transaction of its own and cancels it
 * at the global timeout. A run longer than that still pushes every user and
 * keeps every mapping the pages committed, but Keycloak reports the sync as
 * failed. Paging does not change that.
 */
public final class ScimSync {

    private static final Logger LOGGER = Logger.getLogger(ScimSync.class);
    private static final ScimTracingBridge TRACING = ScimTracingBridge.create();

    /** Above this many groups, warn that the single-transaction group refresh may time out. */
    static final long GROUP_COUNT_WARNING = 500;

    private ScimSync() {}

    public static SynchronizationResult run(KeycloakSessionFactory sessionFactory, String realmId,
                                            ComponentModel model) {
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
        if ("true".equals(model.get("propagation-user"))) {
            if (doImport) {
                contained(model, "user import", result, () ->
                    inTransaction(sessionFactory, realmId, model, "user import", result,
                        (client, session) -> client.importResources(UserAdapter::new, result)));
            }
            if (doRefresh) {
                contained(model, "user refresh", result,
                    () -> refreshUsers(sessionFactory, realmId, model, result));
            }
        }
        if ("true".equals(model.get("propagation-group"))) {
            if (doImport) {
                contained(model, "group import", result, () ->
                    inTransaction(sessionFactory, realmId, model, "group import", result,
                        (client, session) -> client.importResources(GroupAdapter::new, result)));
            }
            if (doRefresh) {
                contained(model, "group refresh", result, () ->
                    inTransaction(sessionFactory, realmId, model, "group refresh", result,
                        (client, session) -> {
                            warnIfManyGroups(session, model);
                            client.refreshResources(GroupAdapter::new, result);
                        }));
            }
        }
        return result;
    }

    /**
     * Refreshes the realm's users one page at a time. One step serves the whole
     * run, because the step counts throttled users across pages. The runner
     * gets the page size the step reads with, because that count is also the
     * throttle threshold.
     */
    private static void refreshUsers(KeycloakSessionFactory sessionFactory, String realmId,
                                     ComponentModel model, SynchronizationResult result) {
        int pageSize = ScimStorageProviderFactory.positiveIntSetting(model,
            ScimStorageProviderFactory.SYNC_PAGE_SIZE,
            ScimStorageProviderFactory.DEFAULT_SYNC_PAGE_SIZE);
        var pageBudget = Duration.ofSeconds(ScimStorageProviderFactory.positiveIntSetting(model,
            ScimStorageProviderFactory.SYNC_PAGE_MAX_SECONDS,
            ScimStorageProviderFactory.DEFAULT_SYNC_PAGE_MAX_SECONDS));
        var step = new RefreshPageStep(sessionFactory, realmId, model, pageBudget, Clock.systemUTC());
        try (var ignored = TRACING.startSpan("scim.sync.refresh", "User", model.get("endpoint"))) {
            PagedSyncRunner.run(step, pageSize, result);
        }
    }

    /**
     * Runs {@code work} in one transaction. A failure inside it is logged and
     * counted there rather than escaping, because an escaping exception rolls the
     * transaction back and discards the mappings for everything already pushed.
     * A persistence failure can still mark the transaction rollback-only, in which
     * case Keycloak rolls it back when the session closes; that is logged so the
     * lost unit is visible.
     */
    private static void inTransaction(KeycloakSessionFactory sessionFactory, String realmId, ComponentModel model,
                                      String half, SynchronizationResult result,
                                      BiConsumer<ScimClient, KeycloakSession> work) {
        KeycloakModelUtils.runJobInTransaction(sessionFactory, session -> {
            session.getContext().setRealm(session.realms().getRealm(realmId));
            var client = ScimClient.forBatchSync(model, session);
            try {
                work.accept(client, session);
            } catch (ScimPropagationException e) {
                LOGGER.warnf(e, "SCIM %s failed on component %s (%s)", half, model.getId(), model.getName());
                result.increaseFailed();
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "SCIM %s failed on component %s (%s)", half, model.getId(), model.getName());
                result.increaseFailed();
            } finally {
                // A throw from close would leave the transaction and roll back
                // the mappings for everything this half already pushed.
                RefreshPageStep.closeQuietly(client);
            }
            if (session.getTransactionManager().getRollbackOnly()) {
                LOGGER.warnf("SCIM %s on component %s was rolled back; mappings written in it were not saved",
                    half, model.getId());
            }
        });
    }

    /**
     * Runs one half of the sync so that a failure escaping it, such as a failed
     * commit or a failed page, is logged and counted without stopping the other
     * halves.
     */
    // package-private so a test can reach it without a session factory
    static void contained(ComponentModel model, String half, SynchronizationResult result, Runnable work) {
        try {
            work.run();
        } catch (ScimPropagationException e) {
            LOGGER.warnf(e, "SCIM %s failed on component %s (%s)", half, model.getId(), model.getName());
            result.increaseFailed();
        } catch (RuntimeException e) {
            // PagedSyncRunner has already logged a failed page with its stack trace.
            LOGGER.errorf("SCIM %s failed on component %s (%s): %s",
                half, model.getId(), model.getName(), e.toString());
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
