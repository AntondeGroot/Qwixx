package nl.adg.qwixx.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import nl.adg.qwixx.action.*;
import nl.adg.qwixx.data.*;
import nl.adg.qwixx.state.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Turn-rules integration tests specific to the Big Points variant.
 *
 * Fixed dice: white1=3, white2=4 (WW=7), red=2, yellow=3, green=4, blue=5.
 * Derived combinations:
 *   WW           = 7
 *   white+red    = 5 or 6    (primary die for BONUS-RY)
 *   white+yellow = 6 or 7    (secondary die for BONUS-RY)
 *   white+green  = 7 or 8    (primary die for BONUS-GB)
 *   white+blue   = 8 or 9    (secondary die for BONUS-GB)
 *
 * Bonus cells in the test layout:
 *   BONUS-RY "5" → white1+red=5     — PRIMARY color die only  (WW≠5, white+yellow≠5)
 *   BONUS-RY "7" → WW=7 AND white2+yellow=7 — both WW and SECONDARY color die
 *   BONUS-GB "7" → WW=7 AND white1+green=7  — both WW and PRIMARY color die
 *   BONUS-GB "9" → white2+blue=9    — SECONDARY color die only (WW≠9, white+green≠9)
 *
 * Layout row indices:
 *   0 = RED ascending   (regular, with lock)
 *   1 = BONUS-RY        (bonus, no lock; upper=0/RED, lower=2/YELLOW)
 *   2 = YELLOW ascending (regular, with lock)
 *   3 = GREEN descending (regular, with lock)
 *   4 = BONUS-GB        (bonus, no lock; upper=3/GREEN, lower=5/BLUE)
 *   5 = BLUE descending  (regular, with lock)
 */
class BigPointsTurnRulesTest {

    private static final int RED_ROW    = 0;
    private static final int BONUS_RY   = 1;
    private static final int YELLOW_ROW = 2;
    private static final int GREEN_ROW  = 3;
    private static final int BONUS_GB   = 4;
    private static final int BLUE_ROW   = 5;

    private StandardTurnRules rules;
    private UUID p1, p2;

    @BeforeEach
    void setUp() {
        rules = new StandardTurnRules(fixedRandom());
        p1 = UUID.randomUUID();
        p2 = UUID.randomUUID();
    }

    // -------------------------------------------------------------------------
    // Bonus cells offered as crossable actions
    // -------------------------------------------------------------------------

