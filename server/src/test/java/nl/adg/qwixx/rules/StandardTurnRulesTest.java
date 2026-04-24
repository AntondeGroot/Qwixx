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
    void passivePlayerGetsNoActionsInActiveMovePhase() {
        GameState state = stateAfterRoll(p1, p1, p2);
        assertTrue(rules.getValidActions(state, p2).isEmpty());
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
    void endTurnSnapshotsPassivePlayers() {
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, firstCrossAction(state, p1));
        rules.apply(state, new EndTurnAction(p1));
        assertNotNull(state.turnState().moveStartProgress().get(p2));
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
    void nonActivePlayerCanAcknowledgeLockWithEndTurn() {
        GameState state = stateInLockPending(p1, p1, p2, 0);
        rules.apply(state, new EndTurnAction(p2));
        assertTrue(state.turnState().lockAcknowledged().contains(p2));
    }

    @Test
    void activePlayerCanCrossLockOnceAllAcknowledged() {
        GameState state = stateInLockPending(p1, p1, p2, 0);
        rules.apply(state, new EndTurnAction(p2));
        List<GameAction> actions = rules.getValidActions(state, p1);
        assertTrue(actions.stream().anyMatch(a -> a instanceof CrossLockAction));
    }

    @Test
    void activePlayerCannotCrossLockBeforeAllAcknowledged() {
        GameState state = stateInLockPending(p1, p1, p2, p3, 0);
        rules.apply(state, new EndTurnAction(p2));  // only p2 acknowledged, p3 hasn't
        assertThrows(IllegalMoveException.class, () -> rules.apply(state, new CrossLockAction(p1, 0)));
    }

    @Test
    void crossLockClosesRowAndRemovesDie() {
        GameState state = stateInLockPending(p1, p1, p2, 0);
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new CrossLockAction(p1, 0));
        assertTrue(state.boardState().closedRows().containsKey(0));
        Color lockColor = state.sheetLayouts().get(p1).rows().get(0).lock().color();
        assertTrue(state.boardState().activeDice().stream().noneMatch(d -> d.color() == lockColor));
    }

    @Test
    void crossLockTransitionsToNextRoll() {
        GameState state = stateInLockPending(p1, p1, p2, 0);
        rules.apply(state, new EndTurnAction(p2));
        rules.apply(state, new CrossLockAction(p1, 0));
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
    }

    @Test
    void nonActivePlayerCanCrossLockToGetBonusAndAcknowledge() {
        GameState state = stateInLockPending(p1, p1, p2, 0);
        crossEnoughForLock(state, p2, 0);
        rules.apply(state, new CrossLockAction(p2, 0));
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
    void giveUpTransitionsToNextRoll() {
        GameState state = stateAfterRoll(p1, p1, p2);
        rules.apply(state, new GiveUpAction(p1));
        assertEquals(TurnPhase.ROLL, state.turnState().phase());
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
    void giveUpThatCausesMaxPunishmentsEndsGame() {
        // punishments must be in place before rolling so they are included in the snapshot
        GameState state = stateInRoll(p1, p1, p2);
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
        if (state.turnState().phase() == TurnPhase.PASSIVE_MOVE) {
            for (UUID passive : passives) {
                if (state.turnState().passivePlayerQueue().contains(passive)) {
                    rules.apply(state, new EndTurnAction(passive));
                }
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
            c.setTags(List.of());
            row.addCell(c);
            last = c;
        }
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 5, List.of(last.id())));
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