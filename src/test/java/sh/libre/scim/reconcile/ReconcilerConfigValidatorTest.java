package sh.libre.scim.reconcile;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcilerConfigValidatorTest {

    private static ComponentModel scim(String enabled, String interval, String threshold) {
        var c = new ComponentModel();
        c.put("reconciler-enabled", enabled);
        if (interval != null) c.put("reconciler-interval-seconds", interval);
        if (threshold != null) c.put("reconciler-stale-threshold-seconds", threshold);
        return c;
    }

    private static ComponentModel ldap(String name, String fullSyncPeriod) {
        var c = new ComponentModel();
        c.setName(name);
        c.put("fullSyncPeriod", fullSyncPeriod);
        return c;
    }

    @Test
    void noopWhenReconcilerDisabled() {
        // No validation runs at all when the feature isn't opted into,
        // so even nonsense values pass.
        var c = scim("false", "-1", "0");
        assertDoesNotThrow(() -> ReconcilerConfigValidator.validate(c, List.of()));
    }

    @Test
    void noopWhenEnabledFlagMissing() {
        var c = scim(null, null, null);
        assertDoesNotThrow(() -> ReconcilerConfigValidator.validate(c, List.of()));
    }

    @Test
    void acceptsDefaultsWhenEnabled() {
        // Defaults: interval=86400, threshold=172800.
        var c = scim("true", null, null);
        assertDoesNotThrow(() -> ReconcilerConfigValidator.validate(c, List.of()));
    }

    @Test
    void acceptsValidExplicitConfig() {
        var c = scim("true", "60", "300");
        assertDoesNotThrow(() -> ReconcilerConfigValidator.validate(c, List.of()));
    }

    @Test
    void rejectsNegativeInterval() {
        var c = scim("true", "-1", "300");
        var ex = assertThrows(ComponentValidationException.class,
            () -> ReconcilerConfigValidator.validate(c, List.of()));
        assertTrue(ex.getMessage().contains("interval"), ex.getMessage());
    }

    @Test
    void rejectsZeroInterval() {
        var c = scim("true", "0", "300");
        assertThrows(ComponentValidationException.class,
            () -> ReconcilerConfigValidator.validate(c, List.of()));
    }

    @Test
    void rejectsZeroThreshold() {
        var c = scim("true", "60", "0");
        var ex = assertThrows(ComponentValidationException.class,
            () -> ReconcilerConfigValidator.validate(c, List.of()));
        assertTrue(ex.getMessage().contains("threshold"), ex.getMessage());
    }

    @Test
    void rejectsThresholdEqualToInterval() {
        var c = scim("true", "300", "300");
        var ex = assertThrows(ComponentValidationException.class,
            () -> ReconcilerConfigValidator.validate(c, List.of()));
        assertTrue(ex.getMessage().contains("greater than"), ex.getMessage());
    }

    @Test
    void rejectsThresholdLessThanInterval() {
        var c = scim("true", "600", "300");
        assertThrows(ComponentValidationException.class,
            () -> ReconcilerConfigValidator.validate(c, List.of()));
    }

    @Test
    void rejectsThresholdShorterThanLdapFullSyncPeriod() {
        var c = scim("true", "60", "300");
        var federation = ldap("primary-ldap", "600");
        var ex = assertThrows(ComponentValidationException.class,
            () -> ReconcilerConfigValidator.validate(c, List.of(federation)));
        assertTrue(ex.getMessage().contains("primary-ldap"), ex.getMessage());
        assertTrue(ex.getMessage().contains("fullSyncPeriod"), ex.getMessage());
    }

    @Test
    void rejectsThresholdEqualToLdapFullSyncPeriod() {
        // The threshold must be strictly greater than the sync period —
        // a user observed exactly threshold-many seconds ago is borderline,
        // and we want a buffer.
        var c = scim("true", "60", "300");
        assertThrows(ComponentValidationException.class,
            () -> ReconcilerConfigValidator.validate(c, List.of(ldap("ldap", "300"))));
    }

    @Test
    void ignoresLdapFederationsWithDisabledPeriodicSync() {
        // fullSyncPeriod = -1 means periodic sync is disabled; nothing to
        // compare against.
        var c = scim("true", "60", "300");
        assertDoesNotThrow(() ->
            ReconcilerConfigValidator.validate(c, List.of(ldap("disabled-ldap", "-1"))));
    }

    @Test
    void enforcesAgainstAllConfiguredLdapFederations() {
        // Multiple federations: any one of them violating the rule fails the
        // validation.
        var c = scim("true", "60", "300");
        assertThrows(ComponentValidationException.class,
            () -> ReconcilerConfigValidator.validate(c, List.of(
                ldap("fast", "100"),       // ok
                ldap("slow", "1000"))));   // not ok
    }
}
