package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.LongoVariantData;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.CardMode;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LongoTurnRulesTest {

    // Fixed dice: white1=3, white2=4 (sum=7), red=2, yellow=3, green=4, blue=5
    static final int FIXED_WHITE_SUM = 7;

    private LongoTurnRules rules;
    private UUID p1, p2;

    @BeforeEach
    void setUp() {
        rules = new LongoTurnRules(fixedRandom());
        p1 = UUID.randomUUID();
        p2 = UUID.randomUUID();
    }

    // --- lock: allMatch semantics ---

    @Test
    void cannotLockWhenOnlyLastCellCrossed() {
        GameState state = stateAfterRoll(p1, p1, p2);
        int rowIndex = 0;
        crossOnlyLastCell(state, p1, rowIndex);
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new DeclareLockIntentAction(p1, rowIndex)));
    }

    @Test
    void cannotLockWhenOnlySecondToLastCellCrossed() {
        GameState state = stateAfterRoll(p1, p1, p2);
        int rowIndex = 0;
        crossOnlySecondToLastCell(state, p1, rowIndex);
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new DeclareLockIntentAction(p1, rowIndex)));
    }

    @Test
    void canLockWhenBothLastCellsCrossedAndMinCrossesMet() {
        GameState state = stateAfterRoll(p1, p1, p2);
        int rowIndex = 0;
        crossBothRequiredCellsWithEnough(state, p1, rowIndex);
        assertDoesNotThrow(() -> rules.apply(state, new DeclareLockIntentAction(p1, rowIndex)));
    }

    @Test
    void cannotLockWithOnlyFiveCrossesEvenIfBothLastCellsCrossed() {
        GameState state = stateAfterRoll(p1, p1, p2);
        int rowIndex = 0;
        crossBothRequiredCellsWithExactly(state, p1, rowIndex, 5);
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new DeclareLockIntentAction(p1, rowIndex)));
    }

    // --- bonus action ---

    @Test
    void bonusActionOfferedWhenWhiteSumMatchesBonusNumber() {
        GameState state = stateAfterRoll(p1, p1, p2);
        // white sum = 7; give p1 bonus numbers [7, 12]
        state.setVariantData(new LongoVariantData(Map.of(p1, List.of(7, 12))));
        SheetLayout layout = state.sheetLayouts().get(p1);
        String firstCellId = layout.rows().get(0).cells().get(0).id();
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof CrossCellAction cc
                        && cc.rowIndex() == 0 && cc.cellId().equals(firstCellId)));
    }

    @Test
    void bonusActionNotOfferedWhenWhiteSumDoesNotMatchBonusNumber() {
        GameState state = stateAfterRoll(p1, p1, p2);
        // white sum = 7; p1's bonus numbers do NOT include 7
        state.setVariantData(new LongoVariantData(Map.of(p1, List.of(5, 12))));
        SheetLayout layout = state.sheetLayouts().get(p1);
        String firstCellId = layout.rows().get(0).cells().get(0).id();
        assertFalse(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof CrossCellAction cc
                        && cc.rowIndex() == 0 && cc.cellId().equals(firstCellId)));
    }

    @Test
    void bonusActionNotOfferedWhenWhiteWhiteAlreadyUsed() {
        GameState state = stateAfterRoll(p1, p1, p2);
        state.setVariantData(new LongoVariantData(Map.of(p1, List.of(7, 12))));
        state.turnState().activeTurnState().setWhiteWhiteUsed();
        SheetLayout layout = state.sheetLayouts().get(p1);
        String firstCellId = layout.rows().get(0).cells().get(0).id();
        assertFalse(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof CrossCellAction cc
                        && cc.rowIndex() == 0 && cc.cellId().equals(firstCellId)));
    }

    @Test
    void bonusActionTargetsRowWithFewestCrosses() {
        GameState state = stateAfterRoll(p1, p1, p2);
        state.setVariantData(new LongoVariantData(Map.of(p1, List.of(7, 12))));
        SheetLayout layout = state.sheetLayouts().get(p1);
        // Cross two cells in row 0 so row 1 has fewer crosses
        String row0Cell0 = layout.rows().get(0).cells().get(0).id();
        String row0Cell1 = layout.rows().get(0).cells().get(1).id();
        state.boardState().sheetProgress().get(p1)
                .updateRowState(0, new RowState(new HashSet<>(Set.of(row0Cell0, row0Cell1)), false));
        // Bonus should now target row 1 (0 crosses < 2 crosses), leftmost cell
        String row1FirstCell = layout.rows().get(1).cells().get(0).id();
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof CrossCellAction cc
                        && cc.rowIndex() == 1 && cc.cellId().equals(row1FirstCell)));
    }

    // --- lock config from factory ---

    @Test
    void longoRowsHaveMinCrossesSix() {
        GameState state = stateInRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        for (int i = 0; i < layout.rows().size(); i++) {
            assertEquals(6, layout.rows().get(i).lock().minCrosses());
        }
    }

    @Test
    void longoRowsHaveTwoRequiredCells() {
        GameState state = stateInRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        for (int i = 0; i < layout.rows().size(); i++) {
            assertEquals(2, layout.rows().get(i).lock().requiredCells().size());
        }
    }

    // -------------------------------------------------------------------------
    // State builders
    // -------------------------------------------------------------------------

    private GameState stateInRoll(UUID active, UUID... allPlayers) {
        List<UUID> players = Arrays.asList(allPlayers);
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID p : players) {
            layouts.put(p, longoLayout());
            progress.put(p, emptyProgress());
        }
        List<Die> dice = new ArrayList<>(List.of(
                new Die(Color.WHITE, 8), new Die(Color.WHITE, 8),
                new Die(Color.RED, 8), new Die(Color.YELLOW, 8),
                new Die(Color.GREEN, 8), new Die(Color.BLUE, 8)));
        BoardState board = new BoardState(progress, dice, new HashMap<>());
        TurnState turn = new TurnState();
        turn.setActivePlayerId(active);
        turn.setPhase(TurnPhase.ROLL);
        return new GameState(CardMode.DETERMINISTIC, players, new LongoVariantData(new HashMap<>()),
                layouts, board, turn);
    }

    private GameState stateAfterRoll(UUID active, UUID... allPlayers) {
        GameState state = stateInRoll(active, allPlayers);
        rules.apply(state, new RollAction(active));
        return state;
    }

    // -------------------------------------------------------------------------
    // Lock setup helpers
    // -------------------------------------------------------------------------

    private void crossOnlyLastCell(GameState state, UUID playerId, int rowIndex) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        Row row = layout.rows().get(rowIndex);
        LockCell lock = row.lock();
        String secondRequired = lock.requiredCells().get(0);
        String lastRequired   = lock.requiredCells().get(1);
        Set<String> crossed = new HashSet<>();
        crossed.add(lastRequired);
        for (Cell c : row.cells()) {
            if (crossed.size() >= lock.minCrosses()) break;
            if (!c.id().equals(secondRequired)) crossed.add(c.id());
        }
        state.boardState().sheetProgress().get(playerId)
                .updateRowState(rowIndex, new RowState(crossed, false));
    }

    private void crossOnlySecondToLastCell(GameState state, UUID playerId, int rowIndex) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        Row row = layout.rows().get(rowIndex);
        LockCell lock = row.lock();
        String secondRequired = lock.requiredCells().get(0);
        String lastRequired   = lock.requiredCells().get(1);
        Set<String> crossed = new HashSet<>();
        crossed.add(secondRequired);
        for (Cell c : row.cells()) {
            if (crossed.size() >= lock.minCrosses()) break;
            if (!c.id().equals(lastRequired)) crossed.add(c.id());
        }
        state.boardState().sheetProgress().get(playerId)
                .updateRowState(rowIndex, new RowState(crossed, false));
    }

    private void crossBothRequiredCellsWithEnough(GameState state, UUID playerId, int rowIndex) {
        crossBothRequiredCellsWithExactly(state, p1, rowIndex, 6);
    }

    private void crossBothRequiredCellsWithExactly(GameState state, UUID playerId, int rowIndex, int total) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        Row row = layout.rows().get(rowIndex);
        LockCell lock = row.lock();
        Set<String> required = new HashSet<>(lock.requiredCells());
        Set<String> crossed = new HashSet<>(required);
        for (Cell c : row.cells()) {
            if (crossed.size() >= total) break;
            crossed.add(c.id());
        }
        state.boardState().sheetProgress().get(playerId)
                .updateRowState(rowIndex, new RowState(crossed, false));
    }

    private void completeTurn(GameState state, UUID active, UUID... passives) {
        if (state.turnState().phase() == TurnPhase.ROLL) {
            rules.apply(state, new RollAction(active));
        }
        nl.adg.qwixx.action.CrossCellAction cross = rules.getValidActions(state, active)
                .stream().filter(a -> a instanceof nl.adg.qwixx.action.CrossCellAction)
                .map(a -> (nl.adg.qwixx.action.CrossCellAction) a)
                .findFirst().orElseThrow();
        rules.apply(state, cross);
        rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(active));
        for (UUID passive : passives) {
            if (state.turnState().passivePlayerQueue().contains(passive)) {
                rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(passive));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Layout factories
    // -------------------------------------------------------------------------

    private SheetLayout longoLayout() {
        List<Row> rows = new ArrayList<>();
        rows.add(longoAscendingRow(Color.RED));
        rows.add(longoAscendingRow(Color.YELLOW));
        rows.add(longoDescendingRow(Color.GREEN));
        rows.add(longoDescendingRow(Color.BLUE));
        return new SheetLayout(rows);
    }

    private Row longoAscendingRow(Color color) {
        Row row = new Row();
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Cell c = new Cell(i);
            c.setColor(color);
            c.setDisplayValue(String.valueOf(i + 2));
            c.setTags(List.of());
            row.addCell(c);
            cells.add(c);
        }
        Cell second = cells.get(13);
        Cell last   = cells.get(14);
        second.setClosingEligible(true);
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 6,
                List.of(second.id(), last.id())));
        return row;
    }

    private Row longoDescendingRow(Color color) {
        Row row = new Row();
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Cell c = new Cell(i);
            c.setColor(color);
            c.setDisplayValue(String.valueOf(16 - i));
            c.setTags(List.of());
            row.addCell(c);
            cells.add(c);
        }
        Cell second = cells.get(13);
        Cell last   = cells.get(14);
        second.setClosingEligible(true);
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 6,
                List.of(second.id(), last.id())));
        return row;
    }

    private SheetProgress emptyProgress() {
        return new SheetProgress(new HashMap<>(), 0);
    }

    private Random fixedRandom() {
        return new Random() {
            private final int[] seq = { 2, 3, 1, 2, 3, 4 }; // → 3,4,2,3,4,5
            private int pos = 0;
            @Override public int nextInt(int bound) {
                return seq[pos++ % seq.length];
            }
        };
    }
}