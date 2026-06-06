package nl.adg.qwixx.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import org.junit.jupiter.api.Test;

class BigPointsScoringEngineTest {

    // --- helpers ---

    private Cell cell(Color color) {
        Cell c = new Cell(0);
        c.setColor(color);
        c.setDisplayValue("5");
        c.setTags(List.of());
        return c;
    }

    private Cell bonusCell(Color primary, Color secondary) {
        Cell c = new Cell(0);
        c.setColor(primary);
        c.setDisplayValue("5");
        c.setTags(List.of(new CellTag.SecondaryColor(secondary)));
        return c;
    }

    /** Builds a row of n plain cells all with the given color. */
    private Row plainRow(Color color, int n) {
        Row row = new Row();
        for (int i = 0; i < n; i++) row.addCell(cell(color));
        return row;
    }

    /** Returns a RowState with all cells in the row crossed. */
    private RowState allCrossed(Row row) {
        Set<String> ids = row.cells().stream().map(Cell::id).collect(Collectors.toSet());
        return new RowState(ids, false);
    }

    private SheetLayout layoutOf(Row... rows) {
        return new SheetLayout(List.of(rows));
    }

    // --- cap behaviour ---

    @Test
    void standardCapOfFifteenGivesTriangular15() {
        // 16 red cells all crossed → without cap: triangular(16)=136, with cap 15: triangular(15)=120
        Row row = plainRow(Color.RED, 16);
        SheetLayout layout = layoutOf(row);
        SheetProgress progress = new SheetProgress(Map.of(0, allCrossed(row)), 0);

        ScoreCard card = new BigPointsScoringEngine(15).calculate(layout, progress);

        assertEquals(120, card.pointsPerColor().get(Color.RED));
        assertEquals(120, card.total());
    }

    @Test
    void standardCapExactly15CrossesIsNotTruncated() {
        Row row = plainRow(Color.RED, 15);
        SheetLayout layout = layoutOf(row);
        SheetProgress progress = new SheetProgress(Map.of(0, allCrossed(row)), 0);

        ScoreCard card = new BigPointsScoringEngine(15).calculate(layout, progress);

        assertEquals(120, card.pointsPerColor().get(Color.RED));
    }

    @Test
    void standardCapBelow15CrossesIsNotTruncated() {
        // 11 crosses → triangular(11) = 66 (well within cap)
        Row row = plainRow(Color.RED, 11);
        SheetLayout layout = layoutOf(row);
        SheetProgress progress = new SheetProgress(Map.of(0, allCrossed(row)), 0);

        ScoreCard card = new BigPointsScoringEngine(15).calculate(layout, progress);

        assertEquals(66, card.pointsPerColor().get(Color.RED));
    }

    @Test
    void longoCapOfNineteenGivesTriangular19() {
        // 20 red cells all crossed → without cap: triangular(20)=210, with cap 19: triangular(19)=190
        Row row = plainRow(Color.RED, 20);
        SheetLayout layout = layoutOf(row);
        SheetProgress progress = new SheetProgress(Map.of(0, allCrossed(row)), 0);

        ScoreCard card = new BigPointsScoringEngine(19).calculate(layout, progress);

        assertEquals(190, card.pointsPerColor().get(Color.RED));
        assertEquals(190, card.total());
    }

    @Test
    void longoCapExactly19CrossesIsNotTruncated() {
        Row row = plainRow(Color.RED, 19);
        SheetLayout layout = layoutOf(row);
        SheetProgress progress = new SheetProgress(Map.of(0, allCrossed(row)), 0);

        ScoreCard card = new BigPointsScoringEngine(19).calculate(layout, progress);

        assertEquals(190, card.pointsPerColor().get(Color.RED));
    }

    @Test
    void capAppliesIndependentlyPerColor() {
        // RED has 16 crosses (capped to 15 → 120), BLUE has 8 crosses (uncapped → 36)
        Row redRow  = plainRow(Color.RED,  16);
        Row blueRow = plainRow(Color.BLUE,  8);
        SheetLayout layout = layoutOf(redRow, blueRow);
        SheetProgress progress = new SheetProgress(Map.of(
                0, allCrossed(redRow),
                1, allCrossed(blueRow)), 0);

        ScoreCard card = new BigPointsScoringEngine(15).calculate(layout, progress);

        assertEquals(120, card.pointsPerColor().get(Color.RED));
        assertEquals(36,  card.pointsPerColor().get(Color.BLUE));
    }

    // --- dual-color credit ---

