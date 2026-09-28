package sh.libre.scim.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;

/**
 * With the crash simulation on, a run takes its lease and then never
 * renews, self-fences, or releases it. A sync started soon after still
 * finds the lease live and is refused. A sync started after the stale
 * threshold takes the lease and runs.
 *
 * <p>This class runs its own Keycloak container, set to simulate a crashed
 * lease holder. It reaches the base class only for the SCIM sink and the
 * setup helpers, so the shared Keycloak and the directory server never
 * start here.
 */
class ScimSyncLeaseStaleIT extends IntegrationTestBase {

    private static KeycloakContainer simulateCrashKeycloak;
    private static Keycloak simulateCrashAdmin;

    @BeforeAll
    static void startSimulateCrashKeycloak() {
        simulateCrashKeycloak = new KeycloakContainer(KEYCLOAK_IMAGE)
            .withProviderLibsFrom(List.of(PLUGIN_JAR))
            .withNetwork(network)
            .withEnv("JAVA_OPTS_APPEND", "-Dscim.sync.lease.simulateCrash=true");
        simulateCrashKeycloak.start();
        simulateCrashAdmin = AdminClients.forContainer(simulateCrashKeycloak);
    }

    @AfterAll
    static void stopSimulateCrashKeycloak() {
        if (simulateCrashAdmin != null) {
            simulateCrashAdmin.close();
        }
        if (simulateCrashKeycloak != null) {
            simulateCrashKeycloak.stop();
        }
    }

    /** A realm on the crash-simulating container, with the SCIM provider and no directory. */
    private RealmResource newSimulateCrashRealm() {
        String realmName = "it-" + UUID.randomUUID().toString().substring(0, 8);
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(realmName);
        realmRep.setEnabled(true);
        simulateCrashAdmin.realms().create(realmRep);
        RealmResource realm = simulateCrashAdmin.realm(realmName);
        addScimStorageProvider(realm, cfg -> {
            cfg.putSingle("sync-refresh", "true");
        });
        return realm;
    }

    /**
     * With the crash simulation on, the first run keeps its lease, with its
     * renewal time at acquisition. A sync 45 seconds later is refused, which
     * proves the stale judgement rather than a null holder. A sync after 120
     * seconds runs. About 3 minutes of wall time plus the container start;
     * the waits are the point of the test.
     */
    @Test
    void aStaleLeaseIsTakenAndAMerelyOldOneIsNot() {
        stubScimUserCreateOk();
        var realm = newSimulateCrashRealm();
        for (int i = 0; i < 3; i++) {
            createAdminUser(realm, "stale-" + i, "stale-" + i + "@test.local");
        }
        String componentId = scimComponent(realm).getId();

        var first = realm.userStorage().syncUsers(componentId, "triggerFullSync");
        assertFalse(first.isIgnored());
        assertEquals(3, first.getUpdated());

        sleepQuietly(45);
        var second = realm.userStorage().syncUsers(componentId, "triggerFullSync");
        assertTrue(second.isIgnored(), "45 seconds after a crashed holder the lease is still live");

        sleepQuietly(80);   // now past 120 seconds since the first run's last renewal
        stubScimUserUpdateOk();
        var third = realm.userStorage().syncUsers(componentId, "triggerFullSync");
        assertFalse(third.isIgnored(), "after the stale threshold the lease is taken");
        assertEquals(3, third.getUpdated());
    }
}
