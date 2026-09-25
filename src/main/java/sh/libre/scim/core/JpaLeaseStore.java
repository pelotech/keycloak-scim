package sh.libre.scim.core;

import java.util.Optional;
import java.util.function.Function;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import sh.libre.scim.jpa.ScimSyncLease;

/**
 * The lease store over Keycloak sessions. Every operation opens its own
 * short transaction, the way the dispatcher's worker threads do, so it is
 * safe from any thread, including the heartbeat's.
 */
final class JpaLeaseStore implements LeaseStore {

    private final KeycloakSessionFactory sessionFactory;

    JpaLeaseStore(KeycloakSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    private static EntityManager em(KeycloakSession session) {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    /**
     * Inserts the row in its own transaction, ahead of the lock-and-decide
     * step, if none exists yet. A lock on a missing row locks nothing, so
     * this step must still run first. Reading before inserting means that
     * every sync after a component's first does not attempt an insert that
     * Hibernate and the transaction manager both log as a failure. Two nodes
     * can still race on a component's first-ever acquisition and both find
     * no row; one insert then fails at commit, but only once.
     */
    @Override
    public void ensureRow(String componentId) {
        KeycloakModelUtils.runJobInTransaction(sessionFactory, session -> {
            var em = em(session);
            if (em.find(ScimSyncLease.class, componentId) == null) {
                var row = new ScimSyncLease();
                row.setComponentId(componentId);
                em.persist(row);
            }
        });
    }

    /** The locked operations, bound to the session that holds the row lock. */
    private record JpaLocked(EntityManager em) implements Locked {
        /**
         * PostgreSQL waits for the row lock without limit, so a second node
         * blocks here and then refuses. H2, which the dev server and the
         * integration tests use, gives up after 2 seconds and throws instead,
         * so two acquisitions of one component in the same instant fail on
         * H2 rather than refusing. Either way, it cannot let two runs both
         * take the lease.
         */
        @Override
        public Optional<Row> lockAndRead(String componentId) {
            var row = em.find(ScimSyncLease.class, componentId, LockModeType.PESSIMISTIC_WRITE);
            return row == null ? Optional.empty() : Optional.of(new Row(row.getHolder(), row.getRenewedAt()));
        }

        @Override
        public void take(String componentId, String token, long now) {
            var row = em.find(ScimSyncLease.class, componentId);
            if (row == null) {
                throw new IllegalStateException("take before lockAndRead for component " + componentId);
            }
            row.setHolder(token);
            row.setAcquiredAt(now);
            row.setRenewedAt(now);
        }
    }

    /**
     * Runs the lock-and-decide step in one transaction. The row lock that
     * {@link Locked#lockAndRead} takes must still be held when {@link
     * Locked#take} writes. Two transactions here would let another node's
     * lock fall between the read and the write, so the decision would no
     * longer be atomic across nodes.
     */
    @Override
    public <T> T inOneTransaction(Function<Locked, T> work) {
        return KeycloakModelUtils.runJobInTransactionWithResult(sessionFactory,
            session -> work.apply(new JpaLocked(em(session))));
    }

    /**
     * Renews in its own short transaction, the way the dispatcher's worker
     * threads open theirs. A tick must not share a transaction with the run
     * it watches, or a hung run would hold the tick open too.
     */
    @Override
    public int renew(String componentId, String token, long now) {
        return KeycloakModelUtils.runJobInTransactionWithResult(sessionFactory, session ->
            em(session).createQuery("update ScimSyncLease l set l.renewedAt = :now "
                    + "where l.componentId = :component and l.holder = :holder")
                .setParameter("now", now)
                .setParameter("component", componentId)
                .setParameter("holder", token)
                .executeUpdate());
    }

    /** Clears the holder in its own short transaction, the same way every other operation here does. */
    @Override
    public int release(String componentId, String token) {
        return KeycloakModelUtils.runJobInTransactionWithResult(sessionFactory, session ->
            em(session).createQuery("update ScimSyncLease l set l.holder = null "
                    + "where l.componentId = :component and l.holder = :holder")
                .setParameter("component", componentId)
                .setParameter("holder", token)
                .executeUpdate());
    }
}
