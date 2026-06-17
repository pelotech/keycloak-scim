package sh.libre.scim.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jakarta.persistence.NoResultException;

import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import de.captaingoldfish.scim.sdk.client.builder.PatchBuilder;
import de.captaingoldfish.scim.sdk.common.constants.enums.PatchOp;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Member;
import de.captaingoldfish.scim.sdk.common.resources.complex.Meta;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;

public class GroupAdapter extends Adapter<GroupModel, Group> {

    private String displayName;
    private Set<String> members = new HashSet<String>();

    public GroupAdapter(KeycloakSession session, String componentId) {
        super(session, componentId, "Group", Logger.getLogger(GroupAdapter.class));
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        if (this.displayName == null) {
            this.displayName = displayName;
        }
    }

    @Override
    public Class<Group> getResourceClass() {
        return Group.class;
    }

    @Override
    public void apply(GroupModel group) {
        setId(group.getId());
        setDisplayName(group.getName());
        this.members = session.users()
                .getGroupMembersStream(session.getContext().getRealm(), group)
                .map(x -> x.getId())
                .collect(Collectors.toSet());
        this.skip = StringUtils.equals(group.getFirstAttribute("scim-skip"), "true");
    }

    /**
     * Like {@link #apply(GroupModel)} but WITHOUT enumerating the group's
     * members. Used to provision a group for membership propagation, where the
     * member list is neither needed (members are added via the single-member
     * delta PATCH) nor safe to read: on a federated group,
     * {@code getGroupMembersStream} re-imports every member, re-firing
     * {@code onImportUserFromLDAP} and causing an unbounded re-import loop.
     * Sets id, displayName, and the {@code scim-skip} flag only.
     */
    public void applyForProvisioning(GroupModel group) {
        setId(group.getId());
        setDisplayName(group.getName());
        this.skip = StringUtils.equals(group.getFirstAttribute("scim-skip"), "true");
    }

    @Override
    public void apply(Group group) {
        setExternalId(group.getId().get());
        setDisplayName(group.getDisplayName().get());
        var groupMembers = group.getMembers();
        if (groupMembers != null && groupMembers.size() > 0) {
            this.members = new HashSet<String>();
            for (var groupMember : groupMembers) {
                var userMapping = this.query("findByExternalId", groupMember.getValue().get(), "User")
                        .getSingleResult();
                this.members.add(userMapping.getId());
            }
        }
    }

    @Override
    public Group toSCIM(Boolean addMeta) {
        var group = new Group();
        group.setId(externalId);
        group.setExternalId(id);
        group.setDisplayName(displayName);
        if (members.size() > 0) {
            var groupMembers = new ArrayList<Member>();
            for (var member : members) {
                var groupMember = new Member();
                try {
                    var userMapping = this.query("findById", member, "User").getSingleResult();
                    groupMember.setValue(userMapping.getExternalId());
                    var ref = new URI(String.format("Users/%s", userMapping.getExternalId()));
                    groupMember.setRef(ref.toString());
                    groupMembers.add(groupMember);
                } catch (Exception e) {
                    LOGGER.error(e);
                }
            }
            group.setMembers(groupMembers);
        }
        if (addMeta) {
            var meta = new Meta();
            try {
                var uri = new URI("Groups/" + externalId);
                meta.setLocation(uri.toString());
            } catch (URISyntaxException e) {
            }
            group.setMeta(meta);
        }
        return group;
    }

    @Override
    public Boolean entityExists() {
        if (this.id == null) {
            return false;
        }
        var group = session.groups().getGroupById(realm, id);
        if (group != null) {
            return true;
        }
        return false;
    }

    @Override
    public Boolean tryToMap() {
        var group = session.groups().getGroupsStream(realm).filter(x -> x.getName() == displayName).findFirst();
        if (group.isPresent()) {
            setId(group.get().getId());
            return true;
        }
        return false;
    }

    @Override
    public void createEntity() {
        var group = session.groups().createGroup(realm, displayName);
        this.id = group.getId();
        for (String mId : members) {
            try {
                var user = session.users().getUserById(realm, mId);
                if (user == null) {
                    throw new NoResultException();
                }
                user.joinGroup(group);
            } catch (Exception e) {
                LOGGER.warn(e);
            }
        }
    }

    @Override
    public Stream<GroupModel> getResourceStream() {
        return this.session.groups().getGroupsStream(this.session.getContext().getRealm());
    }

    @Override
    public Boolean skipRefresh() {
        return false;
    }

    @Override
    public PatchBuilder<Group> toPatchBuilder(ScimRequestBuilder scimRequestBuilder, String url) {
        // A group UPDATE (rename / sync-refresh) carries only the group's own
        // attributes — never the member list. Membership is maintained
        // independently by single-member GROUP_MEMBERSHIP delta PATCHes and the
        // federated-import diff, so re-asserting the whole member list here (a
        // per-member external-id lookup plus a full-list re-send on every rename)
        // is both wasteful and unnecessary.
        PatchBuilder<Group> patchBuilder = scimRequestBuilder.patch(url, Group.class);
        patchBuilder.addOperation()
            .path("displayName")
            .op(PatchOp.REPLACE)
            .value(displayName)
            .next()
            .op(PatchOp.REPLACE)
            .path("externalId")
            .value(id)
            .build();
        return patchBuilder;
    }

    /**
     * Builds a minimal single-member PATCH for a {@code GROUP_MEMBERSHIP}
     * change — one ADD or one REMOVE — rather than re-sending the full member
     * list as {@link #toPatchBuilder} does. This is the delta path: a user
     * joining or leaving a 10k-member group produces a one-member request.
     */
    public PatchBuilder<Group> toMembershipPatchBuilder(
            ScimRequestBuilder scimRequestBuilder,
            String url,
            String userExternalId,
            boolean isAdd) {
        var patchBuilder = scimRequestBuilder.patch(url, Group.class);
        // Captain-P-Goldfish SCIM SDK semantics: .next() ends the current
        // operation and starts a NEW one — it's a separator, not a terminator.
        // A trailing .next() before .build() appends an empty operation with
        // no op/path/value, which a strict SCIM target rejects with
        // 400 invalidSyntax "Missing operation for patch operation". We want
        // exactly one operation per call, so terminate the chain on .build().
        if (isAdd) {
            patchBuilder.addOperation()
                .path("members")
                .op(PatchOp.ADD)
                .valueNodes(List.of(Member.builder().value(userExternalId).build()))
                .build();
        } else {
            // RFC 7644 §3.5.2.2: filter path targets exactly this member.
            patchBuilder.addOperation()
                .path("members[value eq \"" + userExternalId + "\"]")
                .op(PatchOp.REMOVE)
                .build();
        }
        return patchBuilder;
    }

}
