package sh.libre.scim.core;

import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight, always-on counters for {@link ScimClient}'s per-operation
 * cost breakdown. Used by perf tests; cheap enough (LongAdder.add ~ns) to
 * leave on in production for ad-hoc operator inspection.
 *
 * <p>Counters accumulate nanoseconds spent in each phase across the JVM
 * lifetime. Pair {@code CREATE_COUNT} with each {@code *_NANOS} adder to
 * compute average per-call cost. Reset by calling {@link #reset()} (perf
 * tests do this between scenarios).
 */
public final class ScimClientMetrics {

    private ScimClientMetrics() {}

    public static final LongAdder APPLY_MODEL_NANOS = new LongAdder();
    public static final LongAdder QUERY_NANOS = new LongAdder();
    public static final LongAdder HTTP_NANOS = new LongAdder();
    public static final LongAdder APPLY_RESPONSE_NANOS = new LongAdder();
    public static final LongAdder SAVE_MAPPING_NANOS = new LongAdder();
    public static final LongAdder CREATE_COUNT = new LongAdder();

    public static void reset() {
        APPLY_MODEL_NANOS.reset();
        QUERY_NANOS.reset();
        HTTP_NANOS.reset();
        APPLY_RESPONSE_NANOS.reset();
        SAVE_MAPPING_NANOS.reset();
        CREATE_COUNT.reset();
    }

    public static String summary() {
        long n = CREATE_COUNT.sum();
        if (n == 0) return "ScimClient create: count=0";
        long apply = APPLY_MODEL_NANOS.sum();
        long query = QUERY_NANOS.sum();
        long http = HTTP_NANOS.sum();
        long applyResp = APPLY_RESPONSE_NANOS.sum();
        long save = SAVE_MAPPING_NANOS.sum();
        long total = apply + query + http + applyResp + save;
        return String.format(
            "ScimClient create: count=%d total=%dms (avg %.2fms)\n"
            + "  applyModel:    %dms (%.2fms avg, %.1f%%)\n"
            + "  query findById: %dms (%.2fms avg, %.1f%%)\n"
            + "  http send:     %dms (%.2fms avg, %.1f%%)\n"
            + "  applyResponse: %dms (%.2fms avg, %.1f%%)\n"
            + "  saveMapping:   %dms (%.2fms avg, %.1f%%)",
            n, total / 1_000_000, total / 1_000_000.0 / n,
            apply / 1_000_000, apply / 1_000_000.0 / n, 100.0 * apply / total,
            query / 1_000_000, query / 1_000_000.0 / n, 100.0 * query / total,
            http / 1_000_000, http / 1_000_000.0 / n, 100.0 * http / total,
            applyResp / 1_000_000, applyResp / 1_000_000.0 / n, 100.0 * applyResp / total,
            save / 1_000_000, save / 1_000_000.0 / n, 100.0 * save / total
        );
    }
}
