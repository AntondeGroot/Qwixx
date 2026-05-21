package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.RollResult;
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

    // --- lock eligibility ---
    //
    // Longo rows have two closing-eligible cells: "15" (second-to-last) and "16" (last)
    // for ascending rows, and "3" (second-to-last) and "2" (last) for descending rows.
    //
    // New rules (fix for reported bug where lock never activated after crossing "15"):
    //   • Crossing the LAST cell ("16"/"2") always enables locking, whether it is a
    //     permanent cross from a prior turn or just crossed this turn.
    //   • Crossing the SECOND-TO-LAST cell ("15"/"3") enables locking ONLY in the SAME
    //     turn it is crossed (while it is still in the undo buffer).  Once that turn ends
    //     without a lock declaration, the cell is a permanent cross and no longer triggers
    //     locking; only the last cell can then close the row.

    @Test
    void canLockByOnlyLastCellCrossedWithMinCrossesMet() {
        // "16" (ascending) or "2" (descending) alone must enable locking.
        // Uses the ascending RED row (index 0): crosses 6 regular cells + "16" = 7 total.
        GameState state = stateAfterRoll(p1, p1, p2);
        int rowIndex = 0;
        crossOnlyLastCell(state, p1, rowIndex);   // crosses "16" + 6 others, NOT "15"
        assertDoesNotThrow(() -> rules.apply(state, new DeclareLockIntentAction(p1, rowIndex)),
                "Crossing only the last required cell ('16') must be sufficient to lock");
    }

    @Test
    void canLockWhenSecondToLastCellIsCrossedInCurrentTurn() {
        // Crossing "15"/"3" THIS turn (pending in undo buffer) must enable locking.
        // Simulate: 6 regular permanent crosses, then cross "15" in the current turn.
        GameState state = stateAfterRoll(p1, p1, p2);
        int rowIndex = 0;
        SheetLayout layout = state.sheetLayouts().get(p1);
        Row row = layout.rows().get(rowIndex);
        LockCell lock = row.lock();

        // 6 permanent regular crosses (not closing-eligible)
        Set<String> perm = new HashSet<>();
        String secondLast = lock.closingCells().get(0); // "15"
        String last       = lock.closingCells().get(1); // "16"
        for (Cell c : row.cells()) {
            if (perm.size() >= 6) break;
            if (!c.id().equals(secondLast) && !c.id().equals(last)) perm.add(c.id());
        }
        state.boardState().sheetProgress().get(p1).updateRowState(rowIndex, new RowState(perm, false));

        // Cross "15" in the current turn (goes to both sheetProgress and undoBuffer).
        rules.apply(state, new CrossCellAction(p1, rowIndex, secondLast, DiceCombination.WHITE_WHITE));

        // Lock intent must now be valid (7 crosses total, "15" is in pending).
        assertDoesNotThrow(() -> rules.apply(state, new DeclareLockIntentAction(p1, rowIndex)),
                "Crossing the second-to-last cell ('15') in the current turn must enable locking");
    }

    @Test
    void cannotLockWhenSecondToLastCellIsPermanentAndLastNotCrossed() {
        // If "15"/"3" was crossed in a PREVIOUS turn without locking, it is now a
        // permanent cross.  Without "16"/"2", the lock must NOT be eligible.
        // This is the reported bug scenario: clicking "15" then the lock did nothing.
        GameState state = stateAfterRoll(p1, p1, p2);
        int rowIndex = 0;
        // Permanently cross "15" + 6 others (no "16" crossed).
        crossOnlySecondToLastCell(state, p1, rowIndex);
        // The undo buffer is empty at this point (crosses applied directly to sheetProgress).
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new DeclareLockIntentAction(p1, rowIndex)),
                "Permanent '15' without '16' must NOT enable locking — only the current-turn pending cross does");
    }

    @Test
    void canLockWhenBothLastCellsCrossedAndMinCrossesMet() {
        // Classic scenario: both "15" and "16" permanently crossed with 7+ total.
        GameState state = stateAfterRoll(p1, p1, p2);
        int rowIndex = 0;
        crossBothRequiredCellsWithEnough(state, p1, rowIndex);
        assertDoesNotThrow(() -> rules.apply(state, new DeclareLockIntentAction(p1, rowIndex)));
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

    @Test
    void afterCrossingBonusCell_otherTiedBonusCellAndRegularWhiteWhiteDisappear() {
        // RED and YELLOW both have 1 cross (tied fewest) so both offer a bonus cell.
        // After crossing RED's bonus cell the whiteWhiteUsed flag is set, which must:
        //   (a) remove YELLOW's bonus cell from valid actions, and
        //   (b) remove all regular white+white actions from valid actions.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.setVariantData(new LongoVariantData(Map.of(p1, List.of(FIXED_WHITE_SUM))));

        SheetLayout layout = state.sheetLayouts().get(p1);
        SheetProgress prog  = state.boardState().sheetProgress().get(p1);

        prog.updateRowState(0, new RowState(new HashSet<>(Set.of(
                layout.rows().get(0).cells().get(0).id())), false));          // RED:    1 cross at pos 0
        prog.updateRowState(1, new RowState(new HashSet<>(Set.of(
                layout.rows().get(1).cells().get(2).id())), false));          // YELLOW: 1 cross at pos 2
        // GREEN and BLUE must have MORE than 1 cross so RED and YELLOW are actually the fewest.
        Set<String> twoGreen = new HashSet<>(Set.of(
                layout.rows().get(2).cells().get(0).id(),
                layout.rows().get(2).cells().get(1).id()));
        prog.updateRowState(2, new RowState(twoGreen, false));                // GREEN:  2 crosses
        Set<String> twoBlue = new HashSet<>(Set.of(
                layout.rows().get(3).cells().get(0).id(),
                layout.rows().get(3).cells().get(1).id()));
        prog.updateRowState(3, new RowState(twoBlue, false));                 // BLUE:   2 crosses

        String redBonus    = layout.rows().get(0).cells().get(1).id();        // RED leftmost avail = pos 1
        String yellowBonus = layout.rows().get(1).cells().get(3).id();        // YELLOW leftmost avail = pos 3

        // Both bonus cells must be available before the cross.
        List<?> before = rules.getValidActions(state, p1);
        assertTrue(before.stream().anyMatch(a -> a instanceof CrossCellAction cc && cc.cellId().equals(redBonus)),
                "RED bonus must be available before crossing");
        assertTrue(before.stream().anyMatch(a -> a instanceof CrossCellAction cc && cc.cellId().equals(yellowBonus)),
                "YELLOW bonus must be available before crossing");

        // Cross RED's bonus cell.
        rules.apply(state, new CrossCellAction(p1, 0, redBonus, DiceCombination.WHITE_WHITE));

        List<CrossCellAction> after = rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .toList();

        assertFalse(after.stream().anyMatch(cc -> cc.cellId().equals(yellowBonus)),
                "YELLOW bonus must disappear after crossing RED's bonus cell");
        assertFalse(after.stream().anyMatch(cc -> cc.combination() == DiceCombination.WHITE_WHITE),
                "All white+white actions (bonus and regular) must disappear once whiteWhiteUsed is set");
    }

    @Test
    void afterCrossingRegularWhiteWhiteCell_bonusCellDisappears() {
        // When the active player uses their white+white move on a regular cell (value
        // matching the dice), the bonus cell must no longer be offered.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.setVariantData(new LongoVariantData(Map.of(p1, List.of(FIXED_WHITE_SUM))));

        SheetLayout layout = state.sheetLayouts().get(p1);
        SheetProgress prog  = state.boardState().sheetProgress().get(p1);

        // RED: 1 cross (fewest) → bonus targets RED, leftmost available = pos 1.
        prog.updateRowState(0, new RowState(new HashSet<>(Set.of(
                layout.rows().get(0).cells().get(0).id())), false));
        // YELLOW, GREEN, BLUE: 2 crosses each so they are not the fewest.
        for (int r = 1; r <= 3; r++) {
            Set<String> two = new HashSet<>();
            two.add(layout.rows().get(r).cells().get(0).id());
            two.add(layout.rows().get(r).cells().get(1).id());
            prog.updateRowState(r, new RowState(two, false));
        }

        String redBonusCell = layout.rows().get(0).cells().get(1).id(); // RED pos 1 (value "3")

        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof CrossCellAction cc && cc.cellId().equals(redBonusCell)),
                "RED bonus must be available before the regular white+white cross");

        // Pick any regular white+white action that is NOT the bonus cell.
        CrossCellAction regularWW = rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction cc
                        && cc.combination() == DiceCombination.WHITE_WHITE
                        && !cc.cellId().equals(redBonusCell))
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a regular white+white action"));

        rules.apply(state, regularWW);

        assertFalse(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof CrossCellAction cc && cc.cellId().equals(redBonusCell)),
                "Bonus cell must disappear after the regular white+white slot is consumed");
    }

    @Test
    void bonusActionOfferedForFewestCrossesRowEvenWhenDiceValueDiffers() {
        // Reproduces the reported scenario:
        //   RED (index 0): 4 crosses (positions 0–3, values "2"–"5")
        //   YELLOW (index 1): 1 cross at position 2 (value "4") → leftmost available = position 3 ("5")
        //   GREEN  (index 2): 2 crosses (positions 0–1)
        //   BLUE   (index 3): 3 crosses (positions 0–2)
        //   white sum = 11 (e.g. white1=5, white2=6), bonus number includes 11.
        //
        // Yellow has the fewest crosses (1).  The bonus action must be the cell at
        // position 3 (value "5") — a completely different value from the white sum.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.setVariantData(new LongoVariantData(Map.of(p1, List.of(11))));
        state.turnState().setCurrentRoll(
                new RollResult(5, 6, state.turnState().currentRoll().coloredDice())); // ww=11

        SheetLayout layout = state.sheetLayouts().get(p1);
        SheetProgress prog  = state.boardState().sheetProgress().get(p1);

        // RED: 4 crosses at positions 0–3
        Set<String> redCrossed = new HashSet<>();
        for (int i = 0; i < 4; i++) redCrossed.add(layout.rows().get(0).cells().get(i).id());
        prog.updateRowState(0, new RowState(redCrossed, false));

        // YELLOW: 1 cross at position 2 (value "4")
        Set<String> yellowCrossed = new HashSet<>();
        yellowCrossed.add(layout.rows().get(1).cells().get(2).id());
        prog.updateRowState(1, new RowState(yellowCrossed, false));

        // GREEN: 2 crosses at positions 0–1
        Set<String> greenCrossed = new HashSet<>();
        for (int i = 0; i < 2; i++) greenCrossed.add(layout.rows().get(2).cells().get(i).id());
        prog.updateRowState(2, new RowState(greenCrossed, false));

        // BLUE: 3 crosses at positions 0–2
        Set<String> blueCrossed = new HashSet<>();
        for (int i = 0; i < 3; i++) blueCrossed.add(layout.rows().get(3).cells().get(i).id());
        prog.updateRowState(3, new RowState(blueCrossed, false));

        // Bonus must target YELLOW (fewest = 1 cross), leftmost available = position 3 (value "5").
        String expectedBonusCell = layout.rows().get(1).cells().get(3).id();
        assertTrue(
                rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc
                                && cc.rowIndex() == 1
                                && cc.cellId().equals(expectedBonusCell)),
                "Bonus must offer YELLOW position 3 (value '5') — the leftmost available in the " +
                "fewest-crosses row — even though it doesn't match the white sum of 11");

        // The standard white+white action for value "11" must ALSO be available.
        assertTrue(
                rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc && cc.rowIndex() != 1),
                "Standard white+white cells (value '11') must also be in valid actions alongside the bonus");
    }

    @Test
    void bonusActionOfferedForAllRowsTiedAtFewestCrosses() {
        // Two rows share the fewest cross count but have different rightmost positions,
        // so their leftmost-available cells differ.  Both must appear in valid actions.
        //
        //   RED   (index 0): 1 cross at position 0 (value "2") → leftmost available = position 1 ("3")
        //   YELLOW (index 1): 1 cross at position 2 (value "4") → leftmost available = position 3 ("5")
        //   GREEN  (index 2): 3 crosses
        //   BLUE   (index 3): 3 crosses
        GameState state = stateAfterRoll(p1, p1, p2);
        state.setVariantData(new LongoVariantData(Map.of(p1, List.of(7))));
        // Keep the default white sum (3+4=7) so we don't need to override the roll.

        SheetLayout layout = state.sheetLayouts().get(p1);
        SheetProgress prog  = state.boardState().sheetProgress().get(p1);

        // RED: 1 cross at position 0
        Set<String> redCrossed = new HashSet<>(Set.of(layout.rows().get(0).cells().get(0).id()));
        prog.updateRowState(0, new RowState(redCrossed, false));

        // YELLOW: 1 cross at position 2
        Set<String> yellowCrossed = new HashSet<>(Set.of(layout.rows().get(1).cells().get(2).id()));
        prog.updateRowState(1, new RowState(yellowCrossed, false));

        // GREEN and BLUE: 3 crosses each (no longer tied for fewest)
        for (int rowIdx : new int[]{2, 3}) {
            Set<String> crossed = new HashSet<>();
            for (int i = 0; i < 3; i++) crossed.add(layout.rows().get(rowIdx).cells().get(i).id());
            prog.updateRowState(rowIdx, new RowState(crossed, false));
        }

        String redBonus    = layout.rows().get(0).cells().get(1).id(); // position 1 ("3")
        String yellowBonus = layout.rows().get(1).cells().get(3).id(); // position 3 ("5")

        List<CrossCellAction> bonusCrosses = rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .toList();

        assertTrue(
                bonusCrosses.stream().anyMatch(cc -> cc.rowIndex() == 0 && cc.cellId().equals(redBonus)),
                "Bonus must offer RED position 1 ('3') — leftmost available in RED (tied for fewest)");
        assertTrue(
                bonusCrosses.stream().anyMatch(cc -> cc.rowIndex() == 1 && cc.cellId().equals(yellowBonus)),
                "Bonus must offer YELLOW position 3 ('5') — leftmost available in YELLOW (tied for fewest)");
    }

    // --- lock config from factory ---

    @Test
    void longoRowsHaveMinCrossesSeven() {
        GameState state = stateInRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        for (int i = 0; i < layout.rows().size(); i++) {
            assertEquals(7, layout.rows().get(i).lock().minCrosses());
        }
    }

    @Test
    void longoRowsHaveTwoClosingCells() {
        GameState state = stateInRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        for (int i = 0; i < layout.rows().size(); i++) {
            assertEquals(2, layout.rows().get(i).lock().closingCells().size());
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
        String secondRequired = lock.closingCells().get(0);
        String lastRequired   = lock.closingCells().get(1);
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
        String secondRequired = lock.closingCells().get(0);
        String lastRequired   = lock.closingCells().get(1);
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
        // 6 normal + 2 closing = 8 total — a typical valid lock state
        crossBothRequiredCellsWithExactly(state, p1, rowIndex, 8);
    }

    private void crossBothRequiredCellsWithExactly(GameState state, UUID playerId, int rowIndex, int total) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        Row row = layout.rows().get(rowIndex);
        LockCell lock = row.lock();
        Set<String> required = new HashSet<>(lock.closingCells());
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

    /** Returns the first CrossCellAction offered to playerId, or throws if none. */
    private nl.adg.qwixx.action.CrossCellAction completeCrossAction(GameState state, UUID playerId) {
        return rules.getValidActions(state, playerId).stream()
                .filter(a -> a instanceof nl.adg.qwixx.action.CrossCellAction)
                .map(a -> (nl.adg.qwixx.action.CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CrossCellAction available for " + playerId));
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
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 7,
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
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 7,
                List.of(second.id(), last.id())));
        return row;
    }

    private SheetProgress emptyProgress() {
        return new SheetProgress(new HashMap<>(), 0);
    }

    // ── Closing-eligible cell reachability ────────────────────────────────────
    //
    // Longo has TWO closing cells per row (second-to-last and last; both required).
    // minCrosses=6 (non-closing crosses required before any closing cell).
    // Condition: normalCrossed >= 6, where normalCrossed = crossed - already-crossed required.
    //
    // Ascending RED row: closing cells "15" (pos 13) and "16" (pos 14).
    // Descending BLUE row: closing cells "3"  (pos 13) and "2"  (pos 14).

    @Test
    void longoFirstClosingCellNotOfferedWhenTooFewCrosses() {
        // BLUE descending: "3" (pos 13) + "2" (pos 14) are both required.
        // With 5 normal crosses: normalCrossed=5, 5+1=6 < 7 → "3" must NOT be offered.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.turnState().setCurrentRoll(
                new RollResult(1, 2, state.turnState().currentRoll().coloredDice())); // ww=3

        Row blue = state.sheetLayouts().get(p1).rows().get(3);
        Set<String> crosses = new HashSet<>();
        for (int i = 0; i < 5; i++) crosses.add(blue.cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(3, new RowState(crosses, false));

        Cell cell3 = blue.cells().get(13); // "3", closingEligible
        assertTrue(cell3.isClosingEligible());
        assertFalse(
                rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction c && c.cellId().equals(cell3.id())),
                "Longo '3': 5 normal, 5 < 6 (minCrosses) → must not be offered");
    }

    @Test
    void longoFirstClosingCellOfferedWhenEventualTotalReachesMinCrosses() {
        // With 6 normal crosses: normalCrossed=6, 6+1=7 = minCrosses → "3" must be offered.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.turnState().setCurrentRoll(
                new RollResult(1, 2, state.turnState().currentRoll().coloredDice()));

        Row blue = state.sheetLayouts().get(p1).rows().get(3);
        Set<String> crosses = new HashSet<>();
        for (int i = 0; i < 6; i++) crosses.add(blue.cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(3, new RowState(crosses, false));

        Cell cell3 = blue.cells().get(13);
        assertTrue(
                rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction c && c.cellId().equals(cell3.id())),
                "Longo '3': 6 normal, 6 >= 6 (minCrosses) → must be offered");
    }

    @Test
    void longoLastClosingCellNotOfferedWhenTotalStillBelowThreshold() {
        // "2" (pos 14) with "3" already crossed and 5 normal crosses:
        // normalCrossed=5, 5+1=6 < 7 → "2" must NOT be offered.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.turnState().setCurrentRoll(
                new RollResult(1, 1, state.turnState().currentRoll().coloredDice())); // ww=2

        Row blue = state.sheetLayouts().get(p1).rows().get(3);
        Set<String> crosses = new HashSet<>();
        for (int i = 0; i < 5; i++) crosses.add(blue.cells().get(i).id());
        crosses.add(blue.cells().get(13).id()); // "3" already crossed
        state.boardState().sheetProgress().get(p1).updateRowState(3, new RowState(crosses, false));

        Cell cell2 = blue.cells().get(14);
        assertTrue(cell2.isClosingEligible());
        assertFalse(
                rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction c && c.cellId().equals(cell2.id())),
                "Longo '2': 5 normal + '3' already crossed, normalCrossed=5, 5 < 6 (minCrosses) → must not be offered");
    }

    @Test
    void longoLastClosingCellOfferedWhenCrossingItMeetsMinCrosses() {
        // "2" with "3" crossed and 6 normal crosses: normalCrossed=6, 6+1=7 = minCrosses → offered.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.turnState().setCurrentRoll(
                new RollResult(1, 1, state.turnState().currentRoll().coloredDice()));

        Row blue = state.sheetLayouts().get(p1).rows().get(3);
        Set<String> crosses = new HashSet<>();
        for (int i = 0; i < 6; i++) crosses.add(blue.cells().get(i).id());
        crosses.add(blue.cells().get(13).id()); // "3" already crossed
        state.boardState().sheetProgress().get(p1).updateRowState(3, new RowState(crosses, false));

        Cell cell2 = blue.cells().get(14);
        assertTrue(
                rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction c && c.cellId().equals(cell2.id())),
                "Longo '2': 6 normal + '3' already crossed, normalCrossed=6, 6 >= 6 (minCrosses) → must be offered");
    }

    @Test
    void longoAscendingFirstClosingCellNotOfferedWhenTooFewCrosses() {
        // RED ascending: closing cells "15" (pos 13) and "16" (pos 14).
        // With 5 normal crosses: normalCrossed=5, 5+1=6 < 7 → "15" must NOT be offered.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.turnState().setCurrentRoll(
                new RollResult(8, 7, state.turnState().currentRoll().coloredDice())); // ww=15

        Row red = state.sheetLayouts().get(p1).rows().get(0);
        Set<String> crosses = new HashSet<>();
        for (int i = 0; i < 5; i++) crosses.add(red.cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(0, new RowState(crosses, false));

        Cell cell15 = red.cells().get(13);
        assertTrue(cell15.isClosingEligible());
        assertFalse(
                rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction c && c.cellId().equals(cell15.id())),
                "Longo '15': 5 normal, 5 < 6 (minCrosses) → must not be offered");
    }

    @Test
    void longoAscendingFirstClosingCellOfferedWithEnoughCrosses() {
        // With 6 normal crosses: normalCrossed=6, 6+1=7 = minCrosses → "15" offered.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.turnState().setCurrentRoll(
                new RollResult(8, 7, state.turnState().currentRoll().coloredDice()));

        Row red = state.sheetLayouts().get(p1).rows().get(0);
        Set<String> crosses = new HashSet<>();
        for (int i = 0; i < 6; i++) crosses.add(red.cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(0, new RowState(crosses, false));

        Cell cell15 = red.cells().get(13);
        assertTrue(
                rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction c && c.cellId().equals(cell15.id())),
                "Longo '15': 6 normal, 6 >= 6 (minCrosses) → must be offered");
    }

    // ── Passive declares lock intent for a LONGO row ─────────────────────────
    //
    // The lock mechanism is identical for LONGO rows: once the passive's conditions
    // are met the active must acknowledge, after which the row closes globally.

    @Test
    void passiveDeclares_longoRow_activeAcknowledges_closesRow() {
        GameState state = stateAfterRoll(p1, p1, p2);
        crossOnlyLastCell(state, p2, 0);  // p2 has enough crosses via last-cell only
        rules.apply(state, new DeclareLockIntentAction(p2, 0));

        // In the new architecture, DECLARE_LOCK_INTENT does not change phase.
        // Phase stays in ACTIVE_MOVE. The row closes at EVALUATE after all passives EndTurn.
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase(),
                "game must remain in ACTIVE_MOVE after passive declares lock intent for a LONGO row");
        // p2 is in the passive queue (they will EndTurn to trigger EVALUATE)
        assertTrue(state.turnState().passivePlayerQueue().contains(p2),
                "p2 must remain in the passive queue after declaring intent");

        // p1 (active) makes a move and EndTurns → PASSIVE_MOVE
        rules.apply(state, completeCrossAction(state, p1));
        rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(p1));  // → PASSIVE_MOVE
        // p2 EndTurns → EVALUATE → row closes
        rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(p2));

        assertTrue(state.isRowClosed(0),
                "LONGO row must close once all passives have EndTurned (EVALUATE)");
        assertEquals(TurnPhase.ROLL, state.turnState().phase(),
                "turn must advance to ROLL after the LONGO lock closes");
    }

    @Test
    void passiveDeclares_longoRow_activeCanCrossBeforeAcknowledging() {
        GameState state = stateAfterRoll(p1, p1, p2);
        crossOnlyLastCell(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));

        // In the new architecture, p1 (active) can cross a cell in ACTIVE_MOVE,
        // then EndTurns → PASSIVE_MOVE. p2 EndTurns → EVALUATE → row closes.
        nl.adg.qwixx.action.CrossCellAction cross = rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof nl.adg.qwixx.action.CrossCellAction)
                .map(a -> (nl.adg.qwixx.action.CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cross action for p1 in ACTIVE_MOVE"));

        rules.apply(state, cross);
        rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(p1));  // active EndTurns → PASSIVE_MOVE
        rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(p2));  // p2 EndTurns → EVALUATE → row closes

        nl.adg.qwixx.state.RowState rs = state.boardState().sheetProgress().get(p1)
                .rowStates().getOrDefault(cross.rowIndex(),
                        new nl.adg.qwixx.state.RowState(Set.of(), false));
        assertTrue(rs.crossedCells().contains(cross.cellId()),
                "active player's cross must be preserved after the LONGO lock closes");
        assertTrue(state.isRowClosed(0),
                "LONGO row must close after the active player EndTurns and p2 EndTurns (EVALUATE)");
    }

    // ── Bonus cross: cell display value ≠ white+white sum ────────────────────
    //
    // Regression guard: the server must accept a WHITE_WHITE cross for a bonus cell
    // even when the cell's display value does NOT equal the dice white+white sum.
    // (The frontend sends CROSS_WHITE_WHITE for bonus cells regardless of display value.)

    @Test
    void bonusCross_serverAcceptsWhiteWhiteCrossWhenDisplayValueDiffersFromDiceSum() {
        // Fixed dice: white1=3, white2=4 → white sum=7.
        // Bonus number = 7. Row 0 (RED ascending) starts at "2".
        // The bonus cell is the first uncrossed cell (displayValue="2"), which ≠ 7.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.setVariantData(new LongoVariantData(Map.of(p1, List.of(7))));

        SheetLayout layout     = state.sheetLayouts().get(p1);
        String      bonusCellId = layout.rows().get(0).cells().get(0).id(); // displayValue "2"

        // The server must accept this — it's a valid bonus cross even though "2" ≠ 7.
        assertDoesNotThrow(
                () -> rules.apply(state, new CrossCellAction(p1, 0, bonusCellId, DiceCombination.WHITE_WHITE)),
                "server must accept WHITE_WHITE cross for a bonus cell even when displayValue ≠ white+white sum");

        assertTrue(state.boardState().sheetProgress().get(p1)
                           .rowStates().get(0).crossedCells().contains(bonusCellId),
                "bonus cell must be crossed after the action is applied");
    }

    @Test
    void bonusCross_setsWhiteWhiteUsedSoItCannotBeUsedAgain() {
        // After a bonus cross, the white+white slot is consumed — no second cross allowed.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.setVariantData(new LongoVariantData(Map.of(p1, List.of(7))));

        SheetLayout layout     = state.sheetLayouts().get(p1);
        String      bonusCellId = layout.rows().get(0).cells().get(0).id();
        rules.apply(state, new CrossCellAction(p1, 0, bonusCellId, DiceCombination.WHITE_WHITE));

        assertTrue(state.turnState().activeTurnState().whiteWhiteUsed(),
                "whiteWhiteUsed must be set after a bonus cross");

        // Attempting a second WHITE_WHITE cross must be rejected.
        String secondCellId = layout.rows().get(0).cells().get(1).id(); // displayValue "3"
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p1, 0, secondCellId, DiceCombination.WHITE_WHITE)),
                "a second white+white cross must be rejected after the bonus cross");
    }

    // ── Passive co-closer: second-to-last cell crossed this turn ─────────────────
    //
    // Regression: endTurnInPassiveMove previously cleared the undo buffer BEFORE calling
    // evaluate(), so canCrossLock found no pending cross and returned false for the
    // non-declarant passive player — leaving them without a lock cross.
    // Fix: undo buffer is now cleared AFTER evaluate() in endTurnInPassiveMove.

    @Test
    void passive_crossesSecondToLastThisTurn_getsLockCrossed_asNonDeclarant() {
        // p1 (active) crosses BLUE "2" (last closing cell) this turn using white+BLUE die.
        // autoDetectClosingIntent fires at EndTurn → p1 is the declarant.
        // p2 (passive) crosses "3" (second-to-last) during the same turn.
        // At EVALUATE p2 is a non-declarant — canCrossLock must still return true because
        // the undo buffer is cleared AFTER evaluate, not before.
        GameState state = stateInRoll(p1, p1, p2);
        int rowIndex = 3; // BLUE descending: closing cells "3" (pos 13) and "2" (pos 14)

        Row blueRowP1 = state.sheetLayouts().get(p1).rows().get(rowIndex);
        Row blueRowP2 = state.sheetLayouts().get(p2).rows().get(rowIndex);
        LockCell lockP1 = blueRowP1.lock();
        LockCell lockP2 = blueRowP2.lock();
        String p1SecondLast = lockP1.closingCells().get(0); // p1's "3" cell ID
        String p1Last       = lockP1.closingCells().get(1); // p1's "2" cell ID
        String p2SecondLast = lockP2.closingCells().get(0); // p2's "3" cell ID
        String p2Last       = lockP2.closingCells().get(1); // p2's "2" cell ID

        // p1: 6 normal crosses (no closing cells) — p1 will cross "2" this turn to reach minCrosses.
        Set<String> p1Crossed = new HashSet<>();
        for (Cell c : blueRowP1.cells()) {
            if (p1Crossed.size() >= 6) break;
            if (!c.id().equals(p1SecondLast) && !c.id().equals(p1Last)) p1Crossed.add(c.id());
        }
        state.boardState().sheetProgress().get(p1).updateRowState(rowIndex, new RowState(p1Crossed, false));

        // p2: 6 normal crosses (no closing cells) — "3" not yet crossed at turn start.
        Set<String> p2Crossed = new HashSet<>();
        for (Cell c : blueRowP2.cells()) {
            if (p2Crossed.size() >= 6) break;
            if (!c.id().equals(p2SecondLast) && !c.id().equals(p2Last)) p2Crossed.add(c.id());
        }
        state.boardState().sheetProgress().get(p2).updateRowState(rowIndex, new RowState(p2Crossed, false));

        // Roll, then override: white+white=3 (p2 can cross "3"), BLUE die=1 (white+BLUE=2 for p1's "2").
        rules.apply(state, new RollAction(p1));
        Map<Color, Integer> colored = new HashMap<>(state.turnState().currentRoll().coloredDice());
        colored.put(Color.BLUE, 1);
        state.turnState().setCurrentRoll(new RollResult(1, 2, colored));

        // p1 crosses BLUE "2" this turn (white+BLUE=2) → hasActed=true, 7 total crosses.
        CrossCellAction crossTwo = rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.cellId().equals(p1Last))
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("BLUE '2' must be offered to p1 via white+BLUE=2"));
        rules.apply(state, crossTwo);

        // p1 EndTurns → autoDetectClosingIntent fires ("2" in undo buffer, 7 ≥ minCrosses)
        // → pendingClosures[BLUE] = p1 → PASSIVE_MOVE.
        rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(p1));
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase());

        // p2 crosses "3" (second-to-last closing cell, white+white=3) as passive.
        CrossCellAction crossThree = rules.getValidActions(state, p2).stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.cellId().equals(p2SecondLast))
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("BLUE '3' must be offered to p2 as passive"));
        rules.apply(state, crossThree);

        // Sanity: undo buffer is present before EndTurn.
        assertTrue(state.turnState().undoBuffer().containsKey(p2));

        // p2 EndTurns → EVALUATE.  The undo buffer is now cleared AFTER evaluate,
        // so canCrossLock sees "3" as a pending cross and returns true for p2.
        rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(p2));

        nl.adg.qwixx.state.RowState p2RowState = state.boardState()
                .sheetProgress().get(p2).rowStates()
                .getOrDefault(rowIndex, new nl.adg.qwixx.state.RowState(Set.of(), false));
        assertTrue(p2RowState.lockCrossed(),
                "p2 must have lockCrossed=true: crossed second-to-last cell this turn " +
                "even though undo buffer is cleared in endTurnInPassiveMove");

        assertTrue(state.isRowClosed(rowIndex),
                "BLUE row must be closed after EVALUATE");
    }

    // ── Longo: two rows close across two turns ──────────────────────────────────

    @Test
    void longo_twoRowsCloseAcrossTwoTurns() {
        // 2 players (p1=active, p2=passive), Longo variant.
        // p1 has enough crosses for row 0 (RED ascending, index 0).
        // p2 has both closing cells permanently crossed for row 1 (YELLOW ascending, index 1).
        //
        // New architecture flow:
        // Turn 1: p1 is active, declares row 0, EndTurns → PASSIVE_MOVE. p2 EndTurns → EVALUATE.
        //         row 0 closes. p2 becomes next active.
        // Turn 2: p2 rolls, declares row 1, EndTurns → PASSIVE_MOVE. p1 EndTurns → EVALUATE.
        //         row 1 closes. Game over.
        GameState state = stateAfterRoll(p1, p1, p2);

        // Give p1 enough crosses on row 0 (RED): both closing cells + fill to 8 total.
        {
            SheetLayout layout = state.sheetLayouts().get(p1);
            Row row = layout.rows().get(0);
            LockCell lock = row.lock();
            Set<String> closingCells = new HashSet<>(lock.closingCells());
            Set<String> crossed = new HashSet<>(closingCells);
            for (Cell c : row.cells()) {
                if (crossed.size() >= 8) break;
                crossed.add(c.id());
            }
            state.boardState().sheetProgress().get(p1).updateRowState(0, new RowState(crossed, false));
        }

        // Give p2 enough non-closing crosses on row 1 (YELLOW): exactly minCrosses non-closing cells.
        // No closing cells — p2 will cross the last closing cell this turn so auto-detection fires.
        {
            SheetLayout layout = state.sheetLayouts().get(p2);
            Row row = layout.rows().get(1);
            LockCell lock = row.lock();
            Set<String> closingCells = new HashSet<>(lock.closingCells());
            Set<String> crossed = new HashSet<>();
            for (Cell c : row.cells()) {
                if (crossed.size() >= lock.minCrosses()) break;
                if (!closingCells.contains(c.id())) crossed.add(c.id());
            }
            state.boardState().sheetProgress().get(p2).updateRowState(1, new RowState(crossed, false));
        }

        // Turn 1: p1 declares row 0, also crosses another cell (required to EndTurn), then EndTurns
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase(),
                "phase must stay ACTIVE_MOVE after DECLARE_LOCK_INTENT");
        rules.apply(state, completeCrossAction(state, p1)); // active must cross to satisfy EndTurn requirement
        rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(p1));
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase());

        // p2 EndTurns → EVALUATE → row 0 closes → Turn 2: p2 is active
        rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(p2));
        assertTrue(state.isRowClosed(0), "row 0 must close after EVALUATE");
        assertEquals(TurnPhase.ROLL, state.turnState().phase(), "Turn 2 must start with ROLL");
        assertEquals(p2, state.turnState().activePlayerId(), "p2 must be active for Turn 2");

        // Turn 2: p2 rolls, crosses the last closing cell of row 1 → auto-detection fires at EndTurn
        rules.apply(state, new nl.adg.qwixx.action.RollAction(p2));
        String p2LastClosing = state.sheetLayouts().get(p2).rows().get(1).lock().closingCells().getLast();
        rules.apply(state, new nl.adg.qwixx.action.CrossCellAction(p2, 1, p2LastClosing, DiceCombination.WHITE_WHITE));
        rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(p2)); // auto-detection declares row 1

        // p1 EndTurns → EVALUATE → row 1 closes → game over
        rules.apply(state, new nl.adg.qwixx.action.EndTurnAction(p1));
        assertTrue(state.isRowClosed(1), "row 1 must close after p1 EndTurns");
        assertTrue(state.gameOver(), "game must be over after 2 rows close");
    }

    private Random fixedRandom() {
        return rollSequence(2, 3, 1, 2, 3, 4); // → white1=3, white2=4, ..., blue=5
    }

    private Random rollSequence(int... seq) {
        return new Random() {
            private int pos = 0;
            @Override public int nextInt(int bound) { return seq[pos++ % seq.length]; }
        };
    }
}