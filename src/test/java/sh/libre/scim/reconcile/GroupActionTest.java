package sh.libre.scim.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;

import sh.libre.scim.reconcile.ReconcilerRunner.GroupAction;

class GroupActionTest {

    @Test
    void nullGroup_isDelete() {
        // null short-circuits regardless of hasMembers; orphan backstop
        assertThat(ReconcilerRunner.classifyGroup(null, false)).isEqualTo(GroupAction.DELETE);
    }

    @Test
    void groupWithNoMembers_isDelete() {
        // LDAP group was renamed-away or deleted — its members drained to zero → DELETE
        assertThat(ReconcilerRunner.classifyGroup(mock(GroupModel.class), false))
            .isEqualTo(GroupAction.DELETE);
    }

    @Test
    void groupWithMembers_isKeep() {
        // LDAP group still has active members → KEEP
        assertThat(ReconcilerRunner.classifyGroup(mock(GroupModel.class), true))
            .isEqualTo(GroupAction.KEEP);
    }
}
