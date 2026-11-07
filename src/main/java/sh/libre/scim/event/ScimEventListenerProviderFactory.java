package sh.libre.scim.event;

import org.keycloak.Config.Scope;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import sh.libre.scim.core.RoleRefreshScheduler;

public class ScimEventListenerProviderFactory implements EventListenerProviderFactory {

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new ScimEventListenerProvider(session);
    }

    @Override
    public void init(Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // postInit, not init: a refresh needs the session factory.
        RoleRefreshScheduler.start(factory);
    }

    @Override
    public void close() {
        RoleRefreshScheduler.stop();
    }

    @Override
    public String getId() {
        return "scim";
    }
}
