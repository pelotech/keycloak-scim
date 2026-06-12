package sh.libre.scim.core;

/**
 * One queued SCIM user-create operation, coalescable by {@link ScimBulkLane} —
 * identifiers only, no payload. The bulk worker re-fetches the user by id and
 * builds the SCIM JSON in its own post-commit session (the gate spike showed
 * email/name are not yet populated on the import thread, so the payload cannot
 * be built eagerly). {@code realmId} + {@code componentId} let the worker open
 * its session and locate the SCIM component; {@code kcUserId} is the bulkId
 * correlation handle and the mapping local id.
 */
record BulkUserOp(String realmId, String componentId, String kcUserId) {}
