package sh.libre.scim.reconcile;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.utils.KeycloakModelUtils;
import sh.libre.scim.core.ScimClient;
import sh.libre.scim.core.ScimDispatcher;
import sh.libre.scim.core.UserAdapter;
import sh.libre.scim.jpa.ScimResource;
import sh.libre.scim.ldap.ScimLdapStorageMapper;
import sh.libre.scim.storage.ScimStorageProviderFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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

        // Phase 1: identify mappings to delete. Sequential, in this session
        // (witness evaluation is local and cheap; getUserById is a single
        // local lookup). For 10k mappings this is ~10s — dwarfed by Phase 2's
        // HTTP cost in the synchronous version.
        List<String> toDelete = new ArrayList<>();
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
                toDelete.add(m.getId());
            }
        }

        if (toDelete.isEmpty()) {
            return 0;
        }

        // Phase 2: parallel SCIM DELETEs via the shared worker pool.
        // Each worker opens its own KeycloakSession (so mapping cleanup runs
        // in its own transaction) and constructs a ScimClient bound to that
        // session. The N HTTP calls saturate the worker pool's concurrency
        // budget instead of serializing on the caller's thread.
        //
        // Sized at ScimDispatcher.dispatchAsync's pool (default 8). For 1k
        // deletes at ~43 ms HTTP each: synchronous ~46 s -> parallel ~5.4 s
        // (matches the import-side gain).
        var sessionFactory = session.getKeycloakSessionFactory();
        var realmId = realm.getId();
        var componentId = scimProvider.getId();

        List<CompletableFuture<Void>> futures = new ArrayList<>(toDelete.size());
        for (String userId : toDelete) {
            futures.add(ScimDispatcher.dispatchAsync(() -> {
                KeycloakModelUtils.runJobInTransaction(sessionFactory, workerSession -> {
                    var workerRealm = workerSession.realms().getRealm(realmId);
                    if (workerRealm == null) return;
                    workerSession.getContext().setRealm(workerRealm);
                    var component = workerRealm.getComponent(componentId);
                    if (component == null
                        || !ScimStorageProviderFactory.ID.equals(component.getProviderId())) {
                        return;
                    }
                    var workerClient = new ScimClient(component, workerSession);
                    try {
                        workerClient.delete(UserAdapter.class, userId);
                    } finally {
                        workerClient.close();
                    }
                });
            }));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            // Individual delete failures are logged inside ScimClient.delete;
            // join() throws if ANY future completed exceptionally. We don't
            // want one failed delete to abort the count or prevent the
            // others from finishing — they've already run by the time join()
            // sees the exception. Just log and return what we attempted.
            LOGGER.warnf(e.getCause(), "reconciler: one or more parallel deletes failed");
        }
        return toDelete.size();
    }
}
