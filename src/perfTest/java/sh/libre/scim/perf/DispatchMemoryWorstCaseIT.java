package sh.libre.scim.perf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.awaitility.Awaitility.await;

/**
 * Characterizes the CURRENT plugin's container-memory footprint and worst-case
 * behavior under a federation-import flood — not throughput.
 *
 * <p>The async SCIM dispatch worker pool is a bounded {@code ThreadPoolExecutor}
 * over an {@code ArrayBlockingQueue} (default capacity 256). When the queue is
 * full, {@code BlockingPolicy} blocks the producer (back-pressure) instead of
 * growing the queue, so the in-flight backlog — and Keycloak's container memory
 * — is bounded at ~capacity regardless of the sync size N. A slow downstream
 * SCIM sink paces the import to the sink's drain rate rather than allowing the
 * queue to grow unboundedly.
 *
 * <p>Each scenario samples the Keycloak CONTAINER's resident memory via cgroup
 * accounting throughout the run and records peak + a memory curve. Scenarios:
 * <ol>
 *   <li>mem-vs-N, fast sink, 1k</li>
 *   <li>mem-vs-N, fast sink, 10k (does peak scale with N?)</li>
 *   <li>no-plugin baseline, 10k (isolates Keycloak's own import memory; the
 *       delta = plugin/queue cost)</li>
 *   <li>slow sink worst-case, 10k @ 200ms (is the backlog bounded and the
 *       import paced to the sink = back-pressure engaged?)</li>
 * </ol>
 *
 * <p>Run with {@code ./gradlew performanceTest --tests
 * 'sh.libre.scim.perf.DispatchMemoryWorstCaseIT'}. Slow; the 200ms 10k drain
 * alone is ~4-5 minutes. Keycloak heap is held constant across scenarios so
 * peak numbers are comparable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DispatchMemoryWorstCaseIT extends PerfTestBase {

    /** Default for scenarios that don't hardcode their own size. */
    private static final int USER_COUNT = Integer.getInteger("perf.userCount", 10_000);

    private static final PerfReport report = new PerfReport("DispatchMemoryWorstCaseIT");

    private final java.util.List<String> seeded = new java.util.ArrayList<>();

    @AfterEach
    void cleanupSeeded() {
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

    /** Scenario 1: fast sink, 1k. Baseline for the mem-vs-N comparison. */
    @Test
    @Order(1)
    void memVsN_fastSink_1k() throws Exception {
        runFastSinkScenario("fast-sink-1k", 1_000);
    }

    /** Scenario 2: fast sink, 10k. Compare peak vs the 1k run. */
    @Test
    @Order(2)
    void memVsN_fastSink_10k() throws Exception {
        runFastSinkScenario("fast-sink-10k", 10_000);
    }

    private void runFastSinkScenario(String label, int users) throws Exception {
        var counter = stubFastUserCreate();
        var r = newRealmWithScimAndLdap();
        seeded.addAll(seedLdapUsers("perfm" + users, users));

        var sampler = new ContainerMemorySampler(keycloak);
        var notes = new LinkedHashMap<String, String>();
        notes.put("users", String.valueOf(users));
        notes.put("sinkDelayMs", "0");

        sampler.start();
        long t0 = System.nanoTime();
        var sample = report.timedWithNotes("mem-vs-N", label, users, notes, () -> {
            r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
            await().atMost(10, MINUTES).until(() -> counter.get() >= users);
            return null;
        });
        long drainSeconds = (System.nanoTime() - t0) / 1_000_000_000L;
        sampler.stop();

        recordMemNotes(notes, sampler, counter, drainSeconds);
        System.out.println("[perf] " + label + ": " + users + " users; "
            + "peakMemMiB=" + sampler.maxMiB() + "; drainSeconds=" + drainSeconds
            + "; postsObserved=" + counter.get()
            + "; durationMs=" + sample.duration().toMillis());
        System.out.println("[perf] " + label + " mem " + sampler.summary());
    }

    /**
     * Scenario 3: no-plugin baseline, 10k. A realm with NO SCIM provider, so no
     * dispatch queue. Isolates Keycloak's own federation-import memory; the
     * delta vs scenario 2's peak is the plugin/queue cost.
     */
    @Test
    @Order(3)
    void noPluginBaseline_10k() throws Exception {
        int users = 10_000;
        var r = newRealmWithLdapOnly();
        seeded.addAll(seedLdapUsers("perfb", users));

        var sampler = new ContainerMemorySampler(keycloak);
        var notes = new LinkedHashMap<String, String>();
        notes.put("users", String.valueOf(users));
        notes.put("sinkDelayMs", "n/a");
        notes.put("plugin", "absent");

        sampler.start();
        long t0 = System.nanoTime();
        report.timedWithNotes("no-plugin", "no-plugin-10k", users, notes, () -> {
            r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
            return null;
        });
        long drainSeconds = (System.nanoTime() - t0) / 1_000_000_000L;
        sampler.stop();

        notes.put("peakMemMiB", String.valueOf(sampler.maxMiB()));
        notes.put("memCurveMiB", sampler.curveMiB());
        notes.put("users", String.valueOf(users));
        notes.put("drainSeconds", String.valueOf(drainSeconds));
        notes.put("postsObserved", "0");
        System.out.println("[perf] no-plugin-10k: " + users + " users; "
            + "peakMemMiB=" + sampler.maxMiB() + "; importSeconds=" + drainSeconds);
        System.out.println("[perf] no-plugin-10k mem " + sampler.summary());
    }

    /**
     * Scenario 4: slow sink worst-case, 10k @ 200ms. 8 workers / 0.2s ≈ 40/sec,
     * so ~250s to drain 10k. Samples memory throughout the long drain and
     * measures the sync-trigger return time separately from the drain time: if
     * the import finishes fast while the queue still holds ~10k tasks, that
     * demonstrates the absence of back-pressure.
     */
    @Test
    @Order(4)
    void slowSinkWorstCase_10k_200ms() throws Exception {
        int users = 10_000;
        int delayMs = 200;
        var counter = stubSlowUserCreate(delayMs);
        var r = newRealmWithScimAndLdap();
        seeded.addAll(seedLdapUsers("perfs", users));

        var sampler = new ContainerMemorySampler(keycloak);
        var notes = new LinkedHashMap<String, String>();
        notes.put("users", String.valueOf(users));
        notes.put("sinkDelayMs", String.valueOf(delayMs));

        sampler.start();

        // Time the import (sync trigger) separately from the drain. The sync
        // call returns when Keycloak's iteration completes; the queue may still
        // hold ~N tasks at that point.
        long importStart = System.nanoTime();
        final long[] importMillisHolder = new long[1];
        final int[] postsAtImportReturnHolder = new int[1];

        report.timedWithNotes("slow-sink", "slow-sink-10k-200ms", users, notes, () -> {
            r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
            importMillisHolder[0] = (System.nanoTime() - importStart) / 1_000_000L;
            postsAtImportReturnHolder[0] = counter.get();
            // Now wait for the slow queue to drain. With 40/sec this is ~250s.
            await().atMost(10, MINUTES).until(() -> counter.get() >= users);
            return null;
        });

        long drainSeconds = (System.nanoTime() - importStart) / 1_000_000_000L;
        sampler.stop();

        recordMemNotes(notes, sampler, counter, drainSeconds);
        notes.put("importReturnMs", String.valueOf(importMillisHolder[0]));
        notes.put("postsAtImportReturn", String.valueOf(postsAtImportReturnHolder[0]));
        long backlogAtImportReturn = users - postsAtImportReturnHolder[0];
        notes.put("backlogAtImportReturn", String.valueOf(backlogAtImportReturn));

        // --- Back-pressure assertions (the point of this scenario) ---
        // 1) The bounded queue + blocking producer cap the in-flight backlog at
        //    ~queueCapacity (256 default) + the 8 workers, regardless of N.
        //    Before back-pressure this was ~9,824.
        org.junit.jupiter.api.Assertions.assertTrue(backlogAtImportReturn <= 512,
            "expected bounded backlog (<=512) under back-pressure, got " + backlogAtImportReturn);
        // 2) Because the producer is paced to the slow sink, the import (sync
        //    trigger) cannot return until the queue has drained most of the
        //    10k. Its return time therefore reflects the slow drain (~240s),
        //    not the ~4.9s it took with the unbounded queue.
        org.junit.jupiter.api.Assertions.assertTrue(importMillisHolder[0] >= 100_000L,
            "expected the import back-pressured by the slow sink (>=100s), got "
                + importMillisHolder[0] + "ms");

        System.out.println("[perf] slow-sink-10k-200ms: " + users + " users @ "
            + delayMs + "ms sink; peakMemMiB=" + sampler.maxMiB()
            + "; importReturnMs=" + importMillisHolder[0]
            + " (postsObservedAtReturn=" + postsAtImportReturnHolder[0]
            + ", backlogStillQueued=" + backlogAtImportReturn + ")"
            + "; drainSeconds=" + drainSeconds
            + "; postsObserved=" + counter.get());
        System.out.println("[perf] slow-sink-10k-200ms mem " + sampler.summary());
    }

    private void recordMemNotes(LinkedHashMap<String, String> notes,
                                ContainerMemorySampler sampler,
                                AtomicInteger counter,
                                long drainSeconds) {
        notes.put("peakMemMiB", String.valueOf(sampler.maxMiB()));
        notes.put("memCurveMiB", sampler.curveMiB());
        notes.put("drainSeconds", String.valueOf(drainSeconds));
        notes.put("postsObserved", String.valueOf(counter.get()));
    }

    // ---------- SCIM sink stubs ----------

    /**
     * Stubs POST /Users to return a unique 201 immediately, with a request
     * listener incrementing a counter on each POST. The counter is the drain
     * witness: it reflects how many SCIM ops the worker pool has actually
     * pushed to the sink.
     */
    private AtomicInteger stubFastUserCreate() {
        return stubUserCreate(0);
    }

    /**
     * Like {@link #stubFastUserCreate()} but the response carries a fixed delay,
     * modelling a slow downstream SCIM sink. The delay throttles the worker
     * pool's effective drain rate (≈ poolSize / delay), so the unbounded queue
     * holds the backlog while the import races ahead.
     */
    private AtomicInteger stubSlowUserCreate(int delayMs) {
        return stubUserCreate(delayMs);
    }

    private AtomicInteger stubUserCreate(int delayMs) {
        var counter = new AtomicInteger();
        var response = aResponse()
            .withStatus(201)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("""
                {
                  "id": "%s",
                  "userName": "placeholder",
                  "displayName": "placeholder",
                  "active": true,
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                }""".formatted(UUID.randomUUID()));
        if (delayMs > 0) {
            response = response.withFixedDelay(delayMs);
        }
        wireMock.stubFor(post(urlPathEqualTo("/Users")).willReturn(response));

        wireMock.addMockServiceRequestListener((request, resp) -> {
            if (request.getUrl().equals("/Users") && "POST".equals(request.getMethod().getName())) {
                counter.incrementAndGet();
            }
        });
        return counter;
    }
}
