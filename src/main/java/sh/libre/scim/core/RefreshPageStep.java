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
 *
 * <p>A realm and username pair is unique, so the order is total. No row is
 * duplicated or missed at a page boundary. A rename is the exception: a user
 * renamed across the cursor while the sync runs can be seen twice or missed
 * once. Both results are safe, because a push is idempotent and the next run
 * finds the user again.
 *
 * <p>This type and the paging machinery around it stay package-private. A
 * caller in another package starts a sync through the public entry point of
 * this package, so the paging types do not become API.
 */
final class RefreshPageStep implements PageStep<String> {

    private static final Logger LOGGER = Logger.getLogger(RefreshPageStep.class);

    /** One row of the page query: a user id and the username the cursor moves by. */
    record UserRow(String id, String username) {}

    /**
     * One page of rows and the request that read them.
     *
     * @param after the cursor this page starts from; null for the first page
     * @param size the largest number of rows the query could return. It is also
     *     the throttle threshold. The two cannot disagree, because the runner
     *     passes one value for both.
     * @param rows what the query returned, in username order
     */
    record Page(String after, int size, List<UserRow> rows) {}

    /**
     * Where the row loop stopped and why. It holds everything a page outcome
     * needs except the counters, which the caller owns.
     */
    record PageProgress(String cursor, boolean progressed, boolean exhausted, StopReason stopReason) {}

    // One prefix, so the two query strings cannot drift apart. They stay split
    // rather than one string with an optional clause, because JPQL cannot bind
    // a parameter to null and still mean "no bound".
    //
    // Enabled users only, as refresh has always done. Service accounts are
    // excluded, unlike the SPI search refresh used to call: they are client
    // credentials rather than people.
    private static final String PAGE_FILTER = """
        select u.id, u.username from UserEntity u
         where u.realmId = :realmId
           and u.enabled = true
           and u.serviceAccountClientLink is null""";

    private static final String PAGE_ORDER = "\n order by u.username";

    private static final String FIRST_PAGE = PAGE_FILTER + PAGE_ORDER;

    private static final String NEXT_PAGE = PAGE_FILTER + "\n   and u.username > :after" + PAGE_ORDER;

    private final KeycloakSessionFactory sessionFactory;
    private final String realmId;
    private final ComponentModel model;
    /**
     * The wall-clock time a page may spend before it stops between rows. It
     * bounds the number of rows, not the transaction. One push has a normal
     * worst case near 43 seconds, a token refresh doubles that, and each
     * fallback adds more, so a page can run well past this value. The caller
     * must not size the transaction timeout from it.
     */
    private final Duration pageBudget;
    private final Clock clock;
    private final SyncErrorPolicy policy;
    // The runner keeps one step for the whole run, so the count crosses pages.
    private final ThrottleStreak throttleStreak = new ThrottleStreak();

