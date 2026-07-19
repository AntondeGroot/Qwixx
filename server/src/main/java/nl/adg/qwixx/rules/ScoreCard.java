package nl.adg.qwixx.rules;

import jakarta.annotation.Nullable;
import java.util.Map;
import nl.adg.qwixx.data.Color;

public record ScoreCard(
        Map<Color, Integer> crossesPerColor,  // RED, YELLOW, GREEN, BLUE
        Map<Color, Integer> pointsPerColor,   // triangular per color
        int extraCrosses,                     // from ExtraBucket-tagged cells
        int extraPoints,                      // triangular of extraCrosses
        int bonusPoints,                      // flat points from BonusPoints tags
        int punishmentPoints,                 // -5 per punishment
        // Which Bonus B score-time modifiers applied. Their effect is already folded into the
        // fields above; these record it so a score screen can show why the numbers look that way.
        @Nullable Color doubledColor,         // row doubled by DOUBLE_FEWEST, or null
        boolean noPenalty                     // NO_PENALTY nullified the punishments
) {
    public ScoreCard {
        crossesPerColor = Map.copyOf(crossesPerColor);
        pointsPerColor  = Map.copyOf(pointsPerColor);
    }
    public int total() {
        int colorTotal = pointsPerColor.values().stream().mapToInt(Integer::intValue).sum();
        return colorTotal + extraPoints + bonusPoints + punishmentPoints;
    }
}
