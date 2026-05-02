package sh.libre.scim.perf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures per-scenario timing measurements and writes a markdown report to
 * {@code build/reports/perf/}. Designed for ad-hoc baseline / before-after
 * comparison: each test scenario records one or more {@link Sample}s, and
 * the report is appended to so re-running tests builds up a session log.
 *
 * <p>Output is intentionally minimal — wall-clock duration, throughput, and
 * any free-form notes the scenario wants to record. No P50/P99 distribution
 * yet; we'll add that if individual measurements stop being enough.
 */
public final class PerfReport {

    public record Sample(
        String scenario,
        String label,
        Duration duration,
        long itemCount,
        Map<String, String> notes
    ) {
        public double itemsPerSecond() {
            double seconds = duration.toMillis() / 1000.0;
            return seconds == 0 ? 0 : itemCount / seconds;
        }
    }

    private final List<Sample> samples = new ArrayList<>();
    private final String suiteName;
    private final Instant startedAt = Instant.now();

    public PerfReport(String suiteName) {
        this.suiteName = suiteName;
    }

    public Sample timed(String scenario, String label, long itemCount, Runnable work) {
        Map<String, String> notes = new LinkedHashMap<>();
        return timedWithNotes(scenario, label, itemCount, notes, () -> {
            work.run();
            return null;
        });
    }

    public <T> Sample timedWithNotes(String scenario, String label, long itemCount,
                                     Map<String, String> notes, java.util.function.Supplier<T> work) {
        long start = System.nanoTime();
        work.get();
        long elapsed = System.nanoTime() - start;
        var sample = new Sample(scenario, label,
            Duration.ofNanos(elapsed), itemCount, Map.copyOf(notes));
        samples.add(sample);
        return sample;
    }

    public void record(Sample sample) {
        samples.add(sample);
    }

    public List<Sample> samples() {
        return List.copyOf(samples);
    }

    public void write() throws IOException {
        String dir = System.getProperty("perf.report.dir", "build/reports/perf");
        Path reportPath = Path.of(dir, suiteName + ".md");
        Files.createDirectories(reportPath.getParent());

        var sb = new StringBuilder();
        sb.append("# ").append(suiteName).append("\n\n");
        sb.append("Started: ").append(startedAt).append("  \n");
        sb.append("Finished: ").append(Instant.now()).append("\n\n");
        sb.append("| Scenario | Label | Items | Duration | Items/sec | Notes |\n");
        sb.append("| --- | --- | ---: | ---: | ---: | --- |\n");
        for (Sample s : samples) {
            sb.append("| ").append(s.scenario())
              .append(" | ").append(s.label())
              .append(" | ").append(s.itemCount())
              .append(" | ").append(formatDuration(s.duration()))
              .append(" | ").append(String.format("%.1f", s.itemsPerSecond()))
              .append(" | ").append(formatNotes(s.notes()))
              .append(" |\n");
        }
        sb.append("\n");

        Files.writeString(reportPath, sb.toString(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("[perf] report written to " + reportPath.toAbsolutePath());
        System.out.println(sb);
    }

    private static String formatDuration(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) return ms + " ms";
        if (ms < 60_000) return String.format("%.2f s", ms / 1000.0);
        return String.format("%dm %.2fs", ms / 60_000, (ms % 60_000) / 1000.0);
    }

    private static String formatNotes(Map<String, String> notes) {
        if (notes.isEmpty()) return "";
        var parts = new ArrayList<String>();
        notes.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join("; ", parts);
    }
}
