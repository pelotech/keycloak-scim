package sh.libre.scim.core;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.ws.rs.ProcessingException;

import java.util.ArrayList;
import java.util.List;

import de.captaingoldfish.scim.sdk.client.ScimClientConfig;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import de.captaingoldfish.scim.sdk.client.builder.BulkBuilder;
import de.captaingoldfish.scim.sdk.client.exceptions.IORuntimeException;
import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.constants.enums.HttpMethod;
import de.captaingoldfish.scim.sdk.common.exceptions.ResponseException;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.ResourceNode;
import de.captaingoldfish.scim.sdk.common.response.BulkResponse;
import de.captaingoldfish.scim.sdk.common.response.ListResponse;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RoleMapperModel;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.user.SynchronizationResult;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import sh.libre.scim.jpa.ScimProvisionLock;


public class ScimClient {
    private static final ScimTracingBridge TRACING = ScimTracingBridge.create();
    private static final String GROUP_PATCH_OP_KEY = "group-patchOp";

    final protected Logger LOGGER = Logger.getLogger(ScimClient.class);
    final protected ScimRequestBuilder scimRequestBuilder;
    final protected RetryRegistry registry;
    final protected KeycloakSession session;
    final protected ComponentModel model;
    final protected String scimApplicationBaseUrl;
    final protected ScimAuthHeaders auth;

    public ScimClient(ComponentModel model, KeycloakSession session) {
        this(model, session, new ScimAuthHeaders(model));
    }

    // package-private for tests: inject an explicit token source.
    ScimClient(ComponentModel model, KeycloakSession session, OAuthClientCredentialsTokenSource tokenSource) {
        this(model, session, new ScimAuthHeaders(model, tokenSource));
    }

    private ScimClient(ComponentModel model, KeycloakSession session, ScimAuthHeaders auth) {
        this.model = model;
        this.session = session;
        this.scimApplicationBaseUrl = model.get("endpoint");
        this.auth = auth;

        scimRequestBuilder = new ScimRequestBuilder(scimApplicationBaseUrl, genScimClientConfig());

        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(10)
            .intervalFunction(IntervalFunction.ofExponentialBackoff())
            // Retry on both JAX-RS-level network errors (ProcessingException)
            // and the SCIM SDK's own network-error wrapper (IORuntimeException
            // — what Captain Goldfish throws when Apache HttpClient surfaces
            // SocketException, NoHttpResponseException, etc.). Without
            // IORuntimeException here, the entire retry policy is effectively
            // dead code for this client stack — every real-world transient
            // failure surfaces as IORuntimeException and bypasses retry.
            //
            // HTTP error responses do NOT throw; they return a ServerResponse
            // with isSuccess()=false. The result predicate below retries the
            // transient ones (429 + any 5xx — see isRetryableStatus). See
            // ScimResilienceIT#serverErrorIsRetriedAndEventuallySucceeds.
            .retryExceptions(ProcessingException.class, IORuntimeException.class)
            .retryOnResult(result ->
                result instanceof ServerResponse<?> resp && isRetryableStatus(resp.getHttpStatus()))
            .build();

        registry = RetryRegistry.of(retryConfig);
    }

    /** The SCIM provider component id — stable across syncs/restarts. */
    public String getComponentId() {
        return model.getId();
    }

    /**
     * Whether this component sends single-member delta PATCHes for group
     * membership ({@code group-patchOp=true}, the default). When false,
     * membership changes go through a full-group {@code replace} (PUT the whole
     * member list), which enumerates a federated group's members and re-imports
     * them — an unbounded re-import loop. The federated-import membership path
     * therefore only runs when this is true (see ScimLdapStorageMapper).
     */
    public boolean isGroupMembershipDeltaEnabled() {
        return this.model.get(GROUP_PATCH_OP_KEY, false);
    }

