package sh.libre.scim.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * An in-memory store. Rows are keyed by component id. Every operation can be
 * made to throw any {@link Throwable}, so a test can prove the heartbeat
 * survives an {@link Error}, not only a runtime exception.
 */
final class FakeLeaseStore implements LeaseStore {

    static final class Cell {
        String holder;
        Long acquiredAt;
        Long renewedAt;
    }

    final Map<String, Cell> rows = new HashMap<>();
    Throwable ensureFailure;
    Throwable lockFailure;
    Throwable renewFailure;
    Throwable releaseFailure;
    int renewCalls;

    /** Throws a checked or unchecked throwable without declaring it, as a database driver would. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }

    @Override
    public void ensureRow(String componentId) {
        if (ensureFailure != null) FakeLeaseStore.<RuntimeException>sneaky(ensureFailure);
        // The fake throws on an existing row on purpose, so every refuse and
        // takeover test exercises the lenient path that the real store takes
        // only in the first-acquisition race.
        if (rows.containsKey(componentId)) {
            throw new IllegalStateException("duplicate key: " + componentId);
        }
        rows.put(componentId, new Cell());
    }

    /** The locked operations. A handle exists only inside inOneTransaction. */
    private final Locked locked = new Locked() {
        @Override
        public Optional<Row> lockAndRead(String componentId) {
            if (lockFailure != null) FakeLeaseStore.<RuntimeException>sneaky(lockFailure);
            Cell c = rows.get(componentId);
            return c == null ? Optional.empty() : Optional.of(new Row(c.holder, c.renewedAt));
        }

        @Override
        public void take(String componentId, String token, long now) {
            Cell c = Objects.requireNonNull(rows.get(componentId), "take before lockAndRead");
            c.holder = token;
            c.acquiredAt = now;
            c.renewedAt = now;
        }
    };

    @Override
    public int renew(String componentId, String token, long now) {
        renewCalls++;
        if (renewFailure != null) FakeLeaseStore.<RuntimeException>sneaky(renewFailure);
        Cell c = rows.get(componentId);
        if (c == null || !token.equals(c.holder)) return 0;
        c.renewedAt = now;
        return 1;
    }

    @Override
    public int release(String componentId, String token) {
        if (releaseFailure != null) FakeLeaseStore.<RuntimeException>sneaky(releaseFailure);
        Cell c = rows.get(componentId);
        if (c == null || !token.equals(c.holder)) return 0;
        c.holder = null;
        return 1;
    }

    @Override
    public <T> T inOneTransaction(Function<Locked, T> work) {
        return work.apply(locked);
    }
}
