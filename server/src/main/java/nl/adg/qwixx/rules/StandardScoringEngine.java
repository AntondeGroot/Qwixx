package nl.adg.qwixx.rules;

import java.util.EnumMap;
import java.util.Map;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;

public class StandardScoringEngine implements ScoringEngine {

    private static final int PUNISHMENT_PENALTY = -5;

    @Override
    public ScoreCard calculate(SheetLayout layout, SheetProgress progress) {
        Map<Color, Integer> crosses = zeroCrossesPerColor();
        int extraCrosses = 0;
        int bonusPoints  = 0;

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            Row row           = layout.rows().get(rowIndex);
            if (isXChangeRow(row)) continue;
            RowState rowState = progress.rowStates().get(rowIndex);
            if (rowState == null) continue;

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
                        case CellTag.ExtraBucket ignored        -> extraCrosses += 1;
                        case CellTag.DoubleCross ignored        -> crosses.merge(cell.color(), 1, Integer::sum);
                        case CellTag.BonusPoints(int amt)       -> bonusPoints += amt;
                        case CellTag.SecondaryColor(Color sc)   -> crosses.merge(sc, 1, Integer::sum);
                        default -> { /* AutoCross is rule-time only; LuckyNumber rows are handled above */ }
                    }
                }
            }

            if (rowState.lockCrossed() && row.lock() != null) {
                crosses.merge(row.lock().color(), 1, Integer::sum);
            }
        }

        Map<Color, Integer> points = pointsPerColor(crosses);
        int extraPoints      = triangular(extraCrosses);
        int punishmentPoints = progress.punishments() * PUNISHMENT_PENALTY;

        return new ScoreCard(crosses, points, extraCrosses, extraPoints, bonusPoints, punishmentPoints);
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

    protected int maxCrossesPerRow() {
        return Integer.MAX_VALUE;
    }

    private Map<Color, Integer> pointsPerColor(Map<Color, Integer> crosses) {
        int cap = maxCrossesPerRow();
        Map<Color, Integer> points = new EnumMap<>(Color.class);
        for (Color c : Color.values()) points.put(c, triangular(Math.min(cap, crosses.get(c))));
        return points;
    }

    private int triangular(int n) {
        return n * (n + 1) / 2;
    }
}