    @Test
    void activePlayers_wwDice_offersBonusCell_whenPrerequisiteMet() {
        // BONUS-RY cell "7" matches white+white=7.
        // Prereq: "7" must be permanently crossed in a neighbour row before the turn starts.
        GameState state = bigPointsStateInRoll(p1, p1, p2);
        crossCellWithValue(state, p1, RED_ROW, "7");        // satisfy prereq
        rules.apply(state, new RollAction(p1));             // snapshot captures crossed "7"

        List<GameAction> actions = rules.getValidActions(state, p1);
        boolean hasBonusWW = actions.stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.combination() == DiceCombination.WHITE_WHITE)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> cc.rowIndex() == BONUS_RY);
        assertTrue(hasBonusWW, "active player must be offered WHITE_WHITE for bonus cell when prereq met");
    }

    @Test
    void activePlayers_primaryColorDie_offersBonusCell_whenPrerequisiteMet() {
        // BONUS-RY cell "5" matches white1(3)+red(2)=5 → WHITE_COLOR via the primary (RED) die.
        // WW=7 ≠ 5 and white+yellow ≠ 5, so this is exclusively a color-die match.
        GameState state = bigPointsStateInRoll(p1, p1, p2);
        crossCellWithValue(state, p1, RED_ROW, "5");        // satisfy prereq in primary neighbour
        rules.apply(state, new RollAction(p1));

        List<GameAction> actions = rules.getValidActions(state, p1);
        boolean hasBonusColor = actions.stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.combination() == DiceCombination.WHITE_COLOR)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> cc.rowIndex() == BONUS_RY);
        assertTrue(hasBonusColor, "active player must be offered WHITE_COLOR for bonus cell via primary die");
    }

    @Test
    void activePlayers_secondaryColorDie_offersBonusCell_whenPrerequisiteMet() {
        // BONUS-GB cell "9" matches white2(4)+blue(5)=9 → WHITE_COLOR via the secondary (BLUE) die.
        // WW=7 ≠ 9 and white+green ∈ {7,8} ≠ 9, so this is exclusively a secondary-die match.
        GameState state = bigPointsStateInRoll(p1, p1, p2);
        crossCellWithValue(state, p1, BLUE_ROW, "9");       // satisfy prereq in secondary neighbour
        rules.apply(state, new RollAction(p1));

        List<GameAction> actions = rules.getValidActions(state, p1);
        boolean hasBonusColor = actions.stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.combination() == DiceCombination.WHITE_COLOR)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> cc.rowIndex() == BONUS_GB);
        assertTrue(hasBonusColor, "active player must be offered WHITE_COLOR for bonus cell via secondary die");
    }

    @Test
    void passivePlayers_wwDice_offersBonusCell_whenPrerequisiteMet() {
        // Passive players may cross bonus cells — but only with WHITE_WHITE.
        GameState state = bigPointsStateInRoll(p1, p1, p2);
        crossCellWithValue(state, p2, RED_ROW, "7");        // satisfy prereq for p2
        rules.apply(state, new RollAction(p1));             // snapshot captures p2's cross too

        List<GameAction> actions = rules.getValidActions(state, p2);
        boolean hasBonusWW = actions.stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.combination() == DiceCombination.WHITE_WHITE)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> cc.rowIndex() == BONUS_RY);
        assertTrue(hasBonusWW, "passive player must be offered WHITE_WHITE for bonus cell when prereq met");
    }

    @Test
    void passivePlayers_colorDie_notOfferedForBonusCell() {
        // Passive players must NOT receive WHITE_COLOR for bonus cells — that is active-only.
        // BONUS-RY "5" matches white+red (PRIMARY color die) but not WW (7≠5),
        // so a passive player receives no action for it at all.
        GameState state = bigPointsStateInRoll(p1, p1, p2);
        crossCellWithValue(state, p2, RED_ROW, "5");        // satisfy prereq for p2
        rules.apply(state, new RollAction(p1));

        List<GameAction> actions = rules.getValidActions(state, p2);
        boolean hasColorBonusForPassive = actions.stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.combination() == DiceCombination.WHITE_COLOR)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> cc.rowIndex() == BONUS_RY);
        assertFalse(hasColorBonusForPassive, "passive player must NOT be offered WHITE_COLOR for bonus cells");
    }

    @Test
    void bonusCell_notOffered_whenPrerequisiteNotMet() {
        // No permanent crosses in neighbour rows → prereq fails → bonus cells suppressed,
        // even when the dice value matches a bonus cell.
        GameState state = bigPointsStateAfterRoll(p1, p1, p2);

        List<GameAction> actions = rules.getValidActions(state, p1);
        boolean hasBonusAction = actions.stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> cc.rowIndex() == BONUS_RY || cc.rowIndex() == BONUS_GB);
        assertFalse(hasBonusAction, "bonus cell must not be offered when prerequisite is not met");
    }

    @Test
    void bonusCell_offeredAfterPrerequisiteMetInLowerNeighbour() {
        // The prereq may be met in either the upper OR the lower neighbour.
        // Test: prereq met in YELLOW (lower neighbour of BONUS-RY), not RED.
        GameState state = bigPointsStateInRoll(p1, p1, p2);
        crossCellWithValue(state, p1, YELLOW_ROW, "7");     // lower neighbour
        rules.apply(state, new RollAction(p1));

        List<GameAction> actions = rules.getValidActions(state, p1);
        boolean hasBonusWW = actions.stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.combination() == DiceCombination.WHITE_WHITE)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> cc.rowIndex() == BONUS_RY);
        assertTrue(hasBonusWW, "bonus cell must be offered when prereq is met in the lower neighbour");
    }

    @Test
    void bonusCell_notOffered_forValue_whenPrereqMetForDifferentValue() {
        // RED "5" is crossed — satisfies prereq for bonus "5" only.
        // WW=7 would match bonus "7" (position 1), but RED "7" is not crossed.
        GameState state = bigPointsStateInRoll(p1, p1, p2);
        crossCellWithValue(state, p1, RED_ROW, "5");
        rules.apply(state, new RollAction(p1));

        String bonus7id = bonusCellId(state, p1, BONUS_RY, "7");
        assertFalse(rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc
                                && cc.rowIndex() == BONUS_RY && cc.cellId().equals(bonus7id)),
                "bonus '7' must not be offered when only RED '5' is crossed (prereq is value-specific)");
    }

    @Test
    void bonusCell_offered_whenBothNeighboursCrossed() {
        // Both red (upper) and yellow (lower) have "7" crossed — two satisfied neighbours is valid.
        GameState state = bigPointsStateInRoll(p1, p1, p2);
        crossCellWithValue(state, p1, RED_ROW, "7");
        crossCellWithValue(state, p1, YELLOW_ROW, "7");
        rules.apply(state, new RollAction(p1));

        assertTrue(rules.getValidActions(state, p1).stream()
                        .filter(a -> a instanceof CrossCellAction cc
                                && cc.combination() == DiceCombination.WHITE_WHITE)
                        .map(a -> (CrossCellAction) a)
                        .anyMatch(cc -> cc.rowIndex() == BONUS_RY),
                "bonus cell must be offered when both neighbours have the value crossed");
    }

    @Test
    void bonusCell_progression_leftOfCrossedBonusCell_notOffered() {
        // Bonus "8" (position 2) already crossed; WW=7 would match bonus "7" (position 1).
        // Prereq is met (RED "7" crossed), but position 1 < rightmost 2 — progression blocks it.
        GameState state = bigPointsStateInRoll(p1, p1, p2);
        crossCellWithValue(state, p1, RED_ROW, "7");
        crossCellWithValue(state, p1, BONUS_RY, "8");
        rules.apply(state, new RollAction(p1));

        String bonus7id = bonusCellId(state, p1, BONUS_RY, "7");
        assertFalse(rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc
                                && cc.rowIndex() == BONUS_RY && cc.cellId().equals(bonus7id)),
                "bonus '7' must not be offered when bonus '8' (position 2) is already crossed");
    }

    // -------------------------------------------------------------------------
    // Bonus rows excluded from lock conditions
    // -------------------------------------------------------------------------

    @Test
    void bonusRows_notOfferedFor_declareLockIntent() {
        // A fully-crossed bonus row must never generate a DECLARE_LOCK_INTENT action —
        // bonus rows have no lock and can never be closed.
        GameState state = bigPointsStateAfterRoll(p1, p1, p2);
        // Cross all bonus cells directly so the row looks "complete"
        SheetLayout layout = state.sheetLayouts().get(p1);
        Set<String> allBonus = new HashSet<>();
        for (Cell c : layout.rows().get(BONUS_RY).cells()) allBonus.add(c.id());
        state.boardState().sheetProgress().get(p1).updateRowState(BONUS_RY, new RowState(allBonus, false));

        List<GameAction> actions = rules.getValidActions(state, p1);
        boolean hasBonusLockIntent = actions.stream()
                .filter(a -> a instanceof DeclareLockIntentAction)
                .map(a -> (DeclareLockIntentAction) a)
                .anyMatch(d -> d.rowIndex() == BONUS_RY || d.rowIndex() == BONUS_GB);
        assertFalse(hasBonusLockIntent, "DECLARE_LOCK_INTENT must never target a bonus row");
    }

    @Test
    void bonusRows_excluded_from_autoDetectClosingIntent() {
        // Crossing all cells in a bonus row and then ending the turn must NOT add the bonus
        // row to pendingClosures — auto-detect must skip rows where lock()==null.
        GameState state = bigPointsStateAfterRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        Set<String> allBonus = new HashSet<>();
        for (Cell c : layout.rows().get(BONUS_RY).cells()) allBonus.add(c.id());
        state.boardState().sheetProgress().get(p1).updateRowState(BONUS_RY, new RowState(allBonus, false));

        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2));

        assertFalse(state.isRowClosed(BONUS_RY),
                "bonus row must never be recorded as closed");
        assertFalse(state.pendingClosures().containsKey(BONUS_RY),
                "bonus row must never appear in pendingClosures");
    }

    @Test
    void bonusRows_do_not_count_toward_gameOver_twoRowCondition() {
        // Closing 2 regular rows ends the game; bonus rows must not count.
        // Setup: 1 regular row is globally closed + bonus row has all cells crossed.
        // Expected: game is NOT over (only 1 real row closed).
        GameState state = bigPointsStateInRoll(p1, p1, p2);
        state.boardState().closedRows().put(RED_ROW, p1);   // 1 regular row closed

        SheetLayout layout = state.sheetLayouts().get(p1);
        Set<String> allBonus = new HashSet<>();
        for (Cell c : layout.rows().get(BONUS_RY).cells()) allBonus.add(c.id());
        state.boardState().sheetProgress().get(p1).updateRowState(BONUS_RY, new RowState(allBonus, false));

        assertFalse(rules.isGameOver(state),
                "game must not end when only 1 regular row is closed (bonus row must not count)");
    }

    // -------------------------------------------------------------------------
    // Test state builders (Big Points variant)
    // -------------------------------------------------------------------------

    private GameState bigPointsStateInRoll(UUID active, UUID... allPlayers) {
        List<UUID> players = Arrays.asList(allPlayers);
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID p : players) {
            layouts.put(p, bigPointsLayout());
            progress.put(p, emptyProgress());
        }
        List<Die> dice = new ArrayList<>(List.of(
                new Die(Color.WHITE, 6), new Die(Color.WHITE, 6),
                new Die(Color.RED, 6), new Die(Color.YELLOW, 6),
                new Die(Color.GREEN, 6), new Die(Color.BLUE, 6)));
        BoardState board = new BoardState(progress, dice, new HashMap<>());
        TurnState turn = new TurnState();
        turn.setActivePlayerId(active);
        turn.setPhase(TurnPhase.ROLL);
        return new GameState(CardMode.SAME_CARDS, players, null, layouts, board, turn);
    }

    private GameState bigPointsStateAfterRoll(UUID active, UUID... allPlayers) {
        GameState state = bigPointsStateInRoll(active, allPlayers);
        rules.apply(state, new RollAction(active));
        return state;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Permanently cross the first cell in rowIndex whose displayValue equals value for the given player. */
    private void crossCellWithValue(GameState state, UUID playerId, int rowIndex, String value) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        String cellId = layout.rows().get(rowIndex).cells().stream()
                .filter(c -> c.displayValue().equals(value))
                .map(Cell::id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No cell with displayValue=" + value + " in row " + rowIndex));
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

    private CrossCellAction firstCrossAction(GameState state, UUID playerId) {
        return rules.getValidActions(state, playerId).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CrossCellAction available for " + playerId));
    }

    private SheetProgress emptyProgress() {
        return new SheetProgress(new HashMap<>(), 0);
    }

    // -------------------------------------------------------------------------
    // Layout factories
    // -------------------------------------------------------------------------

    /**
     * 6-row Big Points layout:
     *   0: RED ascending    (regular, lock)
     *   1: BONUS-RY         (bonus; primary=RED, secondary=YELLOW; cells "5","7","8","9")
     *   2: YELLOW ascending (regular, lock)
     *   3: GREEN descending (regular, lock)
     *   4: BONUS-GB         (bonus; primary=GREEN, secondary=BLUE; cells "5","7","8","9")
     *   5: BLUE descending  (regular, lock)
     */
    private SheetLayout bigPointsLayout() {
        List<Row> rows = new ArrayList<>();
        rows.add(ascendingRow(Color.RED));            // 0
        rows.add(bonusRow(Color.RED, Color.YELLOW));  // 1 — neighbours set below
        rows.add(ascendingRow(Color.YELLOW));         // 2
        rows.add(descendingRow(Color.GREEN));         // 3
        rows.add(bonusRow(Color.GREEN, Color.BLUE));  // 4 — neighbours set below
        rows.add(descendingRow(Color.BLUE));          // 5

        rows.get(BONUS_RY).setBonusRow(RED_ROW, YELLOW_ROW);
        rows.get(BONUS_GB).setBonusRow(GREEN_ROW, BLUE_ROW);
        return new SheetLayout(rows);
    }

    /** Bonus row with 4 cells at positions 0-3 with displayValues "5","7","8","9". */
    private Row bonusRow(Color primary, Color secondary) {
        Row row = new Row();
        String[] values = {"5", "7", "8", "9"};
        for (int i = 0; i < values.length; i++) {
            Cell c = new Cell(i);
            c.setColor(primary);
            c.setDisplayValue(values[i]);
            c.setTags(List.of(new CellTag.SecondaryColor(secondary)));
            row.addCell(c);
        }
        return row;
    }

    private Row ascendingRow(Color color) {
        Row row = new Row();
        Cell last = null;
        for (int i = 0; i < 11; i++) {
            Cell c = new Cell(i);
            c.setColor(color);
            c.setDisplayValue(String.valueOf(i + 2));
            c.setTags(List.of());
            row.addCell(c);
            last = c;
        }
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 6, List.of(last.id())));
        return row;
    }

    private Row descendingRow(Color color) {
        Row row = new Row();
        Cell last = null;
        for (int i = 0; i < 11; i++) {
            Cell c = new Cell(i);
            c.setColor(color);
            c.setDisplayValue(String.valueOf(12 - i));
            c.setTags(List.of());
            row.addCell(c);
            last = c;
        }
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 6, List.of(last.id())));
        return row;
    }

    private Random fixedRandom() {
        return new Random() {
            private final int[] seq = {2, 3, 1, 2, 3, 4};  // nextInt(6)+1 → 3,4,2,3,4,5
            private int pos = 0;
            @Override public int nextInt(int bound) {
                return seq[pos++ % seq.length];
            }
        };
    }
}
