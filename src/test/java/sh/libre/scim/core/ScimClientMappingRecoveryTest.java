package sh.libre.scim.core;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.captaingoldfish.scim.sdk.common.resources.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.NonUniqueResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import sh.libre.scim.core.exceptions.InconsistentScimMappingException;
import sh.libre.scim.core.exceptions.InvalidResponseFromScimEndpointException;
import sh.libre.scim.jpa.ScimResource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP requests and real adapters, with a controlled mapping store. */
class ScimClientMappingRecoveryTest {
    private WireMockServer server;
    private ScimClient client;
    private ComponentModel component;
    private KeycloakSession session;
    private UserModel user;
    private EntityManager em;
    private TypedQuery<ScimResource> query;
    private final AtomicReference<ScimResource> mapping = new AtomicReference<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var creates = new java.util.concurrent.atomic.AtomicInteger();
        server = new WireMockServer(options().dynamicPort().extensions(
            new com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2() {
                public String getName() { return "unique-user"; }
                public boolean applyGlobally() { return false; }
                public com.github.tomakehurst.wiremock.http.ResponseDefinition transform(
                        com.github.tomakehurst.wiremock.stubbing.ServeEvent event) {
                    return creates.incrementAndGet() == 1
                        ? aResponse().withStatus(201).withHeader("Content-Type", "application/scim+json")
                            .withBody("{\"id\":\"remote-1\",\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}").build()
                        : aResponse().withStatus(409).build();
                }
            }));
        server.start();
        component = new ComponentModel();
        component.setId("destination-1");
        var cfg = new MultivaluedHashMap<String, String>();
        cfg.putSingle("auth-mode", "NONE");
        cfg.putSingle("endpoint", server.baseUrl());
        cfg.putSingle("content-type", "application/scim+json");
        cfg.putSingle("user-provision-on-update", "true");
        component.setConfig(cfg);
        session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-1");
        when(realm.getComponent(component.getId())).thenReturn(component);
        var jpa = mock(JpaConnectionProvider.class);
        em = mock(EntityManager.class);
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpa);
        when(jpa.getEntityManager()).thenReturn(em);
        query = mock(TypedQuery.class, RETURNS_SELF);
        when(em.createNamedQuery("findById", ScimResource.class)).thenReturn(query);
        when(query.getSingleResult()).thenAnswer(inv -> {
            if (mapping.get() == null) throw new NoResultException();
            return mapping.get();
        });
        when(query.getResultList()).thenAnswer(inv -> mapping.get() == null ? List.of() : List.of(mapping.get()));
        when(em.createNamedQuery("deleteDeactivatedByExternalId")).thenReturn(mock(Query.class, RETURNS_SELF));
        doAnswer(inv -> { mapping.set(inv.getArgument(0)); return null; }).when(em).persist(any(ScimResource.class));
        user = mock(UserModel.class);
        when(user.getId()).thenReturn("kc-user-1");
        when(user.getUsername()).thenReturn("alice");
        when(user.getFirstName()).thenReturn("Alice");
        when(user.getEmail()).thenReturn("alice@example.test");
        when(user.isEnabled()).thenReturn(true);
        client = ScimClient.forInteractive(component, session);
        server.stubFor(post(urlEqualTo("/Users")).willReturn(aResponse().withStatus(201)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],\"id\":\"remote-1\"}")));
        server.stubFor(put(urlEqualTo("/Users/remote-1")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json")
            .withBody("{\"id\":\"remote-1\",\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}")));
        server.stubFor(patch(urlEqualTo("/Users/remote-1")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json")
            .withBody("{\"id\":\"remote-1\",\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}")));
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recoversCurrentStateAndLaterUpdatesUseReturnedId(boolean enabled) {
        when(user.isEnabled()).thenReturn(enabled);
        String enterprise = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";
        component.getConfig().put("user-extension-mappings", List.of("department = " + enterprise + ":department"));
        when(user.getFirstAttribute("department")).thenReturn("Engineering");
        var role = mock(org.keycloak.models.RoleModel.class);
        when(role.getName()).thenReturn("scim-role");
        when(role.getFirstAttribute("scim")).thenReturn("true");
        when(user.getRoleMappingsStream()).thenAnswer(inv -> java.util.stream.Stream.of(role));
        client.replace(UserAdapter::new, user);
        assertThat(mapping.get().getExternalId()).isEqualTo("remote-1");
        assertThat(mapping.get().getId()).isEqualTo("kc-user-1");
        assertThat(mapping.get().getComponentId()).isEqualTo("destination-1");
        assertThat(mapping.get().getRealmId()).isEqualTo("realm-1");
        server.verify(1, postRequestedFor(urlEqualTo("/Users"))
            .withRequestBody(matchingJsonPath("$.active", equalTo(Boolean.toString(enabled))))
            .withRequestBody(matchingJsonPath("$.name.givenName", equalTo("Alice")))
            .withRequestBody(matchingJsonPath("$.externalId", equalTo("kc-user-1")))
            .withRequestBody(matchingJsonPath("$['" + enterprise + "'].department", equalTo("Engineering")))
            .withRequestBody(matchingJsonPath("$.roles[0].value", equalTo("scim-role"))));
        when(user.getFirstName()).thenReturn("Changed");
        client.replace(UserAdapter::new, user);
        server.verify(1, postRequestedFor(urlEqualTo("/Users")));
        server.verify(1, putRequestedFor(urlEqualTo("/Users/remote-1"))
            .withRequestBody(matchingJsonPath("$.name.givenName", equalTo("Changed"))));
        assertThat(mapping.get().getBodyHash()).startsWith("cleared-");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"unset,true", "unset,false", "false,true", "false,false"})
    void recoveryIsOffUnlessDestinationOptsIn(String policy, boolean enabled) {
        when(user.isEnabled()).thenReturn(enabled);
        if ("unset".equals(policy)) component.getConfig().remove("user-provision-on-update");
        else component.getConfig().putSingle("user-provision-on-update", policy);
        assertThatThrownBy(() -> client.replace(UserAdapter::new, user))
            .isInstanceOf(InconsistentScimMappingException.class);
        assertThat(mapping.get()).isNull();
        server.verify(0, postRequestedFor(urlEqualTo("/Users")));
        verify(em, never()).persist(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void policyOffStillAllowsRegularCreationAndMappedUpdates(boolean enabled) {
        component.getConfig().putSingle("user-provision-on-update", "false");
        client.create(UserAdapter::new, user);
        when(user.isEnabled()).thenReturn(enabled);
        client.replace(UserAdapter::new, user);
        server.verify(1, postRequestedFor(urlEqualTo("/Users")));
        server.verify(1, putRequestedFor(urlEqualTo("/Users/remote-1"))
            .withRequestBody(matchingJsonPath("$.active", equalTo(Boolean.toString(enabled)))));
    }

    @Test
    void mappedPatchBehaviorIsPreservedAfterRecovery() {
        component.getConfig().putSingle("user-patchOp", "true");
        client.replace(UserAdapter::new, user);
        client.replace(UserAdapter::new, user);
        server.verify(1, postRequestedFor(urlEqualTo("/Users")));
        server.verify(1, patchRequestedFor(urlEqualTo("/Users/remote-1")));
    }

    @Test
    void excludedUserIsNotProvisioned() {
        when(user.getFirstAttribute("scim-skip")).thenReturn("true");
        client.replace(UserAdapter::new, user);
        assertThat(mapping.get()).isNull();
        server.verify(0, postRequestedFor(urlEqualTo("/Users")));
    }

    @Test
    void propagationRoleExclusionIsRespected() {
        component.getConfig().putSingle("propagation-role", "missing-role");
        client.replace(UserAdapter::new, user);
        assertThat(mapping.get()).isNull();
        server.verify(0, postRequestedFor(urlEqualTo("/Users")));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 409, 503})
    void rejectedRecoveryIsClassifiedAndDoesNotMap(int status) {
        server.stubFor(post(urlEqualTo("/Users")).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:Error\"],\"status\":\"" + status + "\"}")));
        var error = catchThrowableOfType(() -> client.replace(UserAdapter::new, user),
            InvalidResponseFromScimEndpointException.class);
        assertThat(error).isNotNull();
        assertThat(error.isTransient()).isEqualTo(status == 503);
        assertThat(mapping.get()).isNull();
        if (status == 409) server.verify(1, postRequestedFor(urlEqualTo("/Users")));
    }

    @Test
    void ambiguousMappingLookupDoesNotTriggerProvisioning() {
        doThrow(new NonUniqueResultException()).when(query).getSingleResult();
        assertThatThrownBy(() -> client.replace(UserAdapter::new, user))
            .isInstanceOf(InconsistentScimMappingException.class);
        server.verify(0, postRequestedFor(urlEqualTo("/Users")));
    }

    @Test
    void mappingAppearingDuringCreateStillReceivesTheUpdate() {
        var existing = new ScimResource();
        existing.setExternalId("remote-1");
        when(query.getResultList()).thenReturn(List.of(existing));
        client.replace(UserAdapter::new, user);
        server.verify(0, postRequestedFor(urlEqualTo("/Users")));
        server.verify(1, putRequestedFor(urlEqualTo("/Users/remote-1"))
            .withRequestBody(matchingJsonPath("$.name.givenName", equalTo("Alice"))));
        verify(query, times(1)).getSingleResult();
    }

    @Test
    void ambiguousMappingAppearingDuringProvisioningRemainsVisibleFailure() {
        when(query.getResultList()).thenReturn(List.of(new ScimResource(), new ScimResource()));
        assertThatThrownBy(() -> client.replace(UserAdapter::new, user))
            .isInstanceOf(InconsistentScimMappingException.class);
        server.verify(0, postRequestedFor(urlEqualTo("/Users")));
    }

    @Test
    void localPersistenceFailureDoesNotReportRecoverySuccess() {
        doThrow(new IllegalStateException("store unavailable")).when(em).persist(any());
        assertThatThrownBy(() -> client.replace(UserAdapter::new, user))
            .isInstanceOf(InconsistentScimMappingException.class);
        assertThat(mapping.get()).isNull();
        server.verify(1, postRequestedFor(urlEqualTo("/Users")));
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"id\":\" \"}"})
    void invalidSuccessfulResponseDoesNotEstablishMapping(String body) {
        server.stubFor(post(urlEqualTo("/Users")).willReturn(aResponse().withStatus(201)
            .withHeader("Content-Type", "application/scim+json").withBody(body)));
        assertThatThrownBy(() -> client.replace(UserAdapter::new, user))
            .isInstanceOf(InvalidResponseFromScimEndpointException.class);
        assertThat(mapping.get()).isNull();
        verify(em, never()).persist(any());
    }

    @Test
    void lostCreateResponseCanBecomeAConflictWithoutInventingAMapping() {
        server.stubFor(post(urlEqualTo("/Users"))
            .inScenario("lost-response").whenScenarioStateIs("Started")
            .willSetStateTo("created")
            .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));
        server.stubFor(post(urlEqualTo("/Users"))
            .inScenario("lost-response").whenScenarioStateIs("created")
            .willReturn(aResponse().withStatus(409)));
        var error = catchThrowableOfType(() -> client.replace(UserAdapter::new, user),
            InvalidResponseFromScimEndpointException.class);
        assertThat(error).isNotNull();
        assertThat(error.httpStatus()).isEqualTo(409);
        assertThat(error.isTransient()).isFalse();
        assertThat(mapping.get()).isNull();
        verify(em, never()).persist(any());
        assertThat(server.findAll(postRequestedFor(urlEqualTo("/Users")))).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void concurrentRecoverySurfacesReceiverConflictAndMapsOnlyTheAcceptedResponse() throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        when(query.getResultList()).thenAnswer(inv -> {
            if (reads.incrementAndGet() <= 2) {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return List.of(); // both attempts observed absence before either POST
            }
            return mapping.get() == null ? List.of() : List.of(mapping.get());
        });
        server.stubFor(post(urlEqualTo("/Users"))
            .willReturn(aResponse().withTransformers("unique-user")));
        java.util.function.Supplier<Throwable> attempt = () -> {
            var worker = ScimClient.forInteractive(component, session);
            try {
                return catchThrowable(() -> worker.replace(UserAdapter::new, user));
            } finally {
                worker.close();
            }
        };
        var first = java.util.concurrent.CompletableFuture.supplyAsync(attempt);
        var second = java.util.concurrent.CompletableFuture.supplyAsync(attempt);
        var outcomes = java.util.Arrays.asList(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
            second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertThat(outcomes.stream().filter(java.util.Objects::isNull).count()).isEqualTo(1);
        var failure = outcomes.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        assertThat(failure).isInstanceOf(InvalidResponseFromScimEndpointException.class);
        assertThat(((InvalidResponseFromScimEndpointException) failure).httpStatus()).isEqualTo(409);
        verify(em, times(1)).persist(any(ScimResource.class));
        assertThat(mapping.get().getExternalId()).isEqualTo("remote-1");
    }

}
