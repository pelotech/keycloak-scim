package sh.libre.scim.reconcile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.UserModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaleAttributeWitnessTest {

    private static final String ATTR = "ldap-federation-last-seen";
    private static final Instant NOW = Instant.parse("2026-04-24T00:00:00Z");
    private static final Duration THRESHOLD = Duration.ofHours(48);

    @Mock UserModel user;

    @Test
    void absentWhenAttributeIsOlderThanThreshold() {
        when(user.getFirstAttribute(ATTR))
            .thenReturn(NOW.minus(Duration.ofHours(49)).toString());

        var witness = new StaleAttributeWitness(ATTR, THRESHOLD, NOW);
        assertEquals(AbsenceWitness.Vote.ABSENT, witness.evaluate(user));
    }

    @Test
    void presentWhenAttributeIsWithinThreshold() {
        when(user.getFirstAttribute(ATTR))
            .thenReturn(NOW.minus(Duration.ofHours(1)).toString());

        var witness = new StaleAttributeWitness(ATTR, THRESHOLD, NOW);
        assertEquals(AbsenceWitness.Vote.PRESENT, witness.evaluate(user));
    }

    @Test
    void presentAtExactThresholdBoundary() {
        // isBefore(cutoff) is strict; equal-to-cutoff counts as present.
        when(user.getFirstAttribute(ATTR))
            .thenReturn(NOW.minus(THRESHOLD).toString());

        var witness = new StaleAttributeWitness(ATTR, THRESHOLD, NOW);
        assertEquals(AbsenceWitness.Vote.PRESENT, witness.evaluate(user));
    }

    @Test
    void abstainWhenAttributeMissing() {
        when(user.getFirstAttribute(ATTR)).thenReturn(null);

        var witness = new StaleAttributeWitness(ATTR, THRESHOLD, NOW);
        assertEquals(AbsenceWitness.Vote.ABSTAIN, witness.evaluate(user));
    }

    @Test
    void abstainWhenAttributeUnparseable() {
        when(user.getFirstAttribute(ATTR)).thenReturn("not-an-instant");

        var witness = new StaleAttributeWitness(ATTR, THRESHOLD, NOW);
        assertEquals(AbsenceWitness.Vote.ABSTAIN, witness.evaluate(user));
    }
}
