package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.storage.user.SynchronizationResult;

import sh.libre.scim.core.exceptions.InconsistentScimMappingException;

/**
 * The parts of the sync coordinator that need no database: the gates that open
 * no transaction, the failure containment around each half, and the group
 * count warning.
 */
class ScimSyncTest {

    private static ComponentModel model(String... keyValuePairs) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            config.putSingle(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        model.setConfig(config);
        model.setId("comp-1");
        model.setName("scim");
        return model;
    }

    private static void assertNothingCounted(SynchronizationResult result) {
        assertThat(result.getAdded()).isZero();
        assertThat(result.getUpdated()).isZero();
        assertThat(result.getRemoved()).isZero();
        assertThat(result.getFailed()).isZero();
    }

    @Test
    void bothHalvesDisabledOpensNoTransaction() {
        var sessionFactory = mock(KeycloakSessionFactory.class);

        var result = ScimSync.run(sessionFactory, "realm-1",
            model("propagation-user", "true", "propagation-group", "true"));

        verifyNoInteractions(sessionFactory);
        assertNothingCounted(result);
    }

    @Test
    void bothPropagationGatesClosedOpensNoTransaction() {
        var sessionFactory = mock(KeycloakSessionFactory.class);

        var result = ScimSync.run(sessionFactory, "realm-1",
            model("sync-import", "true", "sync-refresh", "true",
                "propagation-user", "false", "propagation-group", "false"));

        verifyNoInteractions(sessionFactory);
        assertNothingCounted(result);
    }

    @Test
    void aPropagationFailureInOneHalfIsCountedAndContained() {
        var result = new SynchronizationResult();

        ScimSync.contained(model(), "user refresh", result, () -> {
            throw new InconsistentScimMappingException("no mapping");
        });

        assertThat(result.getFailed()).isEqualTo(1);
    }

    @Test
    void anUnexpectedFailureInOneHalfIsCountedAndContained() {
        var result = new SynchronizationResult();

        ScimSync.contained(model(), "user refresh", result, () -> {
            throw new IllegalStateException("commit failed");
        });

        assertThat(result.getFailed()).isEqualTo(1);
    }

    @Test
    void theGroupCountWarningReadsTheRealmGroupCount() {
        var session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        var groups = mock(GroupProvider.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(session.groups()).thenReturn(groups);
        when(groups.getGroupsCount(realm, false)).thenReturn(ScimSync.GROUP_COUNT_WARNING + 1);

        assertThatCode(() -> ScimSync.warnIfManyGroups(session, model())).doesNotThrowAnyException();
    }

    @Test
    void aFailedGroupCountDoesNotStopTheRefresh() {
        var session = mock(KeycloakSession.class);
        when(session.getContext()).thenThrow(new IllegalStateException("no context"));

        assertThatCode(() -> ScimSync.warnIfManyGroups(session, model())).doesNotThrowAnyException();
    }
}
