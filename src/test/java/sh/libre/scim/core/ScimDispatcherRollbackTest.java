package sh.libre.scim.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransactionManager;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sh.libre.scim.core.exceptions.InconsistentScimMappingException;
import sh.libre.scim.core.exceptions.InvalidResponseFromScimEndpointException;
import sh.libre.scim.core.exceptions.ScimPropagationException;
import sh.libre.scim.storage.ScimStorageProviderFactory;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** ScimDispatcher.runOne calls setRollbackOnly() per rollback-strategy, transience, and failure type. */
@ExtendWith(MockitoExtension.class)
class ScimDispatcherRollbackTest {

    @Mock KeycloakSession session;
    @Mock KeycloakTransactionManager txnManager;

    private ScimDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ScimDispatcher(session);
    }

    /**
     * Builds a ComponentModel that {@code new ScimClient(model, session)} can construct.
     * The endpoint URL must survive ScimRequestBuilder construction; no network call is made.
     */
    private ComponentModel componentWithStrategy(String strategy) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        config.putSingle("rollback-strategy", strategy);
        model.setConfig(config);
        model.setId("comp-" + strategy);
        model.setName("test-" + strategy);
        model.setProviderId(ScimStorageProviderFactory.ID);
        model.setProviderType("org.keycloak.storage.UserStorageProvider");
        return model;
    }

    /** Transient failure: 503 endpoint error. */
    private ScimPropagationException transientEx() {
        return new InvalidResponseFromScimEndpointException(503, "service unavailable");
    }

    /** Permanent failure: missing/inconsistent mapping. */
    private ScimPropagationException permanentEx() {
        return new InconsistentScimMappingException("no mapping found");
    }

    /**
     * Matrix: strategy x failure-type → whether setRollbackOnly() is expected.
     *
     * | strategy      | transient | permanent |
     * |---------------|-----------|-----------|
     * | never         | false     | false     |
     * | always        | true      | true      |
     * | critical-only | true      | false     |
     */
    @ParameterizedTest(name = "strategy={0} transient={1} → rollback={2}")
    @CsvSource({
        "never,         true,  false",
        "never,         false, false",
        "always,        true,  true",
        "always,        false, true",
        "critical-only, true,  true",
        "critical-only, false, false",
    })
    void rollbackCalledAccordingToStrategy(
            String strategy, boolean isTransient, boolean expectRollback) {
        // Lenient: the "never" strategy never calls getTransactionManager(); strict
        // stubbing would raise UnnecessaryStubbingException for those cases.
        org.mockito.Mockito.lenient().when(session.getTransactionManager()).thenReturn(txnManager);

        ScimPropagationException ex = isTransient ? transientEx() : permanentEx();
        var model = componentWithStrategy(strategy.strip());
        dispatcher.runOne(model, client -> { throw ex; });

        if (expectRollback) {
            verify(txnManager, times(1)).setRollbackOnly();
        } else {
            verify(txnManager, never()).setRollbackOnly();
        }
    }
}
