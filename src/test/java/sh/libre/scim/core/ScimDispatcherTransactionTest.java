package sh.libre.scim.core;

import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.ModelException;
import org.keycloak.services.DefaultKeycloakTransactionManager;
import sh.libre.scim.core.exceptions.InvalidResponseFromScimEndpointException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Tests actual transaction completion, rather than just a rollback flag call. */
class ScimDispatcherTransactionTest {
    private KeycloakSession session;
    private DefaultKeycloakTransactionManager transactions;
    private KeycloakTransaction persistence;
    private KeycloakTransaction afterCompletion;

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        var attributes = new HashMap<String, Object>();
        when(session.getAttribute(anyString())).thenAnswer(i -> attributes.get(i.getArgument(0)));
        doAnswer(i -> { attributes.put(i.getArgument(0), i.getArgument(1)); return null; })
            .when(session).setAttribute(anyString(), any());
        transactions = spy(new DefaultKeycloakTransactionManager(session));
        when(session.getTransactionManager()).thenReturn(transactions);
        transactions.begin();
        persistence = mock(KeycloakTransaction.class);
        afterCompletion = mock(KeycloakTransaction.class);
        transactions.enlist(persistence);
        transactions.enlistAfterCompletion(afterCompletion);
    }

    private ComponentModel component(String policy) {
        var model = new ComponentModel();
        model.setId("destination-" + policy);
        model.put("auth-mode", "NONE");
        model.put("endpoint", "https://scim.example.test");
        model.put("content-type", "application/scim+json");
        model.put("rollback-strategy", policy);
        return model;
    }

    @ParameterizedTest
    @CsvSource({"never,409,false", "never,503,false", "always,409,true", "always,503,true",
        "critical-only,409,false", "critical-only,503,true"})
    void explicitCommitHonorsRollbackPolicy(String policy, int status, boolean rollback) {
        try (var dispatcher = new ScimDispatcher(session)) {
            dispatcher.runOne(component(policy), client -> {
                throw new InvalidResponseFromScimEndpointException(status, "receiver rejected request");
            });
        }
        assertThat(transactions.getRollbackOnly()).isEqualTo(rollback);
        if (rollback) {
            assertThatThrownBy(transactions::commit).isInstanceOf(ModelException.class)
                .hasMessageContaining("refusing to commit");
            verify(persistence, never()).commit();
            verify(persistence).rollback();
            verify(afterCompletion, never()).commit();
            verify(afterCompletion).rollback();
        } else {
            assertThatCode(transactions::commit).doesNotThrowAnyException();
            verify(persistence).commit();
            verify(persistence, never()).rollback();
            verify(afterCompletion).commit();
        }
        assertThat(transactions.isActive()).isFalse();
    }

    @Test
    void normalRollbackDoesNotExecuteTheCommitVeto() {
        try (var dispatcher = new ScimDispatcher(session)) {
            dispatcher.runOne(component("always"), client -> {
                throw new InvalidResponseFromScimEndpointException(503, "unavailable");
            });
        }
        assertThatCode(transactions::rollback).doesNotThrowAnyException();
        verify(persistence).rollback();
        verify(persistence, never()).commit();
        verify(afterCompletion).rollback();
    }

    @Test
    void multipleDispatchersRegisterOnlyOneVetoForTheSession() {
        try (var first = new ScimDispatcher(session); var second = new ScimDispatcher(session)) {
            first.runOne(component("always"), client -> {
                throw new InvalidResponseFromScimEndpointException(409, "conflict");
            });
            second.runOne(component("always"), client -> {
                throw new InvalidResponseFromScimEndpointException(503, "unavailable");
            });
        }
        verify(transactions, times(1)).enlistPrepare(any());
        assertThatThrownBy(transactions::commit).isInstanceOf(ModelException.class);
        verify(persistence, times(1)).rollback();
        verify(persistence, never()).commit();
    }
}
