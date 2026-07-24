package nl.adg.qwixx.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import nl.adg.qwixx.action.*;
import nl.adg.qwixx.data.*;
import nl.adg.qwixx.state.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link StandardTurnRules} internals that were under-pinned:
 *   • {@code luckyCrossEligibleColors} — the exact set of colours a 1+5 combo enables
 *     (white+white free choice, white+colour, colour+colour, and the "no combo" case);
 *   • {@code applyUndoLastCross} — the passive-undo and active-full-reset paths and their guards.
 * Both are driven with crafted, deterministic states and assert exact returns / resulting state.
 */
class StandardTurnRulesUnitTest {

    private StandardTurnRules rules;
    private UUID p1;
    private UUID p2;

    @BeforeEach
    void setUp() {
        rules = new StandardTurnRules(fixedRandom());
        p1 = UUID.randomUUID();
        p2 = UUID.randomUUID();
    }

    // ── luckyCrossEligibleColors ──────────────────────────────────────────────

    @Test
    void luckyCrossEligible_nullRollReturnsEmptySetNotNull() {
        Set<Color> result = rules.luckyCrossEligibleColors(null);
        assertNotNull(result, "a null roll yields an empty set, never null");
        assertTrue(result.isEmpty());
    }

    @Test
    void luckyCrossEligible_whiteWhite1And5ReturnsNullForFreeChoice() {
        assertNull(rules.luckyCrossEligibleColors(roll(1, 5, coloredNoCombo())),
                "white+white showing 1 and 5 is free choice → null (all colours eligible)");
    }

    @Test
    void luckyCrossEligible_whiteWhite5And1AlsoFreeChoice() {
        assertNull(rules.luckyCrossEligibleColors(roll(5, 1, coloredNoCombo())),
                "order does not matter — 5 and 1 is still free choice");
    }

    @Test
    void luckyCrossEligible_whiteAndColour1And5ReturnsThatColourOnly() {
        // white1=1, red=5 → only RED. Other dice chosen so they form no 1+5 pair.
        Map<Color, Integer> colored = colored(5, 3, 4, 2);
        assertEquals(Set.of(Color.RED), rules.luckyCrossEligibleColors(roll(1, 4, colored)));
    }

    @Test
    void luckyCrossEligible_white2AndColour5And1ReturnsThatColourOnly() {
        // white2=5, green=1 → only GREEN.
        Map<Color, Integer> colored = colored(2, 3, 1, 4);
        assertEquals(Set.of(Color.GREEN), rules.luckyCrossEligibleColors(roll(3, 5, colored)));
    }

    @Test
    void luckyCrossEligible_twoColoursOf1And5ReturnBothColours() {
        // red=1, blue=5 (yellow=2, green=3 form no pair) → RED and BLUE.
        Map<Color, Integer> colored = colored(1, 2, 3, 5);
        assertEquals(Set.of(Color.RED, Color.BLUE), rules.luckyCrossEligibleColors(roll(3, 4, colored)));
    }

    @Test
    void luckyCrossEligible_noComboReturnsEmptySet() {
        Set<Color> result = rules.luckyCrossEligibleColors(roll(3, 4, coloredNoCombo()));
        assertNotNull(result);
        assertTrue(result.isEmpty(), "no 1+5 pair anywhere → an empty set (nothing eligible)");
    }

    @Test
    void luckyCrossEligible_freeChoiceWinsEvenWhenColouredPairAlsoPresent() {
        // white+white = 1,5 (free choice) AND red=1, blue=5. Free choice must still return null.
        Map<Color, Integer> colored = colored(1, 2, 3, 5);
        assertNull(rules.luckyCrossEligibleColors(roll(1, 5, colored)),
                "free choice (null) takes priority over an otherwise-eligible coloured pair");
    }

    // ── getMinCrossesRequired ─────────────────────────────────────────────────

    @Test
    void getMinCrossesRequired_isFiveForStandard() {
        assertEquals(5, rules.getMinCrossesRequired(),
                "standard Qwixx requires 5 non-closing crosses before the lock");
    }

    // ── applyUndoLastCross — passive path ─────────────────────────────────────

    @Test
    void undoLastCross_passiveRemovesTheCrossAndClearsActedFlag() {
        GameState state = stateAfterRoll(p1, p1, p2);
        CrossCellAction cross = firstCross(state, p2); // passive white+white cross
        rules.apply(state, cross);
        assertTrue(state.turnState().passivesActed().contains(p2), "passive is marked acted after crossing");

        rules.apply(state, new UndoLastCrossAction(p2));

        assertFalse(crossedCells(state, p2, cross.rowIndex()).contains(cross.cellId()),
                "the passive's cross is removed from their sheet");
        assertFalse(state.turnState().passivesActed().contains(p2),
                "the passive is no longer marked acted after undo");
        assertFalse(state.turnState().undoBuffer().containsKey(p2),
                "the passive's pending-cross buffer is cleared");
    }

