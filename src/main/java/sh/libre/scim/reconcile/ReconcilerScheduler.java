package sh.libre.scim.reconcile;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.timer.TimerProvider;

import java.time.Duration;

import sh.libre.scim.storage.ScimStorageProviderFactory;

/**
 * Ties reconciler config (on each SCIM provider component) to Keycloak's
 * {@link TimerProvider}. Call {@link #scheduleIfEnabled} when a component is
 * added, updated, or observed at startup; call {@link #cancel} when a
 * component is removed.
 *
 * <p>The scheduled task runs in its own {@link KeycloakSession} via
 * {@code KeycloakModelUtils.runJobInTransaction}. Reconciliation is idempotent
 * (SCIM DELETE on a missing mapping is a no-op), so two JVMs scheduling the
 * same task in a clustered deployment is safe — just slightly wasteful.
 */
public final class ReconcilerScheduler {

    private static final Logger LOGGER = Logger.getLogger(ReconcilerScheduler.class);

    private ReconcilerScheduler() {}

    public static void scheduleIfEnabled(
            KeycloakSessionFactory sessionFactory,
            KeycloakSession session,
            String realmId,
            ComponentModel component) {
        if (!"true".equalsIgnoreCase(component.get(ScimStorageProviderFactory.RECONCILER_ENABLED))) {
            cancel(session, component);
            return;
        }

        long intervalSeconds = parseLong(
            component.get(ScimStorageProviderFactory.RECONCILER_INTERVAL_SECONDS), 86400L);
        long thresholdSeconds = parseLong(
            component.get(ScimStorageProviderFactory.RECONCILER_STALE_THRESHOLD_SECONDS), 172800L);
        Duration threshold = Duration.ofSeconds(thresholdSeconds);

        String componentId = component.getId();
        String taskName = ScimStorageProviderFactory.reconcilerTaskName(componentId);
        long intervalMillis = intervalSeconds * 1000L;

        Runnable task = () -> KeycloakModelUtils.runJobInTransaction(sessionFactory, innerSession -> {
            var realm = innerSession.realms().getRealm(realmId);
            if (realm == null) {
                LOGGER.debugf("Reconciler task %s: realm %s is gone; skipping", taskName, realmId);
                return;
            }
            innerSession.getContext().setRealm(realm);
            var latest = realm.getComponent(componentId);
            if (latest == null
                || !ScimStorageProviderFactory.ID.equals(latest.getProviderId())
                || !"true".equalsIgnoreCase(latest.get(ScimStorageProviderFactory.RECONCILER_ENABLED))) {
                LOGGER.debugf("Reconciler task %s: component missing or disabled; skipping", taskName);
                return;
            }
            try {
                int deleted = new ReconcilerRunner(innerSession, latest, threshold).run();
                LOGGER.infof("Reconciler %s: deleted %d SCIM resource(s)", taskName, deleted);
            } catch (Exception e) {
                LOGGER.errorf(e, "Reconciler %s failed", taskName);
            }
        });

        var timer = session.getProvider(TimerProvider.class);
        timer.schedule(task, intervalMillis, taskName);
        LOGGER.infof("Reconciler %s scheduled: interval=%ds threshold=%ds",
            taskName, intervalSeconds, thresholdSeconds);
    }

    public static void cancel(KeycloakSession session, ComponentModel component) {
        if (component == null) return;
        var timer = session.getProvider(TimerProvider.class);
        if (timer == null) return;
        String taskName = ScimStorageProviderFactory.reconcilerTaskName(component.getId());
        if (timer.cancelTask(taskName) != null) {
            LOGGER.infof("Reconciler %s cancelled", taskName);
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
