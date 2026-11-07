package sh.libre.scim.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

/**
 * End-to-end role refreshes. Runs its own Keycloak with a short role-refresh
 * delay, so a refresh fires within seconds and cannot leak into other test
 * classes that share a container.
 */
class ScimEffectiveRolesIT extends IntegrationTestBase {

    private static final int DELAY_SECONDS = 5;
    private static KeycloakContainer rolesKeycloak;
    private static Keycloak rolesAdmin;

    @BeforeAll
    static void startRolesKeycloak() {
        rolesKeycloak = new KeycloakContainer(KEYCLOAK_IMAGE)
            .withProviderLibsFrom(List.of(PLUGIN_JAR))
            .withNetwork(network)
            .withEnv("JAVA_OPTS_APPEND", "-Dscim.roles.refreshDelaySeconds=" + DELAY_SECONDS);
        rolesKeycloak.start();
        rolesAdmin = AdminClients.forContainer(rolesKeycloak);
    }

    @AfterAll
    static void stopRolesKeycloak() {
        if (rolesAdmin != null) {
            rolesAdmin.close();
        }
        if (rolesKeycloak != null) {
            rolesKeycloak.stop();
        }
    }

    /** A realm with the SCIM provider and the scim event listener, and no directory. */
    private RealmResource newRealm() {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        String realmName = "it-" + UUID.randomUUID().toString().substring(0, 8);
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(realmName);
        realmRep.setEnabled(true);
        rolesAdmin.realms().create(realmRep);
        RealmResource realm = rolesAdmin.realm(realmName);
        // Users only. Group propagation adds requests these tests do not check.
        addScimStorageProvider(realm, cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("propagation-group", "false");
        });
        enableScimEventListener(realm);
        return realm;
    }

    private static RoleRepresentation markedRole(RealmResource realm, String name) {
        var role = new RoleRepresentation();
        role.setName(name);
        role.setAttributes(Map.of("scim", List.of("true")));
        realm.roles().create(role);
        return realm.roles().get(name).toRepresentation();
    }

    private static RoleRepresentation plainRole(RealmResource realm, String name) {
        var role = new RoleRepresentation();
        role.setName(name);
        realm.roles().create(role);
        return realm.roles().get(name).toRepresentation();
    }

    /** The body of the newest PUT /Users/* whose body names {@code userName}, or "" if none. */
    private static String newestUserPutFor(String userName) {
        return wireMock.getAllServeEvents().stream() // newest first
            .filter(e -> "PUT".equals(e.getRequest().getMethod().getName()))
            .filter(e -> e.getRequest().getUrl().startsWith("/Users/"))
            .map(e -> e.getRequest().getBodyAsString())
            .filter(b -> b.contains("\"" + userName + "\""))
            .findFirst()
            .orElse("");
    }

    /** Counts the one line each role refresh logs when it ends. */
    private static int roleRefreshLogLines(String componentId) {
        var matcher = Pattern.compile("SCIM role refresh of component " + Pattern.quote(componentId)
                + " (finished|stopped) in ")
            .matcher(rolesKeycloak.getLogs());
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Waits for one new end line, then until no further end line appears for
     * {@code DELAY_SECONDS + 2} seconds. It checks at most 5 times, then fails.
     * This does not prove the scheduler is idle. A follow-up run that takes
     * longer than about 2 seconds could still end later.
     */
    private static void awaitRefreshesSettled(String componentId, int before) throws InterruptedException {
        await().atMost(60, SECONDS).until(() -> roleRefreshLogLines(componentId) > before);
        final int maxChecks = 5;
        for (int check = 0; check < maxChecks; check++) {
            int count = roleRefreshLogLines(componentId);
            Thread.sleep((DELAY_SECONDS + 2) * 1000L);
            if (roleRefreshLogLines(componentId) == count) {
                return;
            }
        }
        fail("role refresh end lines kept appearing after " + maxChecks + " checks of "
            + (DELAY_SECONDS + 2) + " seconds each");
    }

    private static String scimComponentId(RealmResource realm) {
        return realm.components().query(realm.toRepresentation().getId(),
                "org.keycloak.storage.UserStorageProvider").stream()
            .filter(c -> "scim".equals(c.getProviderId()))
            .findFirst().orElseThrow().getId();
    }

    @Test
    void aRoleGrantedToAParentGroupReachesSubgroupMembers() {
        var realm = newRealm();
        String componentId = scimComponentId(realm);
        int before = roleRefreshLogLines(componentId);
        var role = markedRole(realm, "from-parent");
        // Creating the role requests a role refresh. Let it end first, so the
        // "admin request sends no user" check below cannot see its PUT.
        await().atMost(30, SECONDS).until(() -> roleRefreshLogLines(componentId) > before);
        String parentId = createGroup(realm, "parent");
        var childRep = new GroupRepresentation();
        childRep.setName("child");
        realm.groups().group(parentId).subGroup(childRep).close();
        String childId = realm.groups().group(parentId).getSubGroups(0, 10, true).get(0).getId();
        String userId = createAdminUser(realm, "sub-member", "sub-member@test.local");
        awaitUserPostFor("sub-member");
        realm.users().get(userId).joinGroup(childId);

        realm.groups().group(parentId).roles().realmLevel().add(List.of(role));
        // The delay is 5 seconds, so a PUT that names the role right after
        // the admin call returns came from inside the admin request.
        assertFalse(newestUserPutFor("sub-member").contains("from-parent"),
            "the admin request itself sends no user");

        await().atMost(30, SECONDS).untilAsserted(() ->
            assertTrue(newestUserPutFor("sub-member").contains("from-parent"),
                "the subgroup member gets the parent group's role"));
    }

    @Test
    void aDirectGrantIsSentAtOnce() {
        var realm = newRealm();
        String componentId = scimComponentId(realm);
        int before = roleRefreshLogLines(componentId);
        var role = markedRole(realm, "direct-grant");
        // Creating the role requests a role refresh. Let it end first, so the
        // check below can only pass through the direct grant.
        await().atMost(30, SECONDS).until(() -> roleRefreshLogLines(componentId) > before);
        String userId = createAdminUser(realm, "granted", "granted@test.local");
        awaitUserPostFor("granted");

        realm.users().get(userId).roles().realmLevel().add(List.of(role));

        await().atMost(DELAY_SECONDS - 1, SECONDS).untilAsserted(() ->
            assertTrue(newestUserPutFor("granted").contains("direct-grant"),
                "a direct grant sends the user before any role refresh could"));
    }

    /**
     * The sequence that needs the event path to clear the hash: a refresh
     * stores the hash of a body without the role, an event sends the role,
     * and removing the role brings the body back to the hashed one.
     */
    @Test
    void removingARoleFromACompositeRemovesItFromHolders() throws Exception {
        var realm = newRealm();
        String componentId = scimComponentId(realm);
        // The user exists before any role event, so the refresh below covers it.
        String userId = createAdminUser(realm, "holder", "holder@test.local");
        awaitUserPostFor("holder");
        int before = roleRefreshLogLines(componentId);
        var inner = markedRole(realm, "inner");
        var outer = plainRole(realm, "outer");
        realm.roles().get("outer").addComposites(List.of(inner));
        // A refresh stores the hash of the body without "inner", and none is pending at the grant.
        awaitRefreshesSettled(componentId, before);
        realm.users().get(userId).roles().realmLevel().add(List.of(outer));
        await().atMost(30, SECONDS).untilAsserted(() ->
            assertTrue(newestUserPutFor("holder").contains("inner"), "a direct grant sends the role at once"));

        realm.roles().get("outer").deleteComposites(List.of(inner));

        await().atMost(30, SECONDS).untilAsserted(() ->
            assertFalse(newestUserPutFor("holder").contains("inner"),
                "the holder loses the role that left the composite"));
    }

    @Test
    void tenRoleEditsInARowRunOneRefresh() throws Exception {
        var realm = newRealm();
        String componentId = scimComponentId(realm);
        int before = roleRefreshLogLines(componentId);

        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            plainRole(realm, "burst-" + i);
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        // On a slow runner the edits can span the delay. Then two refreshes are correct.
        assumeTrue(elapsedMillis < DELAY_SECONDS * 1000L,
            "ten edits took " + elapsedMillis + " ms, longer than the delay");

        await().atMost(30, SECONDS).until(() -> roleRefreshLogLines(componentId) > before);
        Thread.sleep((DELAY_SECONDS + 3) * 1000L);
        assertEquals(before + 1, roleRefreshLogLines(componentId), "ten edits make one role refresh");
    }

    @Test
    void movingAGroupGivesAndTakesItsParentsRole() throws Exception {
        var realm = newRealm();
        String componentId = scimComponentId(realm);
        int before = roleRefreshLogLines(componentId);
        var role = markedRole(realm, "via-move");
        String parentId = createGroup(realm, "move-parent");
        realm.groups().group(parentId).roles().realmLevel().add(List.of(role));
        // No refresh may be pending when the move starts. Otherwise it could
        // send the role even if the move requested nothing.
        awaitRefreshesSettled(componentId, before);
        String movedId = createGroup(realm, "moved");
        String userId = createAdminUser(realm, "mover", "mover@test.local");
        awaitUserPostFor("mover");
        realm.users().get(userId).joinGroup(movedId);
        var movedRep = realm.groups().group(movedId).toRepresentation();

        realm.groups().group(parentId).subGroup(movedRep).close();
        await().atMost(30, SECONDS).untilAsserted(() ->
            assertTrue(newestUserPutFor("mover").contains("via-move"), "a move under the parent gives the role"));

        realm.groups().add(realm.groups().group(movedId).toRepresentation()).close();
        await().atMost(30, SECONDS).untilAsserted(() ->
            assertFalse(newestUserPutFor("mover").contains("via-move"), "a move to the top level takes it away"));
    }

    @Test
    void aManualSyncStillSendsEveryUser() {
        var realm = newRealm();
        var scim = realm.components().component(scimComponentId(realm)).toRepresentation();
        scim.getConfig().putSingle("sync-refresh", "true");
        realm.components().component(scim.getId()).update(scim);
        createAdminUser(realm, "steady", "steady@test.local");
        awaitUserPostFor("steady");
        // Count only the PUTs that name this user. Other tests' users are not in this realm.
        var steadyPut = putRequestedFor(urlPathMatching("/Users/.*"))
            .withRequestBody(containing("\"steady\""));
        int before = wireMock.countRequestsMatching(steadyPut.build()).getCount();

        realm.userStorage().syncUsers(scim.getId(), "triggerFullSync");
        realm.userStorage().syncUsers(scim.getId(), "triggerFullSync");

        int after = wireMock.countRequestsMatching(steadyPut.build()).getCount();
        assertEquals(2, after - before, "each manual sync sends the unchanged user once");
    }
}
