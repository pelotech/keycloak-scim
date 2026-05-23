package sh.libre.scim.core;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class OAuthClientCredentialsTokenSource {

    public interface TokenMinter {
        MintResult mint(OAuthConfig config);
    }

    public record MintResult(String authorizationHeader, long expiresInSeconds) {}

    private static final int EXPIRY_SKEW_SECONDS = 30;

    // JVM-lifetime cache keyed by componentId. Entries are removed only via
    // invalidate() — no eviction on component delete is hooked up at this
    // layer; lifecycle hooks in ScimStorageProviderFactory (Chunk 2 of the
    // plan) call invalidate(componentId) on onUpdate/preRemove to keep the
    // cache consistent with the configured set of components.
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static volatile Clock clock = Clock.systemUTC();

    private final String componentId;
    private final OAuthConfig config;
    private final TokenMinter minter;

    public OAuthClientCredentialsTokenSource(String componentId, OAuthConfig config, TokenMinter minter) {
        this.componentId = componentId;
        this.config = config;
        this.minter = minter;
    }

    public String currentAuthorizationHeader() {
        Entry entry = CACHE.get(componentId);
        if (entry != null && entry.authorizationHeader != null && clock.instant().isBefore(entry.refreshAt)) {
            return entry.authorizationHeader;
        }
        return mintAndStore();
    }

    private String mintAndStore() {
        Entry entry = CACHE.computeIfAbsent(componentId,
            k -> new Entry(null, Instant.EPOCH, new ReentrantLock()));
        entry.lock.lock();
        try {
            Entry current = CACHE.get(componentId);
            if (current != null && current.authorizationHeader != null
                && clock.instant().isBefore(current.refreshAt)) {
                return current.authorizationHeader;
            }
            MintResult r = minter.mint(config);
            Instant refreshAt = clock.instant().plusSeconds(
                Math.max(0, r.expiresInSeconds() - EXPIRY_SKEW_SECONDS));
            // Reuse entry.lock so concurrent mints serialize on the same lock,
            // even after the entry is replaced in the cache.
            Entry fresh = new Entry(r.authorizationHeader(), refreshAt, entry.lock);
            CACHE.put(componentId, fresh);
            return r.authorizationHeader();
        } finally {
            entry.lock.unlock();
        }
    }

    public void invalidate() { CACHE.remove(componentId); }

    /**
     * Drop the cached token for the given componentId. If a mint is in-flight
     * on another thread when invalidate() is called, that mint will complete
     * and its result will be discarded; a subsequent caller will mint again.
     * This means invalidate() may transiently produce two concurrent mints.
     * Acceptable: invalidate() is the "force-refresh after 401" path; an extra
     * mint is cheaper than introducing a synchronization point that blocks
     * SCIM dispatchers during refresh.
     */
    public static void invalidate(String componentId) { CACHE.remove(componentId); }

    static void resetCacheForTests() {
        CACHE.clear();
        clock = Clock.systemUTC();
    }
    static void setClockForTests(Clock c) { clock = c; }

    private record Entry(String authorizationHeader, Instant refreshAt, ReentrantLock lock) {}
}
