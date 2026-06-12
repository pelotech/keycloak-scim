package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the SCIM {@code /Bulk} user-create path: when the SCIM
 * component has {@code bulk-enabled=true}, federation-import creates must be
 * coalesced into {@code POST /Bulk} requests (one op per user) rather than
 * per-user {@code POST /Users} calls.
 *
 * <p>25 LDAP users are seeded so the batch boundary (K=20) is crossed, forcing at
 * least two bulk requests. The {@code /Bulk} sink ({@link #stubScimBulkOk()}) echoes
 * each request op's {@code bulkId} with {@code status:201} and a resource id, via
 * {@link ScimBulkResponseTransformer}, so the plugin resolves each op and persists
 * the user→SCIM mapping.
 */
class ScimBulkUserCreateIT extends IntegrationTestBase {

    private static final int USER_COUNT = 25; // > batch size (20) => >= 2 bulk requests

    /** Distinct bulkIds across every POST /Bulk body WireMock has recorded. */
    private java.util.Set<String> distinctBulkIds() {
        var ids = new java.util.HashSet<String>();
        var p = java.util.regex.Pattern.compile("\"bulkId\"\\s*:\\s*\"([^\"]+)\"");
        for (var e : wireMock.getAllServeEvents()) {
            var req = e.getRequest();
            if ("POST".equals(req.getMethod().getName()) && "/Bulk".equals(req.getUrl())) {
                var m = p.matcher(req.getBodyAsString());
                while (m.find()) ids.add(m.group(1));
            }
        }
        return ids;
    }

    @Test
    void fullSyncWithBulkEnabledUsesBulkEndpointNotPerUserPosts() throws Exception {
        stubScimBulkOk();
        // Stub the per-op create + replace endpoints too. If a regression sent
        // creates per-op, those POSTs would succeed and be counted (rather than
        // 404-ing into noise). The PUT stub keeps the second sync's replace path
        // clean — see the secondary check below.
        stubScimUserCreateOk();
        stubScimUserUpdateOk();

        seedLdapUsers("bulk", USER_COUNT);
        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("bulk-enabled", "true"));

        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");

        // PRIMARY: creates went through /Bulk. 25 users / batch 20 => >= 2 bulk POSTs.
        awaitBulkPostCount(2);
        int bulksAfterFirstSync = bulkPostCount();
        assertTrue(bulksAfterFirstSync >= 2,
            "expected at least 2 /Bulk POSTs for " + USER_COUNT + " users at batch size 20, got "
            + bulksAfterFirstSync);

        // Let the bulk lane fully drain: triggerFullSync returns when Keycloak's
        // iteration completes, but the lane workers issue the /Bulk POSTs (and
        // persist mappings) asynchronously in their own transactions. Wait until
        // the /Bulk POST count goes quiet so every first-sync mapping is durably
        // saved before we re-sync below; otherwise a mapping that lands after the
        // re-import re-fetches its user races into a spurious second bulk create.
        awaitBulkPostCountStable();
        // Small extra settle: the saveMapping() transaction commits just after the
        // /Bulk response is parsed, a hair behind WireMock observing the request.
        sleepQuietly(2);

        // PRIMARY: zero per-op /Users POSTs on the create sync — the bulk path
        // fully replaced per-op creates.
        assertEquals(0, perUserPostCount(),
            "with bulk-enabled=true, the create sync must go through /Bulk, not POST /Users");

        // SECONDARY (mapping persistence): the first sync must have bulk-created
        // EVERY federated user. Each /Bulk op carries the kcUserId as its bulkId,
        // so the set of distinct bulkIds seen equals the set of users that went
        // through the bulk-create path. With 25 seeded users plus the two from
        // the LDAP seed fixture (alice, bob), that is 27 distinct bulkIds.
        java.util.Set<String> bulkIdsFirst = distinctBulkIds();
        assertEquals(USER_COUNT + 2, bulkIdsFirst.size(),
            "every federated user (25 seeded + alice + bob) must be bulk-created; distinct bulkIds="
            + bulkIdsFirst.size());

        // Re-sync the same users. They now exist in Keycloak, so the federation
        // import fires with isCreate=false — the per-op *replace* (PUT) path, not
        // the bulk-create path (the PUT stub keeps that path clean). Crucially,
        // bulkCreateUsers skips any user whose mapping already exists (its findById
        // guard). So the re-sync must introduce NO new distinct bulkId: a brand-new
        // bulkId would mean a user the first round failed to map (getResourceId
        // unresolved => saveMapping never ran), forcing a fresh bulk create now.
        //
        // We assert on the distinct-bulkId delta rather than the raw POST count:
        // the dispatcher can occasionally submit a single user to the lane twice
        // (e.g. across sync iterations), producing a redundant /Bulk POST whose op
        // is correctly skipped server-resolution-side — a benign duplicate that
        // does not indicate a mapping failure.
        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");
        sleepQuietly(5); // let the lane drain whatever the re-sync may have enqueued
        java.util.Set<String> newBulkIds = distinctBulkIds();
        newBulkIds.removeAll(bulkIdsFirst);
        assertTrue(newBulkIds.isEmpty(),
            "re-sync must not bulk-create any new user; new bulkIds (=> first sync failed to "
            + "persist their SCIM mappings) were: " + newBulkIds);
    }
}
