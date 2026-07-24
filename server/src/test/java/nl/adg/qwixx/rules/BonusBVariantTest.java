package nl.adg.qwixx.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.data.BonusBKind;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.factory.ConfigurableGameStyleFactory;
import nl.adg.qwixx.game.options.GameSettings;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.CardMode;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;
import org.junit.jupiter.api.Test;

/**
 * Bonus B: 10 bonus boxes (2 per kind) in the colour rows; crossing both boxes of a kind marks its
 * strip indicator and fires the effect (immediate for FEWEST_TWO / ONE_EACH, score-time for the
 * rest). Fixed dice: white1=3, white2=4 (WW=7), red=2, yellow=3, green=4, blue=5.
 */
class BonusBVariantTest {

    private static final int RED = 0, YELLOW = 1, GREEN = 2, BLUE = 3, STRIP = 4;
    private final UUID p1 = UUID.randomUUID();
    private final StandardTurnRules rules = new StandardTurnRules(fixedRandom());

    @Test
    void completingAPairMarksTheStripIndicator() {
        List<Row> rows = board();
        Cell boxA = rows.get(RED).cells().get(5);   // dv "7"
        Cell boxB = rows.get(YELLOW).cells().get(5); // dv "7"
        addBonusB(boxA, BonusBKind.NO_PENALTY);
        addBonusB(boxB, BonusBKind.NO_PENALTY);
        GameState state = stateWithStrip(rows);
        // One box already crossed; crossing the other completes the pair.
        state.boardState().sheetProgress().get(p1).updateRowState(YELLOW, new RowState(Set.of(boxB.id()), false));
        rules.apply(state, new RollAction(p1));

        rules.apply(state, new CrossCellAction(p1, RED, boxA.id(), DiceCombination.WHITE_WHITE));

        assertTrue(crossed(state, STRIP).contains(rows.get(STRIP).cells().get(4).id()),
                "the NO_PENALTY strip indicator (index 4) is marked when its pair completes");
    }

    @Test
    void fewestTwoCrossesTwoBoxesInTheFewestRow() {
        List<Row> rows = board();
        Cell boxA = rows.get(RED).cells().get(5);
        Cell boxB = rows.get(YELLOW).cells().get(5);
        addBonusB(boxA, BonusBKind.FEWEST_TWO);
        addBonusB(boxB, BonusBKind.FEWEST_TWO);
        GameState state = stateWithStrip(rows);
        state.boardState().sheetProgress().get(p1).updateRowState(YELLOW, new RowState(Set.of(boxB.id()), false));
        rules.apply(state, new RollAction(p1));

        rules.apply(state, new CrossCellAction(p1, RED, boxA.id(), DiceCombination.WHITE_WHITE));

        // After the cross: RED=1, YELLOW=1, GREEN=0, BLUE=0 → fewest is GREEN; it gets 2 forced crosses.
        assertEquals(2, crossed(state, GREEN).size(), "the fewest row (green) receives 2 bonus crosses");
        assertTrue(crossed(state, STRIP).contains(rows.get(STRIP).cells().getFirst().id()), "FEWEST_TWO indicator marked");
    }

    @Test
    void oneEachCrossesOneBoxInEveryRow() {
        List<Row> rows = board();
        Cell boxA = rows.get(RED).cells().get(5);
        Cell boxB = rows.get(GREEN).cells().get(5);
        addBonusB(boxA, BonusBKind.ONE_EACH);
        addBonusB(boxB, BonusBKind.ONE_EACH);
        GameState state = stateWithStrip(rows);
        state.boardState().sheetProgress().get(p1).updateRowState(GREEN, new RowState(Set.of(boxB.id()), false));
        rules.apply(state, new RollAction(p1));

        rules.apply(state, new CrossCellAction(p1, RED, boxA.id(), DiceCombination.WHITE_WHITE));

        // The two empty rows each gain exactly one forced cross.
        assertEquals(1, crossed(state, YELLOW).size(), "yellow gains one forced cross");
        assertEquals(1, crossed(state, BLUE).size(), "blue gains one forced cross");
        assertTrue(crossed(state, STRIP).contains(rows.get(STRIP).cells().get(1).id()), "ONE_EACH indicator marked");
    }

    @Test
    void stripCellsAreNeverOffered() {
        List<Row> rows = board();
        GameState state = stateWithStrip(rows);
        rules.apply(state, new RollAction(p1));

        List<String> stripIds = rows.get(STRIP).cells().stream().map(Cell::id).toList();
        for (GameAction a : rules.getValidActions(state, p1)) {
            if (a instanceof CrossCellAction cross) {
                assertFalse(stripIds.contains(cross.cellId()), "bonus-strip cells must not be clickable");
            }
        }
    }

