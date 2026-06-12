package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScimBulkLaneTest {

    @Test
    void coalescesQueuedOpsIntoBatchesGroupedByComponent() throws Exception {
        var batches = new ConcurrentLinkedQueue<List<BulkUserOp>>();
        var seen = new CountDownLatch(30);
        var lane = ScimBulkLane.forTest(10, 1, group -> { batches.add(group); group.forEach(o -> seen.countDown()); });
        for (int i = 0; i < 30; i++) {
            lane.submit(new BulkUserOp("r", "comp-A", "u" + i));
        }
        assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
        lane.close();
        assertThat(batches.stream().mapToInt(List::size).sum()).isEqualTo(30);
        assertThat(batches).allSatisfy(b -> assertThat(b).allMatch(o -> o.componentId().equals("comp-A")));
        assertThat(batches).allSatisfy(b -> assertThat(b.size()).isLessThanOrEqualTo(10));
    }

    @Test
    void splitsAMixedComponentBatchPerComponent() throws Exception {
        var batches = new ConcurrentLinkedQueue<List<BulkUserOp>>();
        var seen = new CountDownLatch(4);
        var lane = ScimBulkLane.forTest(10, 1, group -> { batches.add(group); group.forEach(o -> seen.countDown()); });
        lane.submit(new BulkUserOp("r", "comp-A", "u1"));
        lane.submit(new BulkUserOp("r", "comp-B", "u2"));
        lane.submit(new BulkUserOp("r", "comp-A", "u3"));
        lane.submit(new BulkUserOp("r", "comp-B", "u4"));
        assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
        lane.close();
        assertThat(batches).allSatisfy(b ->
            assertThat(b.stream().map(BulkUserOp::componentId).distinct().count()).isEqualTo(1L));
    }
}
