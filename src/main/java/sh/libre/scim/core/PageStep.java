package sh.libre.scim.core;

/**
 * One kind of page in a batch sync. The step decides what the cursor means,
 * fetches the page, runs the transaction, and processes each resource;
 * {@link PagedSyncRunner} only loops.
 */
interface PageStep<C> {

    /** The cursor the first page starts from. */
    C initialCursor();

    /**
     * Processes up to {@code size} resources after {@code cursor}.
     *
     * <p>The runner depends on four promises:
     * <ul>
     *   <li>The step returns an outcome. It never returns {@code null}.</li>
     *   <li>The counters are a fresh result that holds this page's counts
     *       alone. The step never returns the run-level result, because the
     *       runner adds what it gets to that result.</li>
     *   <li>{@code progressed} is false only when the step cannot advance past
     *       {@code cursor} at all. A page that processes nothing but moves its
     *       cursor still progressed.</li>
     *   <li>{@code next} is read only when the run goes on. A step that ends
     *       the run may return any cursor, and the runner logs it.</li>
     * </ul>
     *
     * @param cursor where this page starts; the runner passes back the
     *     {@code next} of the page before it
     * @param size the largest number of resources this page may process
     * @return this page's outcome, never {@code null}
     */
    PageOutcome<C> run(C cursor, int size);
}
