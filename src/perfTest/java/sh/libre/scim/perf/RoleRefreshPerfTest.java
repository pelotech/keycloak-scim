package sh.libre.scim.perf;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.awaitility.Awaitility.await;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RoleRepresentation;

/**
 * Measures one role refresh over N mapped users with no change. Run with
 * {@code ./gradlew performanceTest --tests '*RoleRefreshPerfTest' -Dperf.userCount=5000}.
 * 10,000 users fails today, because the manual sync aborts (see docs/performance.md).
 * The perf task sets the role-refresh delay to 1 second on the shared container.
 */
class RoleRefreshPerfTest extends PerfTestBase {

    private static final int USER_COUNT = Integer.getInteger("perf.userCount", 1000);
    /** The LDAP seed file holds alice and bob. They are mapped users too. */
    private static final int SEED_FILE_USERS = 2;
    private static final int MAPPED_USERS = USER_COUNT + SEED_FILE_USERS;
    private static final Pattern FINISHED = Pattern.compile(
        "SCIM role refresh of component (\\S+) finished in (\\d+) ms: (\\d+) users sent, (\\d+) failed");

    @Test
    void aRoleRefreshWithNoChangeSendsNothing() throws Exception {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("sync-refresh", "true"));
        // Without the listener, the role edit below requests nothing.
        enableScimEventListener(r.realm());
        List<String> seeded = new ArrayList<>(seedLdapUsers("rr", USER_COUNT));
        try {
            r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
            await().atMost(10, MINUTES).until(() -> perUserPostCount() >= MAPPED_USERS);
            String scimId = r.realm().components().query(r.realm().toRepresentation().getId(),
                    "org.keycloak.storage.UserStorageProvider").stream()
                .filter(c -> "scim".equals(c.getProviderId())).findFirst().orElseThrow().getId();
            // A manual sync sends every user once and stores every hash.
            int putsBeforeSync = userPutCount();
            long syncStart = System.nanoTime();
            var syncResult = r.realm().userStorage().syncUsers(scimId, "triggerFullSync");
            long syncMillis = (System.nanoTime() - syncStart) / 1_000_000L;
            assertEquals(0, syncResult.getFailed(), "the manual sync had failures: " + syncResult.getStatus());
            assertEquals(MAPPED_USERS, userPutCount() - putsBeforeSync,
                "the manual sync must replace every user once, so every hash is written");
            System.out.printf("[perf] manual sync, %d users, with hash write: %d ms%n", USER_COUNT, syncMillis);

            int linesBefore = finishedLines(scimId).size();
            int putsBeforeRefresh = userPutCount();
            var role = new RoleRepresentation();
            role.setName("perf-trigger");
            r.realm().roles().create(role);

            await().atMost(10, MINUTES).until(() -> finishedLines(scimId).size() > linesBefore);
            var lines = finishedLines(scimId);
            var last = lines.get(lines.size() - 1);
            System.out.printf("[perf] role refresh, %d users, no change: %s ms, %s sent, %s failed%n",
                USER_COUNT, last[0], last[1], last[2]);
            assertEquals("0", last[1], "a refresh with no change sends no user");
            assertEquals("0", last[2], "a refresh with no change has no failure");
            assertEquals(putsBeforeRefresh, userPutCount(), "no PUT /Users request reached the SCIM server");
        } finally {
            cleanupLdapEntries(seeded.stream().map(PerfTestBase::ldapUserDn).toList());
        }
    }

    private int userPutCount() {
        return wireMock.countRequestsMatching(putRequestedFor(urlPathMatching("/Users/.*")).build()).getCount();
    }

    /** {ms, sent, failed} for each finished role refresh of the component, oldest first. */
    private static List<String[]> finishedLines(String componentId) {
        var out = new ArrayList<String[]>();
        var m = FINISHED.matcher(keycloak().getLogs());
        while (m.find()) {
            if (m.group(1).equals(componentId)) {
                out.add(new String[] {m.group(2), m.group(3), m.group(4)});
            }
        }
        return out;
    }
}
