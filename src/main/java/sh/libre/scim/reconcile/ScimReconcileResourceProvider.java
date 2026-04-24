package sh.libre.scim.reconcile;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

import java.time.Duration;

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
 * <p>Response: 200 with a JSON body {@code {"deleted": N}} indicating how
 * many SCIM DELETE calls were issued.
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

        var realm = session.getContext().getRealm();
        var component = realm.getComponent(componentId);
        if (component == null || !ScimStorageProviderFactory.ID.equals(component.getProviderId())) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"no SCIM provider component with id " + componentId + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
        }

        Duration threshold = Duration.ofHours(thresholdHours != null ? thresholdHours : 48L);
        int deleted = new ReconcilerRunner(session, component, threshold).run();

        return Response.ok("{\"deleted\":" + deleted + "}", MediaType.APPLICATION_JSON).build();
    }

    @Override
    public void close() {
        // no-op
    }
}
