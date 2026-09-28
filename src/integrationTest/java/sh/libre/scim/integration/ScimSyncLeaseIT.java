package sh.libre.scim.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;

/**
 * A second sync of a component that is already syncing is refused by the
 * lease. Keycloak's own lock refuses for 30 seconds; the second sync here
 * starts at 40, so only the lease can refuse it.
 */
class ScimSyncLeaseIT extends IntegrationTestBase {

    private static final int USERS = 30;

    /** Thirty users behind a 2-second endpoint take about 60 seconds. */
    private static final int DELAY_MILLIS = 2000;

    /** Ten users per page take about 20 seconds, so no page nears its own budget. */
    private static final String PAGE_SIZE = "10";

    /** A realm on the shared Keycloak, with the SCIM provider and no directory. */
    private RealmResource newRefreshRealm() {
        String realmName = "it-" + UUID.randomUUID().toString().substring(0, 8);
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(realmName);
        realmRep.setEnabled(true);
        admin().realms().create(realmRep);
        RealmResource realm = admin().realm(realmName);
        addScimStorageProvider(realm, cfg -> {
            cfg.putSingle("sync-refresh", "true");
            cfg.putSingle("sync-page-size", PAGE_SIZE);
        });
        return realm;
    }

    @Test
    void aSecondSyncOfARunningComponentIsRefused() throws Exception {
        stubScimUserCreateOk(DELAY_MILLIS);
        RealmResource realm = newRefreshRealm();
        for (int i = 0; i < USERS; i++) {
            createAdminUser(realm, "lease-" + i, "lease-" + i + "@test.local");
        }
        String componentId = scimComponent(realm).getId();

        // The first call blocks for about 60 seconds, so it runs on another
        // thread. The admin client sets no read timeout, so the call waits.
        var first = CompletableFuture.supplyAsync(() ->
            realm.userStorage().syncUsers(componentId, "triggerFullSync"));

        // The wait is the point of the test. A refusal by Keycloak's lock and
        // a refusal by the lease look the same to the caller. 40 seconds is
        // past Keycloak's 30 second lock and 20 seconds before the first run
        // ends, so only the lease can refuse here.
        sleepQuietly(40);
        var second = realm.userStorage().syncUsers(componentId, "triggerFullSync");
        assertTrue(second.isIgnored(),
            "the second sync must be refused by the lease: " + second.getStatus());

        var firstResult = first.get(3, TimeUnit.MINUTES);
        assertEquals(USERS, firstResult.getUpdated(), "the first run must push every user");
        assertEquals(USERS, perUserPostCount(), "no user was pushed twice");

        // The first run mapped every user, so a later run replaces each one.
        stubScimUserUpdateOk();
        var third = realm.userStorage().syncUsers(componentId, "triggerFullSync");
        assertFalse(third.isIgnored(), "a sync after the first ends must run");
        assertEquals(USERS, third.getUpdated(), "the third run must replace every user");
    }
}
