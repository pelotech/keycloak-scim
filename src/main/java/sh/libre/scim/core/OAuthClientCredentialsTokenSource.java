package sh.libre.scim.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

public final class OAuthClientCredentialsTokenSource {

    private static final Logger LOG = Logger.getLogger(OAuthClientCredentialsTokenSource.class);

    public interface TokenMinter {
        MintResult mint(OAuthConfig config);
    }

    public record MintResult(String authorizationHeader, long expiresInSeconds) {}

    /** Package-private; carries parser output including whether expires_in was present. */
    record ParsedToken(MintResult result, boolean hadExpiresIn) {}

    private static final int EXPIRY_SKEW_SECONDS = 30;

    // JVM-lifetime cache keyed by componentId. Entries are removed only via
    // invalidate() — no eviction on component delete is hooked up at this
    // layer; lifecycle hooks in ScimStorageProviderFactory (Chunk 2 of the
    // plan) call invalidate(componentId) on onUpdate/preRemove to keep the
    // cache consistent with the configured set of components.
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> WARNED_NO_EXPIRES_IN = ConcurrentHashMap.newKeySet();
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

    /**
     * Parses a token-endpoint JSON response body into a {@link ParsedToken}.
     *
     * <ul>
     *   <li>Throws {@link IllegalStateException} if the body is not valid JSON.</li>
     *   <li>Throws {@link IllegalStateException} if {@code access_token} is missing or blank.</li>
     *   <li>Defaults {@code token_type} to {@code "Bearer"} if absent.</li>
     *   <li>Defaults {@code expires_in} to {@code 60} seconds if absent; {@code hadExpiresIn} in
     *       the result will be {@code false} so callers can emit a one-time warning.</li>
     * </ul>
     */
    static ParsedToken parseTokenResponse(String body) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("token response not valid JSON", e);
        }
        JsonNode at = root.get("access_token");
        if (at == null || at.asText().isBlank()) {
            throw new IllegalStateException("token response missing access_token");
        }
        String tokenType = root.hasNonNull("token_type") ? root.get("token_type").asText() : "Bearer";
        boolean hadExpiresIn = root.hasNonNull("expires_in");
        long expiresIn = hadExpiresIn ? root.get("expires_in").asLong() : 60L;
        return new ParsedToken(new MintResult(tokenType + " " + at.asText(), expiresIn), hadExpiresIn);
    }

    /**
     * Emits a WARN log at most once per componentId when the token endpoint omits
     * {@code expires_in}. Subsequent requests for the same componentId are silent.
     * Called by {@code HttpTokenMinter} (Task 8) after invoking {@link #parseTokenResponse}.
     */
    void maybeWarnNoExpiresIn(boolean hadExpiresIn) {
        if (!hadExpiresIn && WARNED_NO_EXPIRES_IN.add(componentId)) {
            LOG.warnf("OAuth token endpoint did not return expires_in for component %s; defaulting to 60s",
                componentId);
        }
    }

    static void resetCacheForTests() {
        CACHE.clear();
        WARNED_NO_EXPIRES_IN.clear();
        clock = Clock.systemUTC();
    }
    static void setClockForTests(Clock c) { clock = c; }

    private record Entry(String authorizationHeader, Instant refreshAt, ReentrantLock lock) {}
}
