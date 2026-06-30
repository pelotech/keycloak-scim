package sh.libre.scim.integration;

import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for {@code sync-on-error=auto} batch-sync behavior.
 *
 * <p>{@code ImportSynchronization} drives {@code ScimClient.refreshResources}, which PUTs each
 * mapped user. Under {@code auto}, a transient 503 aborts the whole run — skipping the remaining
 * records would be pointless against an unreachable endpoint.
 *
 * <p>Seeds two mapped users, makes the PUT endpoint return 503, triggers sync, and asserts
 * only ONE user was written (the run aborted on the first transient failure).
 */
class ScimSyncStopOnErrorIT extends IntegrationTestBase {

    /** PUT /Users/* → HTTP 503. */
    private void stubScimUserUpdate503() {
        wireMock.stubFor(put(urlMatching("/Users/.*"))
            .willReturn(aResponse()
                .withStatus(503)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"detail\":\"service unavailable\"}")));
    }

    /** Distinct userName values seen across all PUT /Users/* request bodies. */
    private Set<String> distinctPutUserNames() {
        return wireMock.getAllServeEvents().stream()
            .filter(e -> "PUT".equals(e.getRequest().getMethod().getName())
                && e.getRequest().getUrl().startsWith("/Users/"))
            .map(this::userNameOf)
            .filter(n -> !n.isEmpty())
            .collect(Collectors.toSet());
    }

    /** Crude extraction of the "userName":"..." value from a SCIM request body. */
    private String userNameOf(ServeEvent e) {
        String body = e.getRequest().getBodyAsString();
        int i = body.indexOf("\"userName\"");
        if (i < 0) return "";
        int colon = body.indexOf(':', i);
        int firstQuote = body.indexOf('"', colon + 1);
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) return "";
        return body.substring(firstQuote + 1, secondQuote);
    }

    @Test
    void autoStopsRunOnFirstTransientFailure() {
        // Phase 1: create two users with the endpoint healthy so each gets a SCIM mapping.
        // The mapping is what makes refreshResources later issue PUT (replace) instead of POST.
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("sync-on-error", "auto");
        });
        enableScimEventListener(r.realm());

        createAdminUser(r.realm(), "stop-a", "stop-a@test.local");
        createAdminUser(r.realm(), "stop-b", "stop-b@test.local");
        awaitUserPostFor("stop-a");
        awaitUserPostFor("stop-b");

        var scimComponentId = r.realm().components()
            .query(null, "org.keycloak.storage.UserStorageProvider")
            .stream()
            .filter(c -> "scim".equals(c.getProviderId()))
            .findFirst().orElseThrow().getId();

        // Phase 2: make the refresh PUT endpoint fail. Reset stubs and journal so
        // PUT counts below measure only sync traffic, not phase-1 setup traffic.
        wireMock.resetAll();
        stubScimUserUpdate503();

        // sync-refresh=true causes refreshResources to PUT each mapped user.
        // The first PUT hits 503; after resilience4j exhausts its retry budget
        // the transient exception escapes and sync-on-error=auto aborts the run.
        r.realm().userStorage().syncUsers(scimComponentId, "triggerFullSync");

        // Wait for at least one PUT, then settle briefly before asserting
        // exactly one user was written (the run aborted on the first transient failure).
        await().atMost(90, SECONDS).untilAsserted(() ->
            assertTrue(distinctPutUserNames().size() >= 1,
                "expected the sync to attempt at least one PUT, got "
                    + distinctPutUserNames()));
        sleepQuietly(3);

        Set<String> touched = distinctPutUserNames();
        assertEquals(1, touched.size(),
            "sync-on-error=auto must STOP the run on the first transient failure: "
                + "only one of the two users should have been written, but PUTs targeted "
                + touched);
    }
}
