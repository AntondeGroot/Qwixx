package nl.adg.qwixx.rules;

import nl.adg.qwixx.data.Color;

import java.util.Map;

public record ScoreCard(
        Map<Color, Integer> crossesPerColor,  // RED, YELLOW, GREEN, BLUE
        Map<Color, Integer> pointsPerColor,   // triangular per color
        int extraCrosses,                     // from ExtraBucket-tagged cells
        int extraPoints,                      // triangular of extraCrosses
        int bonusPoints,                      // flat points from BonusPoints tags
        int punishmentPoints                  // -5 per punishment
) {
    public int total() {
        int colorTotal = pointsPerColor.values().stream().mapToInt(Integer::intValue).sum();
        return colorTotal + extraPoints + bonusPoints + punishmentPoints;
    }
}