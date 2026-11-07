package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.RealmModel;
import org.mockito.ArgumentCaptor;
import sh.libre.scim.storage.ScimStorageProviderFactory;

class ScimDispatcherRoleRefreshTest {

    private KeycloakSession session;
    private KeycloakTransactionManager transactions;
    private RoleRefreshScheduler scheduler;

    private static ComponentModel component(String id, String propagationUser) {
        var m = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("propagation-user", propagationUser);
        m.setConfig(config);
        m.setId(id);
        m.setProviderId(ScimStorageProviderFactory.ID);
        return m;
    }

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        transactions = mock(KeycloakTransactionManager.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-1");
        when(realm.getComponentsStream())
            .thenAnswer(i -> Stream.of(component("on", "true"), component("off", "false")));
        when(session.getTransactionManager()).thenReturn(transactions);
        scheduler = new RoleRefreshScheduler(
            (realmId, componentId, shutdown) -> RoleRefreshResult.FINISHED,
            (task, delay) -> {}, Duration.ofSeconds(60), null);
        RoleRefreshScheduler.install(scheduler);
    }

    @AfterEach
    void tearDown() {
        RoleRefreshScheduler.stop();
    }

    private KeycloakTransaction enlisted() {
        var captor = ArgumentCaptor.forClass(KeycloakTransaction.class);
        verify(transactions).enlistAfterCompletion(captor.capture());
        return captor.getValue();
    }

    @Test
    void aRequestIsMadeOnlyAfterCommitAndOnlyForUserPropagation() {
        new ScimDispatcher(session).requestRoleRefresh();
        var transaction = enlisted();
        assertThat(scheduler.state("on")).isNull();

        transaction.commit();

        assertThat(scheduler.state("on")).isEqualTo(RoleRefreshScheduler.State.SCHEDULED);
        assertThat(scheduler.state("off")).isNull();
    }

    @Test
    void aRollbackMakesNoRequest() {
        new ScimDispatcher(session).requestRoleRefresh();

        enlisted().rollback();

        assertThat(scheduler.state("on")).isNull();
    }

    @Test
    void withNoMatchingComponentNothingIsEnlisted() {
        var realm = session.getContext().getRealm();
        when(realm.getComponentsStream()).thenAnswer(i -> Stream.of(component("off", "false")));

        new ScimDispatcher(session).requestRoleRefresh();

        verify(transactions, never()).enlistAfterCompletion(any());
    }

    @Test
    void withNoSchedulerNothingIsEnlisted() {
        RoleRefreshScheduler.stop();

        new ScimDispatcher(session).requestRoleRefresh();

        verify(transactions, never()).enlistAfterCompletion(any());
    }
}
