package sh.libre.scim.perf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.Callable;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.awaitility.Awaitility.await;

/**
 * Latency-swept characterization of SCIM {@code /Bulk}'s payoff: a matrix of
 * <b>bulk {on, off} × sink latency {fast 5ms, medium 50ms, slow 200ms}</b> at a
 * fixed cohort size {@code N = -Dperf.userCount} (default 2000). Each cell seeds
 * N LDAP users, triggers one full federation sync, and times the create-storm
 * end-to-end (sync trigger + async drain) while sampling the Keycloak container's
 * memory.
 *
 * <p>For every cell we record, via {@link PerfReport} notes and a printed
 * {@code [perf]} line:
 * <ul>
 *   <li>{@code bulkEnabled} — which lane the realm used.</li>
 *   <li>{@code sinkDelayMs} — the WireMock per-request fixed delay.</li>
 *   <li>{@code httpRequests} — POSTs the sink actually received
 *       ({@code /Bulk} for bulk-on, {@code /Users} for bulk-off).</li>
 *   <li><b>{@code requestRatio}</b> — N / httpRequests. Bulk-off ≈ 1 (one POST
 *       per user); bulk-on ≈ K (≈ N/⌈N/K⌉). This is the load-bearing number:
 *       it PROVES batching engaged and separates "fewer requests" from "shorter
 *       wall-time".</li>
 *   <li>{@code wallSeconds} — drain wall-clock.</li>
 *   <li>{@code drainRatePerSec} — N / wallSeconds.</li>
 *   <li>{@code peakMemMiB} — peak container RSS during the run.</li>
 * </ul>
 *
 * <h2>Honesty caveat (what this measures)</h2>
 * WireMock applies a per-REQUEST fixed delay only (the round-trip component) and
 * models NO per-op server processing cost. So this IT measures bulk's
 * <b>round-trip amortization only</b> — saving (K−1) round-trips per batch of K.
 * It is a <b>lower bound</b> on real-world benefit: a real SCIM server also
 * amortizes per-request parse/auth/dispatch/framework overhead this harness can't
 * represent. The numbers read "at least this much," not "exactly this much."
 *
 * <h2>K-sensitivity (analytic)</h2>
 * {@code scim.dispatch.bulkBatchSize} (K) is read in the Keycloak <em>container</em>
 * JVM, and the shared container starts once, so K cannot be varied per-cell here.
 * The K effect is therefore reported analytically: bulk request count scales as
 * ⌈N/K⌉, so the measured {@code requestRatio} (≈ K = 20) demonstrates the
 * mechanism; a dedicated container with {@code JAVA_OPTS_APPEND=-Dscim.dispatch.bulkBatchSize=<k>}
 * could measure other K values later.
 *
 * <p>Run with {@code ./gradlew performanceTest --tests
 * 'sh.libre.scim.perf.BulkLatencySweepIT'}. Several minutes; the slow-off cell
 * (N×d / 8 workers ≈ 2000×0.2/8 ≈ 50s) dominates.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BulkLatencySweepIT extends PerfTestBase {

    private static final int N = Integer.getInteger("perf.userCount", 2000);

    /** Container-side default of scim.dispatch.bulkBatchSize; used only to size
     *  the expected-batch await threshold, not to assert exact K. */
    private static final int K = 20;

    private static final PerfReport report = new PerfReport("BulkLatencySweepIT");

    private final java.util.List<String> seeded = new java.util.ArrayList<>();
    private int cell = 0;

    @AfterEach
    void cleanup() {
        if (!seeded.isEmpty()) {
            cleanupLdapEntries(seeded.stream().map(PerfTestBase::ldapUserDn).toList());
            seeded.clear();
        }
        try {
            report.write();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------- bulk ON ----------

    @Test
    @Order(1)
    void bulkOn_fast5ms() throws Exception {
        runBulkOn(5);
    }

    @Test
    @Order(2)
    void bulkOn_medium50ms() throws Exception {
        runBulkOn(50);
    }

    @Test
    @Order(3)
    void bulkOn_slow200ms() throws Exception {
        runBulkOn(200);
    }

    // ---------- bulk OFF ----------

    @Test
    @Order(4)
    void bulkOff_fast5ms() throws Exception {
        runBulkOff(5);
    }

    @Test
    @Order(5)
    void bulkOff_medium50ms() throws Exception {
        runBulkOff(50);
    }

    @Test
    @Order(6)
    void bulkOff_slow200ms() throws Exception {
        runBulkOff(200);
    }

    // ---------- cell bodies ----------

    private void runBulkOn(int delayMs) throws Exception {
        stubScimBulkOk(delayMs);
        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("bulk-enabled", "true"));
        String prefix = "perfbon" + delayMs + "x";
        seeded.addAll(seedLdapUsers(prefix, N));

        // Expected batches ≈ ⌈N/K⌉. Await that floor, then settle, so a final
        // partial batch / timing flush isn't missed.
        int expectedBatchesFloor = N / K; // conservative floor (ignores partial)
        runCell("bulk-on", true, delayMs, r,
            () -> bulkPostCount() >= expectedBatchesFloor,
            this::awaitBulkStable,
            this::bulkPostCount);
    }

    private void runBulkOff(int delayMs) throws Exception {
        stubScimUserCreateOkDelayed(delayMs);
        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("bulk-enabled", "false"));
        String prefix = "perfboff" + delayMs + "x";
        seeded.addAll(seedLdapUsers(prefix, N));

        runCell("bulk-off", false, delayMs, r,
            () -> perUserPostCount() >= N,
            () -> { /* per-op count is exact at N; no settle needed */ },
            this::perUserPostCount);
    }

    /**
     * Shared cell driver: start the memory sampler, trigger the full sync, await
     * the drain witness, settle, stop the sampler, and record + print the row.
     *
     * @param drainReached  predicate that turns true once the witnessed request
     *                      count has reached its expected floor
     * @param settle        extra wait after the floor is reached (bulk needs a
     *                      settle window for the trailing partial batch)
     * @param requestCount  supplier of the final witnessed HTTP request count
     */
    private void runCell(String label, boolean bulkEnabled, int delayMs, TestRealm r,
                         Callable<Boolean> drainReached, Runnable settle,
                         java.util.function.IntSupplier requestCount) throws Exception {
        cell++;
        String cellLabel = label + "-" + delayMs + "ms";

        var sampler = new ContainerMemorySampler(keycloak);
        var notes = new LinkedHashMap<String, String>();
        notes.put("bulkEnabled", String.valueOf(bulkEnabled));
        notes.put("sinkDelayMs", String.valueOf(delayMs));
        notes.put("users", String.valueOf(N));

        sampler.start();
        long t0 = System.nanoTime();
        report.timedWithNotes("bulk-sweep", cellLabel, N, notes, () -> {
            r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
            await().atMost(15, MINUTES).pollInterval(250, MILLISECONDS).until(drainReached);
            return null;
        });
        settle.run();
        double wallSeconds = (System.nanoTime() - t0) / 1_000_000_000.0;
        sampler.stop();

        int httpRequests = requestCount.getAsInt();
        double requestRatio = httpRequests == 0 ? 0 : (double) N / httpRequests;
        double drainRate = wallSeconds == 0 ? 0 : N / wallSeconds;

        notes.put("httpRequests", String.valueOf(httpRequests));
        notes.put("requestRatio", String.format(Locale.ROOT, "%.2f", requestRatio));
        notes.put("wallSeconds", String.format(Locale.ROOT, "%.1f", wallSeconds));
        notes.put("drainRatePerSec", String.format(Locale.ROOT, "%.1f", drainRate));
        notes.put("peakMemMiB", String.valueOf(sampler.maxMiB()));
        notes.put("memCurveMiB", sampler.curveMiB());

        System.out.println(String.format(Locale.ROOT,
            "[perf] %s: bulkEnabled=%s sinkDelayMs=%d N=%d httpRequests=%d "
                + "requestRatio=%.2f wallSeconds=%.1f drainRatePerSec=%.1f peakMemMiB=%d",
            cellLabel, bulkEnabled, delayMs, N, httpRequests,
            requestRatio, wallSeconds, drainRate, sampler.maxMiB()));
        System.out.println("[perf] " + cellLabel + " mem " + sampler.summary());
    }

    /**
     * Settle wait for the bulk lane: block until the {@code POST /Bulk} count has
     * stopped changing, so a trailing partial batch (the last &lt;K ops flushed
     * on a timer) is counted before we read {@code httpRequests}.
     */
    private void awaitBulkStable() {
        await().atMost(60, java.util.concurrent.TimeUnit.SECONDS)
            .pollInterval(500, MILLISECONDS)
            .until(new Callable<Boolean>() {
                private int last = -1;
                private int stable = 0;

                @Override
                public Boolean call() {
                    int now = bulkPostCount();
                    if (now == last) {
                        stable++;
                    } else {
                        stable = 0;
                        last = now;
                    }
                    return now > 0 && stable >= 8; // ~4s of no change
                }
            });
    }

    /**
     * Stubs {@code POST /Users} with a fixed per-request delay (bulk-off lane).
     * Each user create is its own request, so the delay is paid once per user
     * (modulo the worker pool's concurrency) — the deliberate contrast to the
     * bulk lane, where one delay covers K ops.
     */
    private void stubScimUserCreateOkDelayed(int delayMs) {
        var response = com.github.tomakehurst.wiremock.client.WireMock.aResponse()
            .withStatus(201)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("""
                {
                  "id": "%s",
                  "userName": "placeholder",
                  "displayName": "placeholder",
                  "active": true,
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                }""".formatted(java.util.UUID.randomUUID()));
        if (delayMs > 0) {
            response = response.withFixedDelay(delayMs);
        }
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock
            .post(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/Users"))
            .willReturn(response));
    }
}
