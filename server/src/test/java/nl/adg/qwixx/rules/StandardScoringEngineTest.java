package nl.adg.qwixx.rules;

import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.rules.ScoreCard;
import nl.adg.qwixx.rules.StandardScoringEngine;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StandardScoringEngineTest {

    private StandardScoringEngine engine;

    @BeforeEach
    void setUp() {
        engine = new StandardScoringEngine();
    }

    // --- helpers ---

    private Cell cell(Color color) {
        Cell c = new Cell(0);
        c.setColor(color);
        c.setDisplayValue("5");
        c.setTags(List.of());
        return c;
    }

    private Cell cell(Color color, List<CellTag> tags) {
        Cell c = new Cell(0);
        c.setColor(color);
        c.setDisplayValue("5");
        c.setTags(tags);
        return c;
    }

    private SheetLayout layoutOf(Row... rows) {
        return new SheetLayout(List.of(rows));
    }

    private SheetProgress progressOf(Map<Integer, RowState> rowStates, int punishments) {
        return new SheetProgress(rowStates, punishments);
    }

    // --- triangular formula ---

    @Test
    void zeroRedCrossesScoresZero() {
        Row row = new Row();
        Cell c = cell(Color.RED);
        row.addCell(c);

        SheetLayout layout = layoutOf(row);
        SheetProgress progress = progressOf(Map.of(0, new RowState(Set.of(), false)), 0);

        ScoreCard card = engine.calculate(layout, progress);

        assertEquals(0, card.crossesPerColor().get(Color.RED));
        assertEquals(0, card.pointsPerColor().get(Color.RED));
        assertEquals(0, card.total());
    }

    @Test
    void triangularFormulaApplied() {
        // 3 red crosses → triangular(3) = 6
        Row row = new Row();
        Cell c1 = cell(Color.RED);
        Cell c2 = cell(Color.RED);
        Cell c3 = cell(Color.RED);
        row.addCell(c1);
        row.addCell(c2);
        row.addCell(c3);

        SheetLayout layout = layoutOf(row);
        SheetProgress progress = progressOf(
                Map.of(0, new RowState(Set.of(c1.id(), c2.id(), c3.id()), false)), 0);

        ScoreCard card = engine.calculate(layout, progress);

        assertEquals(3, card.crossesPerColor().get(Color.RED));
        assertEquals(6, card.pointsPerColor().get(Color.RED));
        assertEquals(6, card.total());
    }

    // --- punishments ---

    @Test
    void punishmentsDeductFiveEach() {
        SheetLayout layout = layoutOf();
        SheetProgress progress = progressOf(Map.of(), 3);

        ScoreCard card = engine.calculate(layout, progress);

        assertEquals(-15, card.punishmentPoints());
        assertEquals(-15, card.total());
    }

    // --- ExtraBucket tag ---

    @Test
    void extraBucketCountedSeparately() {
        Row row = new Row();
        Cell c = cell(Color.RED, List.of(new CellTag.ExtraBucket()));
        row.addCell(c);

        SheetLayout layout = layoutOf(row);
        SheetProgress progress = progressOf(Map.of(0, new RowState(Set.of(c.id()), false)), 0);

        ScoreCard card = engine.calculate(layout, progress);

        // primary color gets 1 cross → triangular(1) = 1
        assertEquals(1, card.crossesPerColor().get(Color.RED));
        // EXTRA bucket also gets 1 cross → triangular(1) = 1
        assertEquals(1, card.extraCrosses());
        assertEquals(1, card.extraPoints());
        assertEquals(1 + 1, card.total()); // red(1) + extra(1)
    }

    // --- DoubleCross tag ---

    @Test
    void doubleCrossCountsTwiceInPrimaryBucket() {
        Row row = new Row();
        Cell c = cell(Color.BLUE, List.of(new CellTag.DoubleCross()));
        row.addCell(c);

        SheetLayout layout = layoutOf(row);
        SheetProgress progress = progressOf(Map.of(0, new RowState(Set.of(c.id()), false)), 0);

        ScoreCard card = engine.calculate(layout, progress);

        assertEquals(2, card.crossesPerColor().get(Color.BLUE));
        assertEquals(3, card.pointsPerColor().get(Color.BLUE)); // triangular(2) = 3
    }

    // --- BonusPoints tag ---

    @Test
    void bonusPointsAddedFlat() {
        Row row = new Row();
        Cell c = cell(Color.GREEN, List.of(new CellTag.BonusPoints(10)));
        row.addCell(c);

        SheetLayout layout = layoutOf(row);
        SheetProgress progress = progressOf(Map.of(0, new RowState(Set.of(c.id()), false)), 0);

        ScoreCard card = engine.calculate(layout, progress);

        assertEquals(10, card.bonusPoints());
        assertEquals(1 + 10, card.total()); // triangular(1 green) + 10 flat
    }

    // --- lock bonus ---

    @Test
    void lockBonusAddsOneCrossInLockColor() {
        Row row = new Row();
        Cell c = cell(Color.RED);
        row.addCell(c);
        row.addLock(new LockCell("lock-1", Color.RED, 5, List.of(c.id())));

        SheetLayout layout = layoutOf(row);
        SheetProgress progress = progressOf(
                Map.of(0, new RowState(Set.of(c.id()), true)), 0);

        ScoreCard card = engine.calculate(layout, progress);

        // 1 cell cross + 1 lock bonus = 2 red crosses → triangular(2) = 3
        assertEquals(2, card.crossesPerColor().get(Color.RED));
        assertEquals(3, card.pointsPerColor().get(Color.RED));
    }

    @Test
    void lockBonusNotAppliedWhenLockNotCrossed() {
        Row row = new Row();
        Cell c = cell(Color.RED);
        row.addCell(c);
        row.addLock(new LockCell("lock-1", Color.RED, 5, List.of(c.id())));

        SheetLayout layout = layoutOf(row);
        SheetProgress progress = progressOf(
                Map.of(0, new RowState(Set.of(c.id()), false)), 0);

        ScoreCard card = engine.calculate(layout, progress);

        assertEquals(1, card.crossesPerColor().get(Color.RED));
    }

    // --- combined ---

    @Test
    void totalCombinesAllComponents() {
        // 2 yellow crosses → triangular(2) = 3
        // 1 BonusPoints(5) cell crossed
        // 2 punishments → -10
        Row row = new Row();
        Cell c1 = cell(Color.YELLOW);
        Cell c2 = cell(Color.YELLOW, List.of(new CellTag.BonusPoints(5)));
        row.addCell(c1);
        row.addCell(c2);

        SheetLayout layout = layoutOf(row);
        SheetProgress progress = progressOf(
                Map.of(0, new RowState(Set.of(c1.id(), c2.id()), false)), 2);

        ScoreCard card = engine.calculate(layout, progress);

        assertEquals(2, card.crossesPerColor().get(Color.YELLOW));
        assertEquals(3, card.pointsPerColor().get(Color.YELLOW));
        assertEquals(5, card.bonusPoints());
        assertEquals(-10, card.punishmentPoints());
        assertEquals(3 + 5 - 10, card.total());
    }
}