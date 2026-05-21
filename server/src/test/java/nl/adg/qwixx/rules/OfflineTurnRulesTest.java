package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.TakePunishmentAction;
import nl.adg.qwixx.data.Cell;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OfflineTurnRulesTest {

    private OfflineTurnRules rules;
    private UUID p1, p2;

    @BeforeEach
    void setUp() {
        rules = new OfflineTurnRules();
        p1 = UUID.randomUUID();
        p2 = UUID.randomUUID();
    }

    // --- getValidActions ---

    @Test
    void validActionsIncludeReachableCell() {
        GameState state = buildState(p1, p2);
        String firstCellId = layout(state, p1).rows().get(0).cells().get(0).id();
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof CrossCellAction cc && cc.cellId().equals(firstCellId)));
    }

    @Test
    void validActionsExcludeCellsInClosedRow() {
        GameState state = buildState(p1, p2);
        state.boardState().closedRows().put(0, p1);
        String firstCellId = layout(state, p1).rows().get(0).cells().get(0).id();
        assertFalse(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof CrossCellAction cc && cc.rowIndex() == 0));
    }

    @Test
    void validActionsAlwaysIncludeTakePunishment() {
        GameState state = buildState(p1, p2);
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof TakePunishmentAction));
    }

    @Test
    void validActionsIncludeCrossLockWhenConditionsMet() {
        GameState state = buildState(p1, p2);
        crossEnoughForLock(state, p1, 0);
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof DeclareLockIntentAction di && di.rowIndex() == 0));
    }

    @Test
    void validActionsIncludeCrossLockEvenIfRowAlreadyGloballyClosed() {
        GameState state = buildState(p1, p2);
        crossEnoughForLock(state, p1, 0);
        // p2 globally closed row 0 already
        state.boardState().closedRows().put(0, p2);
        // p1 can still lock their own card for row 0
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof DeclareLockIntentAction di && di.rowIndex() == 0));
    }

    @Test
    void validActionsEmptyWhenGameOver() {
        GameState state = buildState(p1, p2);
        state.setGameOver(true);
        assertTrue(rules.getValidActions(state, p1).isEmpty());
    }

    // --- apply: CrossCellAction ---

    @Test
    void crossCellAddsToProgress() {
        GameState state = buildState(p1, p2);
        String cellId = layout(state, p1).rows().get(0).cells().get(0).id();
        rules.apply(state, new CrossCellAction(p1, 0, cellId, DiceCombination.WHITE_WHITE));
        assertTrue(state.boardState().sheetProgress().get(p1)
                .rowStates().get(0).crossedCells().contains(cellId));
    }

    @Test
    void crossCellEnforcesProgressionCheck() {
        GameState state = buildState(p1, p2);
        // Cross cell 2 first, then try to cross cell 1 (backwards — position 1 < rightmost 2)
        String cell2Id = layout(state, p1).rows().get(0).cells().get(2).id();
        String cell1Id = layout(state, p1).rows().get(0).cells().get(1).id();
        state.boardState().sheetProgress().get(p1)
                .updateRowState(0, new RowState(new HashSet<>(Set.of(cell2Id)), false));
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p1, 0, cell1Id, DiceCombination.WHITE_WHITE)));
    }

    @Test
    void crossCellBlockedForGloballyClosed() {
        GameState state = buildState(p1, p2);
        state.boardState().closedRows().put(0, p2);
        String cellId = layout(state, p1).rows().get(0).cells().get(0).id();
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p1, 0, cellId, DiceCombination.WHITE_WHITE)));
    }

    // --- apply: DeclareLockIntentAction ---

    @Test
    void crossLockMarksLockAndClosesRowGlobally() {
        GameState state = buildState(p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertTrue(state.boardState().sheetProgress().get(p1).rowStates().get(0).lockCrossed());
        assertTrue(state.isRowClosed(0));
    }

    @Test
    void crossLockAllowedEvenIfAnotherPlayerAlreadyClosed() {
        GameState state = buildState(p1, p2);
        crossEnoughForLock(state, p1, 0);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        // p1 can still lock row 0 even though p2 already globally closed it
        assertDoesNotThrow(() -> rules.apply(state, new DeclareLockIntentAction(p1, 0)));
        assertTrue(state.boardState().sheetProgress().get(p1).rowStates().get(0).lockCrossed());
    }

    @Test
    void crossLockRejectedWithoutRequiredCells() {
        GameState state = buildState(p1, p2);
        // only cross minCrosses-1 cells, no required cell
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new DeclareLockIntentAction(p1, 0)));
    }

    @Test
    void crossLockRejectedIfAlreadyLocked() {
        GameState state = buildState(p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new DeclareLockIntentAction(p1, 0)));
    }

    // --- apply: TakePunishmentAction ---

    @Test
    void takePunishmentIncreasesCount() {
        GameState state = buildState(p1, p2);
        rules.apply(state, new TakePunishmentAction(p1));
        assertEquals(1, state.boardState().sheetProgress().get(p1).punishments());
    }

    // --- game-over conditions ---

    @Test
    void gameOverAfterTwoDistinctClosedRows() {
        GameState state = buildState(p1, p2);
        crossEnoughForLock(state, p1, 0);
        crossEnoughForLock(state, p1, 1);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertFalse(state.gameOver());
        rules.apply(state, new DeclareLockIntentAction(p1, 1));
        assertTrue(state.gameOver());
    }

    @Test
    void gameOverAfterMaxPunishments() {
        GameState state = buildState(p1, p2);
        for (int i = 0; i < StandardTurnRules.MAX_PUNISHMENTS - 1; i++) {
            rules.apply(state, new TakePunishmentAction(p1));
            assertFalse(state.gameOver());
        }
        rules.apply(state, new TakePunishmentAction(p1));
        assertTrue(state.gameOver());
    }

    @Test
    void twoPlayersClosingSameRowCountsAsOneClosedRow() {
        GameState state = buildState(p1, p2);
        crossEnoughForLock(state, p1, 0);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        assertEquals(1, state.boardState().closedRows().size());
        assertFalse(state.gameOver()); // only 1 distinct row closed
    }

    @Test
    void gameOverWhenTwoPlayersBothCloseSameTwoRows() {
        GameState state = buildState(p1, p2);
        crossEnoughForLock(state, p1, 0);
        crossEnoughForLock(state, p2, 0);
        crossEnoughForLock(state, p1, 1);
        crossEnoughForLock(state, p2, 1);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, new DeclareLockIntentAction(p1, 1));
        assertTrue(state.gameOver());
        // p2 can still lock their card even though game is over? No — gameOver blocks getValidActions
        assertTrue(rules.getValidActions(state, p2).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GameState buildState(UUID... players) {
        List<UUID> playerList = Arrays.asList(players);
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID p : playerList) {
            layouts.put(p, standardLayout());
            progress.put(p, new SheetProgress(new HashMap<>(), 0));
        }
        List<Die> dice = new ArrayList<>(List.of(
                new Die(Color.WHITE, 6), new Die(Color.WHITE, 6),
                new Die(Color.RED, 6), new Die(Color.YELLOW, 6),
                new Die(Color.GREEN, 6), new Die(Color.BLUE, 6)));
        BoardState board = new BoardState(progress, dice, new HashMap<>());
        return new GameState(CardMode.DETERMINISTIC, playerList, null, layouts, board, null);
    }

    private SheetLayout layout(GameState state, UUID player) {
        return state.sheetLayouts().get(player);
    }

    private SheetLayout standardLayout() {
        List<Row> rows = new ArrayList<>();
        rows.add(ascendingRow(Color.RED));
        rows.add(ascendingRow(Color.YELLOW));
        rows.add(descendingRow(Color.GREEN));
        rows.add(descendingRow(Color.BLUE));
        return new SheetLayout(rows);
    }

    private Row ascendingRow(Color color) {
        Row row = new Row();
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            Cell c = new Cell(i);
            c.setColor(color);
            c.setDisplayValue(String.valueOf(i + 2));
            c.setTags(List.of());
            row.addCell(c);
            cells.add(c);
        }
        Cell last = cells.get(10);
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 6, List.of(last.id())));
        return row;
    }

    private Row descendingRow(Color color) {
        Row row = new Row();
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            Cell c = new Cell(i);
            c.setColor(color);
            c.setDisplayValue(String.valueOf(12 - i));
            c.setTags(List.of());
            row.addCell(c);
            cells.add(c);
        }
        Cell last = cells.get(10);
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 6, List.of(last.id())));
        return row;
    }

    // Cross minCrosses cells including the required last cell so the player qualifies to lock.
    private void crossEnoughForLock(GameState state, UUID playerId, int rowIndex) {
        Row row = layout(state, playerId).rows().get(rowIndex);
        LockCell lock = row.lock();
        Set<String> crossed = new HashSet<>(lock.closingCells());
        for (Cell c : row.cells()) {
            if (crossed.size() >= lock.minCrosses()) break;
            crossed.add(c.id());
        }
        state.boardState().sheetProgress().get(playerId)
                .updateRowState(rowIndex, new RowState(crossed, false));
    }
}