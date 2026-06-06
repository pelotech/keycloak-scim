package sh.libre.scim.core;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RoleMapperModel;
import de.captaingoldfish.scim.sdk.common.resources.ResourceNode;

/**
 * Constructs an {@link Adapter} for a given session + component, replacing the
 * previous reflection-based instantiation in {@link ScimClient}. Implemented in
 * practice by the adapter constructors as method references — {@code UserAdapter::new},
 * {@code GroupAdapter::new}.
 *
 * <p>Must be {@code public}: it is a parameter type on {@code ScimClient}'s public
 * CRUD/sync methods, whose callers live in other packages.
 */
@FunctionalInterface
public interface AdapterFactory<M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> {
    A create(KeycloakSession session, String componentId);
}
