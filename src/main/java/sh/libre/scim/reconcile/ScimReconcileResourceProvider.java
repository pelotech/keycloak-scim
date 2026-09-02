package sh.libre.scim.reconcile;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.AdminRoles;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resource.RealmResourceProvider;

import java.time.Duration;
import java.util.Locale;

import sh.libre.scim.core.ScimClientMetrics;
import sh.libre.scim.storage.ScimStorageProviderFactory;

/**
 * Realm-scoped admin endpoint for manually triggering a reconciliation pass.
 *
 * <p>Primary usage is automation — operators can force a pass after a known
 * LDAP cleanup without waiting for the timer. Also the integration-test hook.
 *
 * <p>Route: {@code POST /realms/{realm}/scim-reconcile/{componentId}}.
 *
 * <p>Request body: none. Query params: {@code thresholdHours} (optional,
 * default 48).
 *
 * <p>Response: 200 with a JSON body
 * {@code {"deleted": N, "groupsDeleted": D, "userDeleteMode": "delete"|"deactivate"}}.
 * {@code deleted} counts user deprovision operations issued: SCIM DELETE
 * calls, or {@code active:false} deactivations when the component is
 * configured with {@code delete-mode=deactivate}; {@code userDeleteMode} says
 * which. {@code groupsDeleted} reports the number of federated groups with
 * zero local members deleted by the group phase.
 *
 * <p>Every route here requires an admin caller. Keycloak does not
 * authenticate {@link RealmResourceProvider} routes for us, so each handler
 * calls {@link #requireAdmin()} first. See that method for the rules.
 */
public class ScimReconcileResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;

    public ScimReconcileResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @POST
    @Path("{componentId}")
    public Response reconcile(
            @PathParam("componentId") String componentId,
            @jakarta.ws.rs.QueryParam("thresholdHours") Long thresholdHours) {

        Response denied = requireAdmin();
        if (denied != null) {
            return denied;
        }

        var realm = session.getContext().getRealm();
        var component = realm.getComponent(componentId);
        if (component == null || !ScimStorageProviderFactory.ID.equals(component.getProviderId())) {
            return errorResponse(Response.Status.NOT_FOUND,
                "no SCIM provider component with id " + componentId);
        }

        Duration threshold = Duration.ofHours(thresholdHours != null ? thresholdHours : 48L);
        var result = new ReconcilerRunner(session, component, threshold).run();

        return Response.ok(
            "{\"deleted\":" + result.usersDeprovisioned()
            + ",\"groupsDeleted\":" + result.groupsDeleted()
            + ",\"userDeleteMode\":\"" + result.userDeleteMode() + "\"}",
            MediaType.APPLICATION_JSON).build();
    }

    /**
     * Diagnostic endpoint: returns a plain-text summary of {@link ScimClientMetrics}
     * counters (per-phase timing breakdown for ScimClient.create). Used by the
     * perf test harness.
     *
     * <p>Route: {@code GET /realms/{realm}/scim-reconcile/metrics}
     */
    @GET
    @Path("metrics")
    public Response metrics() {
        Response denied = requireAdmin();
        if (denied != null) {
            return denied;
        }
        return Response.ok(ScimClientMetrics.summary(), MediaType.TEXT_PLAIN).build();
    }

    /** Resets metric counters. Used by the perf harness between scenarios. */
    @POST
    @Path("metrics/reset")
    public Response resetMetrics() {
        Response denied = requireAdmin();
        if (denied != null) {
            return denied;
        }
        ScimClientMetrics.reset();
        return Response.noContent().build();
    }

    /**
     * Checks that the caller is an admin of the realm in the request path.
     * Returns {@code null} when the caller may proceed, otherwise the error
     * response to send back.
     *
     * <p>The caller must present a bearer access token issued by the realm
     * named in the request path. Keycloak verifies a token against the realm
     * of the current request, so a token minted by the master realm does not
     * authenticate a request to another realm. Callers targeting realm
     * {@code X} need a user or service account that lives in realm {@code X}.
     *
     * <p>The authenticated user must hold the realm's {@code manage-users}
     * admin role. That role lives on the {@code realm-management} client in
     * an ordinary realm. The master realm has no {@code realm-management}
     * client; its equivalent roles live on the client returned by
     * {@code getMasterAdminClient()}, which the {@code admin} realm role
     * pulls in as a composite. {@code UserModel.hasRole} resolves composites
     * and group-inherited roles, so holding {@code realm-admin} or master's
     * {@code admin} satisfies the check.
     *
     * <p>Missing or unverifiable token gives 401. A verified token whose user
     * lacks the role gives 403.
     */
    private Response requireAdmin() {
        String tokenString = bearerToken();
        if (tokenString == null) {
            return unauthorized("missing bearer token");
        }

        AuthenticationManager.AuthResult auth =
            new AppAuthManager.BearerTokenAuthenticator(session)
                .setTokenString(tokenString)
                .authenticate();
        if (auth == null || auth.getUser() == null) {
            return unauthorized("invalid bearer token");
        }

        if (!canManageUsers(session.getContext().getRealm(), auth.getUser())) {
            return errorResponse(Response.Status.FORBIDDEN,
                "caller lacks the " + AdminRoles.MANAGE_USERS + " admin role on this realm");
        }
        return null;
    }

    /**
     * Reads the token out of the Authorization header. Parsed here rather
     * than with AppAuthManager's extract helpers because the return type of
     * those helpers changed between the Keycloak versions we support.
     */
    private String bearerToken() {
        var headers = session.getContext().getRequestHeaders();
        if (headers == null) {
            return null;
        }
        String header = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            return null;
        }
        String token = trimmed.substring("bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * True when the user holds manage-users on either client that can carry
     * the realm's admin roles.
     *
     * <p>An ordinary realm keeps them on {@code realm-management}. The master
     * realm has no such client and keeps them on its master admin client
     * instead. For an ordinary realm the master admin client lives in the
     * master realm, and a user of an ordinary realm cannot be mapped to roles
     * from another realm's client, so consulting both is safe.
     */
    private static boolean canManageUsers(RealmModel realm, UserModel user) {
        return holdsManageUsers(realm.getClientByClientId(Constants.REALM_MANAGEMENT_CLIENT_ID), user)
            || holdsManageUsers(realm.getMasterAdminClient(), user);
    }

    private static boolean holdsManageUsers(ClientModel adminClient, UserModel user) {
        if (adminClient == null) {
            return false;
        }
        RoleModel manageUsers = adminClient.getRole(AdminRoles.MANAGE_USERS);
        return manageUsers != null && user.hasRole(manageUsers);
    }

    private static Response unauthorized(String message) {
        return Response.status(Response.Status.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .entity("{\"error\":\"" + message + "\"}")
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    private static Response errorResponse(Response.Status status, String message) {
        return Response.status(status)
            .entity("{\"error\":\"" + message + "\"}")
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    @Override
    public void close() {
        // no-op
    }
}
