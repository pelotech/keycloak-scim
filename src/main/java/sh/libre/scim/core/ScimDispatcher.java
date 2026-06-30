package sh.libre.scim.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

import sh.libre.scim.core.exceptions.ScimPropagationException;
import sh.libre.scim.storage.ScimStorageProviderFactory;

public class ScimDispatcher implements AutoCloseable {
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_GROUP = "group";

    final private KeycloakSession session;
    final private Logger LOGGER = Logger.getLogger(ScimDispatcher.class);
    /**
     * Cache of {@link ScimClient}s keyed by SCIM provider component id.
     *
     * <p>Construction of a {@link ScimClient} is non-trivial: it builds an
     * Apache HttpClient pool, configures auth headers, and instantiates a
     * resilience4j RetryRegistry. Doing that on every event scales linearly
     * with event volume — for a 10k-user federation sync, 10k client setups.
     * Caching by component id within a dispatcher's lifetime collapses that
     * to one client per (dispatcher, component) pair.
     *
     * <p>Lifetime: the dispatcher is owned by an {@link sh.libre.scim.event.ScimEventListenerProvider}
     * (one per Keycloak session), an {@link sh.libre.scim.ldap.ScimLdapStorageMapper}
     * (one per LDAP-mapper instance, also per-session), or a one-off block
     * in {@link sh.libre.scim.storage.ScimStorageProviderFactory#sync}.
     * Each owner must call {@link #close()} when done so the HTTP clients
     * are released.
     */
    private final Map<String, ScimClient> clients = new HashMap<>();

    /**
     * Worker pool for {@link #runAsync}. Sized via system property
     * {@code scim.dispatch.threads} (default 8). The pool is JVM-global —
     * shared across all dispatcher instances and Keycloak sessions, with
     * daemon threads so it doesn't block JVM shutdown.
     *
     * <p>Tuning: 8 is a defensible default for "make 10k-user sync tractable
     * against a single SCIM sink" — most SCIM servers tolerate that
     * concurrency; raising it further runs into either the sink's
     * connection limit or the local Apache HttpClient pool size.
     */
    private static final int POOL_SIZE = Integer.getInteger("scim.dispatch.threads", 8);

    /**
     * Bounded buffer between producers and the worker pool. Caps the in-flight
     * backlog — and therefore the dispatch memory footprint — at ~capacity
     * tasks regardless of the sync size N. When full, {@link BlockingPolicy}
     * blocks the producer (back-pressure) instead of growing the queue.
     * Default 256 ≈ 32 deep per worker at the default pool size: deep enough
     * not to starve a worker on a fast sink, shallow enough to keep the memory
     * bound tight. Tunable via {@code scim.dispatch.queueCapacity}.
     */
    private static final int QUEUE_CAPACITY = Integer.getInteger("scim.dispatch.queueCapacity", 256);