    RefreshPageStep(KeycloakSessionFactory sessionFactory, String realmId, ComponentModel model,
                    Duration pageBudget, Clock clock) {
        // Fail at wiring time. A missing argument must not surface inside the
        // first page transaction, after the run has already started.
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.realmId = Objects.requireNonNull(realmId, "realmId");
        this.model = Objects.requireNonNull(model, "model");
        this.pageBudget = Objects.requireNonNull(pageBudget, "pageBudget");
        this.clock = Objects.requireNonNull(clock, "clock");
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
            if (realm == null) {
                LOGGER.warnf("SCIM sync stopped: realm %s is gone", realmId);
                return realmGone(after);
            }
            session.getContext().setRealm(realm); // adapters resolve the component from it
            // The component comes from the constructor, not from this realm.
            // The short worker jobs in this package re-read it, because each is
            // a separate piece of work. A sync is one long job, so its
            // configuration must stay the same from the first page to the last.
            var client = ScimClient.forSyncPage(model, session);
            try {
                var em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
                var page = new Page(after, size, queryRows(em, realmId, after, size));
                var counters = new SynchronizationResult();
                var transaction = session.getTransactionManager();
                var progress = processRows(page, throttleStreak,
                    row -> {
                        // A cache miss validates a federated user against its
                        // directory and returns null if the entry is gone. A
                        // cache hit returns the cached copy without that check;
                        // removing such users is the reconciler's job.
                        var user = session.users().getUserById(realm, row.id());
                        return refreshLoaded(row.id(), user, counters,
                            u -> client.refreshOne(UserAdapter::new, u, counters, policy));
                    },
                    transaction::getRollbackOnly,
                    () -> overBudget(start, clock.instant(), pageBudget));
                return new PageOutcome<>(progress.cursor(), progress.progressed(),
                    progress.exhausted(), counters, progress.stopReason());
            } finally {
                closeQuietly(client);
            }
        });
    }

    /**
     * The outcome of a page whose realm disappeared during the run. It is
     * exhausted, so the run ends and keeps what the earlier pages committed.
     * It reports progress, so the runner does not treat it as a stuck page.
     */
    static PageOutcome<String> realmGone(String after) {
        return new PageOutcome<>(after, true, true, new SynchronizationResult(), StopReason.NONE);
    }

    /**
     * Closes the page client and keeps any failure out of the transaction. A
     * throw from close would replace the page outcome and roll back users that
     * the endpoint has already accepted.
     */
    static void closeQuietly(ScimClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "SCIM sync: the page client did not close cleanly");
        }
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
     * propagation failures only. An isolated fault, such as a racing read of a
     * shared configuration map, must not leave the page. It would roll the
     * transaction back. The rollback discards the mapping rows of the users
     * already pushed. The endpoint keeps their resources, so the next run
     * creates them again. The failure is counted here instead, and the caller
     * goes on to the next user.
     *
     * <p>This guard is safe only for a fault that leaves the transaction
     * usable. A persistence fault marks the transaction rollback-only, and this
     * method cannot tell the two apart. The caller must therefore test the
     * transaction after each user and end the page when it can no longer
     * commit.
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
     * Handles each row in order and reports where the loop stopped. The cursor
     * moves past every row examined, whether it was pushed, skipped or missing.
     *
     * <p>The checks run in this order: the policy stop, the throttle streak,
     * the failed transaction, then the budget. The first three end the run, so
     * they run after every row. The budget only ends the page, so it runs only
     * while rows remain. A short final page therefore reports no budget stop,
     * and the run does not open another transaction to fetch nothing.
     *
     * @param page the rows to handle and the request that read them
     * @param streak the throttle count, which the caller keeps across pages
     * @param handle pushes one user and reports what to do next
     * @param transactionFailed whether the page transaction can no longer commit
     * @param overBudget whether the page has spent its wall-clock budget
     */
    static PageProgress processRows(Page page, ThrottleStreak streak,
            Function<UserRow, RefreshOutcome> handle, BooleanSupplier transactionFailed,
            BooleanSupplier overBudget) {
        List<UserRow> rows = page.rows();
        String last = page.after();
        StopReason stopReason = StopReason.NONE;
        for (int i = 0; i < rows.size(); i++) {
            UserRow row = rows.get(i);
            last = row.username();
            RefreshOutcome outcome = handle.apply(row);
            boolean streakIsFull = streak.record(outcome, page.size());
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
            if (transactionFailed.getAsBoolean()) {
                LOGGER.errorf("SCIM sync stopped: the page transaction cannot commit, at cursor %s", last);
                stopReason = StopReason.TRANSACTION_FAILED;
                break;
            }
            boolean rowsRemain = i + 1 < rows.size();
            if (rowsRemain && overBudget.getAsBoolean()) {
                stopReason = StopReason.PAGE_BUDGET;
                break;
            }
        }
        boolean progressed = rows.isEmpty() || !Objects.equals(last, page.after());
        boolean exhausted = stopReason == StopReason.NONE && rows.size() < page.size();
        return new PageProgress(last, progressed, exhausted, stopReason);
    }

    /**
     * Whether the page has spent its budget. The clock is wall-clock, so a step
     * of the system clock can cut a page short or extend it. A monotonic source
     * would be stricter. The loop still ends after one page of rows, so a clock
     * step cannot make a page unbounded.
     */
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
