package nl.adg.qwixx.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.TakePunishmentAction;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    // -------------------------------------------------------------------------
    // Big Points — bonus prerequisite (offline mode)
    //
    // Layout row indices for the Big Points section:
    //   0 = RED ascending    (regular, lock)
    //   1 = BONUS-RY         (bonus; upper=0/RED, lower=2/YELLOW)
    //   2 = YELLOW ascending (regular, lock)
    //   3 = GREEN descending (regular, lock)
    //   4 = BONUS-GB         (bonus; upper=3/GREEN, lower=5/BLUE)
    //   5 = BLUE descending  (regular, lock)
    //
    // Bonus row cells (both BONUS-RY and BONUS-GB): "5","7","8","9" at positions 0,1,2,3.
    // -------------------------------------------------------------------------

    private static final int BP_RED    = 0;
    private static final int BP_BONUS_RY = 1;
    private static final int BP_YELLOW = 2;
    private static final int BP_GREEN  = 3;
    private static final int BP_BONUS_GB = 4;
    private static final int BP_BLUE   = 5;

    @Test
    void offlineBonus_notOffered_withoutPrerequisite() {
        // No neighbour row has any value crossed — all bonus cells must be suppressed.
        GameState state = buildBigPointsState(p1, p2);
        boolean anyBonus = rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof CrossCellAction cc
                        && (cc.rowIndex() == BP_BONUS_RY || cc.rowIndex() == BP_BONUS_GB));
        assertFalse(anyBonus, "bonus cells must not be offered when no neighbour value is crossed");
    }

    @Test
    void offlineBonus_offered_whenUpperNeighbourCrossed() {
        // RED (upper neighbour of BONUS-RY) has "7" crossed — bonus "7" must be offered.
        GameState state = buildBigPointsState(p1, p2);
        crossCellWithValue(state, p1, BP_RED, "7");

        String bonus7 = bonusCellId(state, p1, BP_BONUS_RY, "7");
        assertTrue(rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc
                                && cc.rowIndex() == BP_BONUS_RY && cc.cellId().equals(bonus7)),
                "bonus '7' must be offered when upper neighbour (RED) has '7' crossed");
    }

    @Test
    void offlineBonus_offered_whenLowerNeighbourCrossed() {
        // YELLOW (lower neighbour of BONUS-RY) has "7" crossed — bonus "7" must be offered.
        GameState state = buildBigPointsState(p1, p2);
        crossCellWithValue(state, p1, BP_YELLOW, "7");

        String bonus7 = bonusCellId(state, p1, BP_BONUS_RY, "7");
        assertTrue(rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc
                                && cc.rowIndex() == BP_BONUS_RY && cc.cellId().equals(bonus7)),
                "bonus '7' must be offered when lower neighbour (YELLOW) has '7' crossed");
    }

    @Test
    void offlineBonus_offered_whenBothNeighboursCrossed() {
        // Both RED and YELLOW have "7" crossed — two satisfied neighbours is valid.
        GameState state = buildBigPointsState(p1, p2);
        crossCellWithValue(state, p1, BP_RED, "7");
        crossCellWithValue(state, p1, BP_YELLOW, "7");

        String bonus7 = bonusCellId(state, p1, BP_BONUS_RY, "7");
        assertTrue(rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc
                                && cc.rowIndex() == BP_BONUS_RY && cc.cellId().equals(bonus7)),
                "bonus '7' must be offered when both neighbours have the value crossed");
    }

    @Test
    void offlineBonus_notOffered_whenNeighbourHasDifferentValueCrossed() {
        // RED "8","9" are crossed (further right in the neighbour row).
        // Bonus "5" requires RED "5" or YELLOW "5" — having higher values crossed does not help.
        GameState state = buildBigPointsState(p1, p2);
        crossCellWithValue(state, p1, BP_RED, "8");
        crossCellWithValue(state, p1, BP_RED, "9");

        String bonus5 = bonusCellId(state, p1, BP_BONUS_RY, "5");
        assertFalse(rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc
                                && cc.rowIndex() == BP_BONUS_RY && cc.cellId().equals(bonus5)),
                "bonus '5' must not be offered when only RED '8'/'9' are crossed (prereq is value-specific)");
    }

    @Test
    void offlineBonus_cannotCrossLeftOfCrossedBonusCell() {
        // Bonus "8" (position 2) is already crossed; RED "7" is crossed (prereq met for "7").
        // Bonus "7" (position 1) is to the LEFT of rightmost=2 — must not be offered or applied.
        GameState state = buildBigPointsState(p1, p2);
        crossCellWithValue(state, p1, BP_RED, "7");
        crossCellWithValue(state, p1, BP_BONUS_RY, "8");

        String bonus7 = bonusCellId(state, p1, BP_BONUS_RY, "7");
        assertFalse(rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc
                                && cc.rowIndex() == BP_BONUS_RY && cc.cellId().equals(bonus7)),
                "bonus '7' must not be offered when bonus '8' is already crossed (progression rule)");
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p1, BP_BONUS_RY, bonus7, DiceCombination.WHITE_WHITE)),
                "applying bonus '7' when '8' is crossed must throw (progression rule)");
    }

    @Test
    void offlineApply_rejectsBonus_whenPrerequisiteNotMet() {
        // No neighbour value crossed — applying a bonus cross must throw.
        GameState state = buildBigPointsState(p1, p2);
        String bonus7 = bonusCellId(state, p1, BP_BONUS_RY, "7");
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p1, BP_BONUS_RY, bonus7, DiceCombination.WHITE_WHITE)),
                "applying a bonus cell without prereq must throw IllegalMoveException");
    }

    @Test
    void offlineApply_acceptsBonus_whenPrerequisiteMet() {
        // RED "7" crossed — applying bonus "7" must succeed and record the cross.
        GameState state = buildBigPointsState(p1, p2);
        crossCellWithValue(state, p1, BP_RED, "7");
        String bonus7 = bonusCellId(state, p1, BP_BONUS_RY, "7");
        rules.apply(state, new CrossCellAction(p1, BP_BONUS_RY, bonus7, DiceCombination.WHITE_WHITE));
        assertTrue(state.boardState().sheetProgress().get(p1)
                        .rowStates().get(BP_BONUS_RY).crossedCells().contains(bonus7),
                "bonus '7' must be recorded in progress after a valid apply");
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
        return new GameState(CardMode.SAME_CARDS, playerList, null, layouts, board, null);
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

    // ── Big Points helpers ────────────────────────────────────────────────────

    private GameState buildBigPointsState(UUID... players) {
        List<UUID> playerList = Arrays.asList(players);
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID p : playerList) {
            layouts.put(p, bigPointsLayout());
            progress.put(p, new SheetProgress(new HashMap<>(), 0));
        }
        List<Die> dice = new ArrayList<>(List.of(
                new Die(Color.WHITE, 6), new Die(Color.WHITE, 6),
                new Die(Color.RED, 6), new Die(Color.YELLOW, 6),
                new Die(Color.GREEN, 6), new Die(Color.BLUE, 6)));
        BoardState board = new BoardState(progress, dice, new HashMap<>());
        return new GameState(CardMode.SAME_CARDS, playerList, null, layouts, board, null);
    }

    private SheetLayout bigPointsLayout() {
        List<Row> rows = new ArrayList<>();
        rows.add(ascendingRow(Color.RED));               // 0
        rows.add(bonusRowBP(Color.RED, Color.YELLOW));   // 1
        rows.add(ascendingRow(Color.YELLOW));            // 2
        rows.add(descendingRow(Color.GREEN));            // 3
        rows.add(bonusRowBP(Color.GREEN, Color.BLUE));   // 4
        rows.add(descendingRow(Color.BLUE));             // 5
        rows.get(BP_BONUS_RY).setBonusRow(BP_RED, BP_YELLOW);
        rows.get(BP_BONUS_GB).setBonusRow(BP_GREEN, BP_BLUE);
        return new SheetLayout(rows);
    }

    /** Bonus row with cells "5","7","8","9" at positions 0-3. */
    private Row bonusRowBP(Color primary, Color secondary) {
        Row row = new Row();
        for (int i = 0; i < 4; i++) {
            Cell c = new Cell(i);
            c.setColor(primary);
            c.setDisplayValue(new String[]{"5", "7", "8", "9"}[i]);
            c.setTags(List.of(new CellTag.SecondaryColor(secondary)));
            row.addCell(c);
        }
        return row;
    }

    /** Directly crosses the first cell in the given row whose displayValue matches, bypassing rules. */
    private void crossCellWithValue(GameState state, UUID playerId, int rowIndex, String value) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        String cellId = layout.rows().get(rowIndex).cells().stream()
                .filter(c -> c.displayValue().equals(value))
                .map(Cell::id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cell with value=" + value + " in row " + rowIndex));
        SheetProgress progress = state.boardState().sheetProgress().get(playerId);
        RowState current = progress.rowStates().getOrDefault(rowIndex, new RowState(Set.of(), false));
        Set<String> updated = new HashSet<>(current.crossedCells());
        updated.add(cellId);
        progress.updateRowState(rowIndex, new RowState(updated, false));
    }

    private String bonusCellId(GameState state, UUID playerId, int rowIndex, String value) {
        return state.sheetLayouts().get(playerId).rows().get(rowIndex).cells().stream()
                .filter(c -> c.displayValue().equals(value))
                .map(Cell::id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cell with value=" + value + " in row " + rowIndex));
    }

    // ── Standard helpers ──────────────────────────────────────────────────────

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
