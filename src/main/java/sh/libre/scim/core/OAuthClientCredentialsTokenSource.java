package sh.libre.scim.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.util.EntityUtils;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
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
    private static final ObjectMapper TOKEN_RESPONSE_MAPPER = new ObjectMapper();

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
            root = TOKEN_RESPONSE_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("token response not valid JSON", e);
        }
        JsonNode at = root.get("access_token");
        if (at == null || at.asText().isBlank()) {
            throw new IllegalStateException("token response missing access_token");
        }
        String tokenType = root.hasNonNull("token_type") ? root.get("token_type").asText() : "Bearer";
        JsonNode expiresInNode = root.get("expires_in");
        boolean hadExpiresIn = expiresInNode != null && !expiresInNode.isNull() && expiresInNode.canConvertToLong();
        long expiresIn = hadExpiresIn ? expiresInNode.asLong() : 60L;
        return new ParsedToken(new MintResult(tokenType + " " + at.asText(), expiresIn), hadExpiresIn);
    }

    /**
     * Emits a WARN log at most once per componentId when the token endpoint omits
     * {@code expires_in}. Subsequent requests for the same componentId are silent.
     * Called by {@code HttpTokenMinter} after invoking {@link #parseTokenResponse}.
     */
    static void maybeWarnNoExpiresIn(String componentId, boolean hadExpiresIn) {
        if (!hadExpiresIn && WARNED_NO_EXPIRES_IN.add(componentId)) {
            LOG.warnf("OAuth token endpoint did not return expires_in for component %s; defaulting to 60s",
                componentId);
        }
    }

    /** Produces OAuth {@code client_credentials} tokens via HTTP. */
    public static final class HttpTokenMinter implements TokenMinter {
        private final String componentId;

        public HttpTokenMinter(String componentId) {
            this.componentId = componentId;
        }

        @Override
        public MintResult mint(OAuthConfig cfg) {
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpPost post = new HttpPost(cfg.tokenEndpoint());
                post.setHeader("Authorization", basicAuthHeader(cfg.clientId(), cfg.clientSecret()));
                post.setHeader("Content-Type", "application/x-www-form-urlencoded");
                post.setHeader("Accept", "application/json");
                post.setEntity(new StringEntity(formBody(cfg.scope()), StandardCharsets.UTF_8));

                try (CloseableHttpResponse resp = client.execute(post)) {
                    int status = resp.getStatusLine().getStatusCode();
                    HttpEntity entity = resp.getEntity();
                    String body = entity == null
                        ? ""
                        : EntityUtils.toString(entity, StandardCharsets.UTF_8);
                    if (status < 200 || status >= 300) {
                        String truncated = truncate(body, 200);
                        LOG.errorf("OAuth token endpoint returned %d for component %s: %s",
                            status, componentId, truncated);
                        throw new RuntimeException(
                            "token endpoint returned " + status + ": " + truncated);
                    }
                    ParsedToken parsed = parseTokenResponse(body);
                    maybeWarnNoExpiresIn(componentId, parsed.hadExpiresIn());
                    LOG.debugf("OAuth token minted for component %s (expires_in=%ds)",
                        componentId, parsed.result().expiresInSeconds());
                    return parsed.result();
                }
            } catch (IOException e) {
                throw new RuntimeException("token endpoint request failed: " + e.getMessage(), e);
            }
        }

        static String basicAuthHeader(String clientId, String clientSecret) {
            // URL-encode per RFC 6749 §2.3.1 — Java's URLEncoder uses
            // application/x-www-form-urlencoded (space → "+"), which is what the RFC requires.
            String encodedId = java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8);
            String encodedSecret = java.net.URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
            String creds = encodedId + ":" + encodedSecret;
            return "Basic " + java.util.Base64.getEncoder()
                .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
        }

        static String formBody(String scope) {
            if (scope == null || scope.isBlank()) {
                return "grant_type=client_credentials";
            }
            return "grant_type=client_credentials&scope="
                + java.net.URLEncoder.encode(scope, StandardCharsets.UTF_8);
        }

        private static String truncate(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max) + "…";
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
