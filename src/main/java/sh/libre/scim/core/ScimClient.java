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
import de.captaingoldfish.scim.sdk.common.constants.enums.PatchOp;
import de.captaingoldfish.scim.sdk.common.exceptions.ResponseException;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.ResourceNode;
import de.captaingoldfish.scim.sdk.common.resources.User;
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

import sh.libre.scim.core.exceptions.InconsistentScimMappingException;
import sh.libre.scim.core.exceptions.InvalidResponseFromScimEndpointException;
import sh.libre.scim.core.exceptions.ScimPropagationException;
import sh.libre.scim.jpa.ScimProvisionLock;
import sh.libre.scim.jpa.ScimResource;
import sh.libre.scim.storage.ScimStorageProviderFactory;


public class ScimClient {
    private static final ScimTracingBridge TRACING = ScimTracingBridge.create();
    private static final String GROUP_PATCH_OP_KEY = "group-patchOp";
    private static final String USER_PATCH_OP_KEY = "user-patchOp";
    private static final String DELETE_MODE_KEY = ScimStorageProviderFactory.DELETE_MODE;

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

    /**
     * Classifies an exception that escaped a CRUD retry supplier once the retry
     * budget was exhausted. An already-classified {@link ScimPropagationException}
     * (e.g. the non-2xx {@link InvalidResponseFromScimEndpointException}) passes
     * through; anything else is a transport failure (a {@link ProcessingException},
     * the SDK's {@link IORuntimeException}, or a wrapped {@link ResponseException})
     * and becomes a transient endpoint error. Package-private static as a test seam.
     */
    static ScimPropagationException classifyCrudFailure(String op, RuntimeException e) {
        if (e instanceof ScimPropagationException classified) {
            return classified;
        }
        return InvalidResponseFromScimEndpointException.transport(op, e);
    }

    /**
     * True when deprovisioning should set active:false instead of DELETE.
     * Only Users can deactivate (RFC 7643 Groups have no {@code active}
     * attribute); the default mode is {@code delete}. Package-private for tests.
     */
    static boolean shouldDeactivate(ComponentModel model, String adapterType) {
        return "deactivate".equals(model.get(DELETE_MODE_KEY, "delete")) && "User".equals(adapterType);
    }

    /** Whether the sole findById row for this KC id is a retained deactivation tombstone. */
    private static boolean isDeactivatedTombstone(List<ScimResource> rows) {
        return !rows.isEmpty() && rows.get(0).getDeactivatedAt() != null;
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
        if (adapter.skip) {
            return;
        }
        ScimClientMetrics.APPLY_MODEL_NANOS.add(System.nanoTime() - t0);
        // A mapping from a prior import or provision means there is nothing to
        // create. Unless it's a deactivation tombstone: then the user has come
        // back, so replace() pushes active from isEnabled() to the same remote
        // id and clears the flag on success.
        var existing = adapter.query("findById", adapter.getId()).getResultList();
        if (!existing.isEmpty()) {
            if (isDeactivatedTombstone(existing)) {
                LOGGER.infof("Create for deactivated mapping %s: reactivating via replace", adapter.getId());
                this.replace(factory, kcModel);
            }
            return;
        }
        handleCreateResponse(adapter, postResource(adapter));
    }

