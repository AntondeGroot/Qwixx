package nl.adg.qwixx.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class BotStrategyTest {

    private static final double EPS = 1e-9;

    @Test
    void concreteStrategyLoadsItsOwnTrainedProfile() {
        // MOST_POINTS must load /profiles/trained-most-points.json — a real, non-null profile whose
        // values come from the file (not DEFAULT and not the BALANCED average).
        BotProfile points = BotStrategy.MOST_POINTS.profile();

        assertNotNull(points, "a concrete strategy must load its profile from the classpath");
        assertEquals(10.0, points.skipPenalty(), EPS);
        assertEquals(3.187072, points.rarityBonus(), EPS);
        assertEquals(2, points.maxPunishments());
    }

    @Test
    void distinctStrategiesLoadDistinctProfiles() {
        // Each concrete strategy reads a different file, so their key parameters differ.
        BotProfile untrained = BotStrategy.UNTRAINED.profile();
        BotProfile mostWins  = BotStrategy.MOST_WINS.profile();

        assertEquals(3.5, untrained.skipPenalty(), EPS);
        assertEquals(6.351070, mostWins.skipPenalty(), EPS);
        assertEquals(10.410383, mostWins.rarityBonus(), EPS);
    }

    @Test
    void balancedIsTheRuntimeAverageOfTheThreeConcreteProfiles() {
        // BALANCED takes the average branch of profile(); it is NOT loaded from a file. Comparing to
        // the element-wise average of the three concrete profiles pins that branch down.
        BotProfile untrained = BotStrategy.UNTRAINED.profile();
        BotProfile points    = BotStrategy.MOST_POINTS.profile();
        BotProfile wins      = BotStrategy.MOST_WINS.profile();
        BotProfile expected  = BotProfile.average(untrained, points, wins);

        BotProfile balanced = BotStrategy.BALANCED.profile();

        assertNotNull(balanced);
        assertEquals(expected.skipPenalty(),       balanced.skipPenalty(),       EPS);
        assertEquals(expected.rarityBonus(),       balanced.rarityBonus(),       EPS);
        assertEquals(expected.lockBonus(),         balanced.lockBonus(),         EPS);
        assertEquals(expected.punishmentLoss(),    balanced.punishmentLoss(),    EPS);
        assertEquals(expected.passiveThreshold(),  balanced.passiveThreshold(),  EPS);
        assertEquals(expected.maxPunishments(),    balanced.maxPunishments());
        assertEquals(expected.nearLockRemaining(), balanced.nearLockRemaining());

        // The averaged skipPenalty is distinctly (3.5 + 10.0 + 6.351070) / 3 ≈ 6.617 — proving the
        // averaged branch ran rather than a classpath load (which would yield DEFAULT's 3.5).
        assertEquals((3.5 + 10.0 + 6.351070) / 3.0, balanced.skipPenalty(), EPS);
    }
}