    protected ScimClientConfig genScimClientConfig() {
        var builder = ScimClientConfig.builder()
        .httpHeaders(auth.headers())
        .connectTimeout(30)
        .requestTimeout(30)
        .socketTimeout(30)
        .expectedHttpResponseHeaders(auth.expectedResponseHeaders())
        // Override the SDK's hardcoded "no TCP connection reuse" + tiny
        // default pool. See KeepAliveConfigManipulator's javadoc for the
        // background — without this, every SCIM call pays full TCP
        // handshake + teardown cost (~43 ms on localhost in our perf
        // measurements).
        .configManipulator(new KeepAliveConfigManipulator());

        if (tlsHostnameVerificationDisabled()) {
            // Operator-opted-out via -Dscim.tls.insecureHostnameVerification=true.
            // Default is strict verification (the SDK / Apache HttpClient
            // falls back to its default verifier when none is set). Use
            // the escape hatch only for dev, internal-CA-with-CN-drift, or
            // explicitly-trusted self-signed scenarios; production should
            // leave it off.
            builder = builder.hostnameVerifier((s, sslSession) -> true);
        }

        return builder.build();
    }

    // package-private for tests
    static boolean tlsHostnameVerificationDisabled() {
        return Boolean.getBoolean("scim.tls.insecureHostnameVerification");
    }

    // 429 (rate-limited) + any 5xx are transient; retry with backoff.
    // 401/403 are deliberately excluded — sendWithAuthRefresh handles those
    // (token re-mint + one retry), and this retry runs inside that wrapper.
    // package-private for tests
    static boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    protected String genScimUrl(String scimEndpoint, String resourcePath) {
        return "%s/%s/%s".formatted(scimApplicationBaseUrl,
                scimEndpoint,
                resourcePath);
    }