    // POSTs the resource to the SCIM target and returns the raw response. Shared
    // by create() (which then persists the mapping in the caller's transaction)
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
            ServerResponse<S> response;
            try {
                response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                    try {
                        return scimRequestBuilder
                        .create(adapter.getResourceClass(), ("/" + adapter.getSCIMEndpoint()).formatted())
                        .setResource(adapter.toSCIM(false))
                        .sendRequest();
                    } catch (ResponseException e) {
                        throw new RuntimeException(e);
                    }
                }));
            } catch (RuntimeException e) {
                span.recordError(e);
                throw classifyCrudFailure("create " + adapter.getId(), e);
            }
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
     * After a create stores a live mapping, drop any tombstone rows
     * (DEACTIVATED_AT set) with the same external id: the returning user's old
     * mapping under a former KC id. If those stuck around and the component
     * were later flipped back to delete-mode=delete, the reconciler could
     * DELETE a remote id that a live mapping still points to, so this runs on
     * every create-path save no matter the current mode. Tombstones whose
     * external id never comes back are kept.
     */
    private void purgeDeactivatedTombstones(Adapter<?, ?> adapter) {
        if (adapter.getExternalId() == null) {
            return;
        }
        int purged = getEM().createNamedQuery("deleteDeactivatedByExternalId")
            .setParameter("realmId", getRealmId())
            .setParameter("componentId", model.getId())
            .setParameter("type", adapter.getType())
            .setParameter("id", adapter.getExternalId())
            .executeUpdate();
        if (purged > 0) {
            LOGGER.infof("Purged %d deactivated mapping(s) for resurrected %s %s",
                purged, adapter.getType(), adapter.getExternalId());
        }
    }

    /**
     * Persists the SCIM mapping from a create response, but only when the POST
     * succeeded. A rejected create (e.g. the target returns 400 for a user with
     * no email) carries no parsed resource: {@code response.getResource()} is
     * null, so applying it NPEs, and a mapping persisted here would be a phantom
     * record for a resource the target never created. So a rejected create throws
     * instead, leaving the user unmapped for a later sync to retry.
     * Also purges any DEACTIVATED_AT tombstone with this resource's external
     * id; see {@link #purgeDeactivatedTombstones}.
     *
     * @return true if the mapping was applied and saved.
     * @throws InvalidResponseFromScimEndpointException if the POST was rejected.
     */
    // package-private for tests
    <S extends ResourceNode> boolean handleCreateResponse(Adapter<?, S> adapter, ServerResponse<S> response) {
        if (!response.isSuccess()) {
            throw new InvalidResponseFromScimEndpointException(
                response.getHttpStatus(),
                "create " + adapter.getId() + ": " + response.getResponseBody());
        }

        long t0 = System.nanoTime();
        adapter.apply(response.getResource());
        long t1 = System.nanoTime();
        ScimClientMetrics.APPLY_RESPONSE_NANOS.add(t1 - t0);
        adapter.saveMapping();
        long t2 = System.nanoTime();
        ScimClientMetrics.SAVE_MAPPING_NANOS.add(t2 - t1);
        purgeDeactivatedTombstones(adapter);
        ScimClientMetrics.CREATE_COUNT.increment();
        warnOnActiveDisagreement(adapter, response.getResource());
        return true;
    }

    /**
     * Whether a create response contradicts the active state we pushed. A server
     * holding its own suspension state can accept a user as active and still
     * return {@code active: false}. An absent field counts as agreement, since
     * not every endpoint echoes it. Package-private static as a test seam.
     */
    static boolean activeStateDisagrees(Boolean pushed, User returned) {
        if (pushed == null) {
            return false;
        }
        return returned.isActive().map(a -> !a.equals(pushed)).orElse(false);
    }

    /**
     * Logs when the endpoint disagrees with the active state we pushed. We keep
     * our own value and store no active state locally, so without this the
     * difference is invisible: Keycloak shows an enabled user that the remote
     * treats as suspended.
     */
    private void warnOnActiveDisagreement(Adapter<?, ?> adapter, ResourceNode created) {
        if (!(created instanceof User createdUser) || !(adapter instanceof UserAdapter userAdapter)) {
            return;
        }
        if (activeStateDisagrees(userAdapter.getActive(), createdUser)) {
            LOGGER.warnf("SCIM user %s was created with active=%s but the endpoint returned active=%s; "
                + "the remote is holding its own suspension state",
                adapter.getId(), userAdapter.getActive(), createdUser.isActive().orElse(null));
        }
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
                ServerResponse<S> response;
                try {
                    response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                        try {
                            LOGGER.info(adapter.getType());
                            if ((adapter.getType() == "Group" && this.model.get(GROUP_PATCH_OP_KEY, false))
                                 || (adapter.getType() == "User" && this.model.get(USER_PATCH_OP_KEY, false))) {
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
                } catch (RuntimeException e) {
                    span.recordError(e);
                    throw classifyCrudFailure("replace " + adapter.getId(), e);
                }
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
                        throw new InvalidResponseFromScimEndpointException(
                            response.getHttpStatus(),
                            "replace " + adapter.getId() + ": " + response.getResponseBody());
                    }
                }
                if (resource.getDeactivatedAt() != null) {
                    // A successful replace means the user is back (re-import or
                    // an explicit admin action). Sync-refresh skips flagged
                    // rows, so it never lands here.
                    resource.setDeactivatedAt(null);
                    LOGGER.infof("Cleared deactivation flag for %s %s", adapter.getType(), adapter.getId());
                }
                span.setHttpStatus(response.getHttpStatus());
            } catch (NoResultException e) {
                span.recordError(e);
                LOGGER.warnf("failed to replace resource %s, scim mapping not found", adapter.getId());
                throw new InconsistentScimMappingException(
                    "no SCIM mapping for " + adapter.getType() + " " + adapter.getId() + " on replace", e);
            } catch (ScimPropagationException e) {
                // Already classified (e.g. the final-failure throw above) — must
                // not be caught + downgraded by the generic Exception handler below.
                span.recordError(e);
                throw e;
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

        if (shouldDeactivate(model, adapter.getType())) {
            deactivateUser(adapter);
            return;
        }

        try (var span = TRACING.startSpan("scim.delete", adapter.getType(), scimApplicationBaseUrl)) {
            try {
                var resource = adapter.query("findById", adapter.getId()).getSingleResult();
                adapter.apply(resource);

                var retry = registry.retry("delete");

                ServerResponse<S> response;
                try {
                    response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                        try {
                            return scimRequestBuilder.delete(genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId()),
                                                                        adapter.getResourceClass())
                                                     .sendRequest();
                        } catch (ResponseException e) {
                            throw new RuntimeException(e);
                        }
                    }));
                } catch (RuntimeException e) {
                    span.recordError(e);
                    throw classifyCrudFailure("delete " + id, e);
                }

                span.setHttpStatus(response.getHttpStatus());
                if (!response.isSuccess()) {
                    throw new InvalidResponseFromScimEndpointException(
                        response.getHttpStatus(),
                        "delete " + id + ": " + response.getResponseBody());
                }

                getEM().remove(resource);

            } catch (NoResultException e) {
                span.recordError(e);
                LOGGER.warnf("Failed to delete resource %s, scim mapping not found", id);
            }
        }
    }

    /**
     * delete-mode=deactivate: set {@code active: false} on the remote user
     * instead of DELETE, and keep the mapping row (flagged with DEACTIVATED_AT)
     * so a returning user reactivates under the same remote id. With
     * user-patchOp on this is a single PATCH; otherwise GET plus a full PUT
     * with the flag flipped, skipping the PUT if the user is already inactive.
     *
     * <p>A 404 anywhere means the remote user is already gone, which is the
     * goal state: flag the mapping and stop. Unlike replace(), never re-create
     * on 404; re-creating a user in order to deactivate them is backwards. On
     * any other failure the classified exception propagates and DEACTIVATED_AT
     * stays null, so the next reconciler pass retries. Once flagged, repeat
     * calls return without HTTP.
     */
    private void deactivateUser(Adapter<?, ?> adapter) {
        try (var span = TRACING.startSpan("scim.deactivate", adapter.getType(), scimApplicationBaseUrl)) {
            try {
                ScimResource mapping = adapter.query("findById", adapter.getId()).getSingleResult();
                if (mapping.getDeactivatedAt() != null) {
                    LOGGER.debugf("User %s already deactivated, skipping", adapter.getId());
                    return;
                }
                adapter.apply(mapping);
                String url = genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId());
                var retry = registry.retry("deactivate");

                ServerResponse<User> response;
                try {
                    if (this.model.get(USER_PATCH_OP_KEY, false)) {
                        response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                            try {
                                var patch = scimRequestBuilder.patch(url, User.class);
                                patch.addOperation()
                                    .path("active")
                                    .op(PatchOp.REPLACE)
                                    .value("false")
                                    .build();
                                return patch.sendRequest();
                            } catch (ResponseException e) {
                                throw new RuntimeException(e);
                            }
                        }));
                    } else {
                        ServerResponse<User> getResponse = auth.sendWithAuthRefresh(
                            () -> retry.executeSupplier(() -> {
                                try {
                                    return scimRequestBuilder.get(url, User.class).sendRequest();
                                } catch (ResponseException e) {
                                    throw new RuntimeException(e);
                                }
                            }));
                        span.setHttpStatus(getResponse.getHttpStatus());
                        if (getResponse.getHttpStatus() == 404) {
                            markDeactivated(mapping, adapter.getId(), "remote already gone (404)");
                            return;
                        }
                        if (!getResponse.isSuccess()) {
                            throw new InvalidResponseFromScimEndpointException(
                                getResponse.getHttpStatus(),
                                "deactivate (get) " + adapter.getId() + ": " + getResponse.getResponseBody());
                        }
                        User remote = getResponse.getResource();
                        if (!remote.isActive().orElse(true)) {
                            markDeactivated(mapping, adapter.getId(), "remote already inactive");
                            return;
                        }
                        remote.setActive(false);
                        response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                            try {
                                return scimRequestBuilder
                                    .update(url, User.class)
                                    .setResource(remote)
                                    .sendRequest();
                            } catch (ResponseException e) {
                                throw new RuntimeException(e);
                            }
                        }));
                    }
                } catch (RuntimeException e) {
                    span.recordError(e);
                    throw classifyCrudFailure("deactivate " + adapter.getId(), e);
                }

                span.setHttpStatus(response.getHttpStatus());
                if (response.getHttpStatus() == 404) {
                    markDeactivated(mapping, adapter.getId(), "remote already gone (404)");
                    return;
                }
                if (!response.isSuccess()) {
                    throw new InvalidResponseFromScimEndpointException(
                        response.getHttpStatus(),
                        "deactivate " + adapter.getId() + ": " + response.getResponseBody());
                }
                markDeactivated(mapping, adapter.getId(), "set active:false");
            } catch (NoResultException e) {
                span.recordError(e);
                LOGGER.warnf("Failed to deactivate resource %s, scim mapping not found", adapter.getId());
            }
        }
    }

    /** Flags the (managed) mapping row; flushes with the surrounding transaction. */
    private void markDeactivated(ScimResource mapping, String kcId, String reason) {
        mapping.setDeactivatedAt(System.currentTimeMillis());
        LOGGER.infof("SCIM user %s deactivated (%s); mapping retained", kcId, reason);
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
     *
     * @return {@code true} if applied; {@code false} if it couldn't be applied this
     *     import because the group or user mapping isn't committed yet (lazy-import
     *     lag). {@code false} is the self-heal signal — the caller retries next
     *     import — so hard failures throw rather than return it.
     * @throws ScimPropagationException on a hard failure (non-2xx after retries, or
     *     a transport-level failure, both transient).
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
                ServerResponse<Group> response;
                try {
                    response = auth.sendWithAuthRefresh(
                        () -> retry.executeSupplier(() -> {
                            try {
                                return adapter.toMembershipPatchBuilder(
                                        scimRequestBuilder, url, userExternalId, isAdd)
                                    .sendRequest();
                            } catch (ResponseException e) {
                                throw new RuntimeException(e);
                            }
                        }));
                } catch (RuntimeException e) {
                    // Scopes ONLY the send, so the non-2xx throw below (a sibling
                    // statement) is not caught and re-classified here, and the
                    // lazy-lag NoResultException from the earlier getSingleResult
                    // calls falls through to the outer catch. Don't widen this.
                    span.recordError(e);
                    throw classifyCrudFailure("patch membership " + groupId + "/" + userId, e);
                }

                span.setHttpStatus(response.getHttpStatus());
                if (!response.isSuccess()) {
                    throw new InvalidResponseFromScimEndpointException(
                        response.getHttpStatus(),
                        "PATCH membership group " + groupId + " / user " + userId
                            + ": " + response.getResponseBody());
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
     *
     * @return {@code true} when the membership is now propagated; {@code false}
     *     when it could not be propagated this import (the local group is missing,
     *     or the add hit the lazy-import lag) — the caller should retry on a later
     *     import.
     * @throws ScimPropagationException on a hard failure propagated from
     *     {@link #patchGroupMembership} (non-2xx after retries, or transport-level).
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
        SyncErrorPolicy policy = SyncErrorPolicy.fromConfig(this.model.get("sync-on-error"));
        try (var ignored = TRACING.startSpan("scim.sync.refresh", getAdapter(factory).getType(), scimApplicationBaseUrl)) {
            // Use a plain for-loop (not forEach) so return can stop the whole run,
            // not just skip a single lambda invocation.
            for (var resource : getAdapter(factory).getResourceStream().toList()) {
                var adapter = getAdapter(factory);
                try {
                    adapter.apply(resource);
                    LOGGER.infof("Reconciling local resource %s", adapter.getId());
                    if (!adapter.skipRefresh()) {
                        var mapping = adapter.getMapping();
                        if (mapping != null && mapping.getDeactivatedAt() != null) {
                            // Don't re-push a deactivated user just because a
                            // stale local copy still exists; that would fight
                            // the reconciler, which would deactivate it again
                            // next pass. Reactivation needs a re-import or an
                            // explicit admin action.
                            LOGGER.debugf("Skipping refresh for deactivated mapping %s", adapter.getId());
                        } else {
                            if (mapping == null) {
                                LOGGER.info("Creating it");
                                this.create(factory, resource);
                            } else {
                                LOGGER.info("Replacing it");
                                this.replace(factory, resource);
                            }
                            syncRes.increaseUpdated();
                        }
                    }
                } catch (ScimPropagationException e) {
                    LOGGER.warnf(e, "SCIM sync: resource %s failed (%s)",
                        adapter.getId(), e.getClass().getSimpleName());
                    syncRes.increaseFailed();
                    if (policy.shouldStopRun(e)) {
                        LOGGER.errorf("SCIM sync aborted after %s on resource %s",
                            e.getClass().getSimpleName(), adapter.getId());
                        return; // stop the whole run
                    }
                    // else continue
                }
            }
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void importResources(
            AdapterFactory<M, S, A> factory, SynchronizationResult syncRes) {
        LOGGER.info("Import");
        SyncErrorPolicy policy = SyncErrorPolicy.fromConfig(this.model.get("sync-on-error"));
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
                            } else if (mapping.getDeactivatedAt() != null) {
                                // Deactivation tombstone: the local user is
                                // supposed to be absent. Keep the row so a
                                // returning user gets the same remote id.
                                LOGGER.debugf("Keeping deactivated mapping %s (tombstone)", mapping.getId());
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
                            if (shouldDeactivate(model, adapter.getType())
                                    && resource instanceof User remoteUser
                                    && !remoteUser.isActive().orElse(true)) {
                                // Users we deactivated still show up in the
                                // consumer's /Users list, and their local absence
                                // is expected. DELETE_REMOTE would hard-delete our
                                // own tombstone and CREATE_LOCAL would resurrect a
                                // deprovisioned user, so skip both.
                                LOGGER.debugf("Skipping inactive remote user %s under delete-mode=deactivate",
                                    resource.getId().orElse("?"));
                                continue;
                            }
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
                    } catch (ScimPropagationException e) {
                        // More-specific than the generic handler below; must come
                        // first or the classified throw would be swallowed there.
                        LOGGER.warnf(e, "SCIM sync: resource %s failed (%s)",
                            adapter.getId(), e.getClass().getSimpleName());
                        syncRes.increaseFailed();
                        if (policy.shouldStopRun(e)) {
                            LOGGER.errorf("SCIM sync aborted after %s on resource %s",
                                e.getClass().getSimpleName(), adapter.getId());
                            return; // stop the whole run
                        }
                        // else continue
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
        int skipped = 0, failed = 0;
        for (var op : ops) {
            var user = session.users().getUserById(realm, op.kcUserId());
            if (user == null) { skipped++; continue; }
            var adapter = new UserAdapter(session, model.getId());
            adapter.apply(user);
            if (adapter.skip) { skipped++; continue; }
            var existing = adapter.query("findById", adapter.getId()).getResultList();
            if (!existing.isEmpty()) {
                if (isDeactivatedTombstone(existing)) {
                    // Today's callers only feed the bulk lane freshly created
                    // users (new KC ids, no mapping), so this shouldn't be
                    // reachable from real LDAP flows. If a flagged mapping does
                    // show up (a future caller, or an overlapping sync),
                    // reactivate it with an individual replace rather than
                    // silently skipping.
                    try {
                        this.replace(UserAdapter::new, user);
                    } catch (ScimPropagationException e) {
                        LOGGER.warnf(e, "Bulk-lane reactivation replace failed for user %s", op.kcUserId());
                        failed++;
                        continue;
                    }
                    // A reactivation is not a bulk create, so count it as skipped.
                }
                skipped++;
                continue;
            }
            pending.add(adapter);
        }
        if (pending.isEmpty()) return new BulkResult(0, skipped, failed);

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
            return new BulkResult(0, skipped, failed + pending.size());
        }

        var bulk = response.getResource();
        if (bulk == null) {
            // Conformant servers return a body on a 2xx /Bulk; guard the degenerate
            // empty/unparseable-body case so it doesn't NPE the whole batch.
            LOGGER.warnf("SCIM /Bulk returned HTTP %d with no parseable body — %d create(s) lost this round",
                response.getHttpStatus(), pending.size());
            return new BulkResult(0, skipped, failed + pending.size());
        }
        int created = 0;
        for (var adapter : pending) {
            var maybe = bulk.getByBulkId(adapter.getId());
            if (maybe.isEmpty()) { failed++; LOGGER.warnf("No bulk response op for user %s", adapter.getId()); continue; }
            var rop = maybe.get();
            Integer status = rop.getStatus();
            var extId = rop.getResourceId();
            if (status != null && status >= 200 && status < 300 && extId.isPresent()) {
                adapter.setExternalId(extId.get());
                adapter.saveMapping();
                purgeDeactivatedTombstones(adapter);
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
