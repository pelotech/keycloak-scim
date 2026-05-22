package sh.libre.scim.core;

import java.time.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class OAuthClientCredentialsTokenSource {

    public interface TokenMinter {
        MintResult mint(OAuthConfig config);
    }

    public record MintResult(String authorizationHeader, long expiresInSeconds) {}

    private static final int EXPIRY_SKEW_SECONDS = 30;
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
            Entry fresh = new Entry(r.authorizationHeader(), refreshAt, entry.lock);
            CACHE.put(componentId, fresh);
            return r.authorizationHeader();
        } finally {
            entry.lock.unlock();
        }
    }

    public void invalidate() { CACHE.remove(componentId); }
    public static void invalidate(String componentId) { CACHE.remove(componentId); }

    static void resetCacheForTests() { CACHE.clear(); }
    static void setClockForTests(Clock c) { clock = c; }

    private record Entry(String authorizationHeader, Instant refreshAt, ReentrantLock lock) {}
}
