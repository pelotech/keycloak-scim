package sh.libre.scim.integration;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resilience-tier scenarios: outbound auth and retry behavior.
 *
 * <p>Auth: verifies BEARER token plumbing end-to-end (the production
 * default; existing tests use NONE).
 *
 * <p>Retry: pins what {@code ScimClient}'s resilience4j retry actually
 * does. Today it retries on {@code ProcessingException} (network-layer
 * failures) but does NOT retry on HTTP error responses. Both behaviors
 * are tested, including the 5xx no-retry case as a deliberate
 * gap-pinning assertion — if someone widens the retry policy to
 * include server errors, that test will turn red and prompt the
 * accompanying behavioral update.
 */
class ScimResilienceIT extends IntegrationTestBase {

    @Test
    void bearerTokenAppearsOnScimRequests() {
        String token = "secret-token-" + UUID.randomUUID();
        stubScimUserCreateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("auth-mode", "BEARER");
            cfg.putSingle("auth-pass", token);
        });
        enableScimEventListener(r.realm());

        createAdminUser(r.realm(), "bearer-test", "bearer-test@test.local");

        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(postRequestedFor(urlPathEqualTo("/Users"))
                .withHeader("Authorization", equalTo("Bearer " + token)))
        );
    }

    @Test
    void retryOnConnectionFaultEventuallySucceeds() {
        // Two consecutive connection-reset faults, then a clean 201.
        // resilience4j's default retry config retries up to 10 times on
        // ProcessingException with exponential backoff starting at 500ms.
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .inScenario("connection-fault-retry")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
            .willSetStateTo("fault-1-done"));

        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .inScenario("connection-fault-retry")
            .whenScenarioStateIs("fault-1-done")
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
            .willSetStateTo("fault-2-done"));

        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .inScenario("connection-fault-retry")
            .whenScenarioStateIs("fault-2-done")
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "%s",
                      "userName": "placeholder",
                      "displayName": "placeholder",
                      "active": true,
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""".formatted(UUID.randomUUID()))));

        var r = newRealmWithScimAndLdap();
        enableScimEventListener(r.realm());

        createAdminUser(r.realm(), "retry-fault", "retry-fault@test.local");

        // Three attempts: two faulted, one succeeded. Allow time for the
        // 500ms + 750ms backoff intervals plus container processing.
        await().atMost(20, SECONDS).untilAsserted(() -> {
            int attempts = wireMock.countRequestsMatching(
                postRequestedFor(urlPathEqualTo("/Users")).build()
            ).getCount();
            assertTrue(attempts >= 3,
                "expected at least 3 attempts after two connection-fault retries, got " + attempts);
        });
    }

    @Test
    void serverErrorIsNotRetriedGap() {
        // resilience4j is configured to retry only on ProcessingException
        // (network-layer faults), not on HTTP error responses. A 503 from
        // the SCIM sink results in a single attempt — no retry.
        //
        // This is a documented gap in the existing implementation: most
        // operators would expect 5xx responses to retry. If the retry
        // policy is widened to include error responses (RetryConfig with
        // .retryOnResult(...)), this assertion will fail. Update the
        // test to count >= 2 and update docs accordingly.
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse()
                .withStatus(503)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"detail\":\"transient backend failure\"}")));

        var r = newRealmWithScimAndLdap();
        enableScimEventListener(r.realm());

        createAdminUser(r.realm(), "retry-503", "retry-503@test.local");

        // Wait long enough that any retry would have happened. Default
        // backoff: 500ms, 750ms, 1125ms, ... — 5 seconds covers several.
        sleepQuietly(5);

        int attempts = wireMock.countRequestsMatching(
            postRequestedFor(urlPathEqualTo("/Users")).build()
        ).getCount();
        assertEquals(1, attempts,
            "gap pinned: 5xx responses currently do not trigger retry. "
            + "If this fails with attempts > 1, retry policy has been "
            + "widened — update the test and ScimClient docs accordingly.");
    }
}
