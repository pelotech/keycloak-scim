package sh.libre.scim.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import jakarta.persistence.EntityManager;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.storage.user.SynchronizationResult;

/**
 * Pages sync-refresh through Keycloak's local user table by username, one
 * transaction per page.
 *
 * <p>The cursor is the last username examined. Each page reads the next rows
 * of {@code (id, username)} straight from {@code UserEntity}. It then loads
 * each user through the SPI. A user removed during the sync therefore cannot
 * shift the position of any other user. A page shorter than requested marks
 * the end, because the query reads raw rows with no validation or federation
 * lookups.
 */
final class RefreshPageStep implements PageStep<String> {

    private static final Logger LOGGER = Logger.getLogger(RefreshPageStep.class);

    /** One row of the page query: a user id and the username the cursor moves by. */
    record UserRow(String id, String username) {}

    // Enabled users only, as refresh has always done. Service accounts are
    // excluded, unlike the SPI search refresh used to call: they are client
    // credentials rather than people.
    private static final String FIRST_PAGE = """
        select u.id, u.username from UserEntity u
         where u.realmId = :realmId
           and u.enabled = true
           and u.serviceAccountClientLink is null
         order by u.username""";

    private static final String NEXT_PAGE = """
        select u.id, u.username from UserEntity u
         where u.realmId = :realmId
           and u.enabled = true
           and u.serviceAccountClientLink is null
           and u.username > :after
         order by u.username""";

    private final KeycloakSessionFactory sessionFactory;
    private final String realmId;
    private final ComponentModel model;
    private final Duration pageBudget;
    private final Clock clock;
    private final SyncErrorPolicy policy;
    // The runner keeps one step for the whole run, so the count crosses pages.
    private final ThrottleStreak throttleStreak = new ThrottleStreak();

    RefreshPageStep(KeycloakSessionFactory sessionFactory, String realmId, ComponentModel model,
                    Duration pageBudget, Clock clock) {
        this.sessionFactory = sessionFactory;
        this.realmId = realmId;
        this.model = model;
        this.pageBudget = pageBudget;
        this.clock = clock;
        this.policy = SyncErrorPolicy.fromConfig(model.get("sync-on-error"));
    }

    @Override
    public String initialCursor() {
        return null;
    }

    @Override
    public PageOutcome<String> run(String after, int size) {
        return KeycloakModelUtils.runJobInTransactionWithResult(sessionFactory, session -> {
            Instant start = clock.instant(); // the page budget includes the fetch
            var realm = session.realms().getRealm(realmId);
            session.getContext().setRealm(realm); // adapters resolve the component from it
            var client = ScimClient.forSyncPage(model, session);
            try {
                var em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
                var rows = queryRows(em, realmId, after, size);
                var counters = new SynchronizationResult();
                return processRows(after, size, rows, counters, throttleStreak,
                    row -> {
                        // A cache miss validates a federated user against its
                        // directory and returns null if the entry is gone. A
                        // cache hit returns the cached copy without that check;
                        // removing such users is the reconciler's job.
                        var user = session.users().getUserById(realm, row.id());
                        return refreshLoaded(row.id(), user, counters,
                            u -> client.refreshOne(UserAdapter::new, u, counters, policy));
                    },
                    () -> overBudget(start, clock.instant(), pageBudget));
            } finally {
                client.close();
            }
        });
    }

    /** Reads up to {@code size} user rows after {@code after}, or from the start when it is null. */
    static List<UserRow> queryRows(EntityManager em, String realmId, String after, int size) {
        var query = em.createQuery(after == null ? FIRST_PAGE : NEXT_PAGE, Object[].class)
            .setParameter("realmId", realmId)
            .setMaxResults(size);
        if (after != null) {
            query.setParameter("after", after);
        }
        return query.getResultList().stream()
            .map(r -> new UserRow((String) r[0], (String) r[1]))
            .toList();
    }

    /**
     * Pushes a loaded user, or does nothing when the load found no user.
     *
     * <p>A push can throw a plain runtime exception. The client catches
     * propagation failures only. Such an exception must not leave the page. It
     * would roll the transaction back. The rollback discards the mapping rows
     * of the users already pushed. The endpoint keeps their resources, so the
     * next run creates them again. The failure is counted here instead, and
     * the page goes on to the next user.
     */
    static <U> RefreshOutcome refreshLoaded(String id, U user, SynchronizationResult counters,
            Function<U, RefreshOutcome> push) {
        if (user == null) {
            return RefreshOutcome.CONTINUE;
        }
        try {
            return push.apply(user);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "SCIM sync: user %s failed with an unexpected error", id);
            counters.increaseFailed();
            return RefreshOutcome.CONTINUE;
        }
    }

    /**
     * Handles each row in order and builds the page outcome. The cursor moves
     * past every row examined, whether it was pushed, skipped or missing. The
     * policy stop is checked first, then the throttle streak, then the budget.
     * The budget is read only after a row has been handled, so a non-empty page
     * always examines at least one row.
     */
    static PageOutcome<String> processRows(String after, int size, List<UserRow> rows,
            SynchronizationResult counters, ThrottleStreak streak,
            Function<UserRow, RefreshOutcome> handle, BooleanSupplier overBudget) {
        String last = after;
        StopReason stopReason = StopReason.NONE;
        for (UserRow row : rows) {
            last = row.username();
            RefreshOutcome outcome = handle.apply(row);
            boolean streakIsFull = streak.record(outcome, size);
            if (outcome == RefreshOutcome.STOP) {
                stopReason = StopReason.POLICY;
                break;
            }
            if (streakIsFull) {
                LOGGER.errorf("SCIM sync stopped: the endpoint throttled %d users in a row, at cursor %s",
                    streak.count(), last);
                stopReason = StopReason.THROTTLE_STREAK;
                break;
            }
            if (overBudget.getAsBoolean()) {
                stopReason = StopReason.PAGE_BUDGET;
                break;
            }
        }
        boolean progressed = rows.isEmpty() || !Objects.equals(last, after);
        boolean exhausted = stopReason == StopReason.NONE && rows.size() < size;
        return new PageOutcome<>(last, progressed, exhausted, counters, stopReason);
    }

    static boolean overBudget(Instant start, Instant now, Duration budget) {
        return Duration.between(start, now).compareTo(budget) > 0;
    }

    /**
     * Counts throttled users in a row. A throttling endpoint lets the run walk
     * the whole population and push almost nothing, so a full page of throttles
     * ends the run. The count crosses pages, because the runner keeps one step.
     */
    static final class ThrottleStreak {

        private int count;

        /**
         * Records one user outcome.
         *
         * @param outcome what the push reported for this user
         * @param pageSize how many users a full page holds
         * @return true when the endpoint has throttled a full page in a row
         */
        boolean record(RefreshOutcome outcome, int pageSize) {
            // Any push, any skip and any other failure break the streak.
            count = outcome == RefreshOutcome.THROTTLED ? count + 1 : 0;
            return count >= pageSize;
        }

        int count() {
            return count;
        }
    }
}
