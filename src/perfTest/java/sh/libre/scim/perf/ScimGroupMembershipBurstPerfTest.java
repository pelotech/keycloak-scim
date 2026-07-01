package sh.libre.scim.perf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;

import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

/**
 * Counts the SCIM writes a bulk group-membership burst produces, to decide
 * whether coalescing (group-debounce) is worth building.
 *
 * <p>Adding N members to one group fires N {@code GROUP_MEMBERSHIP} events;
 * each produces a group write (a single-member delta PATCH when
 * {@code group-patchOp=true}, or a full-member-list PUT when false) plus a
 * user PUT (the member's own replace). A debounce could collapse the N group
 * writes to one, but the N user PUTs are on distinct users and can't be
 * coalesced. This reports both so the coalescing ceiling (2N -> N+1) is
 * concrete rather than hypothetical.
 *
 * <p>Run: {@code ./gradlew performanceTest --tests
 * 'sh.libre.scim.perf.ScimGroupMembershipBurstPerfTest'}. Override the burst
 * size with {@code -Dperf.membershipCount=N} (default 100).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScimGroupMembershipBurstPerfTest extends PerfTestBase {

    private static final int N = Integer.getInteger("perf.membershipCount", 100);
    private static final PerfReport report = new PerfReport("ScimGroupMembershipBurstPerfTest");

    @AfterEach
    void writeReport() {
        try { report.write(); } catch (Exception e) { e.printStackTrace(); }
    }

    @Test
    @Order(1)
    void bulkMembershipAdd_deltaPatch() {
        runBurst(true, "delta-patch");
    }

    @Test
    @Order(2)
    void bulkMembershipAdd_fullPut() {
        runBurst(false, "full-put");
    }

    private void runBurst(boolean groupPatchOp, String label) {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        stubScimGroupCreateOk();
        stubScimGroupUpdateOk();
        stubScimGroupPatchOk();

        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("propagation-group", "true");
            cfg.putSingle("group-patchOp", String.valueOf(groupPatchOp));
        });
        enableScimEventListener(r.realm());

        // Setup (not measured): one group + N users, all propagated first so
        // their SCIM mappings exist before the membership burst.
        String groupId = createGroup(r.realm(), "burst-group");
        var userIds = new ArrayList<String>();
        for (int i = 0; i < N; i++) {
            userIds.add(createAdminUser(r.realm(), "burst-u" + i, "burst-u" + i + "@test.local"));
        }
        awaitUserPostFor("burst-u" + (N - 1));

        long groupPatchBefore = count(patchRequestedFor(urlPathMatching("/Groups/.*")));
        long groupPutBefore = count(putRequestedFor(urlPathMatching("/Groups/.*")));
        long userPutBefore = count(putRequestedFor(urlPathMatching("/Users/.*")));

        var notes = new LinkedHashMap<String, String>();
        notes.put("membershipCount", String.valueOf(N));
        notes.put("group-patchOp", String.valueOf(groupPatchOp));

        report.timedWithNotes("bulkMembershipAdd", label, N, notes, () -> {
            for (String uid : userIds) {
                r.realm().users().get(uid).joinGroup(groupId);
            }
            // Drain on the group writes — one always fires per membership change,
            // regardless of whether the user replace is skipped (it is, for a
            // plain group with no scim-marked role). Membership propagation runs
            // synchronously on the event thread, so this only guards a straggler.
            await().atMost(120, SECONDS).until(() ->
                (count(patchRequestedFor(urlPathMatching("/Groups/.*"))) - groupPatchBefore)
                    + (count(putRequestedFor(urlPathMatching("/Groups/.*"))) - groupPutBefore) >= N);
            sleepQuietly(1);

            long groupPatch = count(patchRequestedFor(urlPathMatching("/Groups/.*"))) - groupPatchBefore;
            long groupPut = count(putRequestedFor(urlPathMatching("/Groups/.*"))) - groupPutBefore;
            long userPut = count(putRequestedFor(urlPathMatching("/Users/.*"))) - userPutBefore;
            long groupWrites = groupPatch + groupPut;
            long ceiling = (groupWrites > 0 ? 1 : 0) + userPut;

            notes.put("groupPatch", String.valueOf(groupPatch));
            notes.put("groupPut", String.valueOf(groupPut));
            notes.put("userPut", String.valueOf(userPut));
            notes.put("totalWrites", String.valueOf(groupWrites + userPut));
            notes.put("coalesceCeiling", ceiling + " (group writes -> 1; user PUTs unchanged)");

            System.out.printf("[perf] membership burst (%s, N=%d): groupPatch=%d groupPut=%d "
                + "userPut=%d total=%d -> coalescing floor %d (group writes %d->1; %d user PUTs "
                + "not coalesceable)%n",
                label, N, groupPatch, groupPut, userPut, groupWrites + userPut, ceiling,
                groupWrites, userPut);
            return null;
        });
    }

    private long count(RequestPatternBuilder pattern) {
        return wireMock.countRequestsMatching(pattern.build()).getCount();
    }
}
