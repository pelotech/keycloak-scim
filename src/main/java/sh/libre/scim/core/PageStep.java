package sh.libre.scim.core;

/**
 * One kind of page in a batch sync. The step decides what the cursor means,
 * fetches the page, runs the transaction, and processes each resource;
 * {@link PagedSyncRunner} only loops.
 */
interface PageStep<C> {

    /** The cursor the first page starts from. */
    C initialCursor();

    /** Processes up to {@code size} resources after {@code cursor}. */
    PageOutcome<C> run(C cursor, int size);
}