    /**
     * How long a producer may stay blocked before each back-pressure WARN.
     * Default 10s is long enough to stay quiet through a momentarily slow sink,
     * yet short enough to surface a wedged sink within the first interval.
     * Tunable via {@code scim.dispatch.blockWarnMs}.
     */
    private static final long BLOCK_WARN_MS = Long.getLong("scim.dispatch.blockWarnMs", 10_000L);

    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            var t = new Thread(r, "scim-dispatch-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    };

    private static final BlockingPolicy BACKPRESSURE_POLICY =
        new BlockingPolicy(QUEUE_CAPACITY, BLOCK_WARN_MS);

    private static final ThreadPoolExecutor ASYNC_EXECUTOR = new ThreadPoolExecutor(
        POOL_SIZE, POOL_SIZE,
        0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
        THREAD_FACTORY,
        BACKPRESSURE_POLICY);

    /**
     * Count of back-pressure WARN events emitted by the dispatch pool, for
     * operational visibility (a rising count means a slow/wedged SCIM sink is
     * throttling syncs). Not wired to a metrics endpoint — the per-interval
     * WARN log is the primary operator signal.
     */
    public static long backpressureWarnings() {
        return BACKPRESSURE_POLICY.blockedWarnings();
    }

    /**
     * Submit a task to the shared SCIM worker pool. Same pool that
     * {@link #runAsync} uses; consumers can compose {@link CompletableFuture}s
     * around it for parallel-with-await patterns (e.g. the reconciler's
     * batch-delete path needs to fire N deletes in parallel and only return
     * after all complete).
     */
    public static java.util.concurrent.CompletableFuture<Void> dispatchAsync(Runnable task) {
        return java.util.concurrent.CompletableFuture.runAsync(task, ASYNC_EXECUTOR);
    }

    public ScimDispatcher(KeycloakSession session) {
        this.session = session;
    }

    public void run(String scope, Consumer<ScimClient> f) {
        session.getContext().getRealm().getComponentsStream()
                .filter(m -> {
                    return ScimStorageProviderFactory.ID.equals(m.getProviderId()) && m.get("enabled", true)
                            && m.get("propagation-" + scope, false);
                })
                .forEach(m -> runOne(m, f));
    }

    public void runOne(ComponentModel m, Consumer<ScimClient> f) {
        LOGGER.debugf("%s %s %s %s", m.getId(), m.getName(), m.getProviderId(), m.getProviderType());
        var client = clients.computeIfAbsent(m.getId(), id -> new ScimClient(m, session));
        try {
            f.accept(client);
        } catch (ScimPropagationException e) {
            String strategy = m.get("rollback-strategy", "never");
            boolean rollback = switch (strategy) {
                case "always" -> true;
                case "critical-only" -> e.isTransient();
                default -> false; // "never"
            };
            if (rollback) {
                LOGGER.errorf(e, "SCIM critical-path failure on component %s (%s); rolling back transaction",
                    m.getId(), m.getName());
                session.getTransactionManager().setRollbackOnly();
            } else {
                LOGGER.warnf(e, "SCIM dispatch failed on component %s (%s)", m.getId(), m.getName());
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "SCIM dispatch failed on component %s (%s)", m.getId(), m.getName());
        }
    }

    /**
     * Submits SCIM operations for asynchronous execution on a worker pool.
     *
     * <p>Caller returns immediately. Each matching SCIM provider component
     * gets one queued task; the task runs on a worker thread, opens its own
     * Keycloak session via {@code runJobInTransaction}, looks up the
     * component, and invokes {@code op} with a freshly-constructed
     * {@link ScimClient} bound to that worker session.
     *
     * <p>Why async at all: 98% of per-call cost is the SCIM HTTP send
     * (~43ms in our perf measurement). Serializing thousands of these
     * inside the user-import path makes 10k-user syncs take ~7 minutes.
     * With 8 workers in parallel, we approach (request_rate / pool_size)
     * scaling — typically an order-of-magnitude throughput improvement.
     *
     * <p>Trade-off: {@code op} runs in a different session and transaction
     * than the caller. The {@link BiConsumer} receives the worker's session
     * so it can re-fetch any model objects it needs by id rather than
     * relying on captured references. If the caller's transaction rolls
     * back, the worker may still execute (already-fired). Mappings saved
     * by the worker commit independently. This is consistent with the
     * plugin's existing fail-open posture.
     *
     * <p>For synchronous fan-out (e.g., the reconciler endpoint, where the
     * caller wants a result count), use {@link #run} instead.
     */
    public void runAsync(String scope, BiConsumer<ScimClient, KeycloakSession> op) {
        var realm = session.getContext().getRealm();
        var realmId = realm.getId();
        // Snapshot matching component ids in the caller's session — accessing
        // them from the worker via a new session is fine (re-read) but doing
        // the filter here keeps the worker thin and avoids one JPA query per
        // worker.
        List<String> componentIds = realm.getComponentsStream()
            .filter(m -> ScimStorageProviderFactory.ID.equals(m.getProviderId())
                && m.get("enabled", true)
                && m.get("propagation-" + scope, false))
            .map(ComponentModel::getId)
            .toList();
        if (componentIds.isEmpty()) return;
        KeycloakSessionFactory factory = session.getKeycloakSessionFactory();

        // Defer submission until the caller's transaction commits. Submitting
        // immediately would have workers open their own sessions BEFORE the
        // caller's writes are committed, so the worker's
        // session.users().getUserById(...) sees a stale (typically empty)
        // database state — exactly what broke the LDAP-import tests when
        // this was naive submit-on-call. enlistAfterCompletion only fires
        // commit() on success; rollback() skips submission so we don't
        // dispatch SCIM ops for users the caller decided not to persist.
        session.getTransactionManager().enlistAfterCompletion(new KeycloakTransaction() {
            private volatile boolean done = false;

            @Override public void begin() {}

            @Override
            public void commit() {
                for (String componentId : componentIds) {
                    ASYNC_EXECUTOR.submit(() -> {
                        try {
                            KeycloakModelUtils.runJobInTransaction(factory, workerSession -> {
                                var workerRealm = workerSession.realms().getRealm(realmId);
                                if (workerRealm == null) {
                                    LOGGER.warnf("scim async: realm %s gone", realmId);
                                    return;
                                }
                                workerSession.getContext().setRealm(workerRealm);
                                var component = workerRealm.getComponent(componentId);
                                if (component == null
                                    || !ScimStorageProviderFactory.ID.equals(component.getProviderId())) {
                                    LOGGER.debugf("scim async: component %s gone", componentId);
                                    return;
                                }
                                var workerClient = new ScimClient(component, workerSession);
                                try {
                                    op.accept(workerClient, workerSession);
                                } finally {
                                    workerClient.close();
                                }
                            });
                        } catch (RuntimeException e) {
                            LOGGER.errorf(e, "scim async dispatch failed for component %s", componentId);
                        }
                    });
                }
                done = true;
            }

            @Override
            public void rollback() {
                LOGGER.debugf("scim async skipped for %d component(s): caller transaction rolled back",
                    componentIds.size());
                done = true;
            }

            @Override public void setRollbackOnly() {}
            @Override public boolean getRollbackOnly() { return false; }
            @Override public boolean isActive() { return !done; }
        });
    }

    /**
     * Route a federation-imported user CREATE. Per propagation-user component:
     * bulk-enabled → submit an id-only op to the bulk lane on commit; otherwise →
     * the existing per-op create worker. Mirrors runAsync's afterCompletion deferral
     * (rollback → skip). Does NOT reuse runAsync (which would re-include bulk
     * components and double-create). Payload is built in the worker, not eagerly
     * (the import thread lacks the user's email/name until the attribute mappers run).
     */
    public void dispatchUserCreate(UserModel user) {
        var realm = session.getContext().getRealm();
        String realmId = realm.getId();
        String userId = user.getId();

        var components = realm.getComponentsStream()
            .filter(m -> ScimStorageProviderFactory.ID.equals(m.getProviderId())
                && m.get("enabled", true) && m.get("propagation-user", false))
            .toList();
        if (components.isEmpty()) return;

        var bulkOps = new ArrayList<BulkUserOp>();
        var perOpComponentIds = new ArrayList<String>();
        for (var component : components) {
            if (component.get("bulk-enabled", false)) {
                bulkOps.add(new BulkUserOp(realmId, component.getId(), userId));
            } else {
                perOpComponentIds.add(component.getId());
            }
        }

        KeycloakSessionFactory factory = session.getKeycloakSessionFactory();
        var laneRef = bulkOps.isEmpty() ? null : ScimBulkLane.get(session);

        session.getTransactionManager().enlistAfterCompletion(new KeycloakTransaction() {
            private volatile boolean done = false;

            @Override public void begin() {}

            @Override
            public void commit() {
                for (var op : bulkOps) {
                    laneRef.submit(op);
                }
                for (var componentId : perOpComponentIds) {
                    ASYNC_EXECUTOR.submit(() -> {
                        try {
                            KeycloakModelUtils.runJobInTransaction(factory, ws -> {
                                var wr = ws.realms().getRealm(realmId);
                                if (wr == null) return;
                                ws.getContext().setRealm(wr);
                                var component = wr.getComponent(componentId);
                                if (component == null
                                    || !ScimStorageProviderFactory.ID.equals(component.getProviderId())) return;
                                var u = ws.users().getUserById(wr, userId);
                                if (u == null) return;
                                var client = new ScimClient(component, ws);
                                try { client.create(UserAdapter::new, u); } finally { client.close(); }
                            });
                        } catch (RuntimeException e) {
                            LOGGER.errorf(e, "scim dispatchUserCreate failed for component %s", componentId);
                        }
                    });
                }
                done = true;
            }

            @Override
            public void rollback() {
                LOGGER.debugf("scim dispatchUserCreate skipped for %d bulk + %d per-op component(s): "
                    + "caller transaction rolled back",
                    bulkOps.size(), perOpComponentIds.size());
                done = true;
            }

            @Override public void setRollbackOnly() {}
            @Override public boolean getRollbackOnly() { return false; }
            @Override public boolean isActive() { return !done; }
        });
    }

    @Override
    public void close() {
        for (var c : clients.values()) {
            try {
                c.close();
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "error closing ScimClient");
            }
        }
        clients.clear();
    }
}
