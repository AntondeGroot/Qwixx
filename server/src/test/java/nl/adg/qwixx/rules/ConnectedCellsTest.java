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
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.CardMode;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;
import org.junit.jupiter.api.Test;

class ConnectedCellsTest {

    // Fixed dice: white1=3, white2=4 (sum=7), red=2, yellow=3, green=4, blue=5
    // white2 + red = 6 → can cross displayValue "6" (position 4 in ascending row)
    private final UUID p1 = UUID.randomUUID();

    // Rules with fixed dice so rolls are deterministic
    private final StandardTurnRules rules = new StandardTurnRules(fixedRandom());

    // -------------------------------------------------------------------------
    // AutoCross behavior via StandardTurnRules
    // -------------------------------------------------------------------------

    @Test
    void autoCrossCrossesBothConnectedCells() {
        // Row 0 (RED ascending): cell@pos4 (dv="6") has AutoCross -> row1 cell@pos3 (dv="5")
        // Row 1 (YELLOW ascending): cell@pos3 has AutoCross -> row0 cell@pos4 (bidirectional)
        List<Row> rows = buildStandardRows();
        Cell cellA = rows.getFirst().cells().get(4); // pos 4, dv "6"
        Cell cellB = rows.get(1).cells().get(3); // pos 3, dv "5"
        cellA.setTags(List.of(new CellTag.AutoCross(cellB.id())));
        cellB.setTags(List.of(new CellTag.AutoCross(cellA.id())));

        GameState state = buildStateInRoll(rows, p1);
        rules.apply(state, new RollAction(p1));
        // white2 + red = 4 + 2 = 6 → crosses cellA (pos 4, dv "6") in row 0 (RED)
        rules.apply(state, new CrossCellAction(p1, 0, cellA.id(), DiceCombination.WHITE_COLOR));

        Set<String> row0Crossed = rowCrossed(state, p1, 0);
        Set<String> row1Crossed = rowCrossed(state, p1, 1);
        assertTrue(row0Crossed.contains(cellA.id()), "source cell must be crossed");
        assertTrue(row1Crossed.contains(cellB.id()), "auto-cross target must also be crossed");
    }

    @Test
    void autoCrossCanCrossCellBehindRightmostPosition() {
        // Row 0 cell@pos4 (dv="6") AutoCross -> row 1 cell@pos2 (dv="4")
        // Row 1 already has a cross at pos 5 (rightmost=5), so pos2 < 5 would normally fail
        // AutoCross must bypass the progression check
        List<Row> rows = buildStandardRows();
        Cell cellA = rows.getFirst().cells().get(4); // pos 4, dv "6"
        Cell cellB = rows.get(1).cells().get(2); // pos 2, behind the existing cross at pos 5

        cellA.setTags(List.of(new CellTag.AutoCross(cellB.id())));
        cellB.setTags(List.of(new CellTag.AutoCross(cellA.id())));

        GameState state = buildStateInRoll(rows, p1);

        // Pre-cross row 1 position 5 (rightmost will be 5, blocking position 2 normally)
        Cell existingCross = rows.get(1).cells().get(5);
        state.boardState().sheetProgress().get(p1)
                .updateRowState(1, new RowState(Set.of(existingCross.id()), false));

        rules.apply(state, new RollAction(p1));
        rules.apply(state, new CrossCellAction(p1, 0, cellA.id(), DiceCombination.WHITE_COLOR));

        Set<String> row1Crossed = rowCrossed(state, p1, 1);
        assertTrue(row1Crossed.contains(cellB.id()),
                "AutoCross must cross cell behind the rightmost position (progression check bypassed)");
    }

