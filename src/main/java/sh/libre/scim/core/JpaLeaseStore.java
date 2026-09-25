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
     * step. A lock on a missing row locks nothing. Without this step first,
     * two nodes could both insert, and one would fail at commit after it had
     * already decided to take the lease.
     */
    @Override
    public void ensureRow(String componentId) {
        KeycloakModelUtils.runJobInTransaction(sessionFactory, session -> {
            var row = new ScimSyncLease();
            row.setComponentId(componentId);
            em(session).persist(row);
        });
    }

    /** The locked operations, bound to the session that holds the row lock. */
    private record JpaLocked(EntityManager em) implements Locked {
        @Override
        public Optional<Row> lockAndRead(String componentId) {
            var row = em.find(ScimSyncLease.class, componentId, LockModeType.PESSIMISTIC_WRITE);
            return row == null ? Optional.empty() : Optional.of(new Row(row.getHolder(), row.getRenewedAt()));
        }

        @Override
        public void take(String componentId, String token, long now) {
            var row = em.find(ScimSyncLease.class, componentId);
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
