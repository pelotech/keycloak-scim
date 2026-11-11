package sh.libre.scim.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;
import org.testcontainers.Testcontainers;

/**
 * A sync page reuses one connection to the SCIM server for all its users.
 *
 * <p>The SCIM server here is WireMock behind a relay that counts the TCP
 * connections it accepts. WireMock compresses its responses when the client
 * asks for it. In the Keycloak JVM, a compressed response made the client
 * discard its connection after every request, so a sync opened one
 * connection per user. This test runs in a real Keycloak container, because
 * that is where the discard happened.
 */
class ScimConnectionReuseIT extends IntegrationTestBase {

    private static final int USERS = 30;

    private static final AtomicInteger ACCEPTED = new AtomicInteger();
    private static ServerSocket relay;
    private static KeycloakContainer reuseKeycloak;
    private static Keycloak reuseAdmin;

    @BeforeAll
    static void startRelayAndKeycloak() throws Exception {
        relay = new ServerSocket(0);
        var acceptor = new Thread(() -> {
            while (!relay.isClosed()) {
                try {
                    Socket client = relay.accept();
                    ACCEPTED.incrementAndGet();
                    Socket server = new Socket("127.0.0.1", wireMock.port());
                    pump(client.getInputStream(), server.getOutputStream(), client, server);
                    pump(server.getInputStream(), client.getOutputStream(), client, server);
                } catch (Exception e) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        // Must run before the container starts, so it can reach the relay.
        Testcontainers.exposeHostPorts(relay.getLocalPort());
        reuseKeycloak = new KeycloakContainer(KEYCLOAK_IMAGE)
            .withProviderLibsFrom(List.of(PLUGIN_JAR))
            .withNetwork(network);
        reuseKeycloak.start();
        reuseAdmin = AdminClients.forContainer(reuseKeycloak);
    }

    @AfterAll
    static void stopRelayAndKeycloak() throws Exception {
        if (reuseAdmin != null) {
            reuseAdmin.close();
        }
        if (reuseKeycloak != null) {
            reuseKeycloak.stop();
        }
        if (relay != null) {
            relay.close();
        }
    }

    /** Copies bytes one way, and closes both sockets when either side ends. */
    private static void pump(InputStream in, OutputStream out, Socket a, Socket b) {
        var thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (Exception e) {
                // A side closed the connection.
            } finally {
                try { a.close(); } catch (Exception ignored) { }
                try { b.close(); } catch (Exception ignored) { }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @Test
    void aSyncPageReusesOneConnection() {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();
        String realmName = "it-" + UUID.randomUUID().toString().substring(0, 8);
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(realmName);
        realmRep.setEnabled(true);
        reuseAdmin.realms().create(realmRep);
        RealmResource realm = reuseAdmin.realm(realmName);
        addScimStorageProvider(realm, cfg -> {
            cfg.putSingle("endpoint", "http://host.testcontainers.internal:" + relay.getLocalPort());
            cfg.putSingle("sync-refresh", "true");
        });
        enableScimEventListener(realm);
        for (int i = 0; i < USERS; i++) {
            createAdminUser(realm, "reuse" + i, "reuse" + i + "@test.local");
        }
        awaitUserPostFor("reuse" + (USERS - 1));
        String scimId = realm.components().query(realm.toRepresentation().getId(),
                "org.keycloak.storage.UserStorageProvider").stream()
            .filter(c -> "scim".equals(c.getProviderId())).findFirst().orElseThrow().getId();

        int before = ACCEPTED.get();
        var result = realm.userStorage().syncUsers(scimId, "triggerFullSync");
        int opened = ACCEPTED.get() - before;

        assertEquals(USERS, result.getUpdated(), "the sync replaces every user: " + result.getStatus());
        // One page holds all the users. Allow one more connection in case the
        // pool opens a second one while the first is still being released.
        assertTrue(opened <= 2, "a sync page must reuse its connection; it opened " + opened
            + " connections for " + USERS + " users");
    }
}
