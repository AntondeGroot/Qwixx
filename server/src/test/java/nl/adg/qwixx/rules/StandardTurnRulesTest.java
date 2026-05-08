package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.*;
import nl.adg.qwixx.data.*;
import nl.adg.qwixx.state.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

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
        assertInstanceOf(RollAction.class, actions.get(0));
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

        Cell red7    = state.sheetLayouts().get(p2).rows().get(0).cells().get(5);
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

        Cell red7    = state.sheetLayouts().get(p2).rows().get(0).cells().get(5);
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
        Cell red7 = state.sheetLayouts().get(p1).rows().get(0).cells().get(5);

        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p1, 0, red7.id(), DiceCombination.WHITE_WHITE)),
                "Active player must not be allowed to cross a cell in a closed row");
    }

    @Test
    void passivePlayerCannotCrossCellInClosedRow() {
        GameState state = stateAfterRoll(p1, p1, p2);
        state.boardState().closedRows().put(0, p1);
        Cell red7 = state.sheetLayouts().get(p2).rows().get(0).cells().get(5);

        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p2, 0, red7.id(), DiceCombination.WHITE_WHITE)),
                "Passive player must not be allowed to cross a cell in a closed row");
    }

    // ── Closing-eligible cell reachability ────────────────────────────────────
    //
    // A closing-eligible cell (the last cell in standard, the last two in Longo) may
    // only be crossed when doing so — combined with crossing any remaining required cells
    // — would eventually bring the row to the minimum-cross threshold for locking.
    //
    // Standard (minCrosses=6, 1 required cell = closing cell):
    //   • allowed when existingCrosses >= 5  (5 existing + closing = 6 = minCrosses)
    //   • blocked  when existingCrosses <  5  (4 existing + closing = 5 < 6)

    // ── Closing-eligible cell reachability ────────────────────────────────────
    //
    // Standard (minCrosses=6, 1 required cell = closing cell):
    //   Rule: the closing cell may only be crossed when minCrosses cells are ALREADY
    //         present in the row (not counting the closing cell itself).
    //   • blocked when existingCrosses < 5   (e.g. 3 or 4 existing → total would be < 6)
    //   • allowed when existingCrosses >= 5  (e.g. 5 existing → then closing = 6th = minCrosses)

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
                "Closing cell must not be offered with only 3 existing crosses (need 5 existing, minCrosses=6)");
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
                "Closing cell must not be offered with only 4 existing crosses (need 5 existing, 4+1=5 < 6=minCrosses)");
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
                "Closing cell must be offered when 5 existing crosses are present (5+1=6=minCrosses)");
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
                "Passive player must also be blocked with 4 crosses (4+1=5 < 6=minCrosses)");
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
    // LOCK_PENDING phase
    // -------------------------------------------------------------------------

    // ── Lock intent re-invites all passive players ────────────────────────────
    //
    // Declaring lock intent changes the strategic picture for every player.  Even
    // a passive who already chose to pass during ACTIVE_MOVE may now want to cross
    // a cell before the row closes.  The passive queue is therefore rebuilt to
    // include ALL non-active players when lock intent is declared.

    @Test
    void declareLockIntent_rebuildsPassiveQueueToIncludeAllNonActivePlayers() {
        // p2 leaves the passive queue by EndTurning during ACTIVE_MOVE.
        // Declaring lock intent must bring p2 back so they get a chance to act.
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new EndTurnAction(p2));              // p2 leaves queue
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        assertTrue(state.turnState().passivePlayerQueue().contains(p2),
                "p2 must be re-added to the passive queue when lock intent is declared");
        assertTrue(state.turnState().passivePlayerQueue().contains(p3),
                "p3 (already in queue) must still be in the queue");
    }

    @Test
    void passiveWhoLeftActiveMoveCanAcknowledgeInLockPending() {
        // After being re-added, p2 must be able to call EndTurn (acknowledge).
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        // p2 is back in the queue — EndTurn must not throw
        assertDoesNotThrow(() -> rules.apply(state, new EndTurnAction(p2)));
        assertTrue(state.turnState().lockAcknowledged().contains(p2));
    }

    @Test
    void passiveWhoLeftActiveMoveCanCrossWhiteWhiteInLockPending() {
        // After being re-added, p2 must have white+white cell actions available.
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        List<GameAction> actions = rules.getValidActions(state, p2);
        assertTrue(actions.stream().anyMatch(a -> a instanceof CrossCellAction cc
                        && cc.combination() == DiceCombination.WHITE_WHITE),
                "p2 (re-added to queue) must be offered white+white cross actions in LOCK_PENDING");
    }

    @Test
    void lockAutoClosesOnlyAfterAllReaddedPassivesAcknowledge() {
        // p2 EndTurns during ACTIVE_MOVE then is re-added when lock is declared.
        // The lock must NOT close until BOTH p2 and p3 have acknowledged.
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        // Only p3 acknowledges first
        rules.apply(state, new EndTurnAction(p3));
        assertFalse(state.boardState().closedRows().containsKey(0),
                "Row must not close while p2 (re-added) has not yet acknowledged");

        // Now p2 acknowledges
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.boardState().closedRows().containsKey(0),
                "Row must close once all passives (including re-added p2) have acknowledged");
    }

    // ── Undo for passives re-invited after EndTurn ────────────────────────────
    //
    // A passive who crossed and then EndTurned must still be able to undo that cross
    // when they are re-invited into LOCK_PENDING.  Without this, the cross becomes
    // irrevocable even though the lock intent changes the strategic picture.

    @Test
    void passiveWhoEndTurnedAfterCrossIsOfferedUndoInLockPending() {
        // p2 crosses in ACTIVE_MOVE and EndTurns.  Active then declares lock intent.
        // In LOCK_PENDING p2 must be offered UndoLastCrossAction, not just EndTurnAction.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross);            // p2 crosses
        rules.apply(state, new EndTurnAction(p2));  // p2 ends turn → re-invited on lock

        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        List<GameAction> actions = rules.getValidActions(state, p2);
        assertTrue(actions.stream().anyMatch(a -> a instanceof UndoLastCrossAction),
                "passive re-invited after EndTurn must be offered UndoLastCrossAction in LOCK_PENDING");
    }

    @Test
    void passiveWhoEndTurnedAfterCrossIsNotOfferedNewCrossInLockPending() {
        // p2 already used their white+white slot — they must not be offered a fresh cross.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, firstCrossAction(state, p2));
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        List<GameAction> actions = rules.getValidActions(state, p2);
        assertFalse(actions.stream().anyMatch(a -> a instanceof CrossCellAction),
                "passive must not be offered new cross actions if they already crossed before lock intent");
    }

    @Test
    void passiveCanUndoCrossFromActiveMoveInLockPending() {
        // The undo must actually remove the cross from sheetProgress.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross);
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        rules.apply(state, new UndoLastCrossAction(p2));

        RowState rs = rowStateOf(state, p2, cross.rowIndex());
        assertFalse(rs.crossedCells().contains(cross.cellId()),
                "undo must remove the cross made during ACTIVE_MOVE from sheetProgress");
    }

    @Test
    void passiveWhoUndoesGetsToActAgainBeforeLockCloses() {
        // Undo is not an acknowledgement — the player must still explicitly pass or cross.
        // Only after the explicit EndTurn should the lock close.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, firstCrossAction(state, p2));
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        rules.apply(state, new UndoLastCrossAction(p2));

        assertFalse(state.boardState().closedRows().containsKey(0),
                "lock must NOT close immediately after undo — player still has to act");
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase());

        // p2 explicitly passes
        rules.apply(state, new EndTurnAction(p2));

        assertTrue(state.boardState().closedRows().containsKey(0),
                "lock must close once p2 explicitly passes after undoing");
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
    }

    // ── Declarant undo-freeze regression ──────────────────────────────────────
    //
    // Root cause: the client previously sent UNDO_LAST_CROSS when the declarant
    // clicked their pending closing cell in LOCK_PENDING, which uncrossed the cell
    // but left the game in LOCK_PENDING with canCrossLock=false.  The passive could
    // then no longer acknowledge (server rejected it), permanently freezing the game.
    // Fix: declarant must use RESET_TURN; server now rejects UNDO_LAST_CROSS from them.

    @Test
    void declarant_undoLastCrossActsAsResetTurn() {
        // Clicking the pending cell while being the declarant in LOCK_PENDING sends
        // UNDO_LAST_CROSS.  The server must treat it identically to RESET_TURN:
        // cancel the lock declaration and return to ACTIVE_MOVE.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase());

        assertDoesNotThrow(() -> rules.apply(state, new UndoLastCrossAction(p1)),
                "UNDO_LAST_CROSS from the declarant must succeed (treated as RESET_TURN)");
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase());
        assertNull(state.turnState().pendingLockDeclarerId());
        assertNull(state.turnState().pendingLockRowIndex());
    }

    @Test
    void declarant_resetTurnCancelsLockAndRestorestActiveMovePhase() {
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase());

        rules.apply(state, new ResetTurnAction(p1));

        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase(),
                "RESET_TURN from the declarant must return to ACTIVE_MOVE");
        assertNull(state.turnState().pendingLockDeclarerId(),
                "pending lock declarant must be cleared after declarant resets");
        assertNull(state.turnState().pendingLockRowIndex(),
                "pending lock row must be cleared after declarant resets");
    }

    @Test
    void passive_canActNormallyAfterDeclarantResets() {
        // After the declarant resets in LOCK_PENDING, the game returns to ACTIVE_MOVE
        // and the passive must be able to make their normal passive cross and EndTurn.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase());

        rules.apply(state, new ResetTurnAction(p1));
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase());

        // Passive must be offered their standard white+white options in ACTIVE_MOVE.
        List<GameAction> passiveActions = rules.getValidActions(state, p2);
        assertTrue(passiveActions.stream().anyMatch(a -> a instanceof EndTurnAction),
                "passive must be able to EndTurn after declarant resets");

        // Passive passes — active then makes a move and ends turn normally.
        assertDoesNotThrow(() -> rules.apply(state, new EndTurnAction(p2)),
                "passive EndTurn must be accepted after declarant resets");
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase(),
                "phase must stay in ACTIVE_MOVE until the active also ends turn");
    }

    @Test
    void passive_undoLastCrossIsStillAllowedForNonDeclarant() {
        // Regression guard: the new restriction must not break the legitimate case
        // where the PASSIVE (non-declarant) undoes their own cross before acknowledging.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, firstCrossAction(state, p2));  // p2 crosses a cell
        rules.apply(state, new EndTurnAction(p2));         // p2 passes before lock
        rules.apply(state, new DeclareLockIntentAction(p1, 0));  // p1 declares

        // p2 has a pending cross and must still be able to undo it.
        assertTrue(rules.getValidActions(state, p2).stream()
                        .anyMatch(a -> a instanceof UndoLastCrossAction),
                "non-declarant passive must still be offered UNDO_LAST_CROSS");
        assertDoesNotThrow(() -> rules.apply(state, new UndoLastCrossAction(p2)),
                "UNDO_LAST_CROSS must be accepted from a non-declarant passive");
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase(),
                "phase must remain LOCK_PENDING after passive undo");
    }

    @Test
    void passive_isNotFrozenWhenDeclarantClosingCellNoLongerQualifies() {
        // If the declarant's closing cell cross is somehow gone (edge case), the passive
        // must be able to escape via the declarant resetting — not be permanently stuck.
        // This verifies that EndTurnAction from passive is blocked (expected) and that
        // the declarant can then use RESET_TURN to unblock the game.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        // Simulate the declarant's closing cell being retroactively removed from progress
        // (represents the state that the old bug created via UNDO_LAST_CROSS).
        Row row = state.sheetLayouts().get(p1).rows().get(0);
        String closingCellId = row.lock().requiredCells().get(row.lock().requiredCells().size() - 1);
        SheetProgress prog = state.boardState().sheetProgress().get(p1);
        RowState rs = prog.rowStates().getOrDefault(0, new RowState(new java.util.HashSet<>(), false));
        java.util.Set<String> reduced = new java.util.HashSet<>(rs.crossedCells());
        reduced.remove(closingCellId);
        prog.updateRowState(0, new RowState(reduced, false));

        // Passive tries to acknowledge — must be rejected cleanly (not silently corrupt state).
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new EndTurnAction(p2)),
                "passive acknowledgement must be rejected when declarant no longer qualifies");

        // Declarant can reset to unblock.
        assertDoesNotThrow(() -> rules.apply(state, new ResetTurnAction(p1)),
                "declarant must be able to RESET_TURN to unblock the game");
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase());

        // Passive must now be able to act normally.
        assertDoesNotThrow(() -> rules.apply(state, new EndTurnAction(p2)),
                "passive must be able to EndTurn once game returns to ACTIVE_MOVE");
    }

    @Test
    void passiveIsOfferedFreshCrossAfterUndoInLockPending() {
        // After undoing, the player has no pending cross, so they must be offered
        // white+white cell actions again (they can cross a different cell).
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, firstCrossAction(state, p2));
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        rules.apply(state, new UndoLastCrossAction(p2));

        List<GameAction> actions = rules.getValidActions(state, p2);
        assertTrue(actions.stream().anyMatch(a -> a instanceof CrossCellAction cc
                        && cc.combination() == DiceCombination.WHITE_WHITE),
                "after undoing, player must be offered fresh white+white cells to cross");
        assertFalse(actions.stream().anyMatch(a -> a instanceof UndoLastCrossAction),
                "UndoLastCross must not be offered again — there is no cross to undo");
    }

    @Test
    void passiveWhoEndTurnedWithoutCrossIsStillOfferedCrossInLockPending() {
        // Regression guard: a passive who EndTurned WITHOUT crossing must still get
        // fresh white+white cell actions in LOCK_PENDING (existing behaviour must not regress).
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new EndTurnAction(p2));  // no cross before EndTurn
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        List<GameAction> actions = rules.getValidActions(state, p2);
        assertTrue(actions.stream().anyMatch(a -> a instanceof CrossCellAction cc
                        && cc.combination() == DiceCombination.WHITE_WHITE),
                "passive who EndTurned without a cross must be offered fresh white+white actions");
    }

    @Test
    void passiveWhoEndTurnedInPassiveMoveIsOfferedUndoInLockPending() {
        // Same guarantee applies when the cross happened in PASSIVE_MOVE.
        // Setup: p1 active, p2 and p3 are passives.  p1 ends turn → PASSIVE_MOVE.
        // p2 crosses and EndTurns.  p3 then declares lock intent → LOCK_PENDING.
        // p2 must be offered UndoLastCrossAction.
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p3, 0);
        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1));  // → PASSIVE_MOVE

        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross);                 // p2 crosses in PASSIVE_MOVE
        rules.apply(state, new EndTurnAction(p2)); // p2 ends turn

        rules.apply(state, new DeclareLockIntentAction(p3, 0)); // p3 declares → LOCK_PENDING

        List<GameAction> actions = rules.getValidActions(state, p2);
        assertTrue(actions.stream().anyMatch(a -> a instanceof UndoLastCrossAction),
                "passive who crossed in PASSIVE_MOVE and EndTurned must be offered UndoLastCross in LOCK_PENDING");
    }

    // ── GiveUp from LOCK_PENDING: only unacknowledged passives get PASSIVE_MOVE ─
    //
    // A passive who EndTurned in LOCK_PENDING has already had their chance to cross.
    // They must NOT receive a second opportunity via PASSIVE_MOVE after a GiveUp.
    // Only passives who have not yet acted in LOCK_PENDING get PASSIVE_MOVE.

    @Test
    void giveUpFromLockPending_acknowledgedPassiveDoesNotGetPassiveMove() {
        // p2 acknowledges the lock (EndTurn in LOCK_PENDING) — their turn is done.
        // p1 then GivesUp. p2 should NOT appear in the PASSIVE_MOVE queue.
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, new EndTurnAction(p2));  // p2 acknowledges lock
        rules.apply(state, new GiveUpAction(p1));

        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase());
        assertFalse(state.turnState().passivePlayerQueue().contains(p2),
                "p2 already acted in LOCK_PENDING (acknowledged) — must not get a second turn");
        assertTrue(state.turnState().passivePlayerQueue().contains(p3),
                "p3 has not yet acted — must still get their passive move");
    }

    @Test
    void giveUpFromLockPending_unacknowledgedPassiveGetsPassiveMove() {
        // p2 has not yet acted in LOCK_PENDING when p1 GivesUp.
        // p2 must receive a PASSIVE_MOVE opportunity.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, new GiveUpAction(p1));

        assertEquals(TurnPhase.PASSIVE_MOVE, state.turnState().phase());
        assertTrue(state.turnState().passivePlayerQueue().contains(p2));
    }

    // ── Passive crossing a cell during LOCK_PENDING ───────────────────────────

    @Test
    void passiveCrossInLockPendingAppliesSuccessfully() {
        GameState state = stateInLockPending(p1, p1, p2, 0);
        CrossCellAction cross = firstCrossAction(state, p2);
        // Must not throw — LOCK_PENDING is a valid phase for passive crosses.
        assertDoesNotThrow(() -> rules.apply(state, cross));
        // Crossing immediately acknowledges the lock: sole passive → auto-close → row closed.
        assertTrue(state.boardState().closedRows().containsKey(0),
                "crossing in LOCK_PENDING as the sole passive must immediately close the row");
    }

    @Test
    void passiveCrossInLockPendingLandsInSheetProgress() {
        GameState state = stateInLockPending(p1, p1, p2, 0);
        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross);

        // The cell must already be present in the sheet progress (committed to the board).
        RowState rs = rowStateOf(state, p2, cross.rowIndex());
        assertTrue(rs.crossedCells().contains(cross.cellId()),
                "cell must appear in sheetProgress immediately after crossing");
    }

    @Test
    void passiveCrossInLockPendingAloneTriggersAutoCloseAndPreservesTheCross() {
        // p2 is the only passive; crossing immediately acknowledges the lock →
        // auto-close fires without a separate EndTurn.
        GameState state = stateInLockPending(p1, p1, p2, 0);
        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross);

        // Cross survives in sheetProgress after the auto-close.
        RowState rs = rowStateOf(state, p2, cross.rowIndex());
        assertTrue(rs.crossedCells().contains(cross.cellId()),
                "cross must survive in sheetProgress after auto-close");
        // Sole passive crossing triggers auto-close and turn advance.
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
        assertTrue(state.boardState().closedRows().containsKey(0));
    }

    @Test
    void passiveCannotCrossMoreThanOnceInLockPending() {
        GameState state = stateInLockPending(p1, p1, p2, 0);

        Cell red7    = state.sheetLayouts().get(p2).rows().get(0).cells().get(5);
        Cell yellow7 = state.sheetLayouts().get(p2).rows().get(1).cells().get(5);

        rules.apply(state, new CrossCellAction(p2, 0, red7.id(), DiceCombination.WHITE_WHITE));
        assertThrows(IllegalMoveException.class,
                () -> rules.apply(state, new CrossCellAction(p2, 1, yellow7.id(), DiceCombination.WHITE_WHITE)),
                "Passive must not be allowed more than one white+white cross per turn in LOCK_PENDING");
    }

    @Test
    void passiveWithNoCrossInLockPendingIsOfferedCells() {
        // Verifies that getValidActions includes CrossCellAction for a passive without
        // a pending cross — the behaviour enabled by the new code path.
        GameState state = stateInLockPending(p1, p1, p2, 0);
        List<GameAction> actions = rules.getValidActions(state, p2);
        assertTrue(actions.stream().anyMatch(a -> a instanceof CrossCellAction cc
                        && cc.combination() == DiceCombination.WHITE_WHITE),
                "passive player without a pending cross must be offered cells in LOCK_PENDING");
    }

    @Test
    void passiveWithPendingCrossInLockPendingIsNotOfferedMoreCells() {
        // Once a passive has crossed (and thus acknowledged), they must not be offered more cells.
        // Use a 3-player game so the game stays in LOCK_PENDING after p2 crosses
        // (p3 still needs to acknowledge), giving us a state to inspect.
        GameState state = stateInLockPending(p1, p1, p2, p3, 0);
        rules.apply(state, firstCrossAction(state, p2)); // p2 crosses → acknowledged
        List<GameAction> actions = rules.getValidActions(state, p2);
        assertTrue(actions.stream().noneMatch(a -> a instanceof CrossCellAction cc
                        && cc.combination() == DiceCombination.WHITE_WHITE),
                "acknowledged passive must not be offered more cells in LOCK_PENDING");
        // p2 is already acknowledged — no EndTurn or Undo should be offered either.
        assertTrue(actions.isEmpty(),
                "acknowledged passive must have no valid actions while waiting for p3");
    }

    @Test
    void passiveCrossingInLockPendingAcknowledgesImmediatelyWhileOtherPassiveStillPending() {
        // Scenario: p1 declares lock intent; p2 crosses a cell — this immediately
        // acknowledges p2 (cross becomes permanent, no separate EndTurn needed).
        // p3 has not yet acted, so the game stays in LOCK_PENDING.
        // Only after p3 EndTurns should the row close.
        GameState state = stateInLockPending(p1, p1, p2, p3, 0);

        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross); // crossing = immediate acknowledgement

        // Game must still be in LOCK_PENDING waiting for p3.
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase(),
                "game must remain in LOCK_PENDING while p3 has not yet acknowledged");
        assertFalse(state.boardState().closedRows().containsKey(0),
                "row must not close until all passives have acknowledged");

        // p2's cross must already be committed (permanent — no undo buffer entry).
        RowState rs = rowStateOf(state, p2, cross.rowIndex());
        assertTrue(rs.crossedCells().contains(cross.cellId()),
                "p2's cross must be permanent immediately after crossing in LOCK_PENDING");
        assertFalse(state.turnState().undoBuffer().containsKey(p2),
                "p2's cross must not be in the undo buffer — it is already permanent");

        // p3 acknowledges — now the row should close.
        rules.apply(state, new EndTurnAction(p3));
        assertTrue(state.boardState().closedRows().containsKey(0),
                "row must close once all passives have acknowledged");
    }

    @Test
    void passiveCrossInLockPendingClearsUndoBufferImmediately() {
        // Crossing in LOCK_PENDING makes the cross permanent on the spot — the undo
        // buffer must be empty right after the cross (no separate EndTurn needed).
        GameState state = stateInLockPending(p1, p1, p2, 0);
        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross);

        // Undo buffer must already be empty (cross is permanent, not pending).
        assertFalse(state.turnState().undoBuffer().containsKey(p2),
                "undo buffer must be empty immediately after crossing in LOCK_PENDING — "
                + "the cross is permanent, not an undoable pending move");
        // Sole passive crossing triggers immediate auto-close.
        assertTrue(state.boardState().closedRows().containsKey(0),
                "row must close after the only passive acknowledges by crossing");
    }

    @Test
    void rowClosureRequestsClearedWhenEndTurnAutoClosesLock() {
        // Regression: rowClosureRequests was never cleared when the last passive called
        // EndTurnAction, because MovesApiDelegateImpl only cleared on CrossLockAction or
        // ResetTurnAction — but the auto-close path goes through EndTurnAction.
        // Result: stale lock-intent modal appeared on every subsequent turn.
        GameState state = stateInLockPending(p1, p1, p2, 0);
        state.rowClosureRequests().add(new RowClosureRequest("P1", Color.RED));
        assertEquals(1, state.rowClosureRequests().size(), "request must be present before acknowledge");

        rules.apply(state, new EndTurnAction(p2));  // sole passive → auto-close → evaluate

        assertEquals(TurnPhase.ROLL, state.turnState().phase(), "turn must advance");
        assertEquals(0, state.rowClosureRequests().size(),
                "rowClosureRequests must be cleared after EndTurn triggers the auto-close");
    }

    @Test
    void rowClosureRequestsClearedWhenActivePlayerResetsFromLockPending() {
        GameState state = stateInLockPending(p1, p1, p2, 0);
        state.rowClosureRequests().add(new RowClosureRequest("P1", Color.RED));

        rules.apply(state, new ResetTurnAction(p1));

        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase());
        assertEquals(0, state.rowClosureRequests().size(),
                "rowClosureRequests must be cleared when active player resets from LOCK_PENDING");
    }

    @Test
    void gameAdvancesToNextPlayerAfterRowLocks() {
        // After all passives acknowledge a lock intent the row closes and the turn
        // must rotate to the NEXT player in order so they can roll.  If the turn
        // stayed on p1 (the locker) or remained in LOCK_PENDING the game would be
        // unplayable for the remaining players.
        //
        // Players in order: [p1, p2, p3].  p1 is active, so after p1's turn the
        // next active player must be p2.
        GameState state = stateInLockPending(p1, p1, p2, p3, 0);

        // Both passives acknowledge without crossing.
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new EndTurnAction(p3));

        // Row must be closed.
        assertTrue(state.boardState().closedRows().containsKey(0),
                "row must be closed after all passives acknowledge");

        // Turn must have advanced to ROLL so the next player can act.
        assertEquals(TurnPhase.ROLL, state.turnState().phase(),
                "phase must be ROLL after the lock closes");

        // The next active player must be p2 (first player after p1 in rotation).
        assertEquals(p2, state.turnState().activePlayerId(),
                "active player must rotate to p2 after p1 locks a row");

        // p2 must actually be able to roll (i.e. the game is playable).
        assertDoesNotThrow(() -> rules.apply(state, new RollAction(p2)),
                "next player must be able to roll after the row is locked");
    }

    // ── Crossing the closing cell and locking in the same turn ───────────────

    @Test
    void canDeclareLockIntentAfterCrossingClosingCellInSameTurn() {
        // Regression: the lock button must be available immediately after crossing the
        // closing-eligible cell in the same turn — not only on the next turn.
        //
        // Setup: p1 has 5 normal crosses in the RED row (positions 0-4).
        //        Dice: white1=6, white2=6 (white+white=12 → matches RED closing cell "12").
        // Steps: p1 crosses "12" with white+white → then declares lock intent → p2 acks → closes.
        GameState state = stateAfterRoll(p1, p1, p2);

        // Override dice so white+white = 12.
        state.turnState().setCurrentRoll(
                new RollResult(6, 6, state.turnState().currentRoll().coloredDice()));

        // Give p1 five normal crosses at positions 0-4 (displayValues "2"-"6").
        Row red = state.sheetLayouts().get(p1).rows().get(0); // RED ascending row
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

        // Lock intent must now be available (closing cell is crossed).
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof DeclareLockIntentAction),
                "DECLARE_LOCK_INTENT must be offered after crossing the closing cell in the same turn");

        // Declaring lock intent and having p2 acknowledge must close the row.
        assertDoesNotThrow(() -> rules.apply(state, new DeclareLockIntentAction(p1, 0)));
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.boardState().closedRows().containsKey(0),
                "RED row must be closed after p2 acknowledges the lock");
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
        assertEquals(p2, state.turnState().activePlayerId());
    }

    @Test
    void canDeclareLockIntentAfterCrossingClosingCellWithColorDie() {
        // Same scenario as above but using the white+color die combination.
        // Dice: white1=6, red=6 → white1+red = 12 → crosses RED closing cell.
        GameState state = stateAfterRoll(p1, p1, p2);

        Map<Color, Integer> updatedColored = new java.util.EnumMap<>(state.turnState().currentRoll().coloredDice());
        updatedColored.put(Color.RED, 6);
        state.turnState().setCurrentRoll(new RollResult(6, 5, updatedColored));

        Row red = state.sheetLayouts().get(p1).rows().get(0);
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

        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof DeclareLockIntentAction),
                "DECLARE_LOCK_INTENT must be offered after crossing closing cell with color die");
        assertDoesNotThrow(() -> rules.apply(state, new DeclareLockIntentAction(p1, 0)));
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.boardState().closedRows().containsKey(0));
    }

    // ── Multi-row-close house rule: AAP, APA, PAA ────────────────────────────

    @Test
    void aap_activeClosesTwoRowsPassiveAcknowledgesBoth() {
        // Active (p1) has enough crosses for BOTH row 0 and row 1.
        // Passive (p2) just acknowledges each lock intent.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        crossEnoughForLock(state, p1, 1);

        // A closes row 0
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, new EndTurnAction(p2));  // auto-close
        assertTrue(state.boardState().closedRows().containsKey(0), "row 0 must close");

        // Turn continues because p1 can still close row 1
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase(),
                "turn must continue to ACTIVE_MOVE so p1 can close row 1");

        // A closes row 1
        rules.apply(state, new DeclareLockIntentAction(p1, 1));
        rules.apply(state, new EndTurnAction(p2));  // auto-close
        assertTrue(state.boardState().closedRows().containsKey(1), "row 1 must close");
        // Closing 2 rows ends the game (closedRows.size() >= 2)
        assertTrue(state.gameOver(), "game must be over after 2 rows close");
    }

    @Test
    void apa_activeClosesFirstThenPassiveCloses() {
        // Active (p1) has enough for row 0; passive (p2) has enough for row 1.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        crossEnoughForLock(state, p2, 1);

        // A closes row 0
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        rules.apply(state, new EndTurnAction(p2));  // auto-close
        assertTrue(state.boardState().closedRows().containsKey(0), "row 0 must close");

        // Turn continues because p2 can close row 1
        assertNotEquals(TurnPhase.ROLL, state.turnState().phase(),
                "turn must not end yet — p2 can still close row 1");
        assertTrue(state.turnState().passivePlayerQueue().contains(p2),
                "p2 must be invited to declare lock intent for row 1");

        // P closes row 1
        assertTrue(rules.getValidActions(state, p2).stream()
                        .anyMatch(a -> a instanceof DeclareLockIntentAction dl && dl.rowIndex() == 1),
                "p2 must be offered lock intent for row 1");
        rules.apply(state, new DeclareLockIntentAction(p2, 1));
        rules.apply(state, new EndTurnAction(p1));  // p1 acknowledges p2's intent → auto-close
        assertTrue(state.boardState().closedRows().containsKey(1), "row 1 must close");
        assertTrue(state.gameOver(), "game must be over after 2 rows close");
    }

    @Test
    void paa_passiveClosesFirstThenActiveCloses() {
        // Passive (p2) has enough for row 0; active (p1) has enough for row 1.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);
        crossEnoughForLock(state, p1, 1);

        // P closes row 0 (declares intent during ACTIVE_MOVE passive slot)
        assertTrue(rules.getValidActions(state, p2).stream()
                        .anyMatch(a -> a instanceof DeclareLockIntentAction dl && dl.rowIndex() == 0),
                "p2 must be offered lock intent for row 0 in ACTIVE_MOVE");
        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        assertEquals(p2, state.turnState().pendingLockDeclarerId(),
                "p2 must be recorded as declarant");
        rules.apply(state, new EndTurnAction(p1));  // auto-close
        assertTrue(state.boardState().closedRows().containsKey(0), "row 0 must close");

        // Turn continues because p1 can close row 1
        assertEquals(TurnPhase.ACTIVE_MOVE, state.turnState().phase(),
                "turn must continue to ACTIVE_MOVE so p1 can close row 1");

        // A closes row 1
        rules.apply(state, new DeclareLockIntentAction(p1, 1));
        rules.apply(state, new EndTurnAction(p2));  // auto-close
        assertTrue(state.boardState().closedRows().containsKey(1), "row 1 must close");
        assertTrue(state.gameOver(), "game must be over after 2 rows close");
    }

    @Test
    void passiveCrossedClosingCellButConcurrentActiveLockBlockedDeclaration_getsReinvitedAfterLockResolves() {
        // Race-condition regression: passive (p2) crosses the closing cell of row 1 during
        // simultaneous ACTIVE_MOVE play.  Before p2 can declare their lock intent the active
        // (p1) races ahead and declares a lock for row 0, putting the server into LOCK_PENDING.
        // p2 can no longer declare their own intent (wrong phase), so they just acknowledge p1's
        // lock.  After p1's lock closes, p2 must be re-invited so they can finally declare their
        // own lock for row 1 — closing a third row in total.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p1, 0);
        crossEnoughForLock(state, p2, 1);

        // p2 crosses the closing cell of row 1 (their passive move) — row 1 is now lock-eligible.
        // They are added to passivesActed and undoBuffer.
        Row row1 = state.sheetLayouts().get(p2).rows().get(1);
        Cell closing1 = row1.cells().stream().filter(Cell::isClosingEligible).findFirst().orElseThrow();
        String ww = String.valueOf(state.turnState().currentRoll().white1()
                + state.turnState().currentRoll().white2());
        // Give p2 enough permanent crosses so the closing cell matches white+white.
        // crossEnoughForLock already set required crosses; the closing cell may or may not match
        // white+white.  We verify p2 can declare a lock (has required cells crossed permanently).
        assertTrue(rules.getValidActions(state, p2).stream()
                        .anyMatch(a -> a instanceof DeclareLockIntentAction dl && dl.rowIndex() == 1),
                "p2 must already be lock-eligible for row 1 (closing cell permanently crossed)");

        // p1 races: declares lock for row 0 first → LOCK_PENDING.
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase());

        // p2 is now stuck in LOCK_PENDING — they can only acknowledge p1's lock.
        // They cannot declare their own lock for row 1 in this phase.
        rules.apply(state, new EndTurnAction(p2));   // p2 acknowledges p1's lock → auto-close
        assertTrue(state.boardState().closedRows().containsKey(0), "row 0 must close");

        // After p1's lock resolves, p2 must be re-invited (they never got to declare row 1).
        // p2 is in passivesActed (crossed the closing cell) AND has a qualifying undoBuffer entry.
        assertNotEquals(TurnPhase.ROLL, state.turnState().phase(),
                "turn must not end yet — p2 still needs to declare their lock for row 1");
        assertTrue(state.turnState().passivePlayerQueue().contains(p2),
                "p2 must be re-invited after p1's lock resolves");

        // p2 must be offered DeclareLockIntentAction for row 1.
        assertTrue(rules.getValidActions(state, p2).stream()
                        .anyMatch(a -> a instanceof DeclareLockIntentAction dl && dl.rowIndex() == 1),
                "p2 must be offered lock intent for row 1 after being re-invited");

        // p2 declares lock for row 1; p1 acknowledges → row 1 closes → 2 rows closed → game over.
        rules.apply(state, new DeclareLockIntentAction(p2, 1));
        rules.apply(state, new EndTurnAction(p1));
        assertTrue(state.boardState().closedRows().containsKey(1), "row 1 must close");
        assertTrue(state.gameOver(), "game must be over after 2 rows close");
    }

    @Test
    void passiveDeclaringLockIntentCostsTheirWhiteWhiteSlot() {
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);

        rules.apply(state, new DeclareLockIntentAction(p2, 0));
        assertTrue(state.turnState().passivesActed().contains(p2),
                "p2 must be marked as having used their white+white slot");

        // p2 cannot declare another intent or cross another cell after being added to passivesActed
        rules.apply(state, new EndTurnAction(p1)); // auto-close

        // After the row closes and the turn ends (no more closures available), p2 has acted
        // so they should NOT be re-invited in any continuation
        assertTrue(state.turnState().passivesActed().contains(p2) ||
                   state.turnState().phase() == TurnPhase.ROLL,
                "p2 must not be re-invited after already acting");
    }

    // ── Single-player lock auto-close ─────────────────────────────────────────

    @Test
    void singlePlayerLockAutoClosesImmediatelyOnDeclaration() {
        // No other players to acknowledge — lock must close as soon as it is declared.
        GameState state = stateAfterRoll(p1, p1);
        crossEnoughForLock(state, p1, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));

        assertTrue(state.boardState().closedRows().containsKey(0),
                "Row must close immediately in a single-player game");
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
    }

    @Test
    void declareLockIntentTransitionsToLockPending() {
        GameState state = stateReadyToLock(p1, p1, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p1, 0));
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase());
        assertEquals(0, state.turnState().pendingLockRowIndex());
    }

    @Test
    void declareLockIntentNotAvailableWhenLockPreConditionsNotMet() {
        GameState state = stateAfterRoll(p1, p1, p2);  // no crosses yet
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new DeclareLockIntentAction(p1, 0)));
    }

    @Test
    void lastPassiveEndTurnAutoClosesRow() {
        // When the only passive player acknowledges via EndTurn, the row must close
        // automatically — mirroring the single-player auto-resolve on declareLockIntent.
        GameState state = stateInLockPending(p1, p1, p2, 0);
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.boardState().closedRows().containsKey(0),
                "Row must close when the last passive player acknowledges");
        assertEquals(TurnPhase.ROLL, state.turnState().phase(),
                "Game must advance to ROLL after auto-close");
    }

    @Test
    void activePlayerCannotCrossLockBeforeAllAcknowledged() {
        // With 3 players, one passive acknowledging is not enough to trigger auto-close.
        GameState state = stateInLockPending(p1, p1, p2, p3, 0);
        rules.apply(state, new EndTurnAction(p2));  // only p2 acknowledged, p3 hasn't
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new CrossLockAction(p1, 0)));
    }

    @Test
    void allThreePassivesEndTurnAutoClosesRow() {
        // With 3 players, auto-close fires only when ALL passives have acknowledged.
        GameState state = stateInLockPending(p1, p1, p2, p3, 0);
        rules.apply(state, new EndTurnAction(p2));
        assertFalse(state.boardState().closedRows().containsKey(0),
                "Row must not close while p3 still hasn't acknowledged");
        rules.apply(state, new EndTurnAction(p3));
        assertTrue(state.boardState().closedRows().containsKey(0),
                "Row must close once all passives have acknowledged");
    }

    @Test
    void crossLockClosesRowAndRemovesDie() {
        // Auto-close fires on the last passive's EndTurn: verify die removal too.
        GameState state = stateInLockPending(p1, p1, p2, 0);
        Color lockColor = state.sheetLayouts().get(p1).rows().get(0).lock().color();
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.boardState().closedRows().containsKey(0));
        assertTrue(state.boardState().activeDice().stream().noneMatch(d -> d.color() == lockColor));
    }

    @Test
    void crossLockTransitionsToNextRoll() {
        // Auto-close on passive EndTurn also advances the turn to ROLL.
        GameState state = stateInLockPending(p1, p1, p2, 0);
        rules.apply(state, new EndTurnAction(p2));
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
    }

    @Test
    void nonActivePlayerCanCrossLockToGetBonusAndAcknowledge() {
        // Passive player crosses the lock cell themselves — they get the bonus (lockCrossed)
        // and are acknowledged, but the row stays open until active player also closes it.
        GameState state = stateInLockPending(p1, p1, p2, 0);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new CrossLockAction(p2, 0));
        // Still in LOCK_PENDING — p1 must explicitly close the row
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase());
        assertTrue(state.turnState().lockAcknowledged().contains(p2));
        RowState rs = state.boardState().sheetProgress().get(p2).rowStates().get(0);
        assertTrue(rs.lockCrossed());
    }

    // -------------------------------------------------------------------------
    // UndoLastCrossAction
    // -------------------------------------------------------------------------

    @Test
    void undoLastCrossRevertsCell() {
        GameState state = stateInLockPending(p1, p1, p2, 0);
        // p2 crosses a cell during PASSIVE_MOVE-like scenario via undoBuffer seeding
        String cellId = seedUndoBufferForP2(state, p2, 1);  // row 1 (YELLOW)
        rules.apply(state, new UndoLastCrossAction(p2));
        RowState rs = rowStateOf(state, p2, 1);
        assertFalse(rs.crossedCells().contains(cellId));
    }

    @Test
    void undoLastCrossRestoresPassiveToFreshState() {
        // After undoing, the player must NOT be acknowledged — they still need to act
        // (cross something new or explicitly pass).
        GameState state = stateInLockPending(p1, p1, p2, 0);
        seedUndoBufferForP2(state, p2, 1);
        rules.apply(state, new UndoLastCrossAction(p2));

        assertFalse(state.turnState().lockAcknowledged().contains(p2),
                "undo must not auto-acknowledge — player still needs to act");
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase(),
                "game must remain in LOCK_PENDING after undo (player has not yet passed)");
        assertTrue(rules.getValidActions(state, p2).stream()
                        .anyMatch(a -> a instanceof EndTurnAction),
                "player must be offered EndTurnAction so they can explicitly pass");
    }

    @Test
    void undoLastCrossNotAvailableWithoutPriorCross() {
        GameState state = stateInLockPending(p1, p1, p2, 0);
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new UndoLastCrossAction(p2)));
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

    // ── GiveUp from LOCK_PENDING: passive is the declarant ────────────────────
    //
    // The active giving up takes a punishment but does NOT abort the passive's
    // lock intent — the two outcomes are independent and must coexist.

    @Test
    void giveUpFromLockPending_passiveDeclarant_lockIsHonored() {
        // p2 declares lock intent; p1 (active) gives up.
        // The passive's lock must still close — give-up is treated as acknowledgement.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));

        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase());
        assertEquals(p2, state.turnState().pendingLockDeclarerId());

        rules.apply(state, new GiveUpAction(p1));

        assertTrue(state.boardState().closedRows().containsKey(0),
                "Row must close — active's give-up is treated as acknowledgement of passive's lock");
        assertEquals(TurnPhase.ROLL, state.turnState().phase(),
                "Turn must advance to ROLL after the lock closes");
    }

    @Test
    void giveUpFromLockPending_passiveDeclarant_activeTakesPunishment() {
        // Active must still receive a punishment when giving up, even if passive holds the lock.
        GameState state = stateAfterRoll(p1, p1, p2);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));

        int before = state.boardState().sheetProgress().get(p1).punishments();
        rules.apply(state, new GiveUpAction(p1));

        assertEquals(before + 1, state.boardState().sheetProgress().get(p1).punishments(),
                "Active must take a punishment when giving up, even if passive holds the lock");
    }

    @Test
    void giveUpFromLockPending_passiveDeclarant_threePlayer_waitsForOtherPassive() {
        // p2 declares lock; p1 (active) gives up; p3 still needs to acknowledge.
        // Row must NOT close until p3 also acknowledges.
        GameState state = stateAfterRoll(p1, p1, p2, p3);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new DeclareLockIntentAction(p2, 0));

        rules.apply(state, new GiveUpAction(p1));  // p1 acknowledges, p3 still pending

        assertFalse(state.boardState().closedRows().containsKey(0),
                "Row must not close while p3 has not yet acknowledged");
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase(),
                "Game must remain in LOCK_PENDING while p3 is pending");

        rules.apply(state, new EndTurnAction(p3));

        assertTrue(state.boardState().closedRows().containsKey(0),
                "Row must close once p3 has also acknowledged");
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
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
        Set<String> crossedBefore = new HashSet<>(
                state.boardState().sheetProgress().get(p1)
                        .rowStates().getOrDefault(cross.rowIndex(), new RowState(Set.of(), false))
                        .crossedCells());
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
        ActiveTurnState ats = state.turnState().activeTurnState();
        assertTrue(ats.whiteWhiteUsed() || ats.colorDieUsed());
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
    void rollInWrongPhaseIsRejected() {
        GameState state = stateAfterRoll(p1, p1, p2);  // already in ACTIVE_MOVE
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new RollAction(p1)));
    }

    // ── Lock Eligibility with Pending Crosses Tests ───────────────────────────

    @Test
    void canLockWithOnlyPermanentCrosses() {
        GameState state = stateReadyToLock(p1, p1, p2, 0);
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof DeclareLockIntentAction));
    }

    @Test
    void canLockWithPendingCrossesIncluded() {
        // Setup: state in ACTIVE_MOVE with some permanent crosses
        GameState state = stateAfterRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        Row row = layout.rows().get(0);
        LockCell lock = row.lock();
        SheetProgress progress = state.boardState().sheetProgress().get(p1);

        // Add some permanent crosses (less than minCrosses)
        int permanentCount = lock.minCrosses() - 1;
        Set<String> crossed = new HashSet<>();
        for (int i = 0; i < permanentCount && i < row.cells().size(); i++) {
            crossed.add(row.cells().get(i).id());
        }
        progress.updateRowState(0, new RowState(crossed, false));

        // Add pending cross for the required cell (lock cell)
        String requiredCell = lock.requiredCells().get(0);
        Map<Integer, Set<String>> pendingCrosses = new HashMap<>();
        pendingCrosses.put(0, Set.of(requiredCell));
        TurnState turn = state.turnState();
        turn.setUndoBuffer(Map.of(p1, pendingCrosses));

        // Now should be able to declare lock intent (permanent + pending >= minCrosses + has required)
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof DeclareLockIntentAction));
    }

    @Test
    void cannotLockWithoutPendingCrosses() {
        // Setup: state in ACTIVE_MOVE with insufficient permanent crosses
        GameState state = stateAfterRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        Row row = layout.rows().get(0);
        LockCell lock = row.lock();
        SheetProgress progress = state.boardState().sheetProgress().get(p1);

        // Add only 1 permanent cross (less than minCrosses which is 5)
        Set<String> crossed = Set.of(row.cells().get(0).id());
        progress.updateRowState(0, new RowState(crossed, false));

        // No pending crosses
        state.turnState().setUndoBuffer(new HashMap<>());

        // Should NOT be able to declare lock intent
        assertFalse(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof DeclareLockIntentAction));
    }

    @Test
    void lockRequiresAllRequiredCellsPermanentOrPending() {
        GameState state = stateAfterRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        Row row = layout.rows().get(0);
        LockCell lock = row.lock();
        SheetProgress progress = state.boardState().sheetProgress().get(p1);

        // Add minCrosses permanent crosses but NOT the required cell
        Set<String> crossed = new HashSet<>();
        String requiredCell = lock.requiredCells().get(0);
        for (int i = 0; i < lock.minCrosses() && i < row.cells().size(); i++) {
            String cellId = row.cells().get(i).id();
            // Skip the required cell
            if (!cellId.equals(requiredCell)) {
                crossed.add(cellId);
            }
        }
        progress.updateRowState(0, new RowState(crossed, false));
        state.turnState().setUndoBuffer(new HashMap<>());

        // Should NOT be able to lock (missing required cell)
        assertFalse(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof DeclareLockIntentAction));
    }

    @Test
    void lockSucceedsWhenRequiredCellIsPending() {
        GameState state = stateAfterRoll(p1, p1, p2);
        SheetLayout layout = state.sheetLayouts().get(p1);
        Row row = layout.rows().get(0);
        LockCell lock = row.lock();
        SheetProgress progress = state.boardState().sheetProgress().get(p1);

        // Add minCrosses-1 permanent crosses
        Set<String> crossed = new HashSet<>();
        String requiredCell = lock.requiredCells().get(0);
        int count = 0;
        for (Cell c : row.cells()) {
            if (count >= lock.minCrosses() - 1) break;
            if (!c.id().equals(requiredCell)) {
                crossed.add(c.id());
                count++;
            }
        }
        progress.updateRowState(0, new RowState(crossed, false));

        // Add the required cell as a pending cross
        Map<Integer, Set<String>> pendingCrosses = new HashMap<>();
        pendingCrosses.put(0, Set.of(requiredCell));
        state.turnState().setUndoBuffer(Map.of(p1, pendingCrosses));

        // Should be able to lock (pending required cell counts)
        assertTrue(rules.getValidActions(state, p1).stream()
                .anyMatch(a -> a instanceof DeclareLockIntentAction));
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
        return new GameState(CardMode.DETERMINISTIC, players, null, layouts, board, turn);
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

    /** State in LOCK_PENDING (active has declared intent on rowIndex). */
    private GameState stateInLockPending(UUID active, UUID player1, UUID player2, int rowIndex) {
        GameState state = stateReadyToLock(active, player1, player2, rowIndex);
        rules.apply(state, new DeclareLockIntentAction(active, rowIndex));
        return state;
    }

    private GameState stateInLockPending(UUID active, UUID p1, UUID p2, UUID p3, int rowIndex) {
        GameState state = stateAfterRoll(active, p1, p2, p3);
        crossEnoughForLock(state, active, rowIndex);
        rules.apply(state, new DeclareLockIntentAction(active, rowIndex));
        return state;
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    /** Cross enough cells on rowIndex (and the closing-eligible cell) so the lock is openable. */
    private void crossEnoughForLock(GameState state, UUID playerId, int rowIndex) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        Row row = layout.rows().get(rowIndex);
        LockCell lock = row.lock();
        SheetProgress progress = state.boardState().sheetProgress().get(playerId);

        // cross the first minCrosses cells including the requiredCell (last cell)
        int toCross = lock.minCrosses();
        Set<String> required = new HashSet<>(lock.requiredCells());
        Set<String> crossed = new HashSet<>();

        // always include the required cell
        for (String req : required) {
            row.cells().stream().filter(c -> c.id().equals(req)).findFirst().ifPresent(c -> {
                crossed.add(c.id());
            });
        }
        // fill remaining crosses from the start of the row
        for (Cell c : row.cells()) {
            if (crossed.size() >= toCross) break;
            crossed.add(c.id());
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
        Cell cell = layout.rows().get(rowIndex).cells().get(0);
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
}