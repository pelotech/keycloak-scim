package sh.libre.scim.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;

import sh.libre.scim.storage.ScimStorageProviderFactory;

/**
 * Batched user-create lane: a bounded queue of pre-built create ops drained by
 * N daemon workers, each coalescing up to {@code batchSize} ops into one SCIM
 * /Bulk request (grouped by component). Producer back-pressure when full
 * (shared {@link BackpressureSupport}); never drops. JVM-global, mirroring
 * {@link ScimDispatcher}'s executor.
 */
final class ScimBulkLane implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ScimBulkLane.class);
    private static final int THREADS = Integer.getInteger("scim.dispatch.threads", 8);
    private static final int CAPACITY = Integer.getInteger("scim.dispatch.queueCapacity", 256);
    private static final int BATCH_SIZE = Integer.getInteger("scim.dispatch.bulkBatchSize", 20);
    private static final long BLOCK_WARN_MS = Long.getLong("scim.dispatch.blockWarnMs", 10_000L);

    private static volatile ScimBulkLane instance;

    static ScimBulkLane get(KeycloakSession session) {
        var local = instance;
        if (local == null) {
            synchronized (ScimBulkLane.class) {
                local = instance;
                if (local == null) {
                    local = production(session.getKeycloakSessionFactory());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final BlockingQueue<BulkUserOp> queue;
    private final int batchSize;
    private final int capacity;
    private final AtomicLong blockedWarnings = new AtomicLong();
    private final Consumer<List<BulkUserOp>> componentGroupSink;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = true;

    private ScimBulkLane(int batchSize, int threads, int capacity,
                         Consumer<List<BulkUserOp>> componentGroupSink) {
        this.batchSize = batchSize;
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.componentGroupSink = componentGroupSink;
        for (int i = 0; i < threads; i++) {
            var t = new Thread(this::consumeLoop, "scim-bulk-" + (i + 1));
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
    }

    private static ScimBulkLane production(KeycloakSessionFactory factory) {
        return new ScimBulkLane(BATCH_SIZE, THREADS, CAPACITY, group -> sendGroup(factory, group));
    }

    static ScimBulkLane forTest(int batchSize, int threads, Consumer<List<BulkUserOp>> sink) {
        return new ScimBulkLane(batchSize, threads, Math.max(batchSize * 4, 64), sink);
    }

    void submit(BulkUserOp op) {
        try {
            BackpressureSupport.blockingPut(queue, op, BLOCK_WARN_MS, capacity, blockedWarnings, () -> !running);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("interrupted submitting to SCIM bulk lane", e);
        }
    }

    private void consumeLoop() {
        while (running) {
            List<BulkUserOp> batch;
            try {
                BulkUserOp first = queue.take();
                batch = new ArrayList<>(batchSize);
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                Map<String, List<BulkUserOp>> byComponent =
                    batch.stream().collect(Collectors.groupingBy(BulkUserOp::componentId));
                for (var group : byComponent.values()) {
                    componentGroupSink.accept(group);
                }
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "scim bulk lane: batch failed");
            }
        }
    }

    private static void sendGroup(KeycloakSessionFactory factory, List<BulkUserOp> group) {
        String realmId = group.get(0).realmId();
        String componentId = group.get(0).componentId();
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            var realm = session.realms().getRealm(realmId);
            if (realm == null) return;
            session.getContext().setRealm(realm);
            var component = realm.getComponent(componentId);
            if (component == null || !ScimStorageProviderFactory.ID.equals(component.getProviderId())) {
                return;
            }
            var client = new ScimClient(component, session);
            try {
                client.bulkCreateUsers(group);
            } finally {
                client.close();
            }
        });
    }

    long blockedWarnings() { return blockedWarnings.get(); }

    @Override
    public void close() {
        running = false;
        workers.forEach(Thread::interrupt);
    }
}
