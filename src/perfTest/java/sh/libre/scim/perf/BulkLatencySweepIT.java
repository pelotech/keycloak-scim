package sh.libre.scim.perf;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.awaitility.Awaitility.await;

/**
 * Latency-swept characterization of SCIM {@code /Bulk}'s payoff: a matrix of
 * <b>bulk {on, off} × sink latency {fast 5ms, medium 50ms, slow 200ms}</b> at a
 * fixed cohort size {@code N = -Dperf.userCount} (default 2000). Each cell is
 * run {@code K = -Dperf.repeat} times (default 5) so memory and wall-time are
 * reported as <b>mean ± spread</b> rather than a single noisy sample.
 *
 * <h2>Experimental design (why each repeat is structured the way it is)</h2>
 * <ul>
 *   <li><b>Real work every repeat.</b> The create path skips already-mapped
 *       users ({@code findById}), so a second sync against the same realm does
 *       zero creates. Each repeat therefore runs against a <b>fresh realm</b>
 *       (fresh SCIM component → fresh mappings → full N creates). The N LDAP
 *       users are seeded <b>once</b> and reused across every repeat and cell: a
 *       fresh realm re-imports the same LDAP users into a new local realm,
 *       producing N fresh creates each time.</li>
 *   <li><b>Stable Keycloak baseline.</b> Each realm is <b>deleted after
 *       measuring it</b> ({@code admin.realm(name).remove()} cascades users +
 *       components + SCIM mappings), so Keycloak's footprint does not drift
 *       run-to-run and confound later repeats.</li>
 *   <li><b>Baseline-corrected memory delta.</b> Per repeat we sample a quiescent
 *       container-memory baseline just before triggering the sync (after a short
 *       settle), and the peak during the sync + drain. We record {@code peakMiB},
 *       {@code baselineMiB}, and {@code peakDeltaMiB = peak − baseline}. The
 *       delta isolates the sync's memory contribution from Keycloak's (possibly
 *       drifting) absolute footprint and is the cleaner signal — we lead with
 *       it.</li>
 *   <li><b>Per-repeat request counters.</b> WireMock's request journal is reset
 *       ({@code resetRequests()}) before each repeat so each repeat's HTTP count
 *       is its own.</li>
 * </ul>
 *
 * <h2>Honesty caveat (what this measures)</h2>
 * WireMock applies a per-REQUEST fixed delay only (the round-trip component) and
 * models NO per-op server processing cost. So this IT measures bulk's
 * <b>round-trip amortization only</b> — saving (K−1) round-trips per batch of K.
 * It is a <b>lower bound</b> on real-world benefit.
 *
 * <p>Run with {@code ./gradlew performanceTest --tests
 * 'sh.libre.scim.perf.BulkLatencySweepIT'}. Long: K×6 = 30 syncs; the slow-off
 * cell (N×d / 8 workers ≈ 2000×0.2/8 ≈ 50s each) dominates, so expect ~15-25 min.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BulkLatencySweepIT extends PerfTestBase {

    private static final int N = Integer.getInteger("perf.userCount", 2000);

    /** Repeats per cell. Each repeat is a fresh realm → full N creates. */
    private static final int K_REPEAT = Integer.getInteger("perf.repeat", 5);

    /** Container-side default of scim.dispatch.bulkBatchSize; used only to size
     *  the expected-batch await threshold, not to assert exact K. */
    private static final int K_BATCH = 20;

    /** Settle window (ms) before sampling the quiescent memory baseline. */
    private static final long BASELINE_SETTLE_MS = 3000;

    private static final PerfReport report = new PerfReport("BulkLatencySweepIT");

    /** Shared cohort of N LDAP users, seeded once and reused by every cell. */
    private static final List<String> SEEDED = new ArrayList<>();
    private static final String USER_PREFIX = "perfsweep";

    private int cell = 0;

    /** Seed the shared N LDAP users exactly once for the whole class. */
    private void ensureSeeded() throws Exception {
        if (SEEDED.isEmpty()) {
            SEEDED.addAll(seedLdapUsers(USER_PREFIX, N));
        }
    }

    @AfterAll
    static void cleanupLdap() {
        if (!SEEDED.isEmpty()) {
            // Static context: build a throwaway instance only to reach the
            // instance-level cleanup helper, which talks to the shared container.
            new BulkLatencySweepIT().cleanupLdapEntries(
                SEEDED.stream().map(PerfTestBase::ldapUserDn).toList());
            SEEDED.clear();
        }
    }

    @AfterEach
    void flushReport() {
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
        ensureSeeded();
        stubScimBulkOk(delayMs);
        // Expected batches ≈ ⌈N/K⌉. Await that floor, then settle, so a final
        // partial batch (the last drainTo of fewer than K ops) isn't missed.
        int expectedBatchesFloor = N / K_BATCH; // conservative floor (ignores partial)
        runCell("bulk-on", true, delayMs,
            () -> newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("bulk-enabled", "true")),
            () -> bulkPostCount() >= expectedBatchesFloor,
            this::awaitBulkStable,
            this::bulkPostCount);
    }

    private void runBulkOff(int delayMs) throws Exception {
        ensureSeeded();
        stubScimUserCreateOkDelayed(delayMs);
        runCell("bulk-off", false, delayMs,
            () -> newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("bulk-enabled", "false")),
            () -> perUserPostCount() >= N,
            () -> { /* per-op count is exact at N; no settle needed */ },
            this::perUserPostCount);
    }

    /** One repeat's measured outcome. */
    private record RepeatResult(
        long baselineMiB, long peakMiB, long peakDeltaMiB,
        double wallSeconds, int httpRequests, double requestRatio) {}

    /**
     * Drives K repeats of one cell. Each repeat: fresh realm (with the cell's
     * bulk config) → reset request journal → settle + baseline sample → start
     * sampler → trigger sync → await drain → stop sampler → record → delete the
     * realm (so Keycloak's baseline does not drift into the next repeat).
     *
     * @param newRealm      supplier that builds a fresh realm with the cell's config
     * @param drainReached  predicate: true once the witnessed request count hits its floor
     * @param settle        extra wait after the floor (bulk needs a trailing-partial settle)
     * @param requestCount  supplier of the final witnessed HTTP request count
     */
    private void runCell(String label, boolean bulkEnabled, int delayMs,
                         java.util.function.Supplier<TestRealm> newRealm,
                         Callable<Boolean> drainReached, Runnable settle,
                         java.util.function.IntSupplier requestCount) throws Exception {
        cell++;
        String cellLabel = label + "-" + delayMs + "ms";
        var results = new ArrayList<RepeatResult>(K_REPEAT);

        for (int rep = 1; rep <= K_REPEAT; rep++) {
            TestRealm r = newRealm.get();
            try {
                // Fresh per-repeat request journal so each repeat's count is its own.
                wireMock.resetRequests();

                // Settle, then sample a quiescent baseline (KC's footprint with no
                // sync in flight) so we can report a baseline-corrected delta.
                Thread.sleep(BASELINE_SETTLE_MS);
                long baselineBytes = sampleQuiescentMemoryBytes();

                var sampler = new ContainerMemorySampler(keycloak);
                sampler.start();
                long t0 = System.nanoTime();
                r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
                await().atMost(15, MINUTES).pollInterval(250, MILLISECONDS).until(drainReached);
                settle.run();
                double wallSeconds = (System.nanoTime() - t0) / 1_000_000_000.0;
                sampler.stop();

                long peakBytes = Math.max(sampler.maxBytes(), baselineBytes);
                long baselineMiB = ContainerMemorySampler.toMiB(baselineBytes);
                long peakMiB = ContainerMemorySampler.toMiB(peakBytes);
                long peakDeltaMiB = ContainerMemorySampler.toMiB(peakBytes - baselineBytes);

                int httpRequests = requestCount.getAsInt();
                double requestRatio = httpRequests == 0 ? 0 : (double) N / httpRequests;
                var rr = new RepeatResult(baselineMiB, peakMiB, peakDeltaMiB,
                    wallSeconds, httpRequests, requestRatio);
                results.add(rr);

                System.out.println(String.format(Locale.ROOT,
                    "[perf] %s rep %d/%d: baselineMiB=%d peakMiB=%d peakDeltaMiB=%d "
                        + "wallSeconds=%.1f httpRequests=%d requestRatio=%.2f",
                    cellLabel, rep, K_REPEAT, baselineMiB, peakMiB, peakDeltaMiB,
                    wallSeconds, httpRequests, requestRatio));
            } finally {
                // Delete the realm (cascades users + components + SCIM mappings)
                // so Keycloak's baseline footprint does not drift across repeats.
                try {
                    r.realm().remove();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        recordAggregate(cellLabel, bulkEnabled, delayMs, results);
    }

    /** Aggregates the K repeats into min/mean/max (and stddev) and records a row. */
    private void recordAggregate(String cellLabel, boolean bulkEnabled, int delayMs,
                                 List<RepeatResult> results) {
        var deltaStats = Stats.of(results.stream().mapToDouble(RepeatResult::peakDeltaMiB).toArray());
        var peakStats = Stats.of(results.stream().mapToDouble(RepeatResult::peakMiB).toArray());
        var baseStats = Stats.of(results.stream().mapToDouble(RepeatResult::baselineMiB).toArray());
        var wallStats = Stats.of(results.stream().mapToDouble(RepeatResult::wallSeconds).toArray());
        var reqStats = Stats.of(results.stream().mapToDouble(RepeatResult::httpRequests).toArray());
        var ratioStats = Stats.of(results.stream().mapToDouble(RepeatResult::requestRatio).toArray());

        var notes = new LinkedHashMap<String, String>();
        notes.put("bulkEnabled", String.valueOf(bulkEnabled));
        notes.put("sinkDelayMs", String.valueOf(delayMs));
        notes.put("users", String.valueOf(N));
        notes.put("repeats", String.valueOf(results.size()));
        notes.put("peakDeltaMiB", String.format(Locale.ROOT, "min=%.0f mean=%.1f max=%.0f sd=%.1f",
            deltaStats.min(), deltaStats.mean(), deltaStats.max(), deltaStats.stddev()));
        notes.put("peakMiB", String.format(Locale.ROOT, "min=%.0f mean=%.1f max=%.0f",
            peakStats.min(), peakStats.mean(), peakStats.max()));
        notes.put("baselineMiB", String.format(Locale.ROOT, "min=%.0f mean=%.1f max=%.0f",
            baseStats.min(), baseStats.mean(), baseStats.max()));
        notes.put("wallSeconds", String.format(Locale.ROOT, "min=%.1f mean=%.1f max=%.1f sd=%.1f",
            wallStats.min(), wallStats.mean(), wallStats.max(), wallStats.stddev()));
        notes.put("httpRequests", String.format(Locale.ROOT, "min=%.0f mean=%.0f max=%.0f",
            reqStats.min(), reqStats.mean(), reqStats.max()));
        notes.put("requestRatio", String.format(Locale.ROOT, "mean=%.2f", ratioStats.mean()));

        // Record an aggregate Sample (duration = mean wall) so the markdown row carries the aggregates.
        report.record(new PerfReport.Sample("bulk-sweep", cellLabel,
            java.time.Duration.ofMillis((long) (wallStats.mean() * 1000)), N, java.util.Map.copyOf(notes)));

        System.out.println(String.format(Locale.ROOT,
            "[perf] %s AGG (K=%d N=%d): peakDeltaMiB[min=%.0f mean=%.1f max=%.0f sd=%.1f] "
                + "peakMiB[mean=%.1f] baselineMiB[mean=%.1f] "
                + "wallSeconds[min=%.1f mean=%.1f max=%.1f sd=%.1f] "
                + "httpRequests[mean=%.0f] requestRatio[mean=%.2f]",
            cellLabel, results.size(), N,
            deltaStats.min(), deltaStats.mean(), deltaStats.max(), deltaStats.stddev(),
            peakStats.mean(), baseStats.mean(),
            wallStats.min(), wallStats.mean(), wallStats.max(), wallStats.stddev(),
            reqStats.mean(), ratioStats.mean()));
    }

    /** Simple summary statistics over a sample. */
    private record Stats(double min, double mean, double max, double stddev) {
        static Stats of(double[] xs) {
            if (xs.length == 0) {
                return new Stats(0, 0, 0, 0);
            }
            double min = xs[0], max = xs[0], sum = 0;
            for (double x : xs) {
                min = Math.min(min, x);
                max = Math.max(max, x);
                sum += x;
            }
            double mean = sum / xs.length;
            double var = 0;
            for (double x : xs) {
                var += (x - mean) * (x - mean);
            }
            double stddev = Math.sqrt(var / xs.length); // population stddev
            return new Stats(min, mean, max, stddev);
        }
    }

    /**
     * One-shot quiescent container-memory read (bytes), retried briefly to skip
     * a transient failed exec. Returns 0 only if every attempt failed.
     */
    private long sampleQuiescentMemoryBytes() {
        for (int i = 0; i < 5; i++) {
            long b = ContainerMemorySampler.readMemoryBytesOnce(keycloak);
            if (b > 0) {
                return b;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return 0;
    }

    /**
     * Settle wait for the bulk lane: block until the {@code POST /Bulk} count has
     * stopped changing, so a trailing partial batch (the last {@code drainTo} of
     * fewer than K ops) is counted before we read {@code httpRequests}.
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
