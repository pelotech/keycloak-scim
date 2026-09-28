package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.storage.user.SynchronizationResult;

import sh.libre.scim.core.exceptions.InconsistentScimMappingException;

/**
 * The parts of the sync coordinator that need no database: the gates that open
 * no transaction, the lease around the run, the failure containment around
 * each stage, how a stage's counters reach the run, and the group count
 * warning.
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

    /**
     * A factory whose sessions find no realm, so every stage takes its
     * realm-gone branch and needs no database. The transaction helper only
     * begins and closes, and the stages only ask for the realm.
     */
    private static KeycloakSessionFactory factoryWithRealmGone() {
        var session = mock(KeycloakSession.class);
        when(session.getTransactionManager()).thenReturn(mock(KeycloakTransactionManager.class));
        when(session.realms()).thenReturn(mock(RealmProvider.class));
        var factory = mock(KeycloakSessionFactory.class);
        when(factory.create()).thenReturn(session);
        return factory;
    }

    private static void assertNothingCounted(SynchronizationResult result) {
        assertThat(result.getAdded()).isZero();
        assertThat(result.getUpdated()).isZero();
        assertThat(result.getRemoved()).isZero();
        assertThat(result.getFailed()).isZero();
    }

    @Test
    void bothSyncSettingsDisabledOpensNoTransaction() {
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

    // --- the lease around the run ---

    @Test
    void bothSyncSettingsDisabledTakesNoLease() {
        var store = new FakeLeaseStore();

        ScimSync.run(mock(KeycloakSessionFactory.class), "realm-1", model(), store, Clock.systemUTC());

        assertThat(store.rows).isEmpty();
    }

    @Test
    void bothPropagationGatesClosedTakesNoLease() {
        var store = new FakeLeaseStore();

        ScimSync.run(mock(KeycloakSessionFactory.class), "realm-1",
            model("sync-import", "true", "sync-refresh", "true",
                "propagation-user", "false", "propagation-group", "false"),
            store, Clock.systemUTC());

        assertThat(store.rows).isEmpty();
    }

    @Test
    void aRefusedLeaseReturnsIgnoredAndOpensNoStage() {
        var sessionFactory = mock(KeycloakSessionFactory.class);
        var store = new FakeLeaseStore();
        store.ensureRow("comp-1");
        store.inOneTransaction(l -> { l.take("comp-1", "other", 1_000_000L); return null; });

        var result = ScimSync.run(sessionFactory, "realm-1", model("propagation-user", "true", "sync-refresh", "true"),
            store, Clock.fixed(Instant.ofEpochMilli(1_000_500L), ZoneOffset.UTC));

        assertThat(result.isIgnored()).isTrue();
        verifyNoInteractions(sessionFactory);
    }

    @Test
    void aFailedAcquisitionCountsOneFailureAndOpensNoStage() {
        var sessionFactory = mock(KeycloakSessionFactory.class);
        var store = new FakeLeaseStore();
        store.ensureFailure = new IllegalStateException("no privilege");

        var result = ScimSync.run(sessionFactory, "realm-1", model("propagation-user", "true", "sync-refresh", "true"),
            store, Clock.systemUTC());

        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.isIgnored()).isFalse();
        verifyNoInteractions(sessionFactory);
    }

    @Test
    void aFinishedRunClearsItsHolder() {
        var store = new FakeLeaseStore();

        ScimSync.run(factoryWithRealmGone(), "realm-1", model("propagation-user", "true", "sync-refresh", "true"),
            store, Clock.systemUTC());

        assertThat(store.rows.get("comp-1").holder).isNull();
    }

    @Test
    void aStageThatThrowsStillClearsTheHolder() {
        var factory = mock(KeycloakSessionFactory.class);
        when(factory.create()).thenThrow(new IllegalStateException("no session"));
        var store = new FakeLeaseStore();

        var result = ScimSync.run(factory, "realm-1", model("propagation-user", "true", "sync-refresh", "true"),
            store, Clock.systemUTC());

        assertThat(store.rows.get("comp-1").holder).isNull();
        assertThat(result.getFailed()).isEqualTo(1);
    }

    /**
     * Its own component id: a released heartbeat thread of another test can
     * still be alive for an instant, so the thread check must not share a
     * name with one.
     */
    @Test
    void theSimulatedCrashKeepsTheHolderAndStartsNoHeartbeat() {
        var store = new FakeLeaseStore();
        var model = model("propagation-user", "true", "sync-refresh", "true");
        model.setId("comp-crash");
        System.setProperty(ScimSync.SIMULATE_CRASH_PROPERTY, "true");
        try {
            ScimSync.run(factoryWithRealmGone(), "realm-1", model, store, Clock.systemUTC());
        } finally {
            System.clearProperty(ScimSync.SIMULATE_CRASH_PROPERTY);
        }

        assertThat(store.rows.get("comp-crash").holder).isNotNull();
        assertThat(Thread.getAllStackTraces().keySet()).extracting(Thread::getName)
            .doesNotContain("scim-sync-lease-comp-crash");
    }

    @Test
    void everyStageReportsProgressAtItsStartAndEnd() {
        var reports = new int[1];
        var result = new SynchronizationResult();

        ScimSync.runStages(factoryWithRealmGone(), "realm-1", model(), Clock.systemUTC(), result,
            () -> reports[0]++, () -> false, true, true, true, true);

        assertThat(reports[0]).isEqualTo(8);
        assertThat(result.getFailed()).isZero();
    }

    @Test
    void aLostLeaseSkipsEveryStageAndCountsEach() {
        var sessionFactory = mock(KeycloakSessionFactory.class);
        var result = new SynchronizationResult();

        ScimSync.runStages(sessionFactory, "realm-1", model(), Clock.systemUTC(), result,
            () -> {}, () -> true, true, true, true, true);

        assertThat(result.getFailed()).isEqualTo(4);
        verifyNoInteractions(sessionFactory);
    }

    @Test
    void aStageIsSkippedAndCountedWhenTheLeaseIsLost() {
        var ran = new boolean[1];
        var result = new SynchronizationResult();

        ScimSync.unlessLost(() -> true, "group refresh", result, () -> ran[0] = true);

        assertThat(ran[0]).isFalse();
        assertThat(result.getFailed()).isEqualTo(1);
    }

    @Test
    void aStageRunsWhenTheLeaseIsHeld() {
        var ran = new boolean[1];
        var result = new SynchronizationResult();

        ScimSync.unlessLost(() -> false, "group refresh", result, () -> ran[0] = true);

        assertThat(ran[0]).isTrue();
        assertThat(result.getFailed()).isZero();
    }

    // --- containing one stage ---

    @Test
    void aPropagationFailureInOneStageIsCountedAndContained() {
        var result = new SynchronizationResult();

        ScimSync.runContained(model(), "user refresh", result, () -> {
            throw new InconsistentScimMappingException("no mapping");
        });

        assertThat(result.getFailed()).isEqualTo(1);
    }

    @Test
    void anUnexpectedFailureInOneStageIsCountedAndContained() {
        var result = new SynchronizationResult();

        ScimSync.runContained(model(), "user refresh", result, () -> {
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

    // --- merging one stage into the run ---

    @Test
    void aCommittedStageContributesEveryCounter() {
        var result = new SynchronizationResult();
        var staged = new SynchronizationResult();
        staged.setAdded(3);
        staged.setUpdated(4);
        staged.setRemoved(1);
        staged.setFailed(2);

        ScimSync.mergeStage(result, staged, true);

        assertThat(result.getAdded()).isEqualTo(3);
        assertThat(result.getUpdated()).isEqualTo(4);
        assertThat(result.getRemoved()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(2);
    }

    /**
     * A rolled-back stage kept nothing, so reporting its pushes would name work
     * the database discarded. Its failures still happened, and the stage itself
     * is one more.
     */
    @Test
    void aRolledBackStageContributesOnlyItsFailuresPlusTheLostStage() {
        var result = new SynchronizationResult();
        var staged = new SynchronizationResult();
        staged.setAdded(3);
        staged.setUpdated(4);
        staged.setRemoved(1);
        staged.setFailed(2);

        ScimSync.mergeStage(result, staged, false);

        assertThat(result.getAdded()).isZero();
        assertThat(result.getUpdated()).isZero();
        assertThat(result.getRemoved()).isZero();
        assertThat(result.getFailed()).isEqualTo(3);
    }

    @Test
    void aRolledBackStageThatFailedNothingStillCountsAsOneFailure() {
        var result = new SynchronizationResult();

        ScimSync.mergeStage(result, new SynchronizationResult(), false);

        assertThat(result.getFailed()).isEqualTo(1);
    }

    @Test
    void mergingAStageAddsToWhatTheRunAlreadyHas() {
        var result = new SynchronizationResult();
        result.setUpdated(10);
        result.setFailed(5);
        var staged = new SynchronizationResult();
        staged.setUpdated(2);
        staged.setFailed(1);

        ScimSync.mergeStage(result, staged, false);

        assertThat(result.getUpdated()).isEqualTo(10);
        assertThat(result.getFailed()).isEqualTo(7);
    }
}
