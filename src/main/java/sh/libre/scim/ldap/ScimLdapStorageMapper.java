package sh.libre.scim.ldap;

import org.jboss.logging.Logger;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ldap.LDAPStorageProvider;
import org.keycloak.storage.ldap.idm.model.LDAPObject;
import org.keycloak.storage.ldap.idm.query.internal.LDAPQuery;
import org.keycloak.storage.ldap.mappers.LDAPStorageMapper;
import org.keycloak.storage.user.SynchronizationResult;

import javax.naming.AuthenticationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.keycloak.storage.federated.UserFederatedStorageProvider;

import sh.libre.scim.core.GroupAdapter;
import sh.libre.scim.core.ScimDispatcher;
import sh.libre.scim.core.UserAdapter;

public class ScimLdapStorageMapper implements LDAPStorageMapper {

    public static final String LAST_SEEN_ATTRIBUTE = "ldap-federation-last-seen";

    public static final String PROPAGATED_GROUPS_ATTR_PREFIX = "scim-propagated-groups-";

    private static final Logger LOGGER = Logger.getLogger(ScimLdapStorageMapper.class);

    private final ScimDispatcher dispatcher;

    public ScimLdapStorageMapper(ScimDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void onImportUserFromLDAP(LDAPObject ldapUser, UserModel user, RealmModel realm, boolean isCreate) {
        LOGGER.debugf("onImportUserFromLDAP user=%s isCreate=%s", user.getUsername(), isCreate);
        user.setSingleAttribute(LAST_SEEN_ATTRIBUTE, Instant.now().toString());

        // Async dispatch: capture user id by value (the UserModel reference
        // is bound to the import-thread session), let workers re-fetch in
        // their own session. This pulls the SCIM HTTP cost off the
        // user-import thread, which was otherwise serializing the entire
        // LDAP federation sync at the rate of one SCIM POST per user
        // (~43ms each in measurement). With 8 workers, throughput on
        // 10k-user syncs goes from ~22 users/sec to ~150-180 users/sec.
        String userId = user.getId();
        if (isCreate) {
            dispatcher.dispatchUserCreate(user);
        } else {
            dispatcher.runAsync(ScimDispatcher.SCOPE_USER, (client, workerSession) -> {
                var u = workerSession.users().getUserById(workerSession.getContext().getRealm(), userId);
                if (u != null) client.replace(UserAdapter::new, u);
            });
        }

        // Reconcile the user's group memberships. LDAP-driven changes fire no
        // GROUP_MEMBERSHIP event, so this hook is the only signal.
        //   - removals: groups we last propagated but the user has left -> REMOVE PATCH
        //   - additions: groups the user is in that we have NOT propagated -> ADD PATCH
        // Both are computed as a delta against the per-component propagated-group set
        // in federated storage, so a steady-state re-import (the common case — every
        // full sync re-fires this hook for unchanged users) sends zero SCIM PATCHes.
        // getGroupsStream() reads the user's OWN groups; it does NOT enumerate any
        // group's members, so it cannot retrigger the federated re-import loop.
        dispatcher.runAsync(ScimDispatcher.SCOPE_GROUP, (client, workerSession) -> {
            // Only the single-member delta PATCH path is loop-safe. With
            // group-patchOp=false, both add and remove fall back to a full-group
            // `replace` that enumerates the federated group's members and
            // re-imports them (an unbounded re-import loop). So federated
            // group-membership propagation requires group-patchOp=true (the
            // default); on the non-default path we skip it (see docs).
            if (!client.isGroupMembershipDeltaEnabled()) {
                return;
            }
            var workerRealm = workerSession.getContext().getRealm();
            var u = workerSession.users().getUserById(workerRealm, userId);
            if (u == null) return;

            Set<String> current = u.getGroupsStream()
                    .map(GroupModel::getId)
                    .collect(Collectors.toSet());

            // Bookkeeping lives in federated storage (a JPA-backed local store for
            // federated users), NOT in the user's LDAP-backed attributes — the latter
            // are read-only under editMode=READ_ONLY and throw on write from this
            // post-commit worker. getGroupsStream() (materialized only on this
            // re-fetched worker user) stays the source of `current`.
            var fed = workerSession.getProvider(UserFederatedStorageProvider.class);
            String attr = PROPAGATED_GROUPS_ATTR_PREFIX + client.getComponentId();
            List<String> storedList = fed.getAttributes(workerRealm, userId).get(attr);
            Set<String> stored = storedList == null
                    ? new HashSet<>() : new HashSet<>(storedList);

            // Removals — groups we propagated that the user has left. Keep any whose
            // REMOVE did not apply, so the next import retries.
            Set<String> kept = new HashSet<>();
            stored.stream().filter(gid -> !current.contains(gid)).forEach(gid -> {
                if (!client.patchGroupMembership(GroupAdapter::new, gid, userId, false)) {
                    kept.add(gid);
                }
            });

            // Additions — only groups not already propagated (delta), so steady-state
            // re-imports send no redundant PATCHes. A failed/skipped add is NOT recorded
            // here, so it retries on the next import (the lazy-import-lag self-heal).
            Set<String> addedOk = new HashSet<>();
            current.stream().filter(gid -> !stored.contains(gid)).forEach(gid -> {
                if (client.ensureGroupMembership(GroupAdapter::new, gid, userId)) {
                    addedOk.add(gid);
                }
            });

            // Record what SCIM now reflects: already-propagated current groups, plus
            // newly-added successes, plus failed removals (still in SCIM, to retry).
            Set<String> next = new HashSet<>(addedOk);
            current.stream().filter(stored::contains).forEach(next::add);
            next.addAll(kept);
            if (next.isEmpty()) {
                fed.removeAttribute(workerRealm, userId, attr);
            } else {
                fed.setAttribute(workerRealm, userId, attr, new ArrayList<>(next));
            }
        });
    }

    @Override
    public void onRegisterUserToLDAP(LDAPObject ldapUser, UserModel localUser, RealmModel realm) {
        // no-op: Keycloak->LDAP direction is not our concern
    }

    @Override
    public UserModel proxy(LDAPObject ldapUser, UserModel delegate, RealmModel realm) {
        return delegate;
    }

    @Override
    public void beforeLDAPQuery(LDAPQuery query) {
        // no-op
    }

    @Override
    public LDAPStorageProvider getLdapProvider() {
        return null;
    }

    @Override
    public boolean onAuthenticationFailure(LDAPObject ldapUser, UserModel user, AuthenticationException ldapException, RealmModel realm) {
        return false;
    }

    @Override
    public List<UserModel> getGroupMembers(RealmModel realm, GroupModel group, int firstResult, int maxResults) {
        // Must return an empty list, not null. Keycloak's
        // LDAPStorageProvider.getGroupMembersStream iterates over every
        // attached mapper's getGroupMembers and combines the results;
        // a null return value NPEs the stream pipeline.
        return List.of();
    }

    @Override
    public List<UserModel> getRoleMembers(RealmModel realm, RoleModel role, int firstResult, int maxResults) {
        // Same constraint as getGroupMembers — return empty, not null.
        return List.of();
    }

    @Override
    public SynchronizationResult syncDataFromFederationProviderToKeycloak(RealmModel realm) {
        return new SynchronizationResult();
    }

    @Override
    public SynchronizationResult syncDataFromKeycloakToFederationProvider(RealmModel realm) {
        return new SynchronizationResult();
    }

    @Override
    public Set<String> getUserAttributes() {
        return Set.of();
    }

    @Override
    public Set<String> mandatoryAttributeNames() {
        return Set.of();
    }

    @Override
    public void close() {
        // Releases the dispatcher's cached ScimClients. Important: at scale
        // (10k+ user import) the dispatcher accumulates one client per SCIM
        // provider component, each holding an Apache HttpClient pool.
        dispatcher.close();
    }
}
