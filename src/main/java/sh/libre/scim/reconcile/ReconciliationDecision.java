package sh.libre.scim.reconcile;

import org.keycloak.models.UserModel;

import java.util.List;

/**
 * Combines votes from one or more {@link AbsenceWitness}es into a delete /
 * skip decision. Delete only when every witness votes ABSENT; a single
 * PRESENT or ABSTAIN stays the hand.
 */
public final class ReconciliationDecision {

    private ReconciliationDecision() {}

    public static boolean shouldDelete(UserModel user, List<AbsenceWitness> witnesses) {
        if (witnesses.isEmpty()) {
            return false;
        }
        for (AbsenceWitness witness : witnesses) {
            if (witness.evaluate(user) != AbsenceWitness.Vote.ABSENT) {
                return false;
            }
        }
        return true;
    }
}