    protected EntityManager getEM() {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    protected String getRealmId() {
        return session.getContext().getRealm().getId();
    }

    protected <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> A getAdapter(
            AdapterFactory<M, S, A> factory) {
        return factory.create(session, this.model.getId());
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void create(
            AdapterFactory<M, S, A> factory, M kcModel) {
        long t0 = System.nanoTime();
        var adapter = getAdapter(factory);
        adapter.apply(kcModel);
        if (!adapter.skip) {
            ScimClientMetrics.APPLY_MODEL_NANOS.add(System.nanoTime() - t0);
        }
        sendCreate(adapter);
    }

    // Shared create send/persist path: skip + idempotent short-circuit on an
    // existing mapping, then POST and persist the mapping. Used by create()
    // (full apply) and the member-less group-membership provisioning path.
    private <S extends ResourceNode> void sendCreate(Adapter<?, S> adapter) {
        if (adapter.skip) {
            return;
        }
        // If a mapping already exists (created by a prior import or provision), skip.
        if (adapter.query("findById", adapter.getId()).getResultList().size() != 0) {
            return;
        }
        handleCreateResponse(adapter, postResource(adapter));
    }

    // POSTs the resource to the SCIM target and returns the raw response. Shared
    // by sendCreate() (which then persists the mapping in the caller's transaction)
    // and provisionGroupForMembership() (which persists in a nested transaction
    // under a lock). Does NOT touch the mapping table — persistence is the
    // caller's concern.
    private <S extends ResourceNode> ServerResponse<S> postResource(Adapter<?, S> adapter) {
        // Fixed retry name (was "create-" + adapter.getId()). With ScimClient
        // instances now cached across many calls in ScimDispatcher, per-id
        // names would let the RetryRegistry accumulate Retry instances
        // unboundedly. Operation-name granularity is the right scope —
        // resilience4j's per-Retry state isn't per-resource anyway.
        var retry = registry.retry("create");

        try (var span = TRACING.startSpan("scim.create", adapter.getType(), scimApplicationBaseUrl)) {
            ServerResponse<S> response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                try {
                    return scimRequestBuilder
                    .create(adapter.getResourceClass(), ("/" + adapter.getSCIMEndpoint()).formatted())
                    .setResource(adapter.toSCIM(false))
                    .sendRequest();
                } catch (ResponseException e) {
                    throw new RuntimeException(e);
                }
            }));
            span.setHttpStatus(response.getHttpStatus());
            return response;
        }
    }

    // Provision the group for membership propagation WITHOUT enumerating its
    // members. The member-enumerating create()/apply(GroupModel) re-imports
    // every member on a federated group and triggers an unbounded re-import
    // loop; this path sets id + displayName + scim-skip only.
    // Package-private (not private) so EnsureGroupMembershipTest can spy-verify it.
    void provisionGroupForMembership(
            AdapterFactory<GroupModel, Group, GroupAdapter> factory, GroupModel group) {
        var adapter = getAdapter(factory);
        adapter.applyForProvisioning(group);
        // (No APPLY_MODEL_NANOS metric here — applyForProvisioning is a couple of
        // setters, unlike the member-enumerating apply() that create() times.)
        if (adapter.skip) {
            return;
        }

        // Lock-free fast path: the group is already provisioned (a committed
        // mapping exists). The steady state and every already-mapped group take
        // this branch and never touch the lock.
        if (adapter.query("findById", adapter.getId()).getResultList().size() != 0) {
            return;
        }

        // Atomic first-time provisioning, cluster-safe. Several federated-member
        // import workers can reach here for the SAME group concurrently (each in
        // its own transaction); without coordination they all POST /Groups and all
        // persist a mapping — a duplicate that collides in SCIM_RESOURCE (rolling a
        // worker back) or, on a non-deduping server, creates two groups. Serialize
        // on a pessimistic DB lock (SELECT ... FOR UPDATE on the single seeded
        // SCIM_PROVISION_LOCK row), taken on THIS worker's EntityManager and held
        // until its transaction commits: the lock works across cluster nodes, and
        // because it releases only at commit, the next worker to acquire it sees
        // the winner's committed mapping (re-check below) and skips its POST —
        // exactly one POST, one mapping.
        entityManager().find(ScimProvisionLock.class, ScimProvisionLock.GROUPS,
                LockModeType.PESSIMISTIC_WRITE);
        if (adapter.query("findById", adapter.getId()).getResultList().size() != 0) {
            return; // a concurrent worker provisioned + committed while we waited
        }
        ServerResponse<Group> response = postResource(adapter);
        if (!response.isSuccess()) {
            LOGGER.warnf("Failed to provision group %s: HTTP %d %s",
                    adapter.getId(), response.getHttpStatus(), response.getResponseBody());
            return;
        }
        adapter.apply(response.getResource()); // captures the server-assigned external id
        adapter.saveMapping(); // in this worker's own transaction; commits with it, releasing the lock
    }

    private EntityManager entityManager() {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    /**
     * Persists the SCIM mapping from a create response, but only when the POST
     * succeeded. A rejected create (e.g. the target returns 400 for a user with
     * no email) carries no parsed resource: {@code response.getResource()} is
     * null, so applying it NPEs and — worse — a mapping persisted here would be
     * a phantom record for a resource the target never created. On failure we
     * log and bail, leaving the user unmapped so a later sync can retry.
     *
     * @return true if the mapping was applied and saved, false if the response
     *         was unsuccessful and skipped.
     */
    // package-private for tests
    <S extends ResourceNode> boolean handleCreateResponse(Adapter<?, S> adapter, ServerResponse<S> response) {
        if (!response.isSuccess()) {
            LOGGER.warnf("Failed to create SCIM resource %s: HTTP %d %s",
                adapter.getId(), response.getHttpStatus(), response.getResponseBody());
            return false;
        }

        long t0 = System.nanoTime();
        adapter.apply(response.getResource());
        long t1 = System.nanoTime();
        ScimClientMetrics.APPLY_RESPONSE_NANOS.add(t1 - t0);
        adapter.saveMapping();
        long t2 = System.nanoTime();
        ScimClientMetrics.SAVE_MAPPING_NANOS.add(t2 - t1);
        ScimClientMetrics.CREATE_COUNT.increment();
        return true;
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void replace(
            AdapterFactory<M, S, A> factory, M kcModel) {
        var adapter = getAdapter(factory);
        try (var span = TRACING.startSpan("scim.replace", adapter.getType(), scimApplicationBaseUrl)) {
            try {
                adapter.apply(kcModel);
                if (adapter.skip) {
                    return;
                }
                var resource = adapter.query("findById", adapter.getId()).getSingleResult();
                adapter.apply(resource);
                String url = genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId());
                var retry = registry.retry("replace");
                ServerResponse<S> response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                    try {
                        LOGGER.info(adapter.getType());
                        if ((adapter.getType() == "Group" && this.model.get(GROUP_PATCH_OP_KEY, false))
                             || (adapter.getType() == "User" && this.model.get("user-patchOp", false))) {
                            return adapter.toPatchBuilder(scimRequestBuilder, url)
                                          .sendRequest();
                        }
                        else {
                            return scimRequestBuilder
                                .update(url, adapter.getResourceClass())
                                .setResource(adapter.toSCIM(false))
                                .sendRequest();
                        }
                    } catch (ResponseException e) {
                        throw new RuntimeException(e);
                    }
                }));
                if (!response.isSuccess()) {
                    int statusCode = response.getHttpStatus();
                    if (statusCode == 405 && adapter.getType().equals("Group") && !this.model.get(GROUP_PATCH_OP_KEY, false)) {
                        LOGGER.infof("PUT not supported (405) for group %s, falling back to PATCH", adapter.getId());
                        response = adapter.toPatchBuilder(scimRequestBuilder, url).sendRequest();
                    }
                    if (!response.isSuccess()) {
                        int currentStatus = response.getHttpStatus();
                        if (currentStatus == 404 || currentStatus == 400) {
                            LOGGER.infof("Remote resource %s not found (%d), re-creating", adapter.getId(), currentStatus);
                            ServerResponse<S> createResponse = scimRequestBuilder
                                .create(adapter.getResourceClass(), ("/" + adapter.getSCIMEndpoint()).formatted())
                                .setResource(adapter.toSCIM(false))
                                .sendRequest();
                            if (createResponse.isSuccess()) {
                                adapter.apply(createResponse.getResource());
                                var existingMapping = adapter.getMapping();
                                if (existingMapping != null) {
                                    existingMapping.setExternalId(adapter.getExternalId());
                                    getEM().merge(existingMapping);
                                } else {
                                    adapter.saveMapping();
                                }
                            }
                            response = createResponse;
                        }
                    }
                    if (!response.isSuccess()) {
                        LOGGER.warn(response.getResponseBody());
                        LOGGER.warn(response.getHttpStatus());
                    }
                }
                span.setHttpStatus(response.getHttpStatus());
            } catch (NoResultException e) {
                span.recordError(e);
                LOGGER.warnf("failed to replace resource %s, scim mapping not found", adapter.getId());
            } catch (Exception e) {
                span.recordError(e);
                LOGGER.error(e);
            }
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void delete(
            AdapterFactory<M, S, A> factory, String id) {
        var adapter = getAdapter(factory);
        adapter.setId(id);

        try (var span = TRACING.startSpan("scim.delete", adapter.getType(), scimApplicationBaseUrl)) {
            try {
                var resource = adapter.query("findById", adapter.getId()).getSingleResult();
                adapter.apply(resource);

                var retry = registry.retry("delete");

                ServerResponse<S> response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                    try {
                        return scimRequestBuilder.delete(genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId()),
                                                                    adapter.getResourceClass())
                                                 .sendRequest();
                    } catch (ResponseException e) {
                        throw new RuntimeException(e);
                    }
                }));

                span.setHttpStatus(response.getHttpStatus());
                if (!response.isSuccess()) {
                    LOGGER.warn(response.getResponseBody());
                    LOGGER.warn(response.getHttpStatus());
                }

                getEM().remove(resource);

            } catch (NoResultException e) {
                span.recordError(e);
                LOGGER.warnf("Failed to delete resource %s, scim mapping not found", id);
            }
        }
    }

    /**
     * Propagates a single group-membership change as a minimal SCIM PATCH —
     * one member ADD or REMOVE — rather than re-sending the entire member list
     * via {@link #replace}. Only the {@code GROUP_MEMBERSHIP} event path uses
     * this; group attribute changes still go through {@code replace}.
     *
     * <p>When {@code group-patchOp} is disabled (the remote doesn't support
     * PATCH), this falls back to a full {@code replace} so behaviour is
     * unchanged for those deployments. A missing group or user mapping (e.g.
     * the user was never synced) is logged and skipped, mirroring {@link #delete}.
     */
    public boolean patchGroupMembership(
            AdapterFactory<GroupModel, Group, GroupAdapter> factory,
            String groupId, String userId, boolean isAdd) {

        if (!this.model.get(GROUP_PATCH_OP_KEY, false)) {
            var group = session.groups().getGroupById(
                    session.getContext().getRealm(), groupId);
            // group-patchOp=false propagates a membership change via a full-group
            // `replace` (PUT the whole member list). For a FEDERATED (non-local)
            // group, building that list enumerates the group's members and can
            // re-import any not-yet-imported member (re-import loop). Local groups
            // enumerate already-local members and are safe. So skip the replace
            // for a federated group — membership for federated groups requires
            // group-patchOp=true (see docs/ldap-federation-support.md).
            if (group == null || !StorageId.isLocalStorage(group.getId())) {
                if (group != null) {
                    LOGGER.warnf("Skipping membership change for federated group %s: "
                            + "group-patchOp=false cannot propagate it without a re-import "
                            + "loop; requires group-patchOp=true", groupId);
                }
                return true;
            }
            this.replace(factory, group);
            return true;
        }

        var adapter = getAdapter(factory);
        try (var span = TRACING.startSpan(
                isAdd ? "scim.group.member.add" : "scim.group.member.remove",
                "Group", scimApplicationBaseUrl)) {
            try {
                adapter.setId(groupId);
                var groupMapping = adapter.query("findById", groupId).getSingleResult();
                adapter.apply(groupMapping);

                var userMapping = adapter.query("findById", userId, "User").getSingleResult();
                String userExternalId = userMapping.getExternalId();
                String url = genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId());

                var retry = registry.retry("patchMembership");
                ServerResponse<Group> response = auth.sendWithAuthRefresh(
                    () -> retry.executeSupplier(() -> {
                        try {
                            return adapter.toMembershipPatchBuilder(
                                    scimRequestBuilder, url, userExternalId, isAdd)
                                .sendRequest();
                        } catch (ResponseException e) {
                            throw new RuntimeException(e);
                        }
                    }));

                span.setHttpStatus(response.getHttpStatus());
                if (!response.isSuccess()) {
                    LOGGER.warnf("Failed to PATCH membership for group %s / user %s: %d %s",
                            groupId, userId, response.getHttpStatus(), response.getResponseBody());
                    return false;
                }
                return true;
            } catch (NoResultException e) {
                span.recordError(e);
                LOGGER.infof("Skipping membership patch: no SCIM mapping for group %s or user %s",
                        groupId, userId);
                // Direction-aware: a REMOVE with no mapping has nothing to remove
                // (applied → true), but an ADD with no mapping did NOT propagate
                // (e.g. the user's SCIM mapping isn't committed yet — the lazy-import
                // lag), so report not-applied so the caller retries on a later import.
                return !isAdd;
            }
        }
    }

    /**
     * Ensures a federated user's membership in one group is reflected in SCIM:
     * the SCIM group exists, and the user is a member. Used by the LDAP-import
     * path.
     *
     * <p>Returns whether the membership is now propagated: {@code true} when the
     * member-add applied (or the group already had it), {@code false} when it
     * could not be propagated this import — the local group is missing, or the
     * add hit the lazy-import lag (the user's SCIM mapping isn't committed yet).
     * The caller uses this to track the propagated-group set and to retry a
     * {@code false} on a later import, rather than re-asserting every membership
     * on every import.
     *
     * <p>{@link #provisionGroupForMembership} short-circuits once the group has a
     * local mapping, and the member-add is a single-member delta PATCH.
     * Provisioning is deliberately <em>member-less</em> — it must not enumerate
     * the group's members, because on a federated group that re-imports every
     * member and re-fires {@code onImportUserFromLDAP} (an unbounded re-import
     * loop).
     *
     * <p>When {@code group-patchOp=false}, {@link #patchGroupMembership} falls
     * back to a full {@code replace} that itself provisions the group and the
     * membership, so the explicit member-less provisioning is redundant and
     * skipped. NOTE: that {@code replace} path <em>does</em> enumerate members
     * (via {@code GroupAdapter.apply(GroupModel)}), so the re-import loop can
     * still occur on the non-default {@code group-patchOp=false} path — a known
     * residual (see docs/roadmap.md). A missing local group is logged and skipped.
     */
    public boolean ensureGroupMembership(
            AdapterFactory<GroupModel, Group, GroupAdapter> factory,
            String groupId, String userId) {
        var group = session.groups().getGroupById(session.getContext().getRealm(), groupId);
        if (group == null) {
            LOGGER.infof("Skipping membership ensure: group %s not found locally", groupId);
            return false;
        }
        if (this.model.get(GROUP_PATCH_OP_KEY, false)) {
            provisionGroupForMembership(factory, group);
        }
        return this.patchGroupMembership(factory, groupId, userId, true);
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void refreshResources(
            AdapterFactory<M, S, A> factory,
            SynchronizationResult syncRes) {
        LOGGER.info("Refresh resources");
        try (var ignored = TRACING.startSpan("scim.sync.refresh", getAdapter(factory).getType(), scimApplicationBaseUrl)) {
            getAdapter(factory).getResourceStream().forEach(resource -> {
                var adapter = getAdapter(factory);
                adapter.apply(resource);
                LOGGER.infof("Reconciling local resource %s", adapter.getId());
                if (!adapter.skipRefresh()) {
                    var mapping = adapter.getMapping();
                    if (mapping == null) {
                        LOGGER.info("Creating it");
                        this.create(factory, resource);
                    } else {
                        LOGGER.info("Replacing it");
                        this.replace(factory, resource);
                    }
                    syncRes.increaseUpdated();
                }
            });
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void importResources(
            AdapterFactory<M, S, A> factory, SynchronizationResult syncRes) {
        LOGGER.info("Import");
        try (var ignored = TRACING.startSpan("scim.sync.import", getAdapter(factory).getType(), scimApplicationBaseUrl)) {
            try {
                var adapter = getAdapter(factory);
                String listUrl = scimApplicationBaseUrl + "/" + adapter.getSCIMEndpoint();
                Class<S> resourceClass = adapter.getResourceClass();
                ServerResponse<ListResponse<S>> response = auth.sendWithAuthRefresh(() ->
                    scimRequestBuilder.list(listUrl, resourceClass).get().sendRequest());
                ListResponse<S> resourceTypeListResponse = response.getResource();

                for (var resource : resourceTypeListResponse.getListedResources()) {
                    try {
                        LOGGER.infof("Reconciling remote resource %s", resource);
                        adapter = getAdapter(factory);
                        adapter.apply(resource);

                        var mapping = adapter.getMapping();
                        if (mapping != null) {
                            adapter.apply(mapping);
                            if (adapter.entityExists()) {
                                LOGGER.info("Valid mapping found, skipping");
                                continue;
                            } else {
                                LOGGER.info("Delete a dangling mapping");
                                adapter.deleteMapping();
                            }
                        }

                        var mapped = adapter.tryToMap();
                        if (mapped) {
                            LOGGER.info("Matched");
                            adapter.saveMapping();
                        } else {
                            switch (this.model.get("sync-import-action")) {
                                case "CREATE_LOCAL":
                                    LOGGER.info("Create local resource");
                                    try {
                                        adapter.createEntity();
                                        adapter.saveMapping();
                                        syncRes.increaseAdded();
                                    } catch (Exception e) {
                                        LOGGER.error(e);
                                    }
                                    break;
                                case "DELETE_REMOTE":
                                    LOGGER.info("Delete remote resource");
                                    scimRequestBuilder
                                        .delete(genScimUrl(adapter.getSCIMEndpoint(),
                                                           resource.getId().get()),
                                                           adapter.getResourceClass())
                                        .sendRequest();
                                    syncRes.increaseRemoved();
                                    break;
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error(e);
                        e.printStackTrace();
                        syncRes.increaseFailed();
                    }
                }
            } catch (ResponseException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void sync(
            AdapterFactory<M, S, A> factory, SynchronizationResult syncRes) {
        if (this.model.get("sync-import", false)) {
            this.importResources(factory, syncRes);
        }
        if (this.model.get("sync-refresh", false)) {
            this.refreshResources(factory, syncRes);
        }
    }

    /** Aggregated result from a single {@link #bulkCreateUsers} call. */
    public record BulkResult(int created, int skipped, int failed) {
        static final BulkResult EMPTY = new BulkResult(0, 0, 0);
    }

    /** Builds the bulk-create request from (bulkId, json) pairs. Static + package-private = test seam. */
    static BulkBuilder assembleBulkCreate(ScimRequestBuilder builder, List<String[]> idJsonPairs) {
        BulkBuilder bulk = builder.bulk();
        for (var p : idJsonPairs) { // p[0] = bulkId (kcUserId), p[1] = SCIM user JSON
            bulk = bulk.bulkRequestOperation("/Users")
                .bulkId(p[0])
                .method(HttpMethod.POST)
                .data(p[1])
                .next();
        }
        return bulk;
    }

    /**
     * Batched user create via SCIM /Bulk. Re-fetches each user by id in THIS worker
     * session (attributes populated post-commit), applies the adapter, skips
     * scim-skip + already-mapped users, POSTs the rest as one bulk request, then
     * persists a mapping for each operation the server accepted. Per-op failures are
     * logged, not fatal to the batch. {@code failOnErrors} is intentionally unset
     * (server attempts every op). Runs inside the lane's worker transaction.
     */
    public BulkResult bulkCreateUsers(List<BulkUserOp> ops) {
        if (ops.isEmpty()) return BulkResult.EMPTY;
        var realm = session.getContext().getRealm();

        var pending = new ArrayList<UserAdapter>(ops.size());
        int skipped = 0;
        for (var op : ops) {
            var user = session.users().getUserById(realm, op.kcUserId());
            if (user == null) { skipped++; continue; }
            var adapter = new UserAdapter(session, model.getId());
            adapter.apply(user);
            if (adapter.skip) { skipped++; continue; }
            if (!adapter.query("findById", adapter.getId()).getResultList().isEmpty()) { skipped++; continue; }
            pending.add(adapter);
        }
        if (pending.isEmpty()) return new BulkResult(0, skipped, 0);

        var pairs = new ArrayList<String[]>(pending.size());
        for (var a : pending) {
            pairs.add(new String[]{ a.getId(), a.toSCIM(false).toString() });
        }

        var retry = registry.retry("bulkCreate");
        ServerResponse<BulkResponse> response;
        try (var span = TRACING.startSpan("scim.bulkCreate", "User", scimApplicationBaseUrl)) {
            response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() ->
                assembleBulkCreate(scimRequestBuilder, pairs).sendRequest(false)));
            span.setHttpStatus(response.getHttpStatus());
        }

        if (!response.isSuccess()) {
            LOGGER.warnf("SCIM /Bulk request failed: HTTP %d %s — %d user create(s) lost this round",
                response.getHttpStatus(), response.getResponseBody(), pending.size());
            return new BulkResult(0, skipped, pending.size());
        }

        var bulk = response.getResource();
        int created = 0, failed = 0;
        for (var adapter : pending) {
            var maybe = bulk.getByBulkId(adapter.getId());
            if (maybe.isEmpty()) { failed++; LOGGER.warnf("No bulk response op for user %s", adapter.getId()); continue; }
            var rop = maybe.get();
            Integer status = rop.getStatus();
            var extId = rop.getResourceId();
            if (status != null && status >= 200 && status < 300 && extId.isPresent()) {
                adapter.setExternalId(extId.get());
                adapter.saveMapping();
                created++;
            } else {
                failed++;
                LOGGER.warnf("Bulk create failed for user %s: status=%s", adapter.getId(), String.valueOf(status));
            }
        }
        ScimClientMetrics.CREATE_COUNT.add(created);
        return new BulkResult(created, skipped, failed);
    }

    public void close() {
        scimRequestBuilder.close();
    }
}