    @Test
    void undoLastCross_passiveKeepsActivePlayerUntouched() {
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, firstCross(state, p1)); // active makes a cross
        boolean activeUsedDieBefore = state.turnState().activeTurnState().hasActed();
        rules.apply(state, firstCross(state, p2)); // passive crosses

        rules.apply(state, new UndoLastCrossAction(p2));

        assertTrue(activeUsedDieBefore, "precondition: active had acted");
        assertTrue(state.turnState().activeTurnState().hasActed(),
                "undoing the passive's cross must not touch the active player's state");
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase());
    }

    @Test
    void undoLastCross_activePlayerBehavesAsFullReset() {
        GameState state = stateAfterRoll(p1, p1, p2);
        CrossCellAction cross = firstCross(state, p1);
        rules.apply(state, cross);
        assertTrue(state.turnState().activeTurnState().hasActed());

        rules.apply(state, new UndoLastCrossAction(p1));

        assertFalse(crossedCells(state, p1, cross.rowIndex()).contains(cross.cellId()),
                "active undo removes the cross");
        assertFalse(state.turnState().activeTurnState().whiteWhiteUsed(),
                "active undo also resets the dice-usage flags (full reset)");
        assertFalse(state.turnState().activeTurnState().colorDieUsed());
    }

    @Test
    void undoLastCross_throwsWhenThereIsNothingToUndo() {
        GameState state = stateAfterRoll(p1, p1, p2);
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new UndoLastCrossAction(p2)),
                "undo with no pending cross must be rejected");
    }

    @Test
    void undoLastCross_rejectedOutsideMovePhases() {
        GameState state = stateInRoll(p1, p1, p2); // ROLL phase
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new UndoLastCrossAction(p1)),
                "undo is only valid during ACTIVE_MOVE or PASSIVE_MOVE");
    }

    @Test
    void undoLastCross_cancelsPassivesDeclaredClosureForThatRow() {
        // Passive p2 has enough permanent crosses to declare, crosses the closing cell this turn,
        // declares intent, then undoes — the pending closure they declared must be cancelled.
        GameState state = stateAfterRoll(p1, p1, p2);
        Row row = state.sheetLayouts().get(p2).rows().getFirst();
        String closingId = row.lock().closingCells().getFirst();

        // 5 permanent non-closing crosses for p2.
        Set<String> permanent = new HashSet<>();
        for (Cell c : row.cells()) {
            if (permanent.size() >= 5) break;
            if (!c.id().equals(closingId)) permanent.add(c.id());
        }
        state.boardState().sheetProgress().get(p2).updateRowState(0, new RowState(permanent, false));

        // p2 crosses the closing cell this turn via the dice (WW=12 override).
        state.turnState().setCurrentRoll(new RollResult(6, 6, state.turnState().currentRoll().coloredDice()));
        rules.apply(state, new CrossCellAction(p2, 0, closingId, DiceCombination.WHITE_WHITE));
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        assertTrue(state.pendingClosures().containsKey(0), "precondition: p2 declared a closure");

        rules.apply(state, new UndoLastCrossAction(p2));

        assertFalse(state.pendingClosures().containsKey(0),
                "undoing the closing-cell cross cancels the closure p2 declared for that row");
        assertFalse(crossedCells(state, p2, 0).contains(closingId),
                "the closing cell is uncrossed after undo");
    }

    // ── builders / helpers (compact copies of the standard-layout fixtures) ────

    private GameState stateInRoll(UUID active, UUID... allPlayers) {
        List<UUID> players = Arrays.asList(allPlayers);
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID p : players) {
            layouts.put(p, standardLayout());
            progress.put(p, new SheetProgress(new HashMap<>(), 0));
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

    private GameState stateAfterRoll(UUID active, UUID... allPlayers) {
        GameState state = stateInRoll(active, allPlayers);
        rules.apply(state, new RollAction(active));
        return state;
    }

    private CrossCellAction firstCross(GameState state, UUID playerId) {
        return rules.getValidActions(state, playerId).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CrossCellAction available for " + playerId));
    }

    private Set<String> crossedCells(GameState state, UUID playerId, int rowIndex) {
        RowState rs = state.boardState().sheetProgress().get(playerId).rowStates().get(rowIndex);
        return rs != null ? rs.crossedCells() : Set.of();
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

    private static RollResult roll(int white1, int white2, Map<Color, Integer> colored) {
        return new RollResult(white1, white2, colored);
    }

    /** Coloured dice red,yellow,green,blue set to the given face values. */
    private static Map<Color, Integer> colored(int red, int yellow, int green, int blue) {
        Map<Color, Integer> m = new EnumMap<>(Color.class);
        m.put(Color.RED, red);
        m.put(Color.YELLOW, yellow);
        m.put(Color.GREEN, green);
        m.put(Color.BLUE, blue);
        return m;
    }

    /** Coloured dice that (together with any white) form no 1+5 pair. */
    private static Map<Color, Integer> coloredNoCombo() {
        return colored(2, 3, 4, 6);
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
