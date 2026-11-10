package sh.libre.scim.event;

import java.util.HashMap;
import java.util.regex.*;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.RoleUtils;

import sh.libre.scim.core.GroupAdapter;
import sh.libre.scim.core.ScimDispatcher;
import sh.libre.scim.core.UserAdapter;

public class ScimEventListenerProvider implements EventListenerProvider {
    final Logger LOGGER = Logger.getLogger(ScimEventListenerProvider.class);
    ScimDispatcher dispatcher;
    KeycloakSession session;
    HashMap<ResourceType, Pattern> patterns = new HashMap<ResourceType, Pattern>();

    public ScimEventListenerProvider(KeycloakSession session) {
        this.session = session;
        dispatcher = new ScimDispatcher(session);
        patterns.put(ResourceType.USER, Pattern.compile("users/(.+)"));
        // Only three paths: "groups" (a move to the top level), "groups/{id}",
        // and "groups/{id}/children" (a move under {id}, or a subgroup create).
        // With CREATE, "groups/{id}/children" names the parent. The code
        // creates the parent, not the new subgroup. This is a known gap. It
        // is tracked separately and not changed here.
        patterns.put(ResourceType.GROUP, Pattern.compile("^groups(?:/([^/]+)(/children)?)?$"));
        patterns.put(ResourceType.GROUP_MEMBERSHIP, Pattern.compile("users/(.+)/groups/(.+)"));
        patterns.put(ResourceType.REALM_ROLE_MAPPING, Pattern.compile("^(.+)/(.+)/role-mappings"));
        patterns.put(ResourceType.CLIENT_ROLE_MAPPING, Pattern.compile("^(.+)/(.+)/role-mappings"));
    }

    @Override
    public void close() {
        // Releases the dispatcher's cached ScimClients (Apache HttpClient
        // pools). Without this, every Keycloak session would leak whatever
        // SCIM clients its dispatcher accumulated.
        dispatcher.close();
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.VERIFY_EMAIL) {
            var user = getUser(event.getUserId());
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.create(UserAdapter::new, user));
        }
        if (event.getType() == EventType.UPDATE_EMAIL || event.getType() == EventType.UPDATE_PROFILE) {
            var user = getUser(event.getUserId());
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter::new, user));
        }
        if (event.getType() == EventType.DELETE_ACCOUNT) {
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.delete(UserAdapter::new, event.getUserId()));
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // A change to a role, or a deleted client, can change the roles of
        // any number of users. The path does not matter.
        var type = event.getResourceType();
        if (type == ResourceType.REALM_ROLE || type == ResourceType.CLIENT_ROLE
                || (type == ResourceType.CLIENT && event.getOperationType() == OperationType.DELETE)) {
            dispatcher.requestRoleRefresh();
            return;
        }
        var pattern = patterns.get(type);
        if (pattern == null) {
            return;
        }
        var matcher = pattern.matcher(event.getResourcePath());
        if (!matcher.find()) {
            return;
        }
        if (type == ResourceType.USER) {
            var userId = matcher.group(1);
            LOGGER.infof("%s %s", userId, event.getOperationType());
            if (event.getOperationType() == OperationType.CREATE) {
                var user = getUser(userId);
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.create(UserAdapter::new, user));
                user.getGroupsStream().forEach(group -> {
                    dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.replace(GroupAdapter::new, group));
                });
            }
            if (event.getOperationType() == OperationType.UPDATE) {
                var user = getUser(userId);
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter::new, user));
            }
            if (event.getOperationType() == OperationType.DELETE) {
                // Events fire pre-commit, but the resource already flushed the
                // delete, so getUser(userId) returns null here. Skip the lookup:
                // if the user was ever synced its ScimResource mapping still exists
                // and ScimClient.delete propagates; otherwise delete short-circuits
                // on NoResultException. The mapping's existence is the signal that
                // the user was propagated, so the emailVerified gate is moot here.
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.delete(UserAdapter::new, userId));
            }
        }
        if (type == ResourceType.GROUP) {
            var groupId = matcher.group(1);
            boolean children = matcher.group(2) != null;
            var op = event.getOperationType();
            LOGGER.infof("group %s %s", groupId, op);
            if (op == OperationType.UPDATE && (groupId == null || children)) {
                // A move. The path names the new parent, or no group, and a
                // move does not change the moved group's SCIM body. Only the
                // members' roles can change.
                dispatcher.requestRoleRefresh();
                return;
            }
            if (groupId == null) {
                return;
            }
            if (op == OperationType.CREATE) {
                var group = getGroup(groupId);
                dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.create(GroupAdapter::new, group));
            }
            if (op == OperationType.UPDATE) {
                var group = getGroup(groupId);
                dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.replace(GroupAdapter::new, group));
            }
            if (op == OperationType.DELETE) {
                dispatcher.run(ScimDispatcher.SCOPE_GROUP,
                        client -> client.delete(GroupAdapter::new, groupId));
                dispatcher.requestRoleRefresh();
            }
        }
        if (type == ResourceType.GROUP_MEMBERSHIP) {
            var userId = matcher.group(1);
            var groupId = matcher.group(2);
            LOGGER.infof("%s %s from %s", event.getOperationType(), userId, groupId);
            boolean isAdd = event.getOperationType() == OperationType.CREATE;
            dispatcher.run(ScimDispatcher.SCOPE_GROUP,
                    client -> client.patchGroupMembership(GroupAdapter::new, groupId, userId, isAdd));
            // The user's SCIM resource has no groups field, so membership only
            // affects it via roles — and roles are pushed only for scim-marked
            // roles. So a membership change needs a user replace only when the
            // group confers one; otherwise the replace re-sends an identical
            // resource. If the group can't be resolved, replace to be safe.
            var group = getGroup(groupId);
            if (group == null || groupConfersScimRole(group)) {
                var user = getUser(userId);
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter::new, user));
            }
        }
        if (type == ResourceType.REALM_ROLE_MAPPING
                || type == ResourceType.CLIENT_ROLE_MAPPING) {
            var kind = matcher.group(1);
            var id = matcher.group(2);
            LOGGER.infof("%s %s %s roles", event.getOperationType(), kind, id);
            if ("users".equals(kind)) {
                var user = getUser(id);
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter::new, user));
            } else if ("groups".equals(kind)) {
                // Was a PUT for every member, inside this request. A role
                // refresh after commit reaches members of subgroups too, and
                // sends only users whose roles changed.
                dispatcher.requestRoleRefresh();
            }
        }
    }

    private UserModel getUser(String id) {
        return session.users().getUserById(session.getContext().getRealm(), id);
    }

    private GroupModel getGroup(String id) {
        return session.groups().getGroupById(session.getContext().getRealm(), id);
    }

    /**
     * Whether the group, or any parent group, has a role mapping whose
     * composite expansion includes a role marked scim="true". This is the
     * same set of roles UserAdapter sends. If false, a membership change on
     * this group cannot change any member's SCIM roles.
     */
    // package-private for tests
    boolean groupConfersScimRole(GroupModel group) {
        for (var g = group; g != null; g = g.getParent()) {
            boolean marked = RoleUtils.expandCompositeRolesStream(g.getRoleMappingsStream())
                    .anyMatch(r -> "true".equals(r.getFirstAttribute("scim")));
            if (marked) {
                return true;
            }
        }
        return false;
    }
}
