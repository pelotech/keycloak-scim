package sh.libre.scim.core;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The database operations a sync lease needs. Each runs in its own short
 * transaction, except the pair inside {@link #inOneTransaction}.
 * {@link SyncLease} holds every rule and none of the SQL; an implementation
 * holds the SQL and no rule.
 */
interface LeaseStore {

    /**
     * Runs {@code work} in one transaction, so the lock taken by
     * {@link #lockAndRead} is still held when {@link #take} writes.
     */
    <T> T inOneTransaction(Supplier<T> work);

    /** A row as read under the lock. */
    record Row(String holder, Long renewedAt) {}

    /**
     * Inserts a row for the component with no holder. The caller tolerates any
     * failure, because a duplicate surfaces at commit and is the common case.
     */
    void ensureRow(String componentId);

    /**
     * Locks the component's row and reads it. Returns empty when there is no
     * row, which means the insert was refused for a reason other than a
     * duplicate. Valid only inside {@link #inOneTransaction}.
     */
    Optional<Row> lockAndRead(String componentId);

    /**
     * Writes the holder and both timestamps on the row locked by
     * {@link #lockAndRead}. Valid only inside {@link #inOneTransaction}.
     */
    void take(String componentId, String token, long now);

    /** Renews if the row still belongs to {@code token}. Returns the rows matched, 0 or 1. */
    int renew(String componentId, String token, long now);

    /** Clears the holder if the row still belongs to {@code token}. Returns the rows matched, 0 or 1. */
    int release(String componentId, String token);
}
