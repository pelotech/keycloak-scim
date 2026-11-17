package sh.libre.scim.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/** Admin UPDATE recovery through a real Keycloak, including persisted mapping reuse. */
class ScimMissingMappingIT extends IntegrationTestBase {
    private static final String ENTERPRISE = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";
    private record Setup(RealmResource realm, String userId) {}

    private Setup unmapped(boolean enabled, Consumer<MultivaluedHashMap<String, String>> configure) {
        String name = "mapping-" + UUID.randomUUID().toString().substring(0, 8);
        var rep = new RealmRepresentation();
        rep.setRealm(name);
        rep.setEnabled(true);
        admin().realms().create(rep);
        var realm = admin().realm(name);
        addScimStorageProvider(realm, cfg -> {
            cfg.putSingle("user-provision-on-update", "true");
            configure.accept(cfg);
        });
        enableUnmanagedUserAttributes(realm);
        // The listener is registered after creation: this user has no SCIM mapping.
        var user = new UserRepresentation();
        user.setUsername("recover");
        user.setEmail("recover@example.test");
        user.setEmailVerified(true);
        user.setEnabled(enabled);
        user.setFirstName("Before");
        user.setAttributes(Map.of("department", List.of("Engineering")));
        String id;
        try (var response = realm.users().create(user)) {
            assertEquals(201, response.getStatus());
            var path = response.getLocation().getPath();
            id = path.substring(path.lastIndexOf('/') + 1);
        }
        return new Setup(realm, id);
    }

    private void enable(Setup setup) {
        enableScimEventListener(setup.realm());
    }

    private void touch(Setup setup, String name) {
        assertEquals(204, touchStatus(setup, name));
    }

    private int touchStatus(Setup setup, String name) {
        var rep = new UserRepresentation();
        rep.setFirstName(name);
        try {
            setup.realm().users().get(setup.userId()).update(rep);
            return 204;
        } catch (jakarta.ws.rs.WebApplicationException e) {
            try (var response = e.getResponse()) {
                return response.getStatus();
            }
        }
    }