    @Test
    void crossingOneBonusCellAddsCrossToEachNeighbourColor() {
        // One BONUS-RY cell crossed → RED gets 1 cross, YELLOW gets 1 cross.
        // This is the core scoring mechanic: each bonus cell counts for BOTH adjacent rows.
        Cell bonus = bonusCell(Color.RED, Color.YELLOW);
        Row row = new Row();
        row.addCell(bonus);
        SheetLayout layout = layoutOf(row);
        SheetProgress progress = new SheetProgress(
                Map.of(0, new RowState(Set.of(bonus.id()), false)), 0);

        ScoreCard card = new BigPointsScoringEngine(15).calculate(layout, progress);

        assertEquals(1, card.crossesPerColor().get(Color.RED),
                "one BONUS-RY cross must count as 1 cross toward RED");
        assertEquals(1, card.crossesPerColor().get(Color.YELLOW),
                "one BONUS-RY cross must count as 1 cross toward YELLOW");
        assertEquals(0, card.crossesPerColor().get(Color.GREEN),
                "BONUS-RY cross must not affect GREEN");
        assertEquals(0, card.crossesPerColor().get(Color.BLUE),
                "BONUS-RY cross must not affect BLUE");
    }

    @Test
    void bonusGBCellAddsCrossToGreenAndBlueCounts() {
        // BONUS-GB cell (primary=GREEN, secondary=BLUE): must add to GREEN and BLUE, not RED/YELLOW.
        Cell bonus = bonusCell(Color.GREEN, Color.BLUE);
        Row row = new Row();
        row.addCell(bonus);
        SheetLayout layout = layoutOf(row);
        SheetProgress progress = new SheetProgress(
                Map.of(0, new RowState(Set.of(bonus.id()), false)), 0);

        ScoreCard card = new BigPointsScoringEngine(15).calculate(layout, progress);

        assertEquals(1, card.crossesPerColor().get(Color.GREEN));
        assertEquals(1, card.crossesPerColor().get(Color.BLUE));
        assertEquals(0, card.crossesPerColor().get(Color.RED));
        assertEquals(0, card.crossesPerColor().get(Color.YELLOW));
    }

    @Test
    void bonusCrossesAccumulateAcrossMultipleBonusCells() {
        // 3 BONUS-RY cells crossed → both RED and YELLOW each get 3 crosses.
        Row row = new Row();
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Cell c = bonusCell(Color.RED, Color.YELLOW);
            row.addCell(c);
            cells.add(c);
        }
        Set<String> crossed = cells.stream().map(Cell::id).collect(Collectors.toSet());
        SheetLayout layout = layoutOf(row);
        SheetProgress progress = new SheetProgress(
                Map.of(0, new RowState(crossed, false)), 0);

        ScoreCard card = new BigPointsScoringEngine(15).calculate(layout, progress);

        assertEquals(3, card.crossesPerColor().get(Color.RED));
        assertEquals(3, card.crossesPerColor().get(Color.YELLOW));
    }

    @Test
    void regularRedCellDoesNotAddToYellowCrossCount() {
        // Sanity check: only SecondaryColor-tagged cells trigger the dual credit.
        // A plain RED cell must not bleed into YELLOW.
        Cell plain = cell(Color.RED);
        Row row = new Row();
        row.addCell(plain);
        SheetLayout layout = layoutOf(row);
        SheetProgress progress = new SheetProgress(
                Map.of(0, new RowState(Set.of(plain.id()), false)), 0);

        ScoreCard card = new BigPointsScoringEngine(15).calculate(layout, progress);

        assertEquals(1, card.crossesPerColor().get(Color.RED));
        assertEquals(0, card.crossesPerColor().get(Color.YELLOW));
    }

    @Test
    void secondaryColorCrossesAlsoRespectCap() {
        // Bonus row: each cell counts for both RED and YELLOW.
        // 16 bonus cells (primary=RED, secondary=YELLOW) all crossed → each color gets 16 raw crosses.
        // With cap 15: both should score triangular(15) = 120.
        Row row = new Row();
        for (int i = 0; i < 16; i++) row.addCell(bonusCell(Color.RED, Color.YELLOW));

        SheetLayout layout = layoutOf(row);
        SheetProgress progress = new SheetProgress(Map.of(0, allCrossed(row)), 0);

        ScoreCard card = new BigPointsScoringEngine(15).calculate(layout, progress);

        assertEquals(120, card.pointsPerColor().get(Color.RED));
        assertEquals(120, card.pointsPerColor().get(Color.YELLOW));
    }
}
