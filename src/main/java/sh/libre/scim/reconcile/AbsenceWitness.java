package sh.libre.scim.reconcile;

import org.keycloak.models.UserModel;

/**
 * Evidence that a federation-linked user is absent from the upstream federation.
 *
 * The reconciler evaluates each candidate user against one or more witnesses
 * and deletes only when every active witness returns {@link Vote#ABSENT}.
 * A single {@link Vote#PRESENT} or {@link Vote#ABSTAIN} stays the hand of
 * reconciliation — abstain is the safe default when a witness lacks enough
 * information to decide.
 */
public interface AbsenceWitness {

    enum Vote {
        /** Witness sees positive evidence the user is still in the federation. */
        PRESENT,
        /** Witness sees positive evidence the user is gone. */
        ABSENT,
        /** Witness has insufficient data; do not let this witness influence the decision. */
        ABSTAIN,
    }

    Vote evaluate(UserModel user);
}