    @Test
    void autoCrossDoesNotInfiniteLoopOnBidirectionalTags() {
        // Both cells point at each other. When A is crossed, it auto-crosses B.
        // B would try to auto-cross A, but A is already crossed — should not loop.
        List<Row> rows = buildStandardRows();
        Cell cellA = rows.getFirst().cells().get(4);
        Cell cellB = rows.get(1).cells().get(3);
        cellA.setTags(List.of(new CellTag.AutoCross(cellB.id())));
        cellB.setTags(List.of(new CellTag.AutoCross(cellA.id())));

        GameState state = buildStateInRoll(rows, p1);
        rules.apply(state, new RollAction(p1));

        // Should complete without StackOverflowError
        assertDoesNotThrow(() ->
                rules.apply(state, new CrossCellAction(p1, 0, cellA.id(), DiceCombination.WHITE_COLOR)));

        // Exactly one cross in each row (not duplicated by loops)
        assertEquals(1, rowCrossed(state, p1, 0).size(), "row 0 should have exactly one cross");
        assertEquals(1, rowCrossed(state, p1, 1).size(), "row 1 should have exactly one cross");
    }

    @Test
    void autoCrossIsStillAppliedWhenTargetRowIsClosed() {
        // Row 1 is already closed.  Crossing cellA (row 0) would normally auto-cross
        // cellB (row 1), but since row 1 is closed the auto-cross must be silently skipped
        // — neither an exception nor a corrupt state.
        List<Row> rows = buildStandardRows();
        Cell cellA = rows.getFirst().cells().get(4); // pos 4, dv "6"
        Cell cellB = rows.get(1).cells().get(3); // pos 3, dv "5"
        cellA.setTags(List.of(new CellTag.AutoCross(cellB.id())));
        cellB.setTags(List.of(new CellTag.AutoCross(cellA.id())));

        GameState state = buildStateInRoll(rows, p1);
        state.boardState().closedRows().put(1, p1);  // row 1 already closed

        rules.apply(state, new RollAction(p1));
        assertDoesNotThrow(
                () -> rules.apply(state, new CrossCellAction(p1, 0, cellA.id(), DiceCombination.WHITE_COLOR)),
                "crossing a cell whose auto-cross target is in a closed row must not throw");

        assertTrue(rowCrossed(state, p1, 0).contains(cellA.id()),
                "source cell must still be crossed");
        assertTrue(rowCrossed(state, p1, 1).contains(cellB.id()),
                "target cell in the closed row must NOT be crossed");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Set<String> rowCrossed(GameState state, UUID playerId, int rowIndex) {
        RowState rs = state.boardState().sheetProgress().get(playerId).rowStates().get(rowIndex);
        return rs != null ? rs.crossedCells() : Set.of();
    }

    private List<Row> buildStandardRows() {
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

    private GameState buildStateInRoll(List<Row> rows, UUID... players) {
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progresses = new HashMap<>();
        for (UUID pid : players) {
            layouts.put(pid, new SheetLayout(rows));
            progresses.put(pid, new SheetProgress(new HashMap<>(), 0));
        }
        List<Die> dice = new ArrayList<>(List.of(
                new Die(Color.WHITE, 6), new Die(Color.WHITE, 6),
                new Die(Color.RED, 6), new Die(Color.YELLOW, 6),
                new Die(Color.GREEN, 6), new Die(Color.BLUE, 6)));
        BoardState board = new BoardState(progresses, dice, new HashMap<>());
        TurnState turn = new TurnState();
        turn.setActivePlayerId(players[0]);
        turn.setPhase(TurnPhase.ROLL);
        return new GameState(CardMode.SAME_CARDS, List.of(players), null, layouts, board, turn);
    }

    /** Fixed dice: white1=3, white2=4 (sum=7), red=2, yellow=3, green=4, blue=5. */
    private Random fixedRandom() {
        return new Random() {
            private final int[] seq = {2, 3, 1, 2, 3, 4}; // nextInt(6) → 3,4,2,3,4,5
            private int pos = 0;
            @Override public int nextInt(int bound) { return seq[pos++ % seq.length]; }
        };
    }
}
