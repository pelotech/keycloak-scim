package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jakarta.persistence.EntityManager;

/**
 * Pins that provisioning a group for membership does NOT enumerate members
 * (the federated re-import-loop trigger): applyForProvisioning sets id +
 * displayName + scim-skip only, and the resulting SCIM payload carries no
 * members. Crucially it must NOT touch session.users().getGroupMembersStream.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupProvisioningApplyTest {

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock JpaConnectionProvider jpaConnectionProvider;
    @Mock EntityManager entityManager;
    @Mock GroupModel group;

    private GroupAdapter adapter;

    @BeforeEach
    void setUp() {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-id");
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpaConnectionProvider);
        when(jpaConnectionProvider.getEntityManager()).thenReturn(entityManager);
        adapter = new GroupAdapter(session, "component-id");
    }

    @Test
    void applyForProvisioning_setsIdAndName_noMemberEnumeration() {
        when(group.getId()).thenReturn("grp-1");
        when(group.getName()).thenReturn("engineers");
        when(group.getFirstAttribute("scim-skip")).thenReturn(null);

        adapter.applyForProvisioning(group);

        // SCIM payload has the identity but NO members.
        var scim = adapter.toSCIM(false);
        assertThat(scim.getDisplayName().orElse(null)).isEqualTo("engineers");
        assertThat(scim.getMembers()).isNullOrEmpty();
        assertThat(adapter.skip).isFalse();
        // The member-enumeration call must never happen (it is the loop trigger).
        org.mockito.Mockito.verify(session, org.mockito.Mockito.never())
            .users();
    }

    @Test
    void applyForProvisioning_honorsScimSkip() {
        when(group.getId()).thenReturn("grp-1");
        when(group.getName()).thenReturn("engineers");
        when(group.getFirstAttribute("scim-skip")).thenReturn("true");

        adapter.applyForProvisioning(group);

        assertThat(adapter.skip).isTrue();
    }
}
