package sh.libre.scim.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.utils.KeycloakModelUtils;

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
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newFixedThreadPool(
        POOL_SIZE,
        new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                var t = new Thread(r, "scim-dispatch-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });

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
