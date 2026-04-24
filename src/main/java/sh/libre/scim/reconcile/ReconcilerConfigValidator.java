package sh.libre.scim.reconcile;

import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;

import java.util.List;

import sh.libre.scim.storage.ScimStorageProviderFactory;

/**
 * Validates the reconciler config on a SCIM provider component at save time.
 *
 * <p>Pure function over the SCIM component and the realm's LDAP federation
 * components; throws {@link ComponentValidationException} with a specific
 * message when invariants are violated, allowing Keycloak's admin console /
 * REST to surface the error to operators without saving a footgun config.
 *
 * <p>Invariants enforced (only when {@code reconciler-enabled=true}):
 * <ul>
 *   <li>interval and threshold must both be positive,
 *   <li>threshold must be strictly greater than interval (otherwise the
 *       reconciler can fire before any user has had time to age across the
 *       freshness window),
 *   <li>threshold must be strictly greater than the {@code fullSyncPeriod}
 *       of every LDAP federation in the realm whose period is positive
 *       (otherwise the reconciler would delete users that the federation
 *       hasn't yet had a chance to re-observe and refresh the
 *       {@code ldap-federation-last-seen} attribute on).
 * </ul>
 *
 * <p>Federations with {@code fullSyncPeriod <= 0} (periodic sync disabled)
 * are skipped from the federation-period check; operators who disable
 * periodic sync are accepting that the reconciler will operate on
 * lazy-import-only liveness data.
 */
public final class ReconcilerConfigValidator {

    private ReconcilerConfigValidator() {}

    public static void validate(ComponentModel scim, List<ComponentModel> ldapFederations) {
        if (!"true".equalsIgnoreCase(scim.get(ScimStorageProviderFactory.RECONCILER_ENABLED))) {
            return;
        }

        long interval = parseLong(
            scim.get(ScimStorageProviderFactory.RECONCILER_INTERVAL_SECONDS), 86400L);
        long threshold = parseLong(
            scim.get(ScimStorageProviderFactory.RECONCILER_STALE_THRESHOLD_SECONDS), 172800L);

        if (interval <= 0) {
            throw new ComponentValidationException(
                "reconciler-interval-seconds must be a positive integer when the reconciler is enabled (got " + interval + ")");
        }
        if (threshold <= 0) {
            throw new ComponentValidationException(
                "reconciler-stale-threshold-seconds must be a positive integer when the reconciler is enabled (got " + threshold + ")");
        }
        if (threshold <= interval) {
            throw new ComponentValidationException(
                "reconciler-stale-threshold-seconds (" + threshold
                + ") must be greater than reconciler-interval-seconds (" + interval + ")");
        }

        for (ComponentModel ldap : ldapFederations) {
            long fullSync = parseLong(ldap.get("fullSyncPeriod"), -1L);
            if (fullSync > 0 && fullSync >= threshold) {
                throw new ComponentValidationException(
                    "reconciler-stale-threshold-seconds (" + threshold
                    + ") must be greater than the fullSyncPeriod (" + fullSync
                    + ") of LDAP federation '" + ldap.getName()
                    + "' — otherwise the reconciler would delete users the federation hasn't had a chance to re-sync");
            }
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
