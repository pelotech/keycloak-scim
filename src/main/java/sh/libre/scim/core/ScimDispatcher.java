package sh.libre.scim.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;

import sh.libre.scim.storage.ScimStorageProviderFactory;

public class ScimDispatcher implements AutoCloseable {
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_GROUP = "group";

    final private KeycloakSession session;
    final private Logger LOGGER = Logger.getLogger(ScimDispatcher.class);
    /**
     * Cache of {@link ScimClient}s keyed by SCIM provider component id.
     *
     * <p>Construction of a {@link ScimClient} is non-trivial: it builds an
     * Apache HttpClient pool, configures auth headers, and instantiates a
     * resilience4j RetryRegistry. Doing that on every event scales linearly
     * with event volume — for a 10k-user federation sync, 10k client setups.
     * Caching by component id within a dispatcher's lifetime collapses that
     * to one client per (dispatcher, component) pair.
     *
     * <p>Lifetime: the dispatcher is owned by an {@link sh.libre.scim.event.ScimEventListenerProvider}
     * (one per Keycloak session), an {@link sh.libre.scim.ldap.ScimLdapStorageMapper}
     * (one per LDAP-mapper instance, also per-session), or a one-off block
     * in {@link sh.libre.scim.storage.ScimStorageProviderFactory#sync}.
     * Each owner must call {@link #close()} when done so the HTTP clients
     * are released.
     */
    private final Map<String, ScimClient> clients = new HashMap<>();

    public ScimDispatcher(KeycloakSession session) {
        this.session = session;
    }

    public void run(String scope, Consumer<ScimClient> f) {
        session.getContext().getRealm().getComponentsStream()
                .filter(m -> {
                    return ScimStorageProviderFactory.ID.equals(m.getProviderId()) && m.get("enabled", true)
                            && m.get("propagation-" + scope, false);
                })
                .forEach(m -> runOne(m, f));
    }

    public void runOne(ComponentModel m, Consumer<ScimClient> f) {
        LOGGER.debugf("%s %s %s %s", m.getId(), m.getName(), m.getProviderId(), m.getProviderType());
        var client = clients.computeIfAbsent(m.getId(), id -> new ScimClient(m, session));
        try {
            f.accept(client);
        } catch (Exception e) {
            LOGGER.errorf(e, "SCIM dispatch failed on component %s (%s)", m.getId(), m.getName());
        }
    }

    @Override
    public void close() {
        for (var c : clients.values()) {
            try {
                c.close();
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "error closing ScimClient");
            }
        }
        clients.clear();
    }
}
