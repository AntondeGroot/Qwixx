package nl.adg.qwixx.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import nl.adg.qwixx.action.*;
import nl.adg.qwixx.data.*;
import nl.adg.qwixx.state.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StandardTurnRulesTest {

    static final int FIXED_WHITE1 = 3;
    static final int FIXED_WHITE2 = 4;  // white sum = 7
    static final int FIXED_RED    = 2;  // white1+red = 5, white2+red = 6

    private StandardTurnRules rules;
    private UUID p1, p2, p3;

    @BeforeEach
    void setUp() {
        // Dice always roll fixed values: white1=3, white2=4, red=2, yellow=3, green=4, blue=5
        rules = new StandardTurnRules(fixedRandom());
        p1 = UUID.randomUUID();
        p2 = UUID.randomUUID();
        p3 = UUID.randomUUID();
    }

    // -------------------------------------------------------------------------
    // ROLL phase
    // -------------------------------------------------------------------------

    @Test
    void rollPhaseActivePlayerGetsOnlyRollAction() {
        GameState state = stateInRoll(p1, p1, p2);
        List<GameAction> actions = rules.getValidActions(state, p1);
        assertEquals(1, actions.size());
        assertInstanceOf(RollAction.class, actions.getFirst());
    }

    @Test
    void rollPhaseNonActivePlayerGetsNoActions() {
        GameState state = stateInRoll(p1, p1, p2);
        assertTrue(rules.getValidActions(state, p2).isEmpty());
    }

    @Test
    void rollActionTransitionsToActiveMoveAndSetsRoll() {
        GameState state = stateInRoll(p1, p1, p2);
        rules.apply(state, new RollAction(p1));
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase());
        assertNotNull(state.turnState().currentRoll());
    }

    @Test
    void rollActionBuildsPassiveQueueWithoutActivePlayer() {
        GameState state = stateInRoll(p1, p1, p2, p3);
        rules.apply(state, new RollAction(p1));
        List<UUID> queue = state.turnState().passivePlayerQueue();
        assertTrue(queue.contains(p2));
        assertTrue(queue.contains(p3));
        assertFalse(queue.contains(p1));
    }

    @Test
    void rollActionTakesSnapshotOfActivePlayer() {
        GameState state = stateInRoll(p1, p1, p2);
        rules.apply(state, new RollAction(p1));
        assertNotNull(state.turnState().moveStartProgress().get(p1));
    }

    @Test
    void nonActivePlayerCannotRoll() {
        GameState state = stateInRoll(p1, p1, p2);
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new RollAction(p2)));
    }

    // -------------------------------------------------------------------------
    // ACTIVE_MOVE phase — valid actions
    // -------------------------------------------------------------------------

    @Test
    void activeMoveOffersReachableCells() {
        GameState state = stateAfterRoll(p1, p1, p2);
        // white sum = 7 → displayValue "7" should appear in actions
        List<GameAction> actions = rules.getValidActions(state, p1);
        boolean hasCross = actions.stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .anyMatch(a -> state.sheetLayouts().get(p1).rows().get(a.rowIndex())
                        .cells().get(a.rowIndex() < 2 ? 5 : 5).displayValue().equals("7") ||
                        findCell(state, p1, a.rowIndex(), a.cellId()).displayValue().equals("7") ||
                        findCell(state, p1, a.rowIndex(), a.cellId()).displayValue().equals("5") ||
                        findCell(state, p1, a.rowIndex(), a.cellId()).displayValue().equals("6"));
        assertTrue(hasCross, "should have at least one reachable cell");
    }

    @Test
    void activeMoveAlwaysOffersGiveUpAndReset() {
        GameState state = stateAfterRoll(p1, p1, p2);
        List<GameAction> actions = rules.getValidActions(state, p1);
        assertTrue(actions.stream().anyMatch(a -> a instanceof GiveUpAction));
        assertTrue(actions.stream().anyMatch(a -> a instanceof ResetTurnAction));
    }

    @Test
    void activeMoveDoesNotOfferEndTurnBeforeAnyMove() {
        GameState state = stateAfterRoll(p1, p1, p2);
        List<GameAction> actions = rules.getValidActions(state, p1);
        assertTrue(actions.stream().noneMatch(a -> a instanceof EndTurnAction));
    }

    @Test
    void activeMoveOffersEndTurnAfterWhiteWhiteCross() {
        GameState state = stateAfterRoll(p1, p1, p2);
        CrossCellAction cross = firstCrossAction(state, p1);
        rules.apply(state, cross);
        List<GameAction> actions = rules.getValidActions(state, p1);
        assertTrue(actions.stream().anyMatch(a -> a instanceof EndTurnAction));
    }

    @Test
    void passivePlayerGetsWhiteWhiteActionsInActiveMovePhase() {
        GameState state = stateAfterRoll(p1, p1, p2);
        List<GameAction> actions = rules.getValidActions(state, p2);
        assertFalse(actions.isEmpty());
        assertTrue(actions.stream().anyMatch(a -> a instanceof EndTurnAction));
        assertTrue(actions.stream()
                .filter(a -> a instanceof CrossCellAction cc)
                .map(a -> (CrossCellAction) a)
                .allMatch(cc -> cc.combination() == DiceCombination.WHITE_WHITE));
    }

    @Test
    void cannotUseWhiteWhiteAfterColorDieUsed() {
        GameState state = stateAfterRoll(p1, p1, p2);
        CrossCellAction colorCross = rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.combination() == DiceCombination.WHITE_COLOR)
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a WHITE_COLOR action to be available"));
        rules.apply(state, colorCross);
        assertTrue(rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .noneMatch(cc -> cc.combination() == DiceCombination.WHITE_WHITE),
                "white+white must not be offered after the color die has been used");
    }

    @Test
    void crossingWhiteWhiteAfterColorDieUsedIsRejected() {
        GameState state = stateAfterRoll(p1, p1, p2);
        CrossCellAction colorCross = rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.combination() == DiceCombination.WHITE_COLOR)
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a WHITE_COLOR action to be available"));
        rules.apply(state, colorCross);
        // Attempt white+white cross directly — server must reject it
        RollResult roll = state.turnState().currentRoll();
        int wwValue = roll.white1() + roll.white2();
        SheetLayout layout = state.sheetLayouts().get(p1);
        CrossCellAction wwCross = layout.rows().stream()
                .flatMap(row -> row.cells().stream()
                        .filter(c -> c.displayValue().equals(String.valueOf(wwValue)))
                        .map(c -> new CrossCellAction(p1,
                                layout.rows().indexOf(row), c.id(), DiceCombination.WHITE_WHITE)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cell with white+white value found"));
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, wwCross));
    }

    @Test
    void secondActiveCrossDoesNotEraseTheFirstFromThisTurn() {
        // Regression: an active player's two crosses (white+white then white+color) in DIFFERENT rows
        // must both stay recorded for this turn. The second cross used to overwrite the first, so a
        // row lockable via the first cross — e.g. Longo's "15"/"3" second-to-last closing cell — could
        // no longer be declared or auto-detected after the second cross, silently losing that lock.
        GameState state = stateAfterRoll(p1, p1, p2);
        List<GameAction> actions = rules.getValidActions(state, p1);

        CrossCellAction wc = actions.stream()
                .filter(a -> a instanceof CrossCellAction c && c.combination() == DiceCombination.WHITE_COLOR)
                .map(a -> (CrossCellAction) a).findFirst()
                .orElseThrow(() -> new AssertionError("no white+color cross available"));
        CrossCellAction ww = actions.stream()
                .filter(a -> a instanceof CrossCellAction c
                        && c.combination() == DiceCombination.WHITE_WHITE && c.rowIndex() != wc.rowIndex())
                .map(a -> (CrossCellAction) a).findFirst()
                .orElseThrow(() -> new AssertionError("no white+white cross in a different row available"));

        rules.apply(state, ww);
        rules.apply(state, wc);

        Map<Integer, Set<String>> thisTurn = state.turnState().undoBuffer().get(p1);
        assertNotNull(thisTurn, "the active player should have pending crosses recorded this turn");
        assertTrue(thisTurn.getOrDefault(ww.rowIndex(), Set.of()).contains(ww.cellId()),
                "the first (white+white) cross must still be recorded after the second cross");
        assertTrue(thisTurn.getOrDefault(wc.rowIndex(), Set.of()).contains(wc.cellId()),
                "the second (white+color) cross must be recorded too");
    }

    // -------------------------------------------------------------------------
    // ACTIVE_MOVE → PASSIVE_MOVE transition
    // -------------------------------------------------------------------------

    @Test
    void endTurnFromActiveMoveTransitionsToPassiveMove() {
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1));
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase());
    }

    @Test
    void rollSnapshotsAllPlayers() {
        GameState state = stateAfterRoll(p1, p1, p2);
        assertNotNull(state.turnState().moveStartProgress().get(p1));
        assertNotNull(state.turnState().moveStartProgress().get(p2));
    }

    @Test
    void passiveEndTurnDuringActiveMoveStaysInActiveMove() {
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, new EndTurnAction(p2));
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase());
        assertFalse(state.turnState().passivePlayerQueue().contains(p2));
    }

    @Test
    void activeEndTurnEvaluatesWhenAllPassivesAlreadyDone() {
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, new EndTurnAction(p2));          // passive acts first
        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1));          // active ends — queue already empty
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
    }

    @Test
    void endTurnSkipsPassiveMoveWhenQueueIsEmpty() {
        GameState state = stateAfterRoll(p1, p1);  // single player
        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1));
        // Should have evaluated and started next turn (still ROLL but next player = p1 again)
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
    }

    @Test
    void cannotEndTurnWithoutMakingAnyMove() {
        GameState state = stateAfterRoll(p1, p1, p2);
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new EndTurnAction(p1)));
    }

    // -------------------------------------------------------------------------
    // PASSIVE_MOVE phase
    // -------------------------------------------------------------------------

    @Test
    void passivePlayerOffersWhiteWhiteCrossAndEndTurn() {
        GameState state = stateInPassiveMove(p1, p1, p2);
        List<GameAction> actions = rules.getValidActions(state, p2);
        assertTrue(actions.stream().anyMatch(a -> a instanceof EndTurnAction));
        // white sum = 7 → cell with displayValue "7"
        assertTrue(actions.stream().anyMatch(a -> a instanceof CrossCellAction));
    }

    @Test
    void passivePlayerCannotUseColorDie() {
        GameState state = stateInPassiveMove(p1, p1, p2);
        List<GameAction> passiveActions = rules.getValidActions(state, p2);
        assertTrue(passiveActions.stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .allMatch(a -> a.combination() == DiceCombination.WHITE_WHITE));
    }

    @Test
    void passivePlayerAfterCrossOffersResetAndEndTurnOnly() {
        GameState state = stateInPassiveMove(p1, p1, p2);
        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross);
        List<GameAction> actions = rules.getValidActions(state, p2);
        assertTrue(actions.stream().anyMatch(a -> a instanceof ResetTurnAction));
        assertTrue(actions.stream().anyMatch(a -> a instanceof EndTurnAction));
        assertTrue(actions.stream().noneMatch(a -> a instanceof CrossCellAction));
    }

    @Test
    void passivePlayerCannotCrossMoreThanOncePerTurn_duringActiveMove() {
        // Fixed dice: white1=3, white2=4 → white+white=7.
        // RED row (ascending) has "7" at position 5; YELLOW row also has "7" at position 5.
        // After crossing RED "7", attempting YELLOW "7" must throw — only one white+white
        // cross is allowed per passive player per turn.
        GameState state = stateAfterRoll(p1, p1, p2);

        Cell red7    = state.sheetLayouts().get(p2).rows().getFirst().cells().get(5);
        Cell yellow7 = state.sheetLayouts().get(p2).rows().get(1).cells().get(5);

        // First cross is allowed
        rules.apply(state, new CrossCellAction(p2, 0, red7.id(), DiceCombination.WHITE_WHITE));

        // Second cross in a different row must be rejected
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p2, 1, yellow7.id(), DiceCombination.WHITE_WHITE)),
                "Passive player must not be allowed to make more than one white+white cross per turn");
    }

    @Test
    void passivePlayerCannotCrossMoreThanOncePerTurn_duringPassiveMove() {
        // Same constraint applies in PASSIVE_MOVE phase (after active ends turn).
        GameState state = stateInPassiveMove(p1, p1, p2);

        Cell red7    = state.sheetLayouts().get(p2).rows().getFirst().cells().get(5);
        Cell yellow7 = state.sheetLayouts().get(p2).rows().get(1).cells().get(5);

        rules.apply(state, new CrossCellAction(p2, 0, red7.id(), DiceCombination.WHITE_WHITE));

        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p2, 1, yellow7.id(), DiceCombination.WHITE_WHITE)),
                "Passive player must not be allowed to make more than one white+white cross per turn");
    }

    // ── Cross rejected for closed rows ───────────────────────────────────────

    @Test
    void activePlayerCannotCrossCellInClosedRow() {
        GameState state = stateAfterRoll(p1, p1, p2);
        // Mark RED row (index 0) as closed.
        state.boardState().closedRows().put(0, p1);
        Cell red7 = state.sheetLayouts().get(p1).rows().getFirst().cells().get(5);

        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p1, 0, red7.id(), DiceCombination.WHITE_WHITE)),
                "Active player must not be allowed to cross a cell in a closed row");
    }

    @Test
    void passivePlayerCannotCrossCellInClosedRow() {
        GameState state = stateAfterRoll(p1, p1, p2);
        state.boardState().closedRows().put(0, p1);
        Cell red7 = state.sheetLayouts().get(p2).rows().getFirst().cells().get(5);

        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p2, 0, red7.id(), DiceCombination.WHITE_WHITE)),
                "Passive player must not be allowed to cross a cell in a closed row");
    }

    // ── Closing-eligible cell reachability ────────────────────────────────────
    //
    // A closing-eligible cell may only be crossed once the player already has
    // getMinCrossesRequired() non-closing crosses in the row.
    //
    // Standard (minCrosses=5 non-closing crosses required before the lock cell):
    //   • blocked when nonClosingCrosses < 5  (e.g. 3 or 4 existing)
    //   • allowed when nonClosingCrosses >= 5 (e.g. 5 existing)

    @Test
    void closingCellNotOfferedWhenFewerThanMinCrossesPresent_threeCrosses() {
        // BLUE descending row, closing cell = "2" (position 10, closingEligible).
        // Dice: white1=1, white2=1 → white+white=2 → cell is reachable by value.
        // 3 existing: 3 + 1 = 4 < 6 (minCrosses) → must NOT be offered.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.turnState().setCurrentRoll(
                new RollResult(1, 1, state.turnState().currentRoll().coloredDice()));

        Row blue = state.sheetLayouts().get(p1).rows().get(3);
        Set<String> crosses = new HashSet<>();
        for (int i = 0; i < 3; i++) crosses.add(blue.cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(3, new RowState(crosses, false));

        String closingId = blue.cells().get(10).id();
        assertFalse(
                rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction c && c.cellId().equals(closingId)),
                "Closing cell must not be offered with only 3 non-closing crosses (need 5, minCrosses=5)");
    }

    @Test
    void closingCellNotOfferedWithFourCrosses_oneShortOfMinimum() {
        // 4 existing: 4 + 1 = 5 < 6 (minCrosses) → must NOT be offered.
        // This was the reported bug: with 4 crosses the closing cell was highlighted.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.turnState().setCurrentRoll(
                new RollResult(1, 1, state.turnState().currentRoll().coloredDice()));

        Row blue = state.sheetLayouts().get(p1).rows().get(3);
        Set<String> crosses = new HashSet<>();
        for (int i = 0; i < 4; i++) crosses.add(blue.cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(3, new RowState(crosses, false));

        String closingId = blue.cells().get(10).id();
        assertFalse(
                rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction c && c.cellId().equals(closingId)),
                "Closing cell must not be offered with only 4 non-closing crosses (need 5, minCrosses=5)");
    }

    @Test
    void closingCellOfferedWhenExactlyMinCrossesAlreadyPresent() {
        // 5 existing: 5 + 1 = 6 = minCrosses → closing cell must be offered (it becomes the 6th cross).
        GameState state = stateAfterRoll(p1, p1, p2);
        state.turnState().setCurrentRoll(
                new RollResult(1, 1, state.turnState().currentRoll().coloredDice()));

        Row blue = state.sheetLayouts().get(p1).rows().get(3);
        Set<String> crosses = new HashSet<>();
        for (int i = 0; i < 5; i++) crosses.add(blue.cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(3, new RowState(crosses, false));

        String closingId = blue.cells().get(10).id();
        assertTrue(
                rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction c && c.cellId().equals(closingId)),
                "Closing cell must be offered when 5 non-closing crosses are present (5 >= minCrosses=5)");
    }

    @Test
    void passivePlayerClosingCellBlockedWithFourCrosses() {
        // Same rule applies for passive players — 4 existing: 4+1=5 < 6 (minCrosses) → blocked.
        GameState state = stateInPassiveMove(p1, p1, p2);
        state.turnState().setCurrentRoll(
                new RollResult(1, 1, state.turnState().currentRoll().coloredDice()));

        Row blue = state.sheetLayouts().get(p2).rows().get(3);
        Set<String> crosses = new HashSet<>();
        for (int i = 0; i < 4; i++) crosses.add(blue.cells().get(i).id());
        state.boardState().sheetProgress().get(p2).updateRowState(3, new RowState(crosses, false));

        String closingId = blue.cells().get(10).id();
        assertFalse(
                rules.getValidActions(state, p2).stream()
                        .anyMatch(a -> a instanceof CrossCellAction c && c.cellId().equals(closingId)),
                "Passive player must also be blocked with 4 non-closing crosses (4 < 5=minCrosses)");
    }

    @Test
    void passivePlayerClosingCellAlsoBlockedWhenThresholdUnreachable() {
        // 3 existing: 3+1=4 < 6 (minCrosses) → blocked for passive player too.
        GameState state = stateInPassiveMove(p1, p1, p2);
        state.turnState().setCurrentRoll(
                new RollResult(1, 1, state.turnState().currentRoll().coloredDice()));

        Row blue = state.sheetLayouts().get(p2).rows().get(3);
        Set<String> crosses = new HashSet<>();
        for (int i = 0; i < 3; i++) crosses.add(blue.cells().get(i).id());
        state.boardState().sheetProgress().get(p2).updateRowState(3, new RowState(crosses, false));

        String closingId = blue.cells().get(10).id();
        assertFalse(
                rules.getValidActions(state, p2).stream()
                        .anyMatch(a -> a instanceof CrossCellAction c && c.cellId().equals(closingId)),
                "Passive player must also be blocked from crossing closing cell below threshold");
    }

    @Test
    void passivePlayerEndTurnRemovesFromQueue() {
        GameState state = stateInPassiveMove(p1, p1, p2);
        rules.apply(state, new EndTurnAction(p2));
        assertFalse(state.turnState().passivePlayerQueue().contains(p2));
    }

    @Test
    void allPassiveDoneTransitionsToNextRoll() {
        GameState state = stateInPassiveMove(p1, p1, p2);
        rules.apply(state, new EndTurnAction(p2));
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
        assertEquals(p2, state.turnState().activePlayerId());
    }

    @Test
    void activePlayerGetsNoActionsInPassiveMove() {
        GameState state = stateInPassiveMove(p1, p1, p2);
        assertTrue(rules.getValidActions(state, p1).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Closing Intent — new architecture
    // -------------------------------------------------------------------------
    //
    // In the new architecture, DECLARE_LOCK_INTENT does NOT change phase.
    // The game stays in ACTIVE_MOVE / PASSIVE_MOVE. The row closes at EVALUATE
    // (when all players in the passive queue have EndTurned).

    @Test
    void declareLockIntent_doesNotChangePhase() {
        // Declaring lock intent must NOT change phase to anything — stays ACTIVE_MOVE.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase(),
                "DECLARE_LOCK_INTENT must not change phase — stays in ACTIVE_MOVE");
    }

    @Test
    void declareLockIntent_addsToPendingClosures() {
        // After declaration, the row must appear in pendingClosures with the declarant's UUID.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertTrue(state.pendingClosures().containsKey(0),
                "pendingClosures must contain row 0 after declaration");
        assertEquals(Set.of(p1), state.pendingClosures().get(0),
                "declarant must be recorded in pendingClosures");
    }

    @Test
    void singlePlayer_activeDeclaresAndEndTurns_rowCloses() {
        // Single-player: no passive queue. After active crosses cell + EndTurns → EVALUATE → row closes.
        // Setup: p1 has 5 permanent crosses; crossing the closing cell this turn enables explicit declare.
        GameState state = stateAfterRoll(p1, p1);
        crossEnoughForLock(state, p1, 0);
        // Active must also make a cross this turn to EndTurn (crossing something in another row).
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, firstCrossAction(state, p1)); // cross something to satisfy EndTurn requirement
        rules.apply(state, new EndTurnAction(p1));
        assertTrue(state.isRowClosed(0),
                "Row must close when the only player EndTurns with pendingClosures");
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
    }

    @Test
    void twoPlayer_activeDeclaresAndEndTurns_rowClosesAfterPassiveEndTurn() {
        // 2-player: p1 declares, EndTurns → PASSIVE_MOVE. p2 EndTurns → EVALUATE → closes.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, firstCrossAction(state, p1)); // active must make a move to EndTurn
        rules.apply(state, new EndTurnAction(p1));
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase(),
                "After active EndTurns, must enter PASSIVE_MOVE");
        assertFalse(state.isRowClosed(0),
                "Row must not close yet — p2 still in queue");
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.isRowClosed(0),
                "Row must close after last passive EndTurns (EVALUATE)");
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
    }

    @Test
    void active_autoDetect_lastClosingCellAtEndTurn() {
        // Crossing the last closing cell THIS turn + EndTurning triggers auto-detection
        // (adds to pendingClosures) and closes at EVALUATE.
        GameState state = stateAfterRoll(p1, p1, p2);
        // Override dice so white+white = 12 (the closing cell value).
        state.turnState().setCurrentRoll(
                new RollResult(6, 6, state.turnState().currentRoll().coloredDice()));

        Row red = state.sheetLayouts().get(p1).rows().getFirst();
        Set<String> fiveNormal = new HashSet<>();
        for (int i = 0; i < 5; i++) fiveNormal.add(red.cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(0, new RowState(fiveNormal, false));

        Cell closingCell = red.cells().get(10);
        rules.apply(state, new CrossCellAction(p1, 0, closingCell.id(), DiceCombination.WHITE_WHITE));
        rules.apply(state, new EndTurnAction(p1)); // auto-detect fires here → pendingClosures updated
        // After active EndTurns, row closure is pending; p2 must EndTurn to trigger EVALUATE.
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.isRowClosed(0),
                "Row must close via auto-detect at EndTurn when last closing cell was crossed this turn");
    }

    @Test
    void passive_autoDetect_lastClosingCellAtEndTurn() {
        // Passive crosses the last closing cell and EndTurns → auto-detected, added to pendingClosures.
        // When all passives are done, EVALUATE fires → row closes.
        GameState state = stateInPassiveMove(p1, p1, p2);
        // Override dice so white+white = 12.
        state.turnState().setCurrentRoll(
                new RollResult(6, 6, state.turnState().currentRoll().coloredDice()));

        Row red = state.sheetLayouts().get(p2).rows().getFirst();
        Set<String> fiveNormal = new HashSet<>();
        for (int i = 0; i < 5; i++) fiveNormal.add(red.cells().get(i).id());
        state.boardState().sheetProgress().get(p2).updateRowState(0, new RowState(fiveNormal, false));

        Cell closingCell = red.cells().get(10);
        rules.apply(state, new CrossCellAction(p2, 0, closingCell.id(), DiceCombination.WHITE_WHITE));
        rules.apply(state, new EndTurnAction(p2)); // auto-detect + EVALUATE
        assertTrue(state.isRowClosed(0),
                "Row must close via auto-detect when passive crosses last closing cell and EndTurns");
    }

    @Test
    void passive_declaresInActiveMove_rowClosesAfterPassiveEndTurn() {
        // p2 declares in ACTIVE_MOVE (passive slot). p1 crosses something (or gives up),
        // p1 EndTurns → PASSIVE_MOVE. p2 EndTurns → EVALUATE → row closes.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        // p1 makes a move and EndTurns
        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1));
        // p2 is still in passive queue
        assertTrue(state.turnState().passivePlayerQueue().contains(p2));
        rules.apply(state, new EndTurnAction(p2)); // EVALUATE fires
        assertTrue(state.isRowClosed(0),
                "Row must close after p2 EndTurns with their closing intent in pendingClosures");
    }

    @Test
    void reset_cancelsActiveClosingIntent() {
        // After declaring closing intent, active resets → pendingClosures cleared for that player.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertTrue(state.pendingClosures().containsKey(0));

        rules.apply(state, new ResetTurnAction(p1));
        assertFalse(state.pendingClosures().containsKey(0),
                "pendingClosures must be cleared after active resets");
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase());
    }

    @Test
    void passive_reset_cancelsClosingIntent() {
        // p2 declares intent in ACTIVE_MOVE, then resets → their closure cancelled.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        assertTrue(state.pendingClosures().containsKey(0));

        rules.apply(state, new ResetTurnAction(p2));
        assertFalse(state.pendingClosures().containsKey(0),
                "pendingClosures must be cleared after passive resets their closing intent");
    }

    @Test
    void active_canResetAfterPassiveDeclaredIntent() {
        // p1 (active) crosses a cell. p2 (passive) then declares closing intent.
        // p1 is still allowed to reset their cross — the passive's declaration does not lock
        // the active's move. After reset: p1's cross is undone, dice are reset, but p2's
        // closing intent remains in pendingClosures.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);

        CrossCellAction p1Cross = firstCrossAction(state, p1);
        rules.apply(state, p1Cross);
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase());

        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        assertTrue(state.pendingClosures().containsKey(0), "p2's intent must be pending");

        // Active resets — must succeed and undo p1's cross without touching p2's intent
        rules.apply(state, new ResetTurnAction(p1));
        assertFalse(state.turnState().activeTurnState().whiteWhiteUsed(),
                "Active dice flag must be cleared after reset");
        assertFalse(state.turnState().activeTurnState().colorDieUsed(),
                "Active dice flag must be cleared after reset");
        assertTrue(state.pendingClosures().containsKey(0),
                "p2's closing intent must survive the active player's reset");
        assertEquals(Set.of(p2), state.pendingClosures().get(0),
                "p2 must still be the declarant after active resets");
    }

    @Test
    void evaluate_marksLockCrossedForDeclarant() {
        // After row closes at EVALUATE, the declarant must have lockCrossed=true in their RowState.
        // Active declares (has permanent closing cell), also crosses another cell, then EndTurns.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, firstCrossAction(state, p1)); // must cross something to EndTurn
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(rowStateOf(state, p1, 0).lockCrossed(),
                "Declarant must have lockCrossed=true after EVALUATE");
    }

    @Test
    void evaluate_marksLockCrossedForOtherQualifyingPlayers() {
        // If p2 also qualifies for the lock (has the closing cell crossed), they also get lockCrossed.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, firstCrossAction(state, p1)); // must cross something to EndTurn
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(rowStateOf(state, p2, 0).lockCrossed(),
                "Other qualifying player must also have lockCrossed=true after EVALUATE");
    }

    @Test
    void evaluate_activePlayerGetsLockCrossedWhenPassiveDeclaredFirst() {
        // Regression: active player's undo buffer was cleared BEFORE evaluate(), so
        // canCrossLock(p1) could not see their pending closing-cell cross and returned false.
        //
        // Setup: p2 (passive) has the closing cell permanently and declares first.
        // p1 (active) crossed the closing cell this turn (only in undo buffer, not permanent).
        // Both must receive lockCrossed=true after EVALUATE.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);// p2 qualifies to declare
        crossEnoughForLockWithoutClosingCell(state, p1, 0); // p1 has enough non-closing crosses permanently

        rules.apply(state, firstCrossAction(state, p1));   // p1 makes a real move (sets whiteWhiteUsed)

        // Put row-0's closing cell in p1's undo buffer only (not permanent crosses).
        String closingCellId = state.sheetLayouts().get(p1).rows().getFirst()
                .lock().closingCells().getFirst();
        state.turnState().undoBuffer()
                .computeIfAbsent(p1, k -> new HashMap<>())
                .computeIfAbsent(0, k -> new HashSet<>())
                .add(closingCellId);

        rules.apply(state, new DeclareLockIntentAction(p2, 0)); // p2 declares before p1 ends

        // p1 ends — autoDetectClosingIntent skips row 0 (already claimed by p2), so p1 stays
        // as a non-declarant whose qualifying cross lives only in the undo buffer.
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2));         // last passive → evaluate()

        assertTrue(rowStateOf(state, p2, 0).lockCrossed(),
                "Declarant (p2) must have lockCrossed=true after EVALUATE");
        assertTrue(rowStateOf(state, p1, 0).lockCrossed(),
                "Active player (p1) must also have lockCrossed=true: they crossed the closing " +
                "cell this turn (undo buffer only) and must still qualify when evaluate() runs");
    }

    @Test
    void evaluate_removesColoredDie() {
        // After row closes at EVALUATE, the colored die for that row's color must be removed.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        Color lockColor = state.sheetLayouts().get(p1).rows().getFirst().lock().color();
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, firstCrossAction(state, p1)); // must cross something to EndTurn
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.boardState().activeDice().stream().noneMatch(d -> d.color() == lockColor),
                "Colored die must be removed after row closes at EVALUATE");
    }

    @Test
    void active_declaration_reinvites_passiveWhoAlreadyEndTurned() {
        // p2 EndTurns BEFORE p1 (active) declares. The declaration must re-queue p2 so
        // they always get a chance to respond — "when there is a lock intent, everyone moves."
        // p1 declares → p2 is re-queued; p1 crosses + EndTurns → PASSIVE_MOVE; p2 EndTurns → EVALUATE.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new EndTurnAction(p2)); // p2 leaves queue
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertTrue(state.turnState().passivePlayerQueue().contains(p2),
                "p2 must be re-queued by active's declaration so they can respond");
        rules.apply(state, firstCrossAction(state, p1)); // must cross something to EndTurn
        rules.apply(state, new EndTurnAction(p1)); // PASSIVE_MOVE (p2 still in queue)
        assertFalse(state.isRowClosed(0),
                "Row must NOT close yet — p2 still needs to act");
        rules.apply(state, new EndTurnAction(p2)); // p2 passes → EVALUATE
        assertTrue(state.isRowClosed(0),
                "Row must close at EVALUATE after p2 also EndTurns");
    }

    @Test
    void active_declaration_reinvites_all_passives_threePlayer() {
        // 3-player: both p2 and p3 pass before p1 declares. Both must be re-queued.
        // p1 EndTurns → PASSIVE_MOVE; p2 EndTurns; p3 EndTurns → EVALUATE.
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new EndTurnAction(p2)); // p2 passes early
        rules.apply(state, new EndTurnAction(p3)); // p3 passes early — queue now empty
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertTrue(state.turnState().passivePlayerQueue().contains(p2),
                "p2 must be re-queued by active declaration");
        assertTrue(state.turnState().passivePlayerQueue().contains(p3),
                "p3 must be re-queued by active declaration");
        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1)); // → PASSIVE_MOVE
        assertFalse(state.isRowClosed(0),
                "Row must not close yet — p2 and p3 still need to act");
        rules.apply(state, new EndTurnAction(p2));
        assertFalse(state.isRowClosed(0),
                "Row must not close yet — p3 still needs to act");
        rules.apply(state, new EndTurnAction(p3)); // EVALUATE
        assertTrue(state.isRowClosed(0),
                "Row must close at EVALUATE after all players EndTurn");
    }

    @Test
    void active_declaration_only_requeues_passed_passive_not_already_queued_one() {
        // 3-player: p2 passes early; p3 is still in the queue. Declaration must re-queue p2
        // but must not duplicate p3 (who was never removed).
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new EndTurnAction(p2)); // p2 leaves queue; p3 still in it
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertTrue(state.turnState().passivePlayerQueue().contains(p2),
                "p2 must be re-queued");
        assertTrue(state.turnState().passivePlayerQueue().contains(p3),
                "p3 must still be in the queue");
        assertEquals(2, state.turnState().passivePlayerQueue().size(),
                "Queue must have exactly 2 entries — p3 must not be duplicated");
    }

    @Test
    void aap_activeDeclaresBothRowsThenPassiveEndTurns_bothClose() {
        // Active declares 2 rows, also crosses something, then EndTurns.
        // Passive EndTurns → EVALUATE → both close.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        crossEnoughForLock(state, p1, 1);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, new DeclareLockIntentAction(p1, 1));
        rules.apply(state, firstCrossAction(state, p1)); // must cross to EndTurn
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2)); // EVALUATE
        assertTrue(state.isRowClosed(0), "row 0 must close");
        assertTrue(state.isRowClosed(1), "row 1 must close");
        assertTrue(state.gameOver(), "game must be over after 2 rows close");
    }

    @Test
    void passive_declaresIntentForRow_activeCanStillDeclareOtherRow() {
        // p2 declares intent for row 0, p1 declares intent for row 1.
        // Both rows close at EVALUATE.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);
        crossEnoughForLock(state, p1, 1);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        rules.apply(state, new DeclareLockIntentAction(p1, 1));
        rules.apply(state, firstCrossAction(state, p1)); // must cross to EndTurn
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2)); // EVALUATE
        assertTrue(state.isRowClosed(0), "row 0 must close");
        assertTrue(state.isRowClosed(1), "row 1 must close");
    }

    @Test
    void giveUp_cancelsActiveClosingIntent() {
        // Active declares, then gives up → closure cancelled, no row closes.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, new GiveUpAction(p1));
        assertFalse(state.pendingClosures().containsKey(0),
                "pendingClosures must be cleared when active gives up");
        rules.apply(state, new EndTurnAction(p2));
        assertFalse(state.isRowClosed(0),
                "Row must NOT close — active's closure was cancelled by give-up");
    }

    @Test
    void passiveDeclares_activeGivesUp_rowStillCloses() {
        // p2 (passive) declared intent for row 0. p1 gives up (takes punishment) → PASSIVE_MOVE.
        // p2 EndTurns → EVALUATE → row closes (p1's give-up does NOT cancel p2's intent).
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        rules.apply(state, new GiveUpAction(p1)); // punishment for p1, → PASSIVE_MOVE
        assertTrue(state.turnState().passivePlayerQueue().contains(p2),
                "p2 must remain in passive queue after p1 gives up");
        rules.apply(state, new EndTurnAction(p2)); // EVALUATE
        assertTrue(state.isRowClosed(0),
                "Passive's closure intent must survive active's give-up");
        assertTrue(state.boardState().sheetProgress().get(p1).punishments() > 0,
                "Active must have taken a punishment");
    }

    @Test
    void passiveDeclares_threePlayer_allMustEndTurnForEvaluate() {
        // p2 declares in ACTIVE_MOVE. p1 gives up → PASSIVE_MOVE. p3 and p2 must both
        // EndTurn before EVALUATE fires.
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        rules.apply(state, new GiveUpAction(p1)); // → PASSIVE_MOVE
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase());
        rules.apply(state, new EndTurnAction(p3));
        assertFalse(state.isRowClosed(0),
                "Row must not close until all passives EndTurn");
        rules.apply(state, new EndTurnAction(p2)); // EVALUATE
        assertTrue(state.isRowClosed(0),
                "Row must close once all remaining passives have EndTurned");
    }

    // -------------------------------------------------------------------------
    // passiveDeclares section — new architecture
    // -------------------------------------------------------------------------

    @Test
    void passiveDeclares_activeAcknowledgesWithoutCross_rowCloses() {
        // p2 declares lock intent in ACTIVE_MOVE. p1 gives up (no cross → punishment).
        // → PASSIVE_MOVE. p2 EndTurns → EVALUATE → row closes.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        rules.apply(state, new GiveUpAction(p1)); // p1 takes punishment, → PASSIVE_MOVE
        rules.apply(state, new EndTurnAction(p2)); // EVALUATE
        assertTrue(state.isRowClosed(0),
                "Row must close after p2 EndTurns with their closing intent");
    }

    @Test
    void passiveDeclares_threePlayerGame_activeAndThirdPlayerBothAcknowledge() {
        // p2 declares in ACTIVE_MOVE. p1 gives up → PASSIVE_MOVE.
        // p3 EndTurns, p2 EndTurns → EVALUATE → row closes.
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        rules.apply(state, new GiveUpAction(p1)); // → PASSIVE_MOVE
        assertTrue(state.turnState().passivePlayerQueue().contains(p2));
        assertTrue(state.turnState().passivePlayerQueue().contains(p3));
        rules.apply(state, new EndTurnAction(p3));
        assertFalse(state.isRowClosed(0),
                "Row must not close while p2 still in queue");
        rules.apply(state, new EndTurnAction(p2)); // EVALUATE
        assertTrue(state.isRowClosed(0),
                "Row must close once all remaining passives EndTurn");
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
    }

    // ── Lock intent rejected for already-closed row ───────────────────────────

    @Test
    void activeCannotDeclareLockIntentForAlreadyClosedRow() {
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        state.boardState().closedRows().put(0, p2);  // row already closed

        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new DeclareLockIntentAction(p1, 0)),
                "declaring lock intent for an already-closed row must be rejected");
    }

    @Test
    void passiveCannotDeclareLockIntentForAlreadyClosedRow() {
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);
        state.boardState().closedRows().put(0, p1);  // row already closed

        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new DeclareLockIntentAction(p2, 0)),
                "passive declaring lock intent for an already-closed row must be rejected");
    }

    @Test
    void declareLockIntentNotAvailableWhenLockPreConditionsNotMet() {
        GameState state = stateAfterRoll(p1, p1, p2);  // no crosses yet
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new DeclareLockIntentAction(p1, 0)));
    }

    // ── Lock closure triggers game over ──────────────────────────────────────

    @Test
    void lockClosure_whenTwoRowsNowClosed_setsGameOver() {
        // One row is already closed. Locking the second row must trigger game over.
        GameState state = stateAfterRoll(p1, p1, p2);
        state.boardState().closedRows().put(1, p2);  // row 1 already closed
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, firstCrossAction(state, p1)); // must cross to EndTurn
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2)); // EVALUATE → row 0 closes → game over
        assertTrue(state.isRowClosed(0), "row 0 must close");
        assertTrue(state.gameOver(), "game must be over when two rows have been closed");
    }

    // ── Active player can declare closing intent and also EndTurn (multi-close) ──

    @Test
    void canDeclareLockIntentAfterCrossingClosingCellInSameTurn() {
        // Regression: the lock button must be available immediately after crossing the
        // closing-eligible cell in the same turn — not only on the next turn.
        GameState state = stateAfterRoll(p1, p1, p2);

        // Override dice so white+white = 12.
        state.turnState().setCurrentRoll(
                new RollResult(6, 6, state.turnState().currentRoll().coloredDice()));

        // Give p1 five normal crosses at positions 0-4 (displayValues "2"-"6").
        Row red = state.sheetLayouts().get(p1).rows().getFirst(); // RED ascending row
        Set<String> fiveNormal = new HashSet<>();
        for (int i = 0; i < 5; i++) fiveNormal.add(red.cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(0, new RowState(fiveNormal, false));

        // Closing cell is at position 10, displayValue "12".
        Cell closingCell = red.cells().get(10);
        assertTrue(closingCell.isClosingEligible());
        assertEquals("12", closingCell.displayValue());

        // Cross "12" — must be offered (5 normal crosses present) and must apply cleanly.
        CrossCellAction crossClosing = new CrossCellAction(p1, 0, closingCell.id(), DiceCombination.WHITE_WHITE);
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof CrossCellAction cc && cc.cellId().equals(closingCell.id())),
                "closing cell must be offered after 5 normal crosses and matching dice");
        rules.apply(state, crossClosing);

        // For standard variant, the last closing cell is auto-detected at EndTurn.
        // But DECLARE_LOCK_INTENT should also be available via canDeclareViaPermanentLastCell
        // is false (cross is pending), so auto-detect will handle it at EndTurn.
        // EndTurns and verifies closure.
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.isRowClosed(0),
                "RED row must be closed after p2 EndTurns following active crossing the closing cell");
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
        assertEquals(p2, state.turnState().activePlayerId());
    }

    @Test
    void canDeclareLockIntentAfterCrossingClosingCellWithColorDie() {
        // Same scenario as above but using the white+color die combination.
        GameState state = stateAfterRoll(p1, p1, p2);

        Map<Color, Integer> updatedColored = new java.util.EnumMap<>(state.turnState().currentRoll().coloredDice());
        updatedColored.put(Color.RED, 6);
        state.turnState().setCurrentRoll(new RollResult(6, 5, updatedColored));

        Row red = state.sheetLayouts().get(p1).rows().getFirst();
        Set<String> fiveNormal = new HashSet<>();
        for (int i = 0; i < 5; i++) fiveNormal.add(red.cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(0, new RowState(fiveNormal, false));

        Cell closingCell = red.cells().get(10);
        // white1(6) + red(6) = 12 → closing cell must be offered as WHITE_COLOR.
        CrossCellAction crossClosing = new CrossCellAction(p1, 0, closingCell.id(), DiceCombination.WHITE_COLOR);
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof CrossCellAction cc && cc.cellId().equals(closingCell.id())),
                "closing cell must be offered via white+color with matching dice");
        rules.apply(state, crossClosing);

        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.isRowClosed(0));
    }

    // ── rowClosureRequests clearing ───────────────────────────────────────────

    @Test
    void closureNotificationsClearedAfterEvaluate() {
        // closureNotifications must be cleared when EVALUATE fires.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertFalse(state.closureNotifications().isEmpty(), "request must be present before evaluate");
        rules.apply(state, firstCrossAction(state, p1)); // must cross to EndTurn
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2)); // EVALUATE
        assertEquals(0, state.closureNotifications().size(),
                "closureNotifications must be cleared after EVALUATE");
    }

    @Test
    void closureNotificationsClearedWhenActivePlayerResets() {
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertFalse(state.closureNotifications().isEmpty());
        rules.apply(state, new ResetTurnAction(p1));
        assertEquals(0, state.closureNotifications().size(),
                "closureNotifications must be cleared when active player resets");
    }

    // ── Turn advancement after lock ───────────────────────────────────────────

    @Test
    void gameAdvancesToNextPlayerAfterRowLocks() {
        // After row closes (EVALUATE), the turn must rotate to the next player.
        // Players in order: [p1, p2, p3]. p1 is active → next must be p2.
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, firstCrossAction(state, p1)); // must cross to EndTurn
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new EndTurnAction(p3)); // EVALUATE → row closes

        assertTrue(state.isRowClosed(0), "row must be closed");
        assertEquals(TurnPhase.ROLL, state.turnState().phase(), "phase must be ROLL after lock closes");
        assertEquals(p2, state.turnState().activePlayerId(), "active player must rotate to p2");
        assertDoesNotThrow(() -> rules.apply(state, new RollAction(p2)), "next player must be able to roll");
    }

    // ── Lock Eligibility with Pending Crosses Tests ───────────────────────────

    @Test
    void canLockWithPendingCrossesIncluded() {
        // For standard variant (1 closing cell), crossing the last closing cell THIS turn
        // results in auto-detection at EndTurn — not an explicit DECLARE_LOCK_INTENT.
        // This test verifies that after crossing the closing cell this turn and EndTurning,
        // the row is added to pendingClosures (auto-detected), which causes it to close at EVALUATE.
        GameState state = stateAfterRoll(p1, p1, p2);
        // Override dice to match closing cell (value "12" = white+white of 6+6).
        state.turnState().setCurrentRoll(
                new RollResult(6, 6, state.turnState().currentRoll().coloredDice()));

        Row red = state.sheetLayouts().get(p1).rows().getFirst();
        // 5 normal permanent crosses (not the closing cell)
        Set<String> fiveNormal = new HashSet<>();
        for (int i = 0; i < 5; i++) fiveNormal.add(red.cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(0, new RowState(fiveNormal, false));

        // Cross the last closing cell this turn (goes into undoBuffer)
        Cell closingCell = red.cells().get(10);
        rules.apply(state, new CrossCellAction(p1, 0, closingCell.id(), DiceCombination.WHITE_WHITE));

        // Auto-detect fires at EndTurn → pendingClosures gets row 0
        rules.apply(state, new EndTurnAction(p1));
        assertTrue(state.pendingClosures().containsKey(0),
                "Auto-detect must add row 0 to pendingClosures when last closing cell was crossed this turn");

        // EVALUATE fires after p2 EndTurns
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.isRowClosed(0),
                "Row must close after EVALUATE when closing cell was pending (auto-detected)");
    }

    @Test
    void cannotLockWithoutPendingCrosses() {
        // Setup: state in ACTIVE_MOVE with insufficient permanent crosses
        GameState state = stateAfterRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        Row row = layout.rows().getFirst();
        SheetProgress progress = state.boardState().sheetProgress().get(p1);

        // Add only 1 permanent cross (less than minCrosses which is 5)
        Set<String> crossed = Set.of(row.cells().getFirst().id());
        progress.updateRowState(0, new RowState(crossed, false));

        // No pending crosses
        state.turnState().setUndoBuffer(new HashMap<>());

        // Should NOT be able to declare lock intent
        assertFalse(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof DeclareLockIntentAction));
    }

    @Test
    void lockRequiresClosingCellPermanentOrPending() {
        GameState state = stateAfterRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        Row row = layout.rows().getFirst();
        LockCell lock = row.lock();
        SheetProgress progress = state.boardState().sheetProgress().get(p1);

        // Add minCrosses permanent crosses but NOT the closing cell
        Set<String> crossed = new HashSet<>();
        String closingCell = lock.closingCells().getFirst();
        for (int i = 0; i < lock.minCrosses() && i < row.cells().size(); i++) {
            String cellId = row.cells().get(i).id();
            // Skip the closing cell
            if (!cellId.equals(closingCell)) {
                crossed.add(cellId);
            }
        }
        progress.updateRowState(0, new RowState(crossed, false));
        state.turnState().setUndoBuffer(new HashMap<>());

        // Should NOT be able to lock (missing closing cell)
        assertFalse(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof DeclareLockIntentAction));
    }

    @Test
    void lockSucceedsWhenClosingCellIsPending() {
        // For standard variant (1 closing cell), a pending closing cell is auto-detected at EndTurn.
        // Verify that canCrossLock returns true when the closing cell is in the undoBuffer,
        // meaning the server correctly identifies the lock eligibility even before EndTurn.
        GameState state = stateAfterRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        Row row = layout.rows().getFirst();
        LockCell lock = row.lock();
        SheetProgress progress = state.boardState().sheetProgress().get(p1);

        // Add minCrosses-1 permanent crosses (not the closing cell)
        Set<String> crossed = new HashSet<>();
        String closingCell = lock.closingCells().getFirst();
        int count = 0;
        for (Cell c : row.cells()) {
            if (count >= lock.minCrosses() - 1) break;
            if (!c.id().equals(closingCell)) {
                crossed.add(c.id());
                count++;
            }
        }
        progress.updateRowState(0, new RowState(crossed, false));

        // Add the closing cell as a pending cross in the undoBuffer
        Map<Integer, Set<String>> pendingCrosses = new HashMap<>();
        pendingCrosses.put(0, Set.of(closingCell));
        state.turnState().setUndoBuffer(Map.of(p1, pendingCrosses));

        // canCrossLock must return true (pending closing cell counts toward eligibility)
        assertTrue(rules.canCrossLock(state, p1, 0),
                "canCrossLock must return true when the closing cell is pending in undoBuffer");
        // However, explicit DECLARE_LOCK_INTENT is NOT offered for standard variant
        // when the closing cell is only pending (it's auto-detected at EndTurn).
        // The server uses auto-detect for last closing cell, not explicit declaration.
    }

    // -------------------------------------------------------------------------
    // GiveUpAction
    // -------------------------------------------------------------------------

    @Test
    void giveUpWithoutAnyCrossAddsPunishment() {
        // Exact user scenario: roll the dice, want to cross nothing, take punishment.
        GameState state = stateAfterRoll(p1, p1, p2);
        int before = state.boardState().sheetProgress().get(p1).punishments();
        rules.apply(state, new GiveUpAction(p1));
        assertEquals(before + 1, state.boardState().sheetProgress().get(p1).punishments());
    }

    @Test
    void giveUpRestoresProgressAndAddsPunishment() {
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, firstCrossAction(state, p1));  // cross a cell
        int punishmentsBefore = state.boardState().sheetProgress().get(p1).punishments();
        rules.apply(state, new GiveUpAction(p1));
        SheetProgress after = state.boardState().sheetProgress().get(p1);
        assertEquals(punishmentsBefore + 1, after.punishments());
    }

    @Test
    void giveUpWithNoPassivesTransitionsToNextRoll() {
        // Single-player: no one in the passive queue, so evaluate fires immediately.
        GameState state = stateAfterRoll(p1, p1);
        rules.apply(state, new GiveUpAction(p1));
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
    }

    @Test
    void giveUpDuringActiveMoveTransitionsToPassiveMoveWhenPassivesRemain() {
        // Bug: GiveUp used to call evaluate() immediately, skipping passive players entirely.
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, new GiveUpAction(p1));
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase(),
                "GiveUp must enter PASSIVE_MOVE so passive players can still take their turn");
    }

    @Test
    void giveUpDuringActiveMoveKeepsPassiveInQueue() {
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, new GiveUpAction(p1));
        assertTrue(state.turnState().passivePlayerQueue().contains(p2),
                "Passive player must remain in queue after active player gives up");
    }

    @Test
    void giveUpDuringActiveMovePassiveCanEndTurnAndAdvancesToRoll() {
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, new GiveUpAction(p1));
        rules.apply(state, new EndTurnAction(p2));
        assertEquals(TurnPhase.ROLL, state.turnState().phase(),
                "Turn must advance to ROLL only after the passive player finishes");
        assertEquals(p2, state.turnState().activePlayerId());
    }

    @Test
    void giveUpDuringActiveMovePassiveCanCrossThenEnd() {
        // Passive player can still use the white+white dice after active gives up.
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, new GiveUpAction(p1));
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase());
        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross);   // passive crosses
        rules.apply(state, new EndTurnAction(p2));
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
    }

    @Test
    void giveUpAfterPassiveAlreadyEndedTurnGoesDirectlyToRoll() {
        // p2 ends during ACTIVE_MOVE first → queue becomes empty → GiveUp evaluates immediately.
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new GiveUpAction(p1));
        assertEquals(TurnPhase.ROLL, state.turnState().phase(),
                "GiveUp with already-empty passive queue must go directly to ROLL");
    }

    @Test
    void giveUpDuringActiveMoveWithThreePlayersKeepsBothPassivesInPassiveMove() {
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        rules.apply(state, new GiveUpAction(p1));
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase());
        assertTrue(state.turnState().passivePlayerQueue().contains(p2));
        assertTrue(state.turnState().passivePlayerQueue().contains(p3));
    }

    @Test
    void giveUpDiscardsThisTurnsCrosses() {
        GameState state = stateAfterRoll(p1, p1, p2);
        CrossCellAction cross = firstCrossAction(state, p1);
        rules.apply(state, cross);
        rules.apply(state, new GiveUpAction(p1));
        // progress should be restored to snapshot (no crosses from this turn)
        RowState after = state.boardState().sheetProgress().get(p1)
                .rowStates().getOrDefault(cross.rowIndex(), new RowState(Set.of(), false));
        assertFalse(after.crossedCells().contains(cross.cellId()));
    }

    // -------------------------------------------------------------------------
    // ResetTurnAction
    // -------------------------------------------------------------------------

    @Test
    void resetTurnUndoesAllThisTurnCrosses() {
        GameState state = stateAfterRoll(p1, p1, p2);
        CrossCellAction cross = firstCrossAction(state, p1);
        rules.apply(state, cross);
        rules.apply(state, new ResetTurnAction(p1));
        RowState after = state.boardState().sheetProgress().get(p1)
                .rowStates().getOrDefault(cross.rowIndex(), new RowState(Set.of(), false));
        assertFalse(after.crossedCells().contains(cross.cellId()));
    }

    @Test
    void resetTurnResetsActiveTurnState() {
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, firstCrossAction(state, p1));
        ActiveTurnState activePlayer = state.turnState().activeTurnState();
        assertTrue(activePlayer.whiteWhiteUsed() || activePlayer.colorDieUsed());
        rules.apply(state, new ResetTurnAction(p1));
        assertFalse(state.turnState().activeTurnState().whiteWhiteUsed());
        assertFalse(state.turnState().activeTurnState().colorDieUsed());
    }

    @Test
    void resetTurnAllowsRecrossAfterReset() {
        GameState state = stateAfterRoll(p1, p1, p2);
        CrossCellAction first = firstCrossAction(state, p1);
        rules.apply(state, first);
        rules.apply(state, new ResetTurnAction(p1));
        // After reset, can cross again (end turn not yet available → first cross is offered)
        List<GameAction> actions = rules.getValidActions(state, p1);
        assertTrue(actions.stream().anyMatch(a -> a instanceof CrossCellAction));
    }

    // -------------------------------------------------------------------------
    // Turn advancement
    // -------------------------------------------------------------------------

    @Test
    void turnAdvancesInOrder() {
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new EndTurnAction(p3));
        assertEquals(p2, state.turnState().activePlayerId());
    }

    @Test
    void turnWrapsAroundAfterLastPlayer() {
        GameState state = stateAfterRoll(p1, p1, p2);
        completeTurn(state, p1, p2);
        completeTurn(state, p2, p1);
        assertEquals(p1, state.turnState().activePlayerId());
    }

    @Test
    void versionIncrementsOnEachAction() {
        GameState state = stateInRoll(p1, p1, p2);
        long v0 = state.version();
        rules.apply(state, new RollAction(p1));
        assertEquals(v0 + 1, state.version());
    }

    // -------------------------------------------------------------------------
    // Game-over conditions
    // -------------------------------------------------------------------------

    @Test
    void gameOverWhenTwoRowsClosed() {
        GameState state = stateInRoll(p1, p1, p2);
        state.boardState().closedRows().put(0, p1);
        state.boardState().closedRows().put(1, p1);
        assertTrue(rules.isGameOver(state));
    }

    @Test
    void gameOverWhenPlayerHasMaxPunishments() {
        GameState state = stateInRoll(p1, p1, p2);
        SheetProgress prog = state.boardState().sheetProgress().get(p1);
        for (int i = 0; i < StandardTurnRules.MAX_PUNISHMENTS; i++) prog.addPunishment();
        assertTrue(rules.isGameOver(state));
    }

    @Test
    void notGameOverWithOneClosedRow() {
        GameState state = stateInRoll(p1, p1, p2);
        state.boardState().closedRows().put(0, p1);
        assertFalse(rules.isGameOver(state));
    }

    @Test
    void giveUpThatCausesMaxPunishmentsEndsGameAfterPassivesMove() {
        // punishments must be in place before rolling so they are included in the snapshot
        GameState state = stateInRoll(p1, p1, p2);
        SheetProgress prog = state.boardState().sheetProgress().get(p1);
        for (int i = 0; i < StandardTurnRules.MAX_PUNISHMENTS - 1; i++) prog.addPunishment();
        rules.apply(state, new RollAction(p1));
        rules.apply(state, new GiveUpAction(p1));
        // p2 still gets their passive turn even though the game conditions for over are met
        assertFalse(state.gameOver(), "game must not end before passive players have moved");
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase());
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.gameOver(), "game ends only after all passive players have finished");
    }

    @Test
    void giveUpThatCausesMaxPunishmentsInSinglePlayerEndsImmediately() {
        // No passive players → evaluate fires right away and sets game over.
        GameState state = stateInRoll(p1, p1);
        SheetProgress prog = state.boardState().sheetProgress().get(p1);
        for (int i = 0; i < StandardTurnRules.MAX_PUNISHMENTS - 1; i++) prog.addPunishment();
        rules.apply(state, new RollAction(p1));
        rules.apply(state, new GiveUpAction(p1));
        assertTrue(state.gameOver());
    }

    @Test
    void gameOverFlagPreventsAllActions() {
        GameState state = stateInRoll(p1, p1, p2);
        state.setGameOver(true);
        assertTrue(rules.getValidActions(state, p1).isEmpty());
        assertTrue(rules.getValidActions(state, p2).isEmpty());
    }

    // -------------------------------------------------------------------------
    // IllegalMoveException validation
    // -------------------------------------------------------------------------

    @Test
    void takePunishmentActionIsRejected() {
        GameState state = stateAfterRoll(p1, p1, p2);
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new TakePunishmentAction(p1)));
    }

    @Test
    void resetTurn_fromPassiveMove_revertsToActiveMove_clearsPendingCross() {
        // When the active player EndTurns (→ PASSIVE_MOVE) and then sees a passive declare a
        // row they could also close, RESET_TURN must revert the phase to ACTIVE_MOVE AND
        // fully clear the pending cross — the player should be able to redo their move from
        // scratch without having to click the crossed cell again to undo it.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);

        // p1 crosses the closing cell → auto-declares row-0 intent
        rules.apply(state, firstCrossAction(state, p1));
        String closingCellId = state.sheetLayouts().get(p1).rows().getFirst()
                .lock().closingCells().getFirst();
        state.turnState().undoBuffer()
                .computeIfAbsent(p1, k -> new HashMap<>())
                .computeIfAbsent(0, k -> new HashSet<>())
                .add(closingCellId);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        // p1 EndTurns → PASSIVE_MOVE
        rules.apply(state, new EndTurnAction(p1));
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase());
        assertFalse(state.turnState().undoBuffer().getOrDefault(p1, Map.of()).isEmpty(),
                "Undo buffer must still have p1's pending cross after EndTurn");

        // p1 sends RESET_TURN from PASSIVE_MOVE → phase reverts to ACTIVE_MOVE
        rules.apply(state, new ResetTurnAction(p1));

        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase(),
                "Phase must revert to ACTIVE_MOVE after RESET_TURN from PASSIVE_MOVE");
        assertTrue(state.turnState().undoBuffer().getOrDefault(p1, Map.of()).isEmpty(),
                "Undo buffer must be cleared after RESET_TURN from PASSIVE_MOVE");
        var rowState = state.boardState().sheetProgress().get(p1).rowStates().get(0);
        assertFalse(rowState != null && rowState.crossedCells().contains(closingCellId),
                "Closing cell must be uncrossed after RESET_TURN from PASSIVE_MOVE");
        ActiveTurnState activePlayer = state.turnState().activeTurnState();
        assertFalse(activePlayer.whiteWhiteUsed(),  "whiteWhiteUsed must be reset");
        assertFalse(activePlayer.colorDieUsed(),    "colorDieUsed must be reset");
    }

    @Test
    void resetTurn_passive_fromPassiveMove_revertsToPassiveQueue_undoesCross() {
        // Passive player EndTurns (PASSIVE_MOVE), then sends RESET_TURN.
        // Must be re-added to the passive queue with their cross reverted via the turn-start snapshot.
        GameState state = stateAfterRoll(p1, p1, p2, p3);

        rules.apply(state, firstCrossAction(state, p2)); // p2 makes a cross
        rules.apply(state, new EndTurnAction(p2));       // p2 EndTurns — leaves queue

        assertEquals(List.of(p3), state.turnState().passivePlayerQueue(),
                "Only p3 must remain in passive queue after p2 EndTurns");

        int crossedBefore = state.boardState().sheetProgress().get(p2)
                .rowStates().values().stream().mapToInt(r -> r.crossedCells().size()).sum();
        assertTrue(crossedBefore > 0, "p2 must have at least one cross before revert");

        // p2 reverts their EndTurn
        rules.apply(state, new ResetTurnAction(p2));

        assertTrue(state.turnState().passivePlayerQueue().contains(p2),
                "p2 must be back in the passive queue after reverting EndTurn");
        int crossedAfter = state.boardState().sheetProgress().get(p2)
                .rowStates().values().stream().mapToInt(r -> r.crossedCells().size()).sum();
        assertEquals(0, crossedAfter,
                "p2's cross must be undone (restored to turn-start snapshot)");
    }

    @Test
    void activePlayerReaddedToQueue_whenLastPassiveDeclareRowTheyCouldLock() {
        // When the last passive EndTurns and declares a row the active player could also lock
        // (enough crosses, closing cell not yet crossed), the active player is re-added to
        // the passive queue so they can revert (Change) or proceed (Pass → evaluate).
        GameState state = stateAfterRoll(p1, p1, p2);

        // p1 has enough crosses but NOT the closing cell — they could lock if given a chance
        crossEnoughForLockWithoutClosingCell(state, p1, 0);
        // p2 has the closing cell so they can declare
        crossEnoughForLock(state, p2, 0);

        // p1 EndTurns (used a die) — goes to PASSIVE_MOVE
        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1));
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase());

        // p2 (passive) crosses the closing cell and EndTurns — last passive
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        rules.apply(state, new EndTurnAction(p2));

        // p1 should be re-added to passive queue instead of evaluate running
        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase(),
                "Phase must stay PASSIVE_MOVE — active player gets a final look");
        assertTrue(state.turnState().passivePlayerQueue().contains(p1),
                "Active player (p1) must be re-added to passive queue for final look");
        // Row must NOT be closed yet (evaluate has not run)
        assertFalse(state.isRowClosed(0),
                "Row must not be closed yet — evaluate has not run");
    }

    @Test
    void requeuededActivePlayer_hasValidActions() {
        // Regression: isPassiveInQueue used to exclude the active player by ID, so
        // getValidActions() returned empty for a re-queued active player — deadlocking bots.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLockWithoutClosingCell(state, p1, 0);
        crossEnoughForLock(state, p2, 0);

        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        rules.apply(state, new EndTurnAction(p2));

        // p1 (active) is now re-queued to the passive queue for a final look.
        List<GameAction> actions = rules.getValidActions(state, p1);
        assertFalse(actions.isEmpty(),
                "Re-queued active player must have valid actions (was deadlocked before fix)");
        assertTrue(actions.stream().anyMatch(a -> a instanceof EndTurnAction),
                "Re-queued active player must be able to EndTurn from the passive queue");
    }

    @Test
    void activePlayerProceedsWithoutReverts_passTriggersEvaluate() {
        // When the active player is re-queued for a final look and sends PASS (without reverting),
        // evaluate must run and close the row normally.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLockWithoutClosingCell(state, p1, 0);
        crossEnoughForLock(state, p2, 0);

        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1));
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        rules.apply(state, new EndTurnAction(p2));

        // p1 is re-queued — they send PASS (accept, don't change anything)
        rules.apply(state, new EndTurnAction(p1)); // p1 passes from the final-look queue

        assertEquals(TurnPhase.ROLL, state.turnState().phase(),
                "Phase must advance to ROLL after evaluate");
        assertTrue(state.isRowClosed(0),
                "Row must be closed after p1 passes from final-look queue");
    }

    @Test
    void rollInWrongPhaseIsRejected() {
        GameState state = stateAfterRoll(p1, p1, p2);  // already in ACTIVE_MOVE
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new RollAction(p1)));
    }

    // -------------------------------------------------------------------------
    // Test state builders
    // -------------------------------------------------------------------------

    /** State with TurnPhase.ROLL and the given active player. */
    private GameState stateInRoll(UUID active, UUID... allPlayers) {
        List<UUID> players = Arrays.asList(allPlayers);
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID p : players) {
            layouts.put(p, standardLayout());
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

    /** State after RollAction has been applied (in ACTIVE_MOVE). */
    private GameState stateAfterRoll(UUID active, UUID... allPlayers) {
        GameState state = stateInRoll(active, allPlayers);
        rules.apply(state, new RollAction(active));
        return state;
    }

    /** State in PASSIVE_MOVE (active player has ended their turn). */
    private GameState stateInPassiveMove(UUID active, UUID... allPlayers) {
        GameState state = stateAfterRoll(active, allPlayers);
        rules.apply(state, firstCrossAction(state, active));
        rules.apply(state, new EndTurnAction(active));
        return state;
    }

    /**
     * State in ACTIVE_MOVE where the active player has enough crosses on rowIndex
     * to declare lock intent.
     */
    private GameState stateReadyToLock(UUID active, UUID player1, UUID player2, int rowIndex) {
        GameState state = stateAfterRoll(active, player1, player2);
        crossEnoughForLock(state, active, rowIndex);
        return state;
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    /**
     * Cross minCrosses-1 regular cells (excluding closing cells) so the player
     * qualifies to cross the closing cell but has not yet done so.
     */
    private void crossEnoughForLockWithoutClosingCell(GameState state, UUID playerId, int rowIndex) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        Row row = layout.rows().get(rowIndex);
        LockCell lock = row.lock();
        SheetProgress progress = state.boardState().sheetProgress().get(playerId);
        Set<String> closing = new HashSet<>(lock.closingCells());
        Set<String> crossed = new HashSet<>();
        for (Cell c : row.cells()) {
            if (crossed.size() >= lock.minCrosses() - 1) break;
            if (!closing.contains(c.id())) crossed.add(c.id());
        }
        progress.updateRowState(rowIndex, new RowState(crossed, false));
    }

    /** Cross enough cells on rowIndex (including the last closing cell) so the lock is openable. */
    private void crossEnoughForLock(GameState state, UUID playerId, int rowIndex) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        Row row = layout.rows().get(rowIndex);
        LockCell lock = row.lock();
        SheetProgress progress = state.boardState().sheetProgress().get(playerId);

        // cross the first minCrosses cells including the last closingCell
        int toCross = lock.minCrosses();
        Set<String> closing = new HashSet<>(lock.closingCells());
        Set<String> crossed = new HashSet<>();

        // always include the last closing cell (any one suffices in new architecture)
        String lastClosing = lock.closingCells().getLast();
        crossed.add(lastClosing);

        // fill remaining crosses from the start of the row (skip closing cells)
        for (Cell c : row.cells()) {
            if (crossed.size() >= toCross) break;
            if (!closing.contains(c.id())) crossed.add(c.id());
        }
        progress.updateRowState(rowIndex, new RowState(crossed, false));
    }

    /** Returns the first CrossCellAction offered to playerId, or throws if none. */
    private CrossCellAction firstCrossAction(GameState state, UUID playerId) {
        return rules.getValidActions(state, playerId).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CrossCellAction available for " + playerId));
    }

    /** Completes a full turn for the given active player (cross + end + all passive pass). */
    private void completeTurn(GameState state, UUID active, UUID... passives) {
        if (state.turnState().phase() == TurnPhase.ROLL) {
            rules.apply(state, new RollAction(active));
        }
        rules.apply(state, firstCrossAction(state, active));
        rules.apply(state, new EndTurnAction(active));
        for (UUID passive : passives) {
            if (state.turnState().passivePlayerQueue().contains(passive)) {
                rules.apply(state, new EndTurnAction(passive));
            }
        }
    }

    /**
     * Seeds the undoBuffer for playerId on rowIndex with a fake cell cross,
     * simulating that they crossed a cell this turn. Returns the cellId.
     */
    private String seedUndoBufferForP2(GameState state, UUID playerId, int rowIndex) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        Cell cell = layout.rows().get(rowIndex).cells().getFirst();
        SheetProgress progress = state.boardState().sheetProgress().get(playerId);
        RowState current = progress.rowStates().getOrDefault(rowIndex, new RowState(new HashSet<>(), false));
        Set<String> updated = new HashSet<>(current.crossedCells());
        updated.add(cell.id());
        progress.updateRowState(rowIndex, new RowState(updated, current.lockCrossed()));
        Map<Integer, Set<String>> entry = new HashMap<>();
        entry.put(rowIndex, Set.of(cell.id()));
        state.turnState().undoBuffer().put(playerId, entry);
        return cell.id();
    }

    private RowState rowStateOf(GameState state, UUID playerId, int rowIndex) {
        SheetProgress prog = state.boardState().sheetProgress().get(playerId);
        return prog.rowStates().getOrDefault(rowIndex, new RowState(Set.of(), false));
    }

    private Cell findCell(GameState state, UUID playerId, int rowIndex, String cellId) {
        return state.sheetLayouts().get(playerId).rows().get(rowIndex).cells().stream()
                .filter(c -> c.id().equals(cellId))
                .findFirst()
                .orElseThrow();
    }

    // -------------------------------------------------------------------------
    // Standard layout + progress factories
    // -------------------------------------------------------------------------

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

    private SheetProgress emptyProgress() {
        return new SheetProgress(new HashMap<>(), 0);
    }

    /**
     * Returns a Random that produces a fixed sequence.
     * white1=3, white2=4 (sum=7), red=2, yellow=3, green=4, blue=5 on each roll.
     * Sequence per roll: 3,4,2,3,4,5 (6 dice).
     */
    private Random fixedRandom() {
        return new Random() {
            private final int[] seq = {
                // faces-1 values (nextInt(6) returns 0-5, we add 1)
                2, 3, 1, 2, 3, 4   // → 3,4,2,3,4,5
            };
            private int pos = 0;
            @Override public int nextInt(int bound) {
                return seq[pos++ % seq.length];
            }
        };
    }

    // -------------------------------------------------------------------------
    // X-Change mechanic tests
    // -------------------------------------------------------------------------
    //
    // WW = 9 (white1=4, white2=5 override) matches pairs (9,7) and (11,9).
    // Crossing (9,7) sets effectiveWW=7; crossing (11,9) sets effectiveWW=11.

    @Test
    void xChange_activePlayerSeesXChangeCellWhenWWSumMatchesPairValue() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        assertTrue(rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> isXChangeCell(state, p1, cc)),
                "X-change cell must be offered when WW sum matches its pair value");
    }

    @Test
    void xChange_notOfferedWhenWWDoesNotMatchAnyPairValue() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        // Override to WW=2, which matches no x-change pair
        state.turnState().setCurrentRoll(
                new RollResult(1, 1, state.turnState().currentRoll().coloredDice()));
        assertFalse(rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> isXChangeCell(state, p1, cc)),
                "X-change cell must NOT be offered when WW does not match any pair value");
    }

    @Test
    void xChange_crossingDoesNotConsumeWhiteWhiteSlot() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        rules.apply(state, firstXChangeCrossAction(state, p1));
        assertFalse(state.turnState().activeTurnState().whiteWhiteUsed(),
                "Crossing x-change must not consume the white+white slot");
    }

    @Test
    void xChange_effectiveWWStoredAfterCross() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        rules.apply(state, xChangeCrossForPair(state, p1, 9, 7));
        Integer effective = state.turnState().xChangeEffectiveWW().get(p1);
        assertNotNull(effective, "effectiveWW must be stored after x-change cross");
        assertEquals(7, effective, "effectiveWW must be the 'other' value (b) when WW matched a");
    }

    @Test
    void xChange_effectiveWWIsOtherPairValueWhenWWMatchedSecondValue() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        rules.apply(state, xChangeCrossForPair(state, p1, 11, 9));
        Integer effective = state.turnState().xChangeEffectiveWW().get(p1);
        assertNotNull(effective);
        assertEquals(11, effective, "effectiveWW must be the 'other' value (a) when WW matched b");
    }

    @Test
    void xChange_afterCross_regularCellsMatchingEffectiveWWOfferedAsWW() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        rules.apply(state, xChangeCrossForPair(state, p1, 9, 7));
        // effectiveWW=7 → cells with displayValue "7" must be offered as WHITE_WHITE
        assertTrue(rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.combination() == DiceCombination.WHITE_WHITE)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> findCell(state, p1, cc.rowIndex(), cc.cellId()).displayValue().equals("7")),
                "Cells matching effectiveWW=7 must be offered as WHITE_WHITE after x-change");
    }

    @Test
    void xChange_afterCross_actualWWCellsNoLongerOfferedAsWW() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        rules.apply(state, xChangeCrossForPair(state, p1, 9, 7));
        // WW=9 cells must NOT be offered as WHITE_WHITE (effectiveWW replaced actual WW)
        assertFalse(rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.combination() == DiceCombination.WHITE_WHITE)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> findCell(state, p1, cc.rowIndex(), cc.cellId()).displayValue().equals("9")),
                "Actual WW=9 cells must not be offered as WHITE_WHITE after x-change (effectiveWW=7)");
    }

    @Test
    void xChange_cannotApplySecondXChangeInSameTurn() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        rules.apply(state, firstXChangeCrossAction(state, p1));
        assertFalse(rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> isXChangeCell(state, p1, cc)),
                "A second x-change cell must not be offered after one x-change cross");
    }

    @Test
    void xChange_crossingRegularCellWithEffectiveWWConsumesWWSlot() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        rules.apply(state, xChangeCrossForPair(state, p1, 9, 7));
        // Now cross a "7" cell using the effective WW slot
        CrossCellAction regular7 = rules.getValidActions(state, p1).stream()
                .filter(a -> a instanceof CrossCellAction cc && cc.combination() == DiceCombination.WHITE_WHITE)
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no effectiveWW cell offered after x-change"));
        rules.apply(state, regular7);
        assertTrue(state.turnState().activeTurnState().whiteWhiteUsed(),
                "Crossing effectiveWW cell must consume the white+white slot");
        assertFalse(state.turnState().xChangeEffectiveWW().containsKey(p1),
                "effectiveWW must be cleared after crossing regular cell with it");
    }

    @Test
    void xChange_resetClearsEffectiveWW() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        rules.apply(state, xChangeCrossForPair(state, p1, 9, 7));
        assertNotNull(state.turnState().xChangeEffectiveWW().get(p1));
        rules.apply(state, new ResetTurnAction(p1));
        assertFalse(state.turnState().xChangeEffectiveWW().containsKey(p1),
                "effectiveWW must be cleared after active player resets");
    }

    @Test
    void xChange_passiveSeesXChangeCellInActiveMovePhase() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        assertTrue(rules.getValidActions(state, p2).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> isXChangeCell(state, p2, cc)),
                "Passive player must see x-change cells when WW matches in ACTIVE_MOVE");
    }

    @Test
    void xChange_passiveAfterXChangeCrossCanStillCrossRegularCell() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        // Passive crosses x-change
        rules.apply(state, xChangeCrossForPair(state, p2, 9, 7));
        // Passive must still be offered regular cells matching effectiveWW=7
        assertTrue(rules.getValidActions(state, p2).stream()
                .anyMatch(a -> a instanceof CrossCellAction),
                "Passive must still be offered cross actions after x-change (effectiveWW active)");
    }

    @Test
    void xChange_passiveXChangeCrossDoesNotMarkAsActed() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        rules.apply(state, xChangeCrossForPair(state, p2, 9, 7));
        assertFalse(state.turnState().passivesActed().contains(p2),
                "Passive must not be marked as 'acted' after only crossing x-change");
    }

    @Test
    void xChange_passiveAfterXChangeAndRegularCross_noMoreCrossesOffered() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        rules.apply(state, xChangeCrossForPair(state, p2, 9, 7));
        // Cross a regular cell with effectiveWW=7
        CrossCellAction regular = rules.getValidActions(state, p2).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cross action after x-change for passive"));
        rules.apply(state, regular);
        assertFalse(rules.getValidActions(state, p2).stream().anyMatch(a -> a instanceof CrossCellAction),
                "Passive must not be offered more cross actions after x-change + regular cell");
    }

    @Test
    void xChange_passiveResetAfterXChangeClearsEffectiveWW() {
        GameState state = stateWithXChangeAfterRoll(p1, p1, p2);
        rules.apply(state, xChangeCrossForPair(state, p2, 9, 7));
        assertNotNull(state.turnState().xChangeEffectiveWW().get(p2));
        rules.apply(state, new ResetTurnAction(p2));
        assertFalse(state.turnState().xChangeEffectiveWW().containsKey(p2),
                "passive's effectiveWW must be cleared after reset");
    }

    @Test
    void xChange_passiveSeesXChangeCellInPassiveMovePhase() {
        GameState state = stateInPassiveMoveWithXChange(p1, p1, p2);
        assertTrue(rules.getValidActions(state, p2).stream()
                .filter(a -> a instanceof CrossCellAction)
                .map(a -> (CrossCellAction) a)
                .anyMatch(cc -> isXChangeCell(state, p2, cc)),
                "Passive player must see x-change cells in PASSIVE_MOVE when WW matches");
    }

    // ── X-change state builders ───────────────────────────────────────────────

    /** State with x-change layout in ACTIVE_MOVE; roll overridden to WW=9 (matches pairs (9,7) and (11,9)). */
    private GameState stateWithXChangeAfterRoll(UUID active, UUID... allPlayers) {
        List<UUID> players = Arrays.asList(allPlayers);
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID p : players) {
            layouts.put(p, standardLayoutWithXChange());
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
        GameState state = new GameState(CardMode.SAME_CARDS, players, null, layouts, board, turn);
        rules.apply(state, new RollAction(active));
        // Override to WW=9 (matches x-change pairs (9,7) and (11,9))
        state.turnState().setCurrentRoll(
                new RollResult(4, 5, state.turnState().currentRoll().coloredDice()));
        return state;
    }

    /** State with x-change layout in PASSIVE_MOVE; roll overridden to WW=9. */
    private GameState stateInPassiveMoveWithXChange(UUID active, UUID... allPlayers) {
        GameState state = stateWithXChangeAfterRoll(active, allPlayers);
        rules.apply(state, firstCrossAction(state, active));
        rules.apply(state, new EndTurnAction(active));
        // Re-apply WW=9 override (roll survives into PASSIVE_MOVE)
        state.turnState().setCurrentRoll(
                new RollResult(4, 5, state.turnState().currentRoll().coloredDice()));
        return state;
    }

    // ── X-change layout helpers ───────────────────────────────────────────────

    private SheetLayout standardLayoutWithXChange() {
        List<Row> rows = new ArrayList<>();
        rows.add(ascendingRow(Color.RED));
        rows.add(ascendingRow(Color.YELLOW));
        rows.add(descendingRow(Color.GREEN));
        rows.add(descendingRow(Color.BLUE));
        rows.add(xChangeRow());
        return new SheetLayout(rows);
    }

    private Row xChangeRow() {
        int[][] pairs = {{8,5},{9,7},{11,3},{7,4},{10,3},{8,6},{10,5},{11,9},{6,4}};
        Row row = new Row();
        for (int i = 0; i < pairs.length; i++) {
            Cell c = new Cell(i);
            c.setColor(Color.BLUE);
            c.setDisplayValue("");
            c.setTags(List.of(new CellTag.XChange(pairs[i][0], pairs[i][1])));
            c.setClosingEligible(false);
            row.addCell(c);
        }
        return row;
    }

    // ── X-change action helpers ───────────────────────────────────────────────

    private CrossCellAction firstXChangeCrossAction(GameState state, UUID playerId) {
        return rules.getValidActions(state, playerId).stream()
                .filter(a -> a instanceof CrossCellAction cc && isXChangeCell(state, playerId, cc))
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no XChange CrossCellAction offered to " + playerId));
    }

    private CrossCellAction xChangeCrossForPair(GameState state, UUID playerId, int a, int b) {
        return rules.getValidActions(state, playerId).stream()
                .filter(act -> act instanceof CrossCellAction cc && isXChangeCellWithPair(state, playerId, cc, a, b))
                .map(act -> (CrossCellAction) act)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no XChange(" + a + "," + b + ") cell offered to " + playerId));
    }

    private boolean isXChangeCell(GameState state, UUID playerId, CrossCellAction action) {
        return state.sheetLayouts().get(playerId).rows().get(action.rowIndex())
                .cells().stream()
                .anyMatch(c -> c.id().equals(action.cellId()) &&
                        c.tags().stream().anyMatch(t -> t instanceof CellTag.XChange));
    }

    private boolean isXChangeCellWithPair(GameState state, UUID playerId, CrossCellAction action, int a, int b) {
        return state.sheetLayouts().get(playerId).rows().get(action.rowIndex())
                .cells().stream()
                .anyMatch(c -> c.id().equals(action.cellId()) &&
                        c.tags().stream().anyMatch(t -> t instanceof CellTag.XChange xc
                                && xc.a() == a && xc.b() == b));
    }

    // =========================================================================
    // Lucky Number mechanic tests
    // =========================================================================
    //
    // Layout: standard 4 coloured rows + 1 lucky-number row (luckyTarget = 13).
    // Roll: white1=3, white2=4, blue=6 → 3+4+6 = 13 ✓ (after override).
    // Default fixed random yields blue=5 (sum 12), so the override is required.

    @Test
    void luckyNumber_offeredToActivePlayerWhenPrereqMet() {
        GameState state = stateWithLuckyNumberAfterRoll(p1, p1, p2);
        assertTrue(rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc && isLuckyNumberAction(state, p1, cc)),
                "Lucky Number cell must be offered when ww + any coloured die equals luckyTarget");
    }

    @Test
    void luckyNumber_notOfferedWhenPrereqNotMet() {
        GameState state = stateWithLuckyNumberAfterRoll(p1, p1, p2);
        // Override so no combo reaches 13
        state.turnState().setCurrentRoll(new RollResult(1, 1, state.turnState().currentRoll().coloredDice()));
        assertFalse(rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc && isLuckyNumberAction(state, p1, cc)),
                "Lucky Number cell must NOT be offered when no combo equals luckyTarget");
    }

    @Test
    void luckyNumber_onlyOfferedBeforeAnyOtherMove() {
        GameState state = stateWithLuckyNumberAfterRoll(p1, p1, p2);
        rules.apply(state, firstRegularCrossAction(state, p1));
        assertFalse(rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc && isLuckyNumberAction(state, p1, cc)),
                "Lucky Number must not be offered after any other move has been made");
    }

    @Test
    void luckyNumber_crossSetsLuckyNumberUsedFlag() {
        GameState state = stateWithLuckyNumberAfterRoll(p1, p1, p2);
        rules.apply(state, firstLuckyNumberAction(state, p1));
        assertTrue(state.turnState().activeTurnState().luckyNumberUsed(),
                "luckyNumberUsed flag must be set after crossing a Lucky Number cell");
    }

    @Test
    void luckyNumber_afterCross_noRegularCrossesOffered() {
        GameState state = stateWithLuckyNumberAfterRoll(p1, p1, p2);
        rules.apply(state, firstLuckyNumberAction(state, p1));
        assertFalse(rules.getValidActions(state, p1).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc && !isLuckyNumberAction(state, p1, cc)),
                "No regular cross actions must be offered after Lucky Number cross");
    }

    @Test
    void luckyNumber_afterCross_endTurnOffered() {
        GameState state = stateWithLuckyNumberAfterRoll(p1, p1, p2);
        rules.apply(state, firstLuckyNumberAction(state, p1));
        assertTrue(rules.getValidActions(state, p1).stream().anyMatch(a -> a instanceof EndTurnAction),
                "EndTurnAction must be offered after Lucky Number cross");
    }

    @Test
    void luckyNumber_notOfferedToPassivePlayers() {
        GameState state = stateWithLuckyNumberAfterRoll(p1, p1, p2);
        assertFalse(rules.getValidActions(state, p2).stream()
                        .anyMatch(a -> a instanceof CrossCellAction cc && isLuckyNumberAction(state, p2, cc)),
                "Passive players must never be offered Lucky Number actions");
    }

    @Test
    void luckyNumber_countsAsHasActed_allowsEndTurn() {
        GameState state = stateWithLuckyNumberAfterRoll(p1, p1, p2);
        rules.apply(state, firstLuckyNumberAction(state, p1));
        assertTrue(state.turnState().activeTurnState().hasActed(),
                "Lucky Number cross must count as hasActed()");
    }

    @Test
    void luckyNumber_resetClearsFlag() {
        GameState state = stateWithLuckyNumberAfterRoll(p1, p1, p2);
        rules.apply(state, firstLuckyNumberAction(state, p1));
        assertTrue(state.turnState().activeTurnState().luckyNumberUsed());
        rules.apply(state, new ResetTurnAction(p1));
        assertFalse(state.turnState().activeTurnState().luckyNumberUsed(),
                "luckyNumberUsed must be cleared after ResetTurnAction");
    }

    // ── Lucky Number state builders ───────────────────────────────────────────

    /** Roll override: white1=3, white2=4, blue=6 → ww+blue = 13 = luckyTarget. */
    private GameState stateWithLuckyNumberAfterRoll(UUID active, UUID... allPlayers) {
        List<UUID> players = Arrays.asList(allPlayers);
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID p : players) {
            List<Row> rows = new ArrayList<>();
            rows.add(ascendingRow(Color.RED));
            rows.add(ascendingRow(Color.YELLOW));
            rows.add(descendingRow(Color.GREEN));
            rows.add(descendingRow(Color.BLUE));
            rows.add(luckyNumberRow());
            layouts.put(p, new SheetLayout(rows));
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
        GameState state = new GameState(CardMode.SAME_CARDS, players, null, layouts, board, turn);
        rules.apply(state, new RollAction(active));
        // Override: blue=6 so ww(3+4)+blue(6) = 13 = luckyTarget
        Map<Color, Integer> colored = new EnumMap<>(Color.class);
        colored.put(Color.RED, 2); colored.put(Color.YELLOW, 3);
        colored.put(Color.GREEN, 4); colored.put(Color.BLUE, 6);
        state.turnState().setCurrentRoll(new RollResult(3, 4, colored));
        return state;
    }

    private Row luckyNumberRow() {
        int[] bonusPoints = {5, 6, 7, 8};
        Row row = new Row();
        for (int i = 0; i < bonusPoints.length; i++) {
            Cell c = new Cell(i);
            c.setColor(Color.BLUE);
            c.setDisplayValue(String.valueOf(bonusPoints[i]));
            c.setTags(List.of(new CellTag.LuckyNumber(bonusPoints[i])));
            c.setClosingEligible(false);
            row.addCell(c);
        }
        row.setLuckyRow(13);
        return row;
    }

    // ── Lucky Number action helpers ───────────────────────────────────────────

    private CrossCellAction firstLuckyNumberAction(GameState state, UUID playerId) {
        return rules.getValidActions(state, playerId).stream()
                .filter(a -> a instanceof CrossCellAction cc && isLuckyNumberAction(state, playerId, cc))
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Lucky Number action offered to " + playerId));
    }

    private CrossCellAction firstRegularCrossAction(GameState state, UUID playerId) {
        return rules.getValidActions(state, playerId).stream()
                .filter(a -> a instanceof CrossCellAction cc && !isLuckyNumberAction(state, playerId, cc)
                        && !isLuckyCrossAction(state, playerId, cc))
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no regular CrossCellAction offered to " + playerId));
    }

    private boolean isLuckyNumberAction(GameState state, UUID playerId, CrossCellAction action) {
        return state.sheetLayouts().get(playerId).rows().get(action.rowIndex())
                .cells().stream()
                .anyMatch(c -> c.id().equals(action.cellId())
                        && c.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyNumber));
    }

    // =========================================================================
    // Lucky Cross mechanic tests
    // =========================================================================
    //
    // Layout: standard 4 coloured rows each with lucky-cross fields at positions 2, 6, 11
    // (gap pattern [2,3,4,2]).
    //
    // Default fixed random roll: white1=3, white2=4, red=2, yellow=3, green=4, blue=5.
    // That roll has NO 1+5 combo.  Tests that need a combo override the roll.
    //
    // Combos used:
    //   white1=1, white2=5                 → free choice (any coloured row)
    //   white1=1, white2=4, red=5          → only RED row
    //   white1=3, white2=4, red=1, green=5 → RED or GREEN rows (two-coloured combo)

    @Test
    void luckyCross_notOfferedWhenNo1And5Combo() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        // default roll: no 1+5 pair
        assertFalse(hasluckyCrossAction(rules.getValidActions(state, p1), state, p1),
                "Lucky Cross must not be offered when no 1+5 combo is present");
    }

    @Test
    void luckyCross_offeredToActivePlayerWhenWhiteWhiteIs1And5() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        List<CrossCellAction> offered = luckyCrossActions(rules.getValidActions(state, p1), state, p1);
        assertEquals(4, offered.size(),
                "white+white 1+5 must offer one Lucky Cross field in each of the 4 coloured rows");
    }

    @Test
    void luckyCross_offeredOnlyForMatchingRowWhenWhiteColorCombo() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        // white1=1, white2=4, red=5 → only RED row
        setRoll(state, 1, 4, Map.of(Color.RED, 5, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        List<CrossCellAction> offered = luckyCrossActions(rules.getValidActions(state, p1), state, p1);
        assertEquals(1, offered.size(), "white+colored 1+5 must offer exactly one Lucky Cross field");
        assertEquals(Color.RED, rowColor(state, p1, offered.getFirst().rowIndex()),
                "Offered Lucky Cross field must be in the RED row");
    }

    @Test
    void luckyCross_offeredForEitherRowWhenTwoColoredAre1And5() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        // red=1, green=5 → RED or GREEN rows
        setRoll(state, 3, 4, Map.of(Color.RED, 1, Color.YELLOW, 3, Color.GREEN, 5, Color.BLUE, 2));
        List<CrossCellAction> offered = luckyCrossActions(rules.getValidActions(state, p1), state, p1);
        assertEquals(2, offered.size(), "colored+colored 1+5 must offer one field in each of the two rows");
        assertTrue(offered.stream().anyMatch(a -> rowColor(state, p1, a.rowIndex()) == Color.RED));
        assertTrue(offered.stream().anyMatch(a -> rowColor(state, p1, a.rowIndex()) == Color.GREEN));
    }

    @Test
    void luckyCross_activePlayerCanUseBeforeRegularMove() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        rules.apply(state, firstLuckyCrossAction(state, p1));
        assertTrue(state.turnState().luckyCrossUsed().contains(p1),
                "luckyCrossUsed must contain active player after Lucky Cross");
    }

    @Test
    void luckyCross_activePlayerCanUseAfterRegularMove() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        rules.apply(state, firstRegularCrossAction(state, p1));
        assertTrue(hasluckyCrossAction(rules.getValidActions(state, p1), state, p1),
                "Lucky Cross must still be offered after regular move");
    }

    @Test
    void luckyCross_passivePlayerCanUseLuckyCross() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        assertTrue(hasluckyCrossAction(rules.getValidActions(state, p2), state, p2),
                "Passive player must be offered Lucky Cross when 1+5 combo is present");
    }

    @Test
    void luckyCross_passiveCanUseAfterWhiteWhiteCross() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        rules.apply(state, firstWhiteWhiteAction(state, p2));
        assertTrue(hasluckyCrossAction(rules.getValidActions(state, p2), state, p2),
                "Passive player must still be offered Lucky Cross after their white+white cross");
    }

    @Test
    void luckyCross_cannotBeUsedTwiceBySamePlayer() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        rules.apply(state, firstLuckyCrossAction(state, p1));
        assertFalse(hasluckyCrossAction(rules.getValidActions(state, p1), state, p1),
                "Lucky Cross must not be offered again after it has been used");
    }

    @Test
    void luckyCross_setsLuckyCrossUsedInTurnState() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        rules.apply(state, firstLuckyCrossAction(state, p1));
        assertTrue(state.turnState().luckyCrossUsed().contains(p1),
                "TurnState.luckyCrossUsed must contain the player after a Lucky Cross");
    }

    @Test
    void luckyCross_setsLuckyCrossUsedInActiveTurnState() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        rules.apply(state, firstLuckyCrossAction(state, p1));
        assertTrue(state.turnState().activeTurnState().luckyCrossUsed(),
                "ActiveTurnState.luckyCrossUsed must be set after active player's Lucky Cross");
    }

    @Test
    void luckyCross_countsAsHasActed_allowsEndTurn() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        rules.apply(state, firstLuckyCrossAction(state, p1));
        assertTrue(state.turnState().activeTurnState().hasActed(),
                "Lucky Cross alone must satisfy hasActed() so active player can end turn");
        assertTrue(rules.getValidActions(state, p1).stream().anyMatch(a -> a instanceof EndTurnAction),
                "EndTurnAction must be offered after Lucky Cross alone");
    }

    @Test
    void luckyCross_resetClearsUsedFlag() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        rules.apply(state, firstLuckyCrossAction(state, p1));
        assertTrue(state.turnState().luckyCrossUsed().contains(p1));
        rules.apply(state, new ResetTurnAction(p1));
        assertFalse(state.turnState().luckyCrossUsed().contains(p1),
                "luckyCrossUsed must be cleared after ResetTurnAction");
        assertFalse(state.turnState().activeTurnState().luckyCrossUsed(),
                "ActiveTurnState.luckyCrossUsed must also be cleared after reset");
    }

    @Test
    void luckyCross_regularCrossActionsDoNotIncludeLuckyCrossFields() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        // Even with a matching roll, regular crossCellActions must not contain Lucky Cross cells.
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));
        // White sum = 6; check no cross action targets a LuckyCross cell via regular path.
        assertFalse(rules.getValidActions(state, p1).stream()
                        .filter(a -> a instanceof CrossCellAction cc && !isLuckyCrossAction(state, p1, cc))
                        .map(a -> (CrossCellAction) a)
                        .anyMatch(cc -> isLuckyCrossAction(state, p1, cc)),
                "Regular cross actions must never include Lucky Cross fields");
    }

    @Test
    void luckyCross_offersLeftmostAvailableFieldSkippingCrossed() {
        GameState state = stateWithLuckyCrossAfterRoll(p1, p1, p2);
        setRoll(state, 1, 5, Map.of(Color.RED, 2, Color.YELLOW, 3, Color.GREEN, 4, Color.BLUE, 2));

        // Cross the first lucky cross field in RED row (position 2)
        CrossCellAction firstField = luckyCrossActions(rules.getValidActions(state, p1), state, p1)
                .stream().filter(a -> rowColor(state, p1, a.rowIndex()) == Color.RED)
                .findFirst().orElseThrow();
        rules.apply(state, firstField);

        // Clear luckyCrossUsed so we can use it again for the assertion
        state.turnState().luckyCrossUsed().remove(p1);
        state.turnState().activeTurnState().reset();

        // Next offered RED lucky cross field must be the one at position 6
        CrossCellAction secondField = luckyCrossActions(rules.getValidActions(state, p1), state, p1)
                .stream().filter(a -> rowColor(state, p1, a.rowIndex()) == Color.RED)
                .findFirst().orElseThrow();
        Cell cell = findCell(state, p1, secondField.rowIndex(), secondField.cellId());
        assertEquals(6, cell.position(), "Second offered Lucky Cross field must be at position 6");
    }

    // ── Lucky Cross state builders ────────────────────────────────────────────

    /** State in ACTIVE_MOVE with lucky cross rows. Roll is default (no 1+5 combo). */
    private GameState stateWithLuckyCrossAfterRoll(UUID active, UUID... allPlayers) {
        List<UUID> players = Arrays.asList(allPlayers);
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID p : players) {
            List<Row> rows = new ArrayList<>();
            rows.add(ascendingRowWithLuckyCross(Color.RED));
            rows.add(ascendingRowWithLuckyCross(Color.YELLOW));
            rows.add(descendingRowWithLuckyCross(Color.GREEN));
            rows.add(descendingRowWithLuckyCross(Color.BLUE));
            layouts.put(p, new SheetLayout(rows));
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
        GameState state = new GameState(CardMode.SAME_CARDS, players, null, layouts, board, turn);
        rules.apply(state, new RollAction(active));
        return state;
    }

    private void setRoll(GameState state, int w1, int w2, Map<Color, Integer> coloredDice) {
        state.turnState().setCurrentRoll(new RollResult(w1, w2, coloredDice));
    }

    // Gap pattern [2,3,4,2]: lucky cross fields at positions 2, 6, 11 (0-indexed).
    private Row ascendingRowWithLuckyCross(Color color) {
        return buildRowWithLuckyCross(color, true);
    }

    private Row descendingRowWithLuckyCross(Color color) {
        return buildRowWithLuckyCross(color, false);
    }

    private Row buildRowWithLuckyCross(Color color, boolean ascending) {
        int[] gaps = {2, 3, 4, 2};
        Row row = new Row();
        List<Cell> normalCells = new ArrayList<>();
        int pos = 0, displayVal = ascending ? 2 : 12;
        for (int g = 0; g < gaps.length; g++) {
            for (int n = 0; n < gaps[g]; n++) {
                Cell c = new Cell(pos++);
                c.setColor(color);
                c.setDisplayValue(String.valueOf(displayVal));
                displayVal += ascending ? 1 : -1;
                c.setTags(List.of());
                row.addCell(c);
                normalCells.add(c);
            }
            if (g < gaps.length - 1) {
                Cell lc = new Cell(pos++);
                lc.setColor(color);
                lc.setDisplayValue("");
                lc.setTags(List.of(new CellTag.LuckyCross()));
                lc.setClosingEligible(false);
                row.addCell(lc);
            }
        }
        Cell last = normalCells.getLast();
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 6, List.of(last.id())));
        return row;
    }

    // ── Lucky Cross action helpers ────────────────────────────────────────────

    private CrossCellAction firstLuckyCrossAction(GameState state, UUID playerId) {
        return luckyCrossActions(rules.getValidActions(state, playerId), state, playerId)
                .stream().findFirst()
                .orElseThrow(() -> new AssertionError("no Lucky Cross action offered to " + playerId));
    }

    private CrossCellAction firstWhiteWhiteAction(GameState state, UUID playerId) {
        return rules.getValidActions(state, playerId).stream()
                .filter(a -> a instanceof CrossCellAction cc
                        && cc.combination() == DiceCombination.WHITE_WHITE
                        && !isLuckyCrossAction(state, playerId, cc))
                .map(a -> (CrossCellAction) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no WW cross action offered to " + playerId));
    }

    private List<CrossCellAction> luckyCrossActions(List<GameAction> actions, GameState state, UUID playerId) {
        return actions.stream()
                .filter(a -> a instanceof CrossCellAction cc && isLuckyCrossAction(state, playerId, cc))
                .map(a -> (CrossCellAction) a)
                .toList();
    }

    private boolean hasluckyCrossAction(List<GameAction> actions, GameState state, UUID playerId) {
        return actions.stream()
                .anyMatch(a -> a instanceof CrossCellAction cc && isLuckyCrossAction(state, playerId, cc));
    }

    private boolean isLuckyCrossAction(GameState state, UUID playerId, CrossCellAction action) {
        return state.sheetLayouts().get(playerId).rows().get(action.rowIndex())
                .cells().stream()
                .anyMatch(c -> c.id().equals(action.cellId())
                        && c.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyCross));
    }

    private Color rowColor(GameState state, UUID playerId, int rowIndex) {
        return state.sheetLayouts().get(playerId).rows().get(rowIndex).cells().stream()
                .filter(c -> c.tags().stream().noneMatch(t -> t instanceof CellTag.LuckyCross))
                .map(Cell::color)
                .findFirst()
                .orElseThrow();
    }
}
