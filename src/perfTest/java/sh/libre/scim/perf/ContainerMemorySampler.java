package sh.libre.scim.perf;

import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Samples a Testcontainers container's resident memory by exec'ing into it and
 * reading the cgroup memory accounting file. Used by the dispatch-memory perf
 * scenarios to characterize Keycloak's container footprint while a federation
 * sync floods the SCIM dispatch queue.
 *
 * <p>Reads cgroup v2 ({@code /sys/fs/cgroup/memory.current}) first, falling
 * back to cgroup v1 ({@code /sys/fs/cgroup/memory/memory.usage_in_bytes}).
 * Polls on a background daemon thread roughly every second, recording every
 * sample (relative timestamp ms, bytes) and the running max. Robust to a
 * failing exec: a bad sample is skipped rather than aborting the run.
 */
final class ContainerMemorySampler {

    /** One memory reading: millis since {@link #start()} and bytes in use. */
    record Sample(long tMillis, long bytes) {}

    private final GenericContainer<?> container;
    private final long intervalMillis;

    private final List<Sample> samples = new ArrayList<>();
    private volatile long maxBytes = 0;
    private volatile boolean running = false;
    private Thread thread;
    private long startNanos;

    ContainerMemorySampler(GenericContainer<?> container) {
        this(container, 1000);
    }

    ContainerMemorySampler(GenericContainer<?> container, long intervalMillis) {
        this.container = container;
        this.intervalMillis = intervalMillis;
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        startNanos = System.nanoTime();
        thread = new Thread(this::pollLoop, "container-mem-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void pollLoop() {
        while (running) {
            long bytes = readMemoryBytes();
            if (bytes > 0) {
                long t = (System.nanoTime() - startNanos) / 1_000_000L;
                synchronized (this) {
                    samples.add(new Sample(t, bytes));
                }
                if (bytes > maxBytes) {
                    maxBytes = bytes;
                }
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Returns memory-in-use bytes, or -1 if the read failed. */
    private long readMemoryBytes() {
        // cgroup v2 first, then v1 fallback. A single exec tries both so we
        // don't pay two round-trips per sample on a v1 host.
        try {
            var result = container.execInContainer("sh", "-c",
                "cat /sys/fs/cgroup/memory.current 2>/dev/null "
                    + "|| cat /sys/fs/cgroup/memory/memory.usage_in_bytes 2>/dev/null");
            if (result.getExitCode() != 0) {
                return -1;
            }
            String out = result.getStdout().trim();
            if (out.isEmpty()) {
                return -1;
            }
            // The combined command can emit two lines if both files exist on
            // some hybrid hosts; take the first non-empty numeric line.
            for (String line : out.split("\\R")) {
                String s = line.trim();
                if (!s.isEmpty()) {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException ignored) {
                        // try next line
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    long maxBytes() {
        return maxBytes;
    }

    static long toMiB(long bytes) {
        return bytes / (1024 * 1024);
    }

    long maxMiB() {
        return toMiB(maxBytes);
    }

    synchronized int sampleCount() {
        return samples.size();
    }

    /**
     * A handful of curve points (MiB) at ~0/25/50/75/100% of the recorded run,
     * formatted as {@code "t0ms=NNN, t1234ms=NNN, ..."}. Conveys the curve
     * shape (flat / rises-then-drains / stays-elevated) compactly.
     */
    synchronized String curveMiB() {
        if (samples.isEmpty()) {
            return "(no samples)";
        }
        int n = samples.size();
        int[] idx = {
            0,
            (n - 1) / 4,
            (n - 1) / 2,
            (3 * (n - 1)) / 4,
            n - 1
        };
        var sb = new StringBuilder();
        int last = -1;
        for (int i : idx) {
            if (i == last) {
                continue; // dedupe when very few samples
            }
            last = i;
            Sample s = samples.get(i);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(String.format(Locale.ROOT, "t%dms=%d", s.tMillis(), toMiB(s.bytes())));
        }
        return sb.toString();
    }

    /** Max plus the curve points, for a one-line log summary. */
    synchronized String summary() {
        return String.format(Locale.ROOT, "maxMiB=%d; samples=%d; curve=[%s]",
            maxMiB(), samples.size(), curveMiB());
    }
}
