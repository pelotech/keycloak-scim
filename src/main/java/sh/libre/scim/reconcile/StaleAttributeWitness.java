package sh.libre.scim.reconcile;

import org.jboss.logging.Logger;
import org.keycloak.models.UserModel;

import java.time.Duration;
import java.time.Instant;

/**
 * Witness that votes {@link Vote#ABSENT} when the federation-liveness
 * attribute is older than the configured threshold.
 *
 * <p>Abstain conditions:
 * <ul>
 *   <li>Attribute is missing — user has never been observed via our mapper,
 *       so we have no basis to reason about liveness (e.g. a user created
 *       via admin REST against a federation that has never sync'd).
 *   <li>Attribute is unparseable — malformed state; abstain rather than
 *       risk deleting on corrupt data.
 * </ul>
 */
public class StaleAttributeWitness implements AbsenceWitness {

    private static final Logger LOGGER = Logger.getLogger(StaleAttributeWitness.class);

    private final String attributeName;
    private final Duration threshold;
    private final Instant now;

    public StaleAttributeWitness(String attributeName, Duration threshold, Instant now) {
        this.attributeName = attributeName;
        this.threshold = threshold;
        this.now = now;
    }

    @Override
    public Vote evaluate(UserModel user) {
        String raw = user.getFirstAttribute(attributeName);
        if (raw == null) {
            return Vote.ABSTAIN;
        }
        Instant seenAt;
        try {
            seenAt = Instant.parse(raw);
        } catch (Exception e) {
            LOGGER.warnf("User %s has unparseable %s attribute %s; abstaining",
                user.getId(), attributeName, raw);
            return Vote.ABSTAIN;
        }
        return seenAt.isBefore(now.minus(threshold)) ? Vote.ABSENT : Vote.PRESENT;
    }
}
