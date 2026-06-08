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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resilience-tier scenarios: outbound auth and retry behavior.
 *
 * <p>Auth: verifies BEARER token plumbing end-to-end (the production
 * default; existing tests use NONE).
 *
 * <p>Retry: pins what {@code ScimClient}'s resilience4j retry actually
 * does. It retries on {@code ProcessingException} (network-layer
 * failures) and, via a result predicate, on transient HTTP error
 * responses — {@code 429} and any {@code 5xx} (see
 * {@code ScimClient#isRetryableStatus}). Both paths are covered:
 * connection-fault recovery and server-error/rate-limit recovery.
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
    void serverErrorIsRetriedAndEventuallySucceeds() {
        // A transient 429 (rate-limit) then a 503 (server error), then a
        // clean 201. The resilience4j result predicate retries on both
        // 429 and 5xx (ScimClient#isRetryableStatus), so the create
        // eventually succeeds. Serving one of each status exercises both
        // arms of the predicate end-to-end in a single scenario.
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .inScenario("server-error-retry")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"detail\":\"slow down\"}"))
            .willSetStateTo("rate-limited-done"));

        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .inScenario("server-error-retry")
            .whenScenarioStateIs("rate-limited-done")
            .willReturn(aResponse()
                .withStatus(503)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"detail\":\"transient backend failure\"}"))
            .willSetStateTo("server-error-done"));

        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .inScenario("server-error-retry")
            .whenScenarioStateIs("server-error-done")
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

        createAdminUser(r.realm(), "retry-5xx", "retry-5xx@test.local");

        // Three attempts: 429, 503, then 201. Allow time for the
        // 500ms + 750ms backoff intervals plus container processing.
        await().atMost(20, SECONDS).untilAsserted(() -> {
            int attempts = wireMock.countRequestsMatching(
                postRequestedFor(urlPathEqualTo("/Users")).build()
            ).getCount();
            assertTrue(attempts >= 3,
                "expected at least 3 attempts after a 429 and a 503 retry, got " + attempts);
        });
    }
}
