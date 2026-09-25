package sh.libre.scim.core;

import java.util.Optional;
import java.util.function.Function;

/**
 * The database operations a sync lease needs. Each runs in its own short
 * transaction, except the pair inside {@link #inOneTransaction}.
 * {@link SyncLease} holds every rule and none of the SQL; an implementation
 * holds the SQL and no rule.
 */
interface LeaseStore {

    /** A row as read under the lock. */
    record Row(String holder, Long renewedAt) {}

    /**
     * The two operations that must share one transaction, so the row lock
     * taken by {@link #lockAndRead} is still held when {@link #take} writes.
     * A handle exists only inside {@link LeaseStore#inOneTransaction}, so
     * neither operation can be called outside it.
     */
    interface Locked {

        /**
         * Locks the component's row and reads it. Returns empty when there is
         * no row, which means the insert was refused for a reason other than
         * a duplicate.
         */
        Optional<Row> lockAndRead(String componentId);

        /**
         * Writes the holder and both timestamps on the row locked by
         * {@link #lockAndRead}.
         */
        void take(String componentId, String token, long now);
    }

    /**
     * Runs {@code work} in one transaction, so the lock taken by
     * {@link Locked#lockAndRead} is still held when {@link Locked#take} writes.
     */
    <T> T inOneTransaction(Function<Locked, T> work);

    /**
     * Inserts a row for the component with no holder, if none exists yet.
     * The caller tolerates any failure. A duplicate is rare: it can surface
     * at commit only when two nodes race to insert the same component for
     * the first time.
     */
    void ensureRow(String componentId);

    /** Renews if the row still belongs to {@code token}. Returns the rows matched, 0 or 1. */
    int renew(String componentId, String token, long now);

    /** Clears the holder if the row still belongs to {@code token}. Returns the rows matched, 0 or 1. */
    int release(String componentId, String token);
}