    private void acceptCreate(String path, String id) {
        wireMock.stubFor(post(urlPathEqualTo(path)).willReturn(aResponse().withStatus(201)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],\"id\":\"" + id + "\"}")));
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void recoveryPreservesCurrentStateAndMappingAcrossTransactions(boolean active, boolean usePatch) throws Exception {
        acceptCreate("/Users", "recovered-id");
        wireMock.stubFor(put(urlPathEqualTo("/Users/recovered-id"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json")
                .withBody("{\"id\":\"recovered-id\",\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}")));
        wireMock.stubFor(patch(urlPathEqualTo("/Users/recovered-id"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json")
                .withBody("{\"id\":\"recovered-id\",\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}")));
        var setup = unmapped(active, cfg -> {
            cfg.putSingle("user-patchOp", Boolean.toString(usePatch));
            cfg.put("user-extension-mappings", List.of("department = " + ENTERPRISE + ":department"));
        });
        var role = new RoleRepresentation();
        role.setName("scim-role");
        role.setAttributes(Map.of("scim", List.of("true")));
        setup.realm().roles().create(role);
        setup.realm().users().get(setup.userId()).roles().realmLevel()
            .add(List.of(setup.realm().roles().get("scim-role").toRepresentation()));
        enable(setup);
        touch(setup, "Recovery");
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/Users")));
        var request = wireMock.findAll(postRequestedFor(urlPathEqualTo("/Users"))).getFirst();
        var body = new ObjectMapper().readTree(request.getBodyAsString());
        assertEquals(active, body.path("active").asBoolean());
        assertEquals("Recovery", body.path("name").path("givenName").asText());
        assertEquals(setup.userId(), body.path("externalId").asText());
        assertEquals("Engineering", body.path(ENTERPRISE).path("department").asText());
        assertTrue(body.path("roles").toString().contains("scim-role"));
        touch(setup, "Later");
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/Users")));
        if (usePatch) wireMock.verify(1, patchRequestedFor(urlPathEqualTo("/Users/recovered-id")));
        else wireMock.verify(1, putRequestedFor(urlPathEqualTo("/Users/recovered-id"))
            .withRequestBody(matchingJsonPath("$.name.givenName", equalTo("Later"))));
        assertEquals(active, setup.realm().users().get(setup.userId()).toRepresentation().isEnabled());
    }

    @Test
    void enablingAnUnmappedUserProvisionsWithoutAnotherEdit() {
        acceptCreate("/Users", "enabled-id");
        var setup = unmapped(false, cfg -> {});
        enable(setup);
        var update = new UserRepresentation();
        update.setEnabled(true);
        setup.realm().users().get(setup.userId()).update(update);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/Users"))
            .withRequestBody(matchingJsonPath("$.active", equalTo("true"))));
    }

    @ParameterizedTest
    @CsvSource({"never,409,false", "always,409,true", "critical-only,409,false",
        "never,503,false", "always,503,true", "critical-only,503,true"})
    void recoveryFailureUsesRollbackPolicyAndLeavesNoMapping(String policy, int status, boolean rollback) {
        wireMock.stubFor(post(urlPathEqualTo("/Users")).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:Error\"],\"status\":\"" + status + "\"}")));
        var setup = unmapped(true, cfg -> cfg.putSingle("rollback-strategy", policy));
        enable(setup);
        assertEquals(rollback ? 400 : 204, touchStatus(setup, "Failed"));
        assertEquals(rollback ? "Before" : "Failed",
            setup.realm().users().get(setup.userId()).toRepresentation().getFirstName());
        if (status == 409) wireMock.verify(1, postRequestedFor(urlPathEqualTo("/Users")));
        wireMock.resetRequests();
        acceptCreate("/Users", "retry-id");
        touch(setup, "Retry");
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/Users")));
    }

    @ParameterizedTest
    @CsvSource({"never,409,false,false", "never,503,false,false", "always,409,true,false",
        "always,503,true,false", "critical-only,409,false,false", "critical-only,503,true,false",
        "never,409,false,true", "never,503,false,true", "always,409,true,true",
        "always,503,true,true", "critical-only,409,false,true", "critical-only,503,true,true"})
    void mappedUpdateFailureUsesRollbackPolicy(String policy, int status, boolean rollback, boolean usePatch) {
        acceptCreate("/Users", "mapped-id");
        var setup = unmapped(true, cfg -> {
            cfg.putSingle("rollback-strategy", policy);
            cfg.putSingle("user-patchOp", Boolean.toString(usePatch));
        });
        enable(setup);
        touch(setup, "Mapped");
        wireMock.stubFor(put(urlPathEqualTo("/Users/mapped-id"))
            .willReturn(aResponse().withStatus(status)));
        wireMock.stubFor(patch(urlPathEqualTo("/Users/mapped-id"))
            .willReturn(aResponse().withStatus(status)));

        assertEquals(rollback ? 400 : 204, touchStatus(setup, "Failed"));
        assertEquals(rollback ? "Mapped" : "Failed",
            setup.realm().users().get(setup.userId()).toRepresentation().getFirstName());
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/Users")));
        if (status == 409) {
            if (usePatch) wireMock.verify(1, patchRequestedFor(urlPathEqualTo("/Users/mapped-id")));
            else wireMock.verify(1, putRequestedFor(urlPathEqualTo("/Users/mapped-id")));
        }

        wireMock.stubFor(put(urlPathEqualTo("/Users/mapped-id"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json")
                .withBody("{\"id\":\"mapped-id\",\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}")));
        wireMock.stubFor(patch(urlPathEqualTo("/Users/mapped-id"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json")
                .withBody("{\"id\":\"mapped-id\",\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}")));
        touch(setup, "Retry");
        assertEquals("Retry", setup.realm().users().get(setup.userId()).toRepresentation().getFirstName());
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/Users")));
    }

    @Test
    void oneDestinationConflictDoesNotDiscardOtherMapping() {
        acceptCreate("/Users", "first-id");
        wireMock.stubFor(put(urlPathEqualTo("/Users/first-id"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json")
                .withBody("{\"id\":\"first-id\",\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}")));
        wireMock.stubFor(post(urlPathEqualTo("/second/Users"))
            .willReturn(aResponse().withStatus(409)));
        var setup = unmapped(true, cfg -> cfg.putSingle("rollback-strategy", "never"));
        var second = scimComponent(setup.realm());
        second.setId(null);
        second.setName("second-destination");
        second.getConfig().putSingle("endpoint", "http://host.testcontainers.internal:" + wireMock.port() + "/second");
        try (var response = setup.realm().components().add(second)) {
            assertEquals(201, response.getStatus());
        }
        enable(setup);
        touch(setup, "First");
        touch(setup, "Second");
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/Users")));
        wireMock.verify(1, putRequestedFor(urlPathEqualTo("/Users/first-id")));
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/second/Users")));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"false"})
    void policyIsIndependentForEachDestinationAndDefaultsOff(String policy) {
        acceptCreate("/Users", "first-id");
        var setup = unmapped(true, cfg -> {});
        var second = scimComponent(setup.realm());
        second.setId(null);
        second.setName("default-off-destination");
        second.getConfig().putSingle("endpoint", "http://host.testcontainers.internal:" + wireMock.port() + "/second");
        if (policy == null) second.getConfig().remove("user-provision-on-update");
        else second.getConfig().putSingle("user-provision-on-update", policy);
        try (var response = setup.realm().components().add(second)) {
            assertEquals(201, response.getStatus());
        }
        enable(setup);
        touch(setup, "Policy");
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/Users")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/second/Users")));
        assertEquals("Policy", setup.realm().users().get(setup.userId()).toRepresentation().getFirstName());
    }

    @ParameterizedTest
    @CsvSource({"skip", "role", "provider", "propagation"})
    void exclusionsDoNotProvision(String exclusion) {
        var setup = unmapped(true, cfg -> {
            if (exclusion.equals("role")) cfg.putSingle("propagation-role", "not-granted");
            if (exclusion.equals("provider")) cfg.putSingle("enabled", "false");
            if (exclusion.equals("propagation")) cfg.putSingle("propagation-user", "false");
        });
        if (exclusion.equals("skip")) {
            var rep = setup.realm().users().get(setup.userId()).toRepresentation();
            rep.setAttributes(Map.of("scim-skip", List.of("true")));
            setup.realm().users().get(setup.userId()).update(rep);
        }
        enable(setup);
        touch(setup, "Excluded");
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/Users")));
    }
}
