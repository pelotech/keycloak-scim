package sh.libre.scim.integration;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@code rollback-strategy}.
 *
 * <p>The {@code scim} event listener dispatches synchronously pre-commit on the admin
 * user-create transaction. When the SCIM POST fails, {@code ScimDispatcher.runOne}
 * calls {@code session.getTransactionManager().setRollbackOnly()} per the strategy,
 * rolling back the create. Three prerequisites: the scim event listener is registered
 * ({@code enableScimEventListener}), users are created with {@code emailVerified=true}
 * (the listener gates propagation on this), and {@code bulk-enabled=false} (bulk creates
 * are deferred post-commit and cannot participate in rollback).
 *
 * <p>The 503 cases block for tens of seconds while resilience4j exhausts its retry budget
 * before the classified exception escapes. This is inherent to the synchronous critical path.
 */
class ScimCriticalPathRollbackIT extends IntegrationTestBase {

    /** POST /Users → HTTP 503 (a transient endpoint failure). */
    private void stubScimUserCreate503() {
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse()
                .withStatus(503)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"detail\":\"service unavailable\"}")));
    }

    /** POST /Users → HTTP 400 (a permanent/client-side failure). */
    private void stubScimUserCreate400() {
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"detail\":\"bad request\"}")));
    }

    /**
     * Creates a user via admin REST and returns the raw status without throwing.
     * The base-class {@code createAdminUser} throws on >=400, which would mask the
     * rollback-induced failure being asserted here.
     */
    private int createUserStatus(RealmResource realm, String username, String email) {
        var user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        user.setEmailVerified(true);
        user.setEnabled(true);
        try (Response resp = realm.users().create(user)) {
            return resp.getStatus();
        }
    }

    /** True if Keycloak has a user with the given exact username. */
    private boolean userExists(RealmResource realm, String username) {
        List<UserRepresentation> found = realm.users().search(username, true);
        return found.stream().anyMatch(u -> username.equals(u.getUsername()));
    }

    @Test
    void alwaysRollsBackOnTransientFailure() {
        // rollback-strategy=always: any SCIM failure rolls back the admin create.
        stubScimUserCreate503();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("bulk-enabled", "false");
            cfg.putSingle("rollback-strategy", "always");
        });
        enableScimEventListener(r.realm());

        // Keycloak builds the 201 response inside the transaction before it commits, so the
        // REST client still sees 201 even when setRollbackOnly() discards the create at commit.
        // Assert user absence, not HTTP status.
        createUserStatus(r.realm(), "rb-always", "rb-always@test.local");

        assertEquals(false, userExists(r.realm(), "rb-always"),
            "user must NOT exist after rollback-strategy=always rolls back the failed SCIM create");
    }

    @Test
    void neverFailsOpenOnTransientFailure() {
        // rollback-strategy=never: SCIM 503 is swallowed (fail-open); admin create succeeds.
        stubScimUserCreate503();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("bulk-enabled", "false");
            cfg.putSingle("rollback-strategy", "never");
        });
        enableScimEventListener(r.realm());

        int status = createUserStatus(r.realm(), "rb-never", "rb-never@test.local");

        assertTrue(status >= 200 && status < 300,
            "admin create should succeed (2xx) under rollback-strategy=never, got " + status);
        assertEquals(true, userExists(r.realm(), "rb-never"),
            "user MUST exist under rollback-strategy=never: the SCIM failure is swallowed (fail-open)");
    }

    @Test
    void criticalOnlyDoesNotRollBackOnPermanentFailure() {
        // rollback-strategy=critical-only rolls back only on transient failures.
        // A permanent 400 does not roll back; the user exists.
        stubScimUserCreate400();
        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("bulk-enabled", "false");
            cfg.putSingle("rollback-strategy", "critical-only");
        });
        enableScimEventListener(r.realm());

        int status = createUserStatus(r.realm(), "rb-critical", "rb-critical@test.local");

        assertTrue(status >= 200 && status < 300,
            "admin create should succeed (2xx): a permanent 400 does not roll back under "
                + "critical-only, got " + status);
        assertEquals(true, userExists(r.realm(), "rb-critical"),
            "user MUST exist: critical-only does not roll back on a permanent (non-transient) failure");
    }
}
