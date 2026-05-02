package sh.libre.scim.reconcile;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import sh.libre.scim.core.ScimClient;
import sh.libre.scim.core.UserAdapter;
import sh.libre.scim.jpa.ScimResource;
import sh.libre.scim.ldap.ScimLdapStorageMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One reconciliation pass for a single SCIM provider component: walks the
 * realm's federation-linked users, evaluates configured witnesses, and issues
 * a SCIM DELETE (via the existing {@link ScimClient}) for each user every
 * witness agrees is absent.
 *
 * <p>Scope note: we do not delete the local {@code UserModel}. That is
 * Keycloak's responsibility (see upstream issue #35235); when upstream lands
 * the fix, admin {@code USER/DELETE} events will fire and the existing event
 * listener will propagate. Until then, SCIM stays consistent with LDAP even
 * while Keycloak holds orphan {@code UserModel} rows locally.
 */
public class ReconcilerRunner {

    private static final Logger LOGGER = Logger.getLogger(ReconcilerRunner.class);

    private final KeycloakSession session;
    private final ComponentModel scimProvider;
    private final Duration staleThreshold;

    public ReconcilerRunner(KeycloakSession session, ComponentModel scimProvider,
                            Duration staleThreshold) {
        this.session = session;
        this.scimProvider = scimProvider;
        this.staleThreshold = staleThreshold;
    }

    /**
     * @return number of SCIM DELETE calls issued during this pass.
     */
    public int run() {
        var realm = session.getContext().getRealm();
        var em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        List<AbsenceWitness> witnesses = List.of(
            new StaleAttributeWitness(
                ScimLdapStorageMapper.LAST_SEEN_ATTRIBUTE,
                staleThreshold,
                Instant.now())
        );

        // Iterate our own mappings rather than Keycloak's user list. Walking
        // Keycloak's users triggers federation enumeration, which can silently
        // drop users whose upstream entry has disappeared (exactly the case
        // we're trying to detect). The mapping table persists independently
        // of federation state, so it's a stable anchor for "who have we ever
        // propagated to this SCIM provider."
        var mappings = em.createNamedQuery("findByComponentAndType", ScimResource.class)
            .setParameter("realmId", realm.getId())
            .setParameter("componentId", scimProvider.getId())
            .setParameter("type", "User")
            .getResultList();

        LOGGER.debugf("Reconciler: %d mapped User resources for SCIM provider %s",
            mappings.size(), scimProvider.getId());

        int deleted = 0;
        var client = new ScimClient(scimProvider, session);
        try {
            for (ScimResource m : mappings) {
                var user = session.users().getUserById(realm, m.getId());
                boolean shouldDelete;
                if (user == null) {
                    // Orphaned mapping: the local UserModel is gone (federation
                    // enumeration dropped it, or someone deleted it). SCIM
                    // sink shouldn't keep the resource either.
                    shouldDelete = true;
                } else if (user.getFederationLink() == null) {
                    // Local-only user; out of scope for the LDAP reconciler.
                    continue;
                } else {
                    shouldDelete = ReconciliationDecision.shouldDelete(user, witnesses);
                }

                if (shouldDelete) {
                    LOGGER.infof("Reconciler: mapping %s (username=%s) is absent; issuing SCIM DELETE",
                        m.getId(), user != null ? user.getUsername() : "<gone>");
                    client.delete(UserAdapter.class, m.getId());
                    deleted++;
                }
            }
        } finally {
            client.close();
        }
        return deleted;
    }
}