    @Test
    void factoryGeneratesTenBoxesAndAFiveCellStrip() {
        UUID player = UUID.randomUUID();
        List<Row> rows = new ConfigurableGameStyleFactory(GameSettings.builder().bonusB(true).build())
                .buildRows(List.of(player)).get(player);

        Map<BonusBKind, Integer> counts = new HashMap<>();
        List<Row> coloured = rows.stream().filter(r -> r.lock() != null).toList();
        assertEquals(4, coloured.size(), "four coloured rows");
        for (Row row : coloured) {
            for (Cell c : row.cells()) {
                for (CellTag t : c.tags()) {
                    if (t instanceof CellTag.BonusB(BonusBKind k)) counts.merge(k, 1, Integer::sum);
                }
            }
        }
        assertEquals(5, counts.size(), "all 5 kinds appear in the colour rows");
        for (BonusBKind k : BonusBKind.values()) assertEquals(2, counts.get(k), k + " has exactly 2 boxes");

        Row strip = rows.stream().filter(Row::isBonusBStrip).findFirst().orElseThrow();
        assertEquals(5, strip.cells().size(), "the strip has 5 indicator cells");
    }

    @Test
    void sameCardsPlacesBoxesPerTheOfficialSpec() {
        List<Row> rows = new ConfigurableGameStyleFactory(GameSettings.builder().bonusB(true).build())
                .buildRows(List.of(UUID.randomUUID())).values().iterator().next();
        // Default colours: row 0 = RED (asc), 1 = YELLOW (asc), 2 = GREEN (desc), 3 = BLUE (desc).
        assertEquals(Color.RED, rows.getFirst().cells().getFirst().color());
        assertEquals(Color.BLUE, rows.get(3).cells().getFirst().color());
        assertKind(rows.getFirst(), "6", BonusBKind.PLUS_13);        // R6
        assertKind(rows.getFirst(), "8", BonusBKind.DOUBLE_FEWEST);  // R8
        assertKind(rows.get(1), "3", BonusBKind.NO_PENALTY);     // Y3
        assertKind(rows.get(1), "7", BonusBKind.ONE_EACH);       // Y7
        assertKind(rows.get(1), "11", BonusBKind.FEWEST_TWO);    // Y11
        assertKind(rows.get(2), "9", BonusBKind.FEWEST_TWO);     // G9
        assertKind(rows.get(2), "5", BonusBKind.NO_PENALTY);     // G5
        assertKind(rows.get(3), "10", BonusBKind.DOUBLE_FEWEST); // B10
        assertKind(rows.get(3), "7", BonusBKind.ONE_EACH);       // B7
        assertKind(rows.get(3), "4", BonusBKind.PLUS_13);        // B4
    }

    private void assertKind(Row row, String value, BonusBKind expected) {
        Cell cell = row.cells().stream().filter(c -> c.displayValue().equals(value)).findFirst().orElseThrow();
        assertTrue(cell.tags().stream().anyMatch(t -> t instanceof CellTag.BonusB(BonusBKind k) && k == expected),
                "cell " + value + " in row " + row.cells().getFirst().color() + " should be " + expected);
    }

    // ── Scoring modifiers ─────────────────────────────────────────────────────

    @Test
    void doubleFewestDoublesTheFewestRowScore() {
        var score = scoreWith(Map.of(RED, 3, YELLOW, 5, GREEN, 5, BLUE, 5), BonusBKind.DOUBLE_FEWEST);
        // RED has fewest (3 → triangular(3)=6); doubled = 12. Others 5 → 15 each.
        assertEquals(12, score.pointsPerColor().get(Color.RED), "fewest row's points are doubled");
        assertEquals(15, score.pointsPerColor().get(Color.YELLOW), "other rows unaffected");
        assertEquals(12 + 15 * 3, score.total(), "total reflects the doubled fewest row");
        assertEquals(Color.RED, score.doubledColor(), "the score card reports which row was doubled");
    }

    @Test
    void doubledColourFollowsTheTieBreakWhenRowsAreLevel() {
        var score = scoreWith(Map.of(RED, 5, YELLOW, 3, GREEN, 3, BLUE, 5), BonusBKind.DOUBLE_FEWEST);
        // YELLOW and GREEN tie on 3; the RED→YELLOW→GREEN→BLUE tie-break picks YELLOW.
        assertEquals(Color.YELLOW, score.doubledColor(), "the reported row matches the one actually doubled");
        assertEquals(12, score.pointsPerColor().get(Color.YELLOW), "the reported row is the doubled one");
        assertEquals(6, score.pointsPerColor().get(Color.GREEN), "the tied row is left alone");
    }

    @Test
    void noDoubledColourWithoutTheBonus() {
        var score = scoreWith(Map.of(RED, 3, YELLOW, 5, GREEN, 5, BLUE, 5), null);
        assertNull(score.doubledColor(), "no row is doubled when DOUBLE_FEWEST was not achieved");
    }

    @Test
    void plus13AddsThirteenPoints() {
        var plain = scoreWith(Map.of(RED, 3, YELLOW, 3, GREEN, 3, BLUE, 3), null);
        var bonus = scoreWith(Map.of(RED, 3, YELLOW, 3, GREEN, 3, BLUE, 3), BonusBKind.PLUS_13);
        assertEquals(13, bonus.bonusPoints(), "+13 recorded as bonus points");
        assertEquals(plain.total() + 13, bonus.total(), "+13 added to the total");
    }

