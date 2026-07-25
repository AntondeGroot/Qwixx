package nl.adg.qwixx.rules;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import nl.adg.qwixx.data.BonusBKind;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;

public class StandardScoringEngine implements ScoringEngine {

    private static final int PUNISHMENT_PENALTY = -5;

    /** Per-colour cross count that counts toward the score; crosses beyond it score nothing.
     *  {@link Integer#MAX_VALUE} means uncapped (the normal game). */
    private final int maxCrossesPerColor;

    public StandardScoringEngine() {
        this(Integer.MAX_VALUE);
    }

    public StandardScoringEngine(int maxCrossesPerColor) {
        this.maxCrossesPerColor = maxCrossesPerColor;
    }

    @Override
    public ScoreCard calculate(SheetLayout layout, SheetProgress progress) {
        Map<Color, Integer> crosses = zeroCrossesPerColor();
        int extraCrosses = 0;
        int bonusPoints  = 0;
        Set<BonusBKind> achievedB = EnumSet.noneOf(BonusBKind.class); // Bonus B: kinds whose pair completed

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            Row row           = layout.rows().get(rowIndex);
            if (isXChangeRow(row)) continue;
            // Bonus A bar cells are pure triggers — the crosses they force already score in the
            // colour rows, so the bar itself must not add points (and forfeited cells even less).
            if (row.isBonusBar()) continue;
            RowState rowState = progress.rowStates().get(rowIndex);
            if (rowState == null) continue;

            // Bonus B strip: the crossed indicators record which bonuses were achieved; the strip
            // itself scores nothing (its forced crosses already scored in the colour rows).
            if (row.isBonusBStrip()) {
                for (Cell cell : row.cells()) {
                    if (!rowState.crossedCells().contains(cell.id())) continue;
                    for (CellTag tag : cell.tags()) {
                        if (tag instanceof CellTag.BonusB(BonusBKind kind)) achievedB.add(kind);
                    }
                }
                continue;
            }

            // Lucky Number row: score = sum of all crossed cells' bonus points (max 5+6+7+8 = 26).
            if (row.isLuckyRow()) {
                for (Cell cell : row.cells()) {
                    if (!rowState.crossedCells().contains(cell.id())) continue;
                    for (CellTag tag : cell.tags()) {
                        if (tag instanceof CellTag.LuckyNumber(int pts)) bonusPoints += pts;
                    }
                }
                continue;
            }

            for (Cell cell : row.cells()) {
                if (!rowState.crossedCells().contains(cell.id())) continue;
                crosses.merge(cell.color(), 1, Integer::sum);
                for (CellTag tag : cell.tags()) {
                    switch (tag) {
                        case CellTag.ExtraBucket ignored        -> { extraCrosses += 1; }
                        case CellTag.DoubleCross ignored        -> crosses.merge(cell.color(), 1, Integer::sum);
                        case CellTag.BonusPoints(int amt)       -> { bonusPoints += amt; }
                        case CellTag.SecondaryColor(Color sc)   -> crosses.merge(sc, 1, Integer::sum);
                        default -> { /* AutoCross is rule-time only; LuckyNumber rows are handled above */ }
                    }
                }
            }

            if (rowState.lockCrossed() && row.hasLock()) {
                crosses.merge(row.lock().color(), 1, Integer::sum);
            }
        }

        Map<Color, Integer> points = pointsPerColor(crosses);
        int extraPoints      = triangular(extraCrosses);

        // Bonus B score-time modifiers.
        Color doubledColor = null;
        if (achievedB.contains(BonusBKind.DOUBLE_FEWEST)) {
            doubledColor = fewestColour(crosses);
            points.merge(doubledColor, Objects.requireNonNull(points.get(doubledColor)), Integer::sum); // that row counts double
        }
        if (achievedB.contains(BonusBKind.PLUS_13)) bonusPoints += 13;
        boolean noPenalty = achievedB.contains(BonusBKind.NO_PENALTY);
        int punishmentPoints = noPenalty ? 0 : progress.punishments() * PUNISHMENT_PENALTY;

        return new ScoreCard(crosses, points, extraCrosses, extraPoints, bonusPoints, punishmentPoints,
                doubledColor, noPenalty);
    }

    /** The colour with the fewest crosses (ties broken RED→YELLOW→GREEN→BLUE). */
    private static Color fewestColour(Map<Color, Integer> crosses) {
        Color best = Color.RED;
        int bestCount = Integer.MAX_VALUE;
        for (Color c : new Color[]{Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE}) {
            int v = crosses.getOrDefault(c, 0);
            if (v < bestCount) { bestCount = v; best = c; }
        }
        return best;
    }

    private static boolean isXChangeRow(Row row) {
        return row.cells().stream()
                .anyMatch(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.XChange));
    }

    private static Map<Color, Integer> zeroCrossesPerColor() {
        Map<Color, Integer> map = new EnumMap<>(Color.class);
        for (Color c : Color.values()) map.put(c, 0);
        return map;
    }

    private Map<Color, Integer> pointsPerColor(Map<Color, Integer> crosses) {
        Map<Color, Integer> points = new EnumMap<>(Color.class);
        for (Color c : Color.values()) {
            points.put(c, triangular(Math.min(maxCrossesPerColor, Objects.requireNonNull(crosses.get(c)))));
        }
        return points;
    }

    private int triangular(int n) {
        return n * (n + 1) / 2;
    }
}
