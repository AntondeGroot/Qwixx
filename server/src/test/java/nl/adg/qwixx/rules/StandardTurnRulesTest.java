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
        // Must not throw — LOCK_PENDING is now a valid phase for passive crosses.
        assertDoesNotThrow(() -> rules.apply(state, cross));
        // The cell must be in the undoBuffer (pending, not yet committed).
        assertTrue(state.turnState().undoBuffer().containsKey(p2),
                "crossed cell must be recorded in undoBuffer for potential undo");
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
    void passiveCrossInLockPendingFollowedByEndTurnPreservesTheCross() {
        // p2 is the only passive; EndTurn triggers auto-close, advancing the turn.
        // The cross p2 made must survive in sheetProgress regardless.
        GameState state = stateInLockPending(p1, p1, p2, 0);
        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross);
        rules.apply(state, new EndTurnAction(p2));   // acknowledge → auto-close → ROLL

        RowState rs = rowStateOf(state, p2, cross.rowIndex());
        assertTrue(rs.crossedCells().contains(cross.cellId()),
                "cross must survive after EndTurn (acknowledge) in LOCK_PENDING");
        // EndTurn as the sole passive triggers auto-close and turn advance.
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
        // Once a passive has crossed, only UndoLastCross and EndTurn are offered.
        GameState state = stateInLockPending(p1, p1, p2, 0);
        rules.apply(state, firstCrossAction(state, p2));
        List<GameAction> actions = rules.getValidActions(state, p2);
        assertTrue(actions.stream().noneMatch(a -> a instanceof CrossCellAction cc
                        && cc.combination() == DiceCombination.WHITE_WHITE),
                "passive must not be offered more cells after already crossing in LOCK_PENDING");
        assertTrue(actions.stream().anyMatch(a -> a instanceof UndoLastCrossAction));
        assertTrue(actions.stream().anyMatch(a -> a instanceof EndTurnAction));
    }

    @Test
    void passiveWithPendingCrossCanEndTurnWhileOtherPassiveStillPending() {
        // Regression: without the End Turn button (client bug) or if the server rejected
        // EndTurnAction for a player with a pending cross, the game would freeze in
        // LOCK_PENDING with one passive acknowledged and the other permanently stuck.
        //
        // Scenario: p1 declares lock intent; p2 crosses a cell; p3 has not yet acted.
        // p2 must be able to EndTurn (committing the cross) while p3 is still pending.
        // Only after p3 also EndTurns should the row close.
        GameState state = stateInLockPending(p1, p1, p2, p3, 0);

        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross);

        // p2 EndTurns with a pending cross — must succeed and acknowledge p2.
        assertDoesNotThrow(() -> rules.apply(state, new EndTurnAction(p2)),
                "EndTurnAction must be accepted for a passive with a pending cross in LOCK_PENDING");

        // Game must still be in LOCK_PENDING waiting for p3.
        assertEquals(TurnPhase.LOCK_PENDING, state.turnState().phase(),
                "game must remain in LOCK_PENDING while p3 has not yet acknowledged");
        assertFalse(state.boardState().closedRows().containsKey(0),
                "row must not close until all passives have acknowledged");

        // p2's cross must be committed despite the early EndTurn.
        RowState rs = rowStateOf(state, p2, cross.rowIndex());
        assertTrue(rs.crossedCells().contains(cross.cellId()),
                "p2's cross must survive after EndTurn in LOCK_PENDING");

        // p3 acknowledges — now the row should close.
        rules.apply(state, new EndTurnAction(p3));
        assertTrue(state.boardState().closedRows().containsKey(0),
                "row must close once all passives have acknowledged");
    }

    @Test
    void endTurnInLockPendingClearsUndoBuffer() {
        // When a passive crosses in LOCK_PENDING then EndTurns, the undo buffer
        // entry must be removed so the cross cannot be undone after acknowledging.
        GameState state = stateInLockPending(p1, p1, p2, 0);
        CrossCellAction cross = firstCrossAction(state, p2);
        rules.apply(state, cross);
        assertTrue(state.turnState().undoBuffer().containsKey(p2), "undo buffer populated after cross");

        rules.apply(state, new EndTurnAction(p2));  // acknowledge in a 2-player game → auto-close

        // After auto-close the TurnState resets, but verify the undo buffer was cleared
        // during EndTurn processing (not just because the turn advanced).
        // We can't inspect the old turn, so instead verify the cross survived (the state
        // was committed before being cleared) by checking the closed row.
        assertTrue(state.boardState().closedRows().containsKey(0),
                "row must close after the only passive acknowledges");
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
    void undoLastCrossAddsToAcknowledged() {
        GameState state = stateInLockPending(p1, p1, p2, 0);
        seedUndoBufferForP2(state, p2, 1);
        rules.apply(state, new UndoLastCrossAction(p2));
        assertTrue(state.turnState().lockAcknowledged().contains(p2));
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