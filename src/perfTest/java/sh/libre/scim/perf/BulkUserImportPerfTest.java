package sh.libre.scim.perf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

/**
 * Baseline scale measurements for the user-import path. Seeds N users into
 * LDAP, exercises Keycloak's federation sync, and reports throughput.
 *
 * <p>Tests are sized to fit a developer's patience (default 1000 users for
 * a sub-minute run) but the constants below can be lifted to 10000+ for a
 * full-scale measurement. The harness writes a markdown report with all
 * scenarios run in this JVM to {@code build/reports/perf/}.
 *
 * <p>Run with {@code ./gradlew performanceTest}. Not part of {@code check}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BulkUserImportPerfTest extends PerfTestBase {

    /**
     * Number of users to seed for each scenario. Lift to 10_000 for a real
     * scale-test pass; the default 1_000 keeps a single run under ~60 s
     * locally and gives meaningful throughput numbers.
     */
    private static final int USER_COUNT = Integer.getInteger("perf.userCount", 1000);

    private static final PerfReport report = new PerfReport("BulkUserImportPerfTest");

    private final java.util.List<String> seeded = new java.util.ArrayList<>();

    @AfterEach
    void cleanupSeeded() throws Exception {
        // Pull the per-phase ScimClient timing breakdown from the Keycloak
        // container before cleaning up — the metrics are useful even when
        // the test has already passed.
        try {
            var http = java.net.http.HttpClient.newHttpClient();
            // Any realm route works; the metrics endpoint is global.
            var resp = http.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                    keycloak.getAuthServerUrl() + "/realms/master/scim-reconcile/metrics"))
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            System.out.println("[perf] " + resp.body());
            // Reset between scenarios so each test's metrics are isolated.
            http.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                    keycloak.getAuthServerUrl() + "/realms/master/scim-reconcile/metrics/reset"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.out.println("[perf] metrics fetch failed: " + e.getMessage());
        }

        if (!seeded.isEmpty()) {
            cleanupLdapEntries(seeded.stream().map(PerfTestBase::ldapUserDn).toList());
            seeded.clear();
        }
        try { report.write(); } catch (Exception e) { e.printStackTrace(); }
    }

    /**
     * triggerFullSync over a freshly-seeded N-user LDAP. This is the primary
     * "all users at once" path — the worst-case scenario for the per-user
     * cost of {@code onImportUserFromLDAP}: ScimClient construction, JPA
     * lookup, HTTP POST, mapping persist.
     *
     * <p>SCIM sink stub returns 201 immediately with no artificial latency,
     * so the measured time isolates plugin + Keycloak overhead from real
     * network round-trip cost.
     */
    @Test
    @Order(1)
    void triggerFullSync_baselineThroughput() throws Exception {
        var counter = stubFastUserCreate();
        var r = newRealmWithScimAndLdap();

        seeded.addAll(seedLdapUsers("perfu", USER_COUNT));

        var notes = new LinkedHashMap<String, String>();
        notes.put("seedCount", String.valueOf(USER_COUNT));
        notes.put("scimSinkLatency", "0ms");

        var sample = report.timedWithNotes(
            "triggerFullSync",
            "fresh-import-N-users",
            USER_COUNT,
            notes,
            () -> {
                r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
                // triggerFullSync returns when Keycloak's iteration completes,
                // but our SCIM POSTs may still be in flight (event listener
                // path is async-ish in some configurations). Wait until
                // WireMock has seen the expected count, capped generously.
                await().atMost(10, java.util.concurrent.TimeUnit.MINUTES)
                    .until(() -> counter.get() >= USER_COUNT);
                return null;
            }
        );

        notes.put("postsObserved", String.valueOf(counter.get()));
        System.out.println("[perf] triggerFullSync baseline: "
            + USER_COUNT + " users in " + sample.duration().toMillis()
            + " ms (" + String.format("%.1f", sample.itemsPerSecond()) + " users/sec)");
    }

    /**
     * Pure Keycloak baseline: LDAP federation sync of N users with NO
     * SCIM plugin attached (no scim provider, no scim-ldap-sync mapper).
     * Measures Keycloak's per-user federation-import cost in isolation,
     * so subsequent scenarios' "with plugin" times can be expressed as
     * an overhead delta rather than absolute numbers.
     */
    @Test
    @Order(0)
    void triggerFullSync_keycloakAloneBaseline() throws Exception {
        var r = newRealmWithLdapOnly();

        seeded.addAll(seedLdapUsers("perfk", USER_COUNT));

        var notes = new LinkedHashMap<String, String>();
        notes.put("seedCount", String.valueOf(USER_COUNT));
        notes.put("plugin", "absent");

        var sample = report.timedWithNotes(
            "triggerFullSync",
            "no-plugin-N-users",
            USER_COUNT,
            notes,
            () -> {
                r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
                // No SCIM dispatch; sync is fully synchronous from
                // Keycloak's perspective.
                return null;
            }
        );

        System.out.println("[perf] triggerFullSync (no plugin): "
            + USER_COUNT + " users in " + sample.duration().toMillis()
            + " ms (" + String.format("%.1f", sample.itemsPerSecond()) + " users/sec)");
    }

    /**
     * Lazy-import N users one at a time via admin REST search-by-username.
     * Compares against triggerFullSync to surface whether bulk-mode has any
     * advantage today (it largely doesn't, since each user still goes
     * through onImportUserFromLDAP independently).
     */
    @Test
    @Order(2)
    void lazyImport_baselineThroughput() throws Exception {
        var counter = stubFastUserCreate();
        var r = newRealmWithScimAndLdap();

        seeded.addAll(seedLdapUsers("perfl", USER_COUNT));

        var notes = new LinkedHashMap<String, String>();
        notes.put("seedCount", String.valueOf(USER_COUNT));
        notes.put("trigger", "users().search()");

        var sample = report.timedWithNotes(
            "lazyImport",
            "search-by-username-N-users",
            USER_COUNT,
            notes,
            () -> {
                IntStream.range(0, USER_COUNT).forEach(i ->
                    r.realm().users().search("perfl" + i, 0, 1));
                await().atMost(10, java.util.concurrent.TimeUnit.MINUTES)
                    .until(() -> counter.get() >= USER_COUNT);
                return null;
            }
        );

        notes.put("postsObserved", String.valueOf(counter.get()));
        System.out.println("[perf] lazyImport baseline: "
            + USER_COUNT + " users in " + sample.duration().toMillis()
            + " ms (" + String.format("%.1f", sample.itemsPerSecond()) + " users/sec)");
    }

    /**
     * Stubs POST /Users to return a unique 201 each time, with a counter
     * recording invocations. Returned counter increments on each successful
     * response; await() polls it to know when the under-test work is done.
     */
    private AtomicInteger stubFastUserCreate() {
        var counter = new AtomicInteger();
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
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

        // WireMock's RequestListener fires for every received request, giving
        // us a non-blocking counter independent of stub-state machinery.
        wireMock.addMockServiceRequestListener((request, response) -> {
            if (request.getUrl().equals("/Users") && "POST".equals(request.getMethod().getName())) {
                counter.incrementAndGet();
            }
        });
        return counter;
    }
}
