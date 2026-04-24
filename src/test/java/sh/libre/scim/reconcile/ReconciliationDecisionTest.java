package sh.libre.scim.reconcile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.UserModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class ReconciliationDecisionTest {

    @Mock UserModel user;

    private static AbsenceWitness alwaysVotes(AbsenceWitness.Vote vote) {
        return u -> vote;
    }

    @Test
    void neverDeleteWhenWitnessListIsEmpty() {
        assertFalse(ReconciliationDecision.shouldDelete(user, List.of()));
    }

    @Test
    void deleteWhenOnlyWitnessVotesAbsent() {
        assertTrue(ReconciliationDecision.shouldDelete(user,
            List.of(alwaysVotes(AbsenceWitness.Vote.ABSENT))));
    }

    @Test
    void deleteWhenAllWitnessesVoteAbsent() {
        assertTrue(ReconciliationDecision.shouldDelete(user, List.of(
            alwaysVotes(AbsenceWitness.Vote.ABSENT),
            alwaysVotes(AbsenceWitness.Vote.ABSENT)
        )));
    }

    @Test
    void skipWhenAnyWitnessVotesPresent() {
        assertFalse(ReconciliationDecision.shouldDelete(user, List.of(
            alwaysVotes(AbsenceWitness.Vote.ABSENT),
            alwaysVotes(AbsenceWitness.Vote.PRESENT)
        )));
    }

    @Test
    void skipWhenAnyWitnessAbstains() {
        // Abstain is not a veto of "absent"; it means the witness lacks
        // evidence. The policy is conservative — any non-ABSENT stays the hand.
        assertFalse(ReconciliationDecision.shouldDelete(user, List.of(
            alwaysVotes(AbsenceWitness.Vote.ABSENT),
            alwaysVotes(AbsenceWitness.Vote.ABSTAIN)
        )));
    }

    @Test
    void skipWhenAllWitnessesAbstain() {
        assertFalse(ReconciliationDecision.shouldDelete(user, List.of(
            alwaysVotes(AbsenceWitness.Vote.ABSTAIN),
            alwaysVotes(AbsenceWitness.Vote.ABSTAIN)
        )));
    }
}
