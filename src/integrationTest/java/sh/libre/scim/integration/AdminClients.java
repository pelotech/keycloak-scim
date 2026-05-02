package sh.libre.scim.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.ext.ContextResolver;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;

/**
 * Build a Keycloak admin client whose Jackson mapper tolerates unknown response
 * fields. The CI matrix runs against multiple Keycloak server versions; a server
 * newer than the pinned admin-client model classes returns fields those classes
 * don't know about, which Jackson rejects by default.
 */
final class AdminClients {

    private AdminClients() {}

    static Keycloak forContainer(KeycloakContainer container) {
        ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        ContextResolver<ObjectMapper> resolver = new ContextResolver<>() {
            @Override
            public ObjectMapper getContext(Class<?> type) {
                return mapper;
            }
        };

        ResteasyClientBuilder builder = (ResteasyClientBuilder) ResteasyClientBuilder.newBuilder();
        builder.connectionPoolSize(10);
        builder.register(resolver);
        ResteasyClient client = builder.build();

        return KeycloakBuilder.builder()
            .serverUrl(container.getAuthServerUrl())
            .realm("master")
            .clientId("admin-cli")
            .username(container.getAdminUsername())
            .password(container.getAdminPassword())
            .resteasyClient(client)
            .build();
    }
}