    @Test
    void noPenaltyRemovesMinusPoints() {
        List<Row> rows = board();
        rows.add(strip());
        Map<Integer, RowState> rowStates = new HashMap<>();
        rowStates.put(STRIP, new RowState(Set.of(rows.get(STRIP).cells().get(4).id()), false)); // NO_PENALTY
        var score = new StandardScoringEngine()
                .calculate(new SheetLayout(rows), new SheetProgress(rowStates, 3)); // 3 mis-rolls
        assertEquals(0, score.punishmentPoints(), "mis-rolls subtract nothing when NO_PENALTY is achieved");
        assertTrue(score.noPenalty(), "the score card reports that the shield applied");
    }

    @Test
    void noPenaltyFlagIsOffWithoutTheBonus() {
        var score = scoreWith(Map.of(RED, 3, YELLOW, 3, GREEN, 3, BLUE, 3), null);
        assertFalse(score.noPenalty(), "the shield did not apply");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private nl.adg.qwixx.rules.ScoreCard scoreWith(Map<Integer, Integer> crossesPerRow, BonusBKind achieved) {
        List<Row> rows = board();
        rows.add(strip());
        Map<Integer, RowState> rowStates = new HashMap<>();
        crossesPerRow.forEach((rowIndex, n) -> {
            Set<String> cross = new java.util.HashSet<>();
            for (int j = 0; j < n; j++) cross.add(rows.get(rowIndex).cells().get(j).id());
            rowStates.put(rowIndex, new RowState(cross, false));
        });
        if (achieved != null) {
            int idx = List.of(BonusBKind.values()).indexOf(achieved);
            rowStates.put(STRIP, new RowState(Set.of(rows.get(STRIP).cells().get(idx).id()), false));
        }
        return new StandardScoringEngine().calculate(new SheetLayout(rows), new SheetProgress(rowStates, 0));
    }

    private Set<String> crossed(GameState state, int rowIndex) {
        RowState rs = state.boardState().sheetProgress().get(p1).rowStates().get(rowIndex);
        return rs != null ? rs.crossedCells() : Set.of();
    }

    private void addBonusB(Cell cell, BonusBKind kind) {
        List<CellTag> tags = new ArrayList<>(cell.tags());
        tags.add(new CellTag.BonusB(kind));
        cell.setTags(tags);
    }

    private List<Row> board() {
        List<Row> rows = new ArrayList<>();
        rows.add(ascendingRow(Color.RED));
        rows.add(ascendingRow(Color.YELLOW));
        rows.add(descendingRow(Color.GREEN));
        rows.add(descendingRow(Color.BLUE));
        return rows;
    }

    private Row ascendingRow(Color color) {
        Row row = new Row();
        Cell last = null;
        for (int i = 0; i < 11; i++) {
            Cell c = new Cell(i);
            c.setColor(color);
            c.setDisplayValue(String.valueOf(i + 2));
            c.setTags(new ArrayList<>());
            row.addCell(c);
            last = c;
        }
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 5, List.of(last.id())));
        return row;
    }

    private Row descendingRow(Color color) {
        Row row = new Row();
        Cell last = null;
        for (int i = 0; i < 11; i++) {
            Cell c = new Cell(i);
            c.setColor(color);
            c.setDisplayValue(String.valueOf(12 - i));
            c.setTags(new ArrayList<>());
            row.addCell(c);
            last = c;
        }
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 5, List.of(last.id())));
        return row;
    }

    private Row strip() {
        Row row = new Row();
        row.setBonusBStrip();
        BonusBKind[] kinds = BonusBKind.values();
        for (int i = 0; i < kinds.length; i++) {
            Cell c = new Cell(i);
            c.setColor(Color.RED); // inert placeholder; strip cells are neutral indicators
            c.setDisplayValue("");
            c.setTags(List.of(new CellTag.BonusB(kinds[i])));
            row.addCell(c);
        }
        return row;
    }

    private GameState stateWithStrip(List<Row> rows) {
        rows.add(strip()); // index STRIP (4)
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progresses = new HashMap<>();
        layouts.put(p1, new SheetLayout(rows));
        progresses.put(p1, new SheetProgress(new HashMap<>(), 0));
        List<Die> dice = new ArrayList<>(List.of(
                new Die(Color.WHITE, 6), new Die(Color.WHITE, 6),
                new Die(Color.RED, 6), new Die(Color.YELLOW, 6),
                new Die(Color.GREEN, 6), new Die(Color.BLUE, 6)));
        BoardState board = new BoardState(progresses, dice, new HashMap<>());
        TurnState turn = new TurnState();
        turn.setActivePlayerId(p1);
        turn.setPhase(TurnPhase.ROLL);
        return new GameState(CardMode.SAME_CARDS, List.of(p1), null, layouts, board, turn);
    }

    /** Fixed dice: white1=3, white2=4 (sum=7), red=2, yellow=3, green=4, blue=5. */
    private Random fixedRandom() {
        return new Random() {
            private final int[] seq = {2, 3, 1, 2, 3, 4};
            private int pos = 0;
            @Override public int nextInt(int bound) { return seq[pos++ % seq.length]; }
        };
    }
}
