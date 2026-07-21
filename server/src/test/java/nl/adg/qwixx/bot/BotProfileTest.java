package nl.adg.qwixx.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BotProfileTest {

    private static final double EPS = 1e-9;

    @Test
    void mutateNudgesEveryParameterDeterministicallyForAFixedSeed() {
        // mutate() is deterministic given the RNG, so a fixed seed pins an exact snapshot of every
        // field. Seed 1 with magnitude 2.0 keeps all continuous results strictly inside their clamp
        // ranges and gives both discrete parameters a non-zero nudge, so each arithmetic step
        // (skipPenalty + noise, noise * magnitude, maxPunishments + delta, nextInt(3) - 1) and each
        // clamp changes the snapshot if it is broken. Magnitude must NOT be 1.0: at 1.0 the
        // `noise * magnitude` step is indistinguishable from `noise / magnitude`.
        BotProfile mutated = BotProfile.DEFAULT.mutate(2.0, new Random(1));

        assertEquals(6.623162080, mutated.skipPenalty(), EPS);
        assertEquals(3.783634786, mutated.rarityBonus(), EPS);
        assertEquals(2.817544234, mutated.lockBonus(), EPS);
        assertEquals(11.252759182, mutated.punishmentLoss(), EPS);
        assertEquals(-2.236566421, mutated.passiveThreshold(), EPS);
        assertEquals(4, mutated.maxPunishments());
        assertEquals(4, mutated.nearLockRemaining());
    }
}
