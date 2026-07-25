package nl.adg.qwixx.bot;

import static java.lang.Math.clamp;
import static java.lang.Math.round;

import java.util.Random;

/**
 * All tunable parameters that drive the bot's loss function and decision rules.
 * {@link #DEFAULT} preserves the hand-tuned baseline values so training can start from a
 * known-good point and branch from there.
 */
public record BotProfile(
        double skipPenalty,       // coefficient in:  skipPenalty * (gap + prevSkips)^1.5
        double rarityBonus,       // per unit of |displayValue - diceCenter|
        double lockBonus,         // flat reduction when the row is within nearLockRemaining of a lock
        double punishmentLoss,    // active player prefers punishment when best loss exceeds this
        double passiveThreshold,  // passive player passes when best loss exceeds this
        int    maxPunishments,    // never voluntarily take a punishment at or above this count
        int    nearLockRemaining  // "close to lock" when this many cells or fewer remain
) {
    /** Hand-tuned baseline — used as the starting point for simulation training. */
    public static final BotProfile DEFAULT =
            new BotProfile(3.5, 5.0, 5.0, 15.0, 0.0, 3, 3);

    /**
     * Returns a new profile with each continuous parameter nudged by Gaussian noise
     * of the given magnitude. Discrete parameters are randomly incremented or decremented.
     * All values are clamped to sensible ranges.
     */
    public BotProfile mutate(double magnitude, Random rng) {
        return new BotProfile(
                clamp(skipPenalty       + rng.nextGaussian() * magnitude,       0.5, 10.0),
                clamp(rarityBonus       + rng.nextGaussian() * magnitude,       0.0, 20.0),
                clamp(lockBonus         + rng.nextGaussian() * magnitude,       0.0, 20.0),
                clamp(punishmentLoss    + rng.nextGaussian() * magnitude * 3.0, 0.0, 60.0),
                clamp(passiveThreshold  + rng.nextGaussian() * magnitude,     -10.0, 10.0),
                clamp(maxPunishments    + rng.nextInt(3) - 1, 1, 5),
                clamp(nearLockRemaining + rng.nextInt(3) - 1, 1, 7)
        );
    }

    /** Returns a new profile whose parameters are the mean of the given profiles. */
    public static BotProfile average(BotProfile... profiles) {
        double sp = 0, rb = 0, lb = 0, pl = 0, pt = 0, mp = 0, nlr = 0;
        for (BotProfile p : profiles) {
            sp  += p.skipPenalty();      rb  += p.rarityBonus();
            lb  += p.lockBonus();        pl  += p.punishmentLoss();
            pt  += p.passiveThreshold(); mp  += p.maxPunishments();
            nlr += p.nearLockRemaining();
        }
        int n = profiles.length;
        return new BotProfile(sp / n, rb / n, lb / n, pl / n, pt / n,
                (int) round(mp / n), (int) round(nlr / n));
    }
}
