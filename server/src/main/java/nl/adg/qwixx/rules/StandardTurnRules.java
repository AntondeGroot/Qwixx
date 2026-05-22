package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.EndTurnAction;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.action.GiveUpAction;
import nl.adg.qwixx.action.ResetTurnAction;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.action.TakePunishmentAction;
import nl.adg.qwixx.action.UndoLastCrossAction;
import static nl.adg.qwixx.rules.CellCrosser.getRowState;

import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowClosureRequest;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class StandardTurnRules implements TurnRules {

    static final int MAX_PUNISHMENTS = 4;

    private final DiceRoller diceRoller;
    private final CellCrosser cellCrosser;

    public StandardTurnRules() {
        this.diceRoller  = new DiceRoller(new Random());
        this.cellCrosser = new CellCrosser(diceRoller);
    }

    public StandardTurnRules(Random random) {
        this.diceRoller  = new DiceRoller(random);
        this.cellCrosser = new CellCrosser(diceRoller);
    }

    @Override
    public List<GameAction> getValidActions(GameState state, UUID playerId) {
        if (state.gameOver()) return List.of();

        TurnState turn = state.turnState();
        boolean isActive = playerId.equals(turn.activePlayerId());

        return switch (turn.phase()) {
            case ROLL -> isActive ? List.of(new RollAction(playerId)) : List.of();

            case ACTIVE_MOVE -> isActive
                    ? activeActions(state, playerId, turn)
                    : passiveActions(state, playerId, turn);

            case PASSIVE_MOVE -> passiveActions(state, playerId, turn);

            case EVALUATE -> List.of();
        };
    }

    private List<GameAction> activeActions(GameState state, UUID playerId, TurnState turn) {
        List<GameAction> actions = new ArrayList<>();
        actions.addAll(crossCellActions(state, playerId, true));
        actions.addAll(declareLockIntentActions(state, playerId));
        actions.add(new GiveUpAction(playerId));
        actions.add(new ResetTurnAction(playerId));
        if (turn.activeTurnState().hasActed()) {
            actions.add(new EndTurnAction(playerId));
        }
        return actions;
    }

    private List<GameAction> passiveActions(GameState state, UUID playerId, TurnState turn) {
        if (!TurnHelper.isPassiveInQueue(turn, playerId)) return List.of();
        List<GameAction> actions = new ArrayList<>();
        if (!TurnHelper.hasAlreadyActed(turn, playerId)) {
            actions.addAll(crossCellActions(state, playerId, false));
        } else {
            // Already acted (cross, declaration, or both): can reset but not cross again.
            actions.add(new ResetTurnAction(playerId));
        }
        actions.addAll(declareLockIntentActions(state, playerId));
        actions.add(new EndTurnAction(playerId));
        return actions;
    }

    @Override
    public GameState apply(GameState state, GameAction action) {
        switch (action) {
            case RollAction a              -> applyRoll(state, a);
            case CrossCellAction a         -> applyCrossCell(state, a);
            case DeclareLockIntentAction a -> applyDeclareLockIntent(state, a);
            case UndoLastCrossAction a     -> applyUndoLastCross(state, a);
            case GiveUpAction a            -> applyGiveUp(state, a);
            case ResetTurnAction a         -> applyResetTurn(state, a);
            case EndTurnAction a           -> applyEndTurn(state, a);
            case TakePunishmentAction _    -> throw new IllegalMoveException("TakePunishmentAction only valid in offline mode");
        }
        state.incrementVersion();
        return state;
    }

    @Override
    public boolean isGameOver(GameState state) {
        if (state.closedRows().size() >= 2) return true;
        return state.boardState().sheetProgress().values().stream()
                .anyMatch(this::hasMaxPunishments);
    }

    private void applyRoll(GameState state, RollAction action) {
        TurnState turn = state.turnState();
        requirePhase(turn, TurnPhase.ROLL);
        requireActivePlayer(turn, action.playerId());

        var roll = diceRoller.roll(state.boardState().activeDice());
        turn.setCurrentRoll(roll);
        turn.setActiveTurnState(new ActiveTurnState());

        List<UUID> passive = new ArrayList<>(state.players());
        passive.remove(action.playerId());
        turn.setPassivePlayerQueue(passive);

        turn.setMoveStartProgress(snapshotProgress(state));
        turn.setUndoBuffer(new HashMap<>());

        turn.setPhase(TurnPhase.ACTIVE_MOVE);
    }

    private Map<UUID, SheetProgress> snapshotProgress(GameState state) {
        Map<UUID, SheetProgress> snap = new HashMap<>();
        for (UUID pid : state.players()) {
            snap.put(pid, deepCopy(getProgress(state, pid)));
        }
        return snap;
    }

    private void applyCrossCell(GameState state, CrossCellAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        if (state.isRowClosed(action.rowIndex()))
            throw new IllegalMoveException("row is closed");

        if (isActive) {
            if (turn.phase() != TurnPhase.ACTIVE_MOVE)
                throw new IllegalMoveException("expected phase ACTIVE_MOVE but was " + turn.phase());
        } else {
            requirePassiveMayCrossCell(turn, playerId);
        }

        Map<Integer, Set<String>> crossed = crossCellWithAutoTags(state, playerId, action.rowIndex(), action.cellId());
        savePendingCrosses(turn, playerId, crossed);

        if (isActive) {
            recordActiveDiceUsage(turn.activeTurnState(), action.combination());
        } else {
            turn.passivesActed().add(playerId);
        }
    }

    private void requirePassiveMayCrossCell(TurnState turn, UUID playerId) {
        if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.PASSIVE_MOVE)
            throw new IllegalMoveException("expected phase ACTIVE_MOVE or PASSIVE_MOVE but was " + turn.phase());
        if (!turn.passivePlayerQueue().contains(playerId))
            throw new IllegalMoveException("player not in passive queue");
        if (TurnHelper.hasPendingCross(turn, playerId))
            throw new IllegalMoveException("passive player already made a white+white cross this turn");
    }

    private void recordActiveDiceUsage(ActiveTurnState activePlayer, DiceCombination combination) {
        if (combination == DiceCombination.WHITE_WHITE) {
            if (activePlayer.colorDieUsed())
                throw new IllegalMoveException("white+white is not allowed after the color die has been used");
            if (activePlayer.whiteWhiteUsed())
                throw new IllegalMoveException("white+white already used this turn");
            activePlayer.setWhiteWhiteUsed();
        } else {
            if (activePlayer.colorDieUsed())
                throw new IllegalMoveException("color die already used this turn");
            activePlayer.setColorDieUsed();
        }
    }

    // DECLARE_LOCK_INTENT: record closing intent without changing phase.
    // The row closes at EVALUATE once all players have made their moves.
    // For the second-to-last closing cell (Longo "15"/"3"): explicit YES action from client.
    // For the last closing cell ("16"/"2", standard "12"): auto-detected at each player's EndTurn.
    private void applyDeclareLockIntent(GameState state, DeclareLockIntentAction action) {
        TurnState turn   = state.turnState();
        UUID declarerId  = action.playerId();
        int rowIndex     = action.rowIndex();
        boolean isActive = declarerId.equals(turn.activePlayerId());

        requireMayDeclareIntent(state, turn, declarerId, rowIndex, isActive);

        boolean isFirstDeclaration = state.rowClosureRequests().isEmpty();
        recordClosureIntent(state, declarerId, rowIndex);

        if (isActive && isFirstDeclaration) {
            // Re-queue passive players who already left the queue, but ONLY on the first
            // declaration of the turn. After a RESET_TURN the prior request from the canceled
            // declaration is cleared but later declarations (from other players) remain, so
            // isFirstDeclaration = false — preventing players who have fully acted from being
            // forced to act a second time.
            restorePassivesToQueue(state, turn);
        } else if (!isActive) {
            turn.passivesActed().add(declarerId);
        }
    }

    private void requireMayDeclareIntent(GameState state, TurnState turn, UUID declarerId, int rowIndex, boolean isActive) {
        if (isActive) {
            if (turn.phase() != TurnPhase.ACTIVE_MOVE)
                throw new IllegalMoveException("active can only declare closing intent in ACTIVE_MOVE");
        } else {
            if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.PASSIVE_MOVE)
                throw new IllegalMoveException("passive can only declare closing intent in ACTIVE_MOVE or PASSIVE_MOVE");
            if (!turn.passivePlayerQueue().contains(declarerId))
                throw new IllegalMoveException("player not in passive queue");
        }
        if (!canCrossLock(state, declarerId, rowIndex))
            throw new IllegalMoveException("lock pre-conditions not met");
        if (rowHasPendingClosure(state, rowIndex))
            throw new IllegalMoveException("row is already declared for closure this turn");
    }

    private void recordClosureIntent(GameState state, UUID declarerId, int rowIndex) {
        state.pendingClosures().put(rowIndex, declarerId);
        state.rowClosureRequests().add(new RowClosureRequest(declarerId, getLockColor(state, declarerId, rowIndex)));
    }

    private void applyUndoLastCross(GameState state, UndoLastCrossAction action) {
        TurnState turn = state.turnState();
        if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.PASSIVE_MOVE)
            throw new IllegalMoveException("UndoLastCrossAction not valid in phase " + turn.phase());

        UUID playerId = action.playerId();
        Map<Integer, Set<String>> lastCross = getPendingCrosses(turn, playerId);
        if (lastCross == null) throw new IllegalMoveException("no cross to undo");

        // For the active player, undo = full reset so dice usage flags are also cleared.
        if (playerId.equals(turn.activePlayerId())) {
            applyResetTurn(state, new ResetTurnAction(playerId));
            return;
        }

        SheetProgress progress = getProgress(state, playerId);
        for (var entry : lastCross.entrySet()) {
            int idx = entry.getKey();
            RowState current = getRowState(progress, idx);
            Set<String> updated = new HashSet<>(current.crossedCells());
            updated.removeAll(entry.getValue());
            progress.updateRowState(idx, new RowState(updated, current.lockCrossed()));

            // Cancel any closing intent the player declared for this row.
            UUID declarant = state.pendingClosures().get(idx);
            if (playerId.equals(declarant)) {
                state.pendingClosures().remove(idx);
                state.rowClosureRequests().removeIf(r -> r.playerId().equals(playerId));
            }
        }

        clearPendingCrosses(turn, playerId);
        turn.passivesActed().remove(playerId);
    }

    private void applyGiveUp(GameState state, GiveUpAction action) {
        TurnState turn = state.turnState();
        requireActivePlayer(turn, action.playerId());
        if (turn.phase() != TurnPhase.ACTIVE_MOVE)
            throw new IllegalMoveException("GiveUpAction not valid in phase " + turn.phase());

        UUID playerId = action.playerId();
        restoreToSnapshot(state, turn, playerId);
        cancelPlayerClosingIntents(state, playerId);
        getProgress(state, playerId).addPunishment();

        evaluateOrTransitionToPassiveMove(state, turn);
    }

    private void applyResetTurn(GameState state, ResetTurnAction action) {
        TurnState turn   = state.turnState();
        UUID playerId    = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        revertPlayerProgress(state, turn, playerId);
        turn.passivesActed().remove(playerId);
        if (isActive && turn.activeTurnState() != null) turn.activeTurnState().reset();

        if (activePlayerRevertingToMove(isActive, turn)) {
            turn.passivePlayerQueue().remove(playerId); // in case re-added for final look
            turn.setPhase(TurnPhase.ACTIVE_MOVE);
        } else if (passivePlayerRevertingAfterEarlyEndTurn(isActive, turn, playerId)) {
            turn.passivePlayerQueue().add(playerId);
        }
        // else: passive still in queue — no queue change needed
    }

    private void revertPlayerProgress(GameState state, TurnState turn, UUID playerId) {
        restoreToSnapshot(state, turn, playerId);
        cancelPlayerClosingIntents(state, playerId);
        clearPendingCrosses(turn, playerId);
    }

    /** Removes all pending closures and notifications declared by this player this turn. */
    private void cancelPlayerClosingIntents(GameState state, UUID playerId) {
        state.pendingClosures().entrySet().removeIf(e -> e.getValue().equals(playerId));
        state.rowClosureRequests().removeIf(r -> r.playerId().equals(playerId));
    }

    private void applyEndTurn(GameState state, EndTurnAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        switch (turn.phase()) {
            case ACTIVE_MOVE  -> endTurnInActiveMove(state, turn, playerId, isActive);
            case PASSIVE_MOVE -> endTurnInPassiveMove(state, turn, playerId);
            default -> throw new IllegalMoveException("EndTurnAction not valid in phase " + turn.phase());
        }
    }

    private void endTurnInActiveMove(GameState state, TurnState turn, UUID playerId, boolean isActive) {
        if (isActive) {
            if (!turn.activeTurnState().hasActed())
                throw new IllegalMoveException("must make at least one move before ending turn");
            autoDetectClosingIntent(state, turn, playerId);
            evaluateOrTransitionToPassiveMove(state, turn);
        } else {
            if (!turn.passivePlayerQueue().contains(playerId))
                throw new IllegalMoveException("player not in passive queue");
            autoDetectClosingIntent(state, turn, playerId);
            turn.passivePlayerQueue().remove(playerId);
        }
    }

    private void evaluateOrTransitionToPassiveMove(GameState state, TurnState turn) {
        if (turn.passivePlayerQueue().isEmpty()) {
            evaluate(state);
        } else {
            turn.setPhase(TurnPhase.PASSIVE_MOVE);
        }
    }

    private void endTurnInPassiveMove(GameState state, TurnState turn, UUID playerId) {
        if (!turn.passivePlayerQueue().contains(playerId))
            throw new IllegalMoveException("player not in passive queue");
        autoDetectClosingIntent(state, turn, playerId);
        turn.passivePlayerQueue().remove(playerId);
        if (turn.passivePlayerQueue().isEmpty()) {
            handleEmptyPassiveQueue(state, turn, playerId);
        }
        clearPendingCrosses(turn, playerId);
    }

    private void handleEmptyPassiveQueue(GameState state, TurnState turn, UUID playerId) {
        UUID activeId = turn.activePlayerId();
        if (!playerId.equals(activeId) && activePlayerCouldClaimAnyPendingRow(state, activeId)) {
            // Give the active player a last look so they can revert or pass on newly declared rows.
            turn.passivePlayerQueue().add(activeId);
        } else {
            evaluate(state);
        }
    }

    /**
     * Returns true when the active player could lock {@code rowIndex} IF they crossed the
     * closing cell this turn (i.e. they have enough permanent+pending crosses to meet
     * minCrosses but have not yet crossed any closing cell for this row).
     * Used to decide whether to delay EVALUATE and give the active player a last look.
     */
    private boolean couldActivePlayerLockRow(GameState state, UUID activeId, int rowIndex) {
        if (rowIsNotLockable(state, activeId, rowIndex)) return false;
        // Don't re-queue if the active player already declared this row themselves.
        if (activeId.equals(state.pendingClosures().get(rowIndex))) return false;
        Set<String> allCrosses = allCrossesForPlayer(state, activeId, rowIndex);
        // If the player already has the LAST closing cell crossed they already qualify at
        // evaluate — re-queuing would interrupt the game without benefit.
        // (Earlier closing cells like Longo "15" may be crossed from a prior turn; in that
        // case the player might still want to also cross "16" this turn, so we allow re-queue.)
        if (allCrosses.contains(getLastClosingCell(state, activeId, rowIndex))) return false;
        // Re-queue if the player already has enough non-closing crosses to qualify.
        return hasEnoughNonClosingCrosses(state, activeId, rowIndex, allCrosses);
    }

    // Auto-detect closing intent for a player who crossed the LAST closing cell this turn.
    // The second-to-last closing cell (Longo "15"/"3") requires an explicit YES from the client.
    private void autoDetectClosingIntent(GameState state, TurnState turn, UUID playerId) {
        if (!hasPendingCrosses(turn, playerId)) return;

        SheetLayout layout = getLayout(state, playerId);
        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (qualifiesForAutoClose(state, playerId, rowIndex)) {
                recordClosureIntent(state, playerId, rowIndex);
            }
        }
    }

    private boolean qualifiesForAutoClose(GameState state, UUID playerId, int rowIndex) {
        if (state.isRowClosed(rowIndex)) return false;
        if (rowHasPendingClosure(state, rowIndex)) return false;
        if (getRow(state, playerId, rowIndex).lock() == null) return false;
        if (!crossedThisTurn(state, playerId, rowIndex, getLastClosingCell(state, playerId, rowIndex))) return false;
        return canCrossLock(state, playerId, rowIndex);
    }

    private void evaluate(GameState state) {
        for (var entry : new HashMap<>(state.pendingClosures()).entrySet()) {
            applyRowClosure(state, entry.getKey(), entry.getValue());
        }

        state.pendingClosures().clear();
        state.rowClosureRequests().clear();
        state.turnState().undoBuffer().clear();

        if (isGameOver(state)) {
            state.setGameOver(true);
            return;
        }

        advanceToNextPlayer(state);
    }

    private void advanceToNextPlayer(GameState state) {
        List<UUID> players = state.players();
        UUID active = state.turnState().activePlayerId();
        UUID next = players.get((players.indexOf(active) + 1) % players.size());

        TurnState nextTurn = new TurnState();
        nextTurn.setActivePlayerId(next);
        nextTurn.setPhase(TurnPhase.ROLL);
        state.setTurnState(nextTurn);
    }

    private List<GameAction> crossCellActions(GameState state, UUID playerId, boolean isActive) {
        return cellCrosser.crossCellActions(state, playerId, isActive, getMinCrossesRequired());
    }

    // Offers DECLARE_LOCK_INTENT to any player who crossed a non-final closing cell this turn,
    // giving them an explicit YES/NO choice before their turn ends (Longo "15"/"3" modal).
    // Crossing the last closing cell is handled silently by auto-detection at EndTurn.
    private List<GameAction> declareLockIntentActions(GameState state, UUID playerId) {
        List<GameAction> actions = new ArrayList<>();
        SheetLayout layout = getLayout(state, playerId);
        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (canOfferLockDeclaration(state, playerId, rowIndex)) {
                actions.add(new DeclareLockIntentAction(playerId, rowIndex));
            }
        }
        return actions;
    }

    private boolean canOfferLockDeclaration(GameState state, UUID playerId, int rowIndex) {
        if (state.isRowClosed(rowIndex)) return false;
        if (rowHasPendingClosure(state, rowIndex)) return false;
        return canDeclareViaNonFinalClosingCell(state, playerId, rowIndex);
    }

    // True when a non-final closing cell is a pending cross this turn AND the player qualifies.
    // This is the Longo "15"/"3" explicit YES scenario.
    protected boolean canDeclareViaNonFinalClosingCell(GameState state, UUID playerId, int rowIndex) {
        if (rowIsNotLockable(state, playerId, rowIndex)) return false;
        if (!hasMultipleClosingCells(state, playerId, rowIndex)) return false;

        List<String> closing = getClosingCells(state, playerId, rowIndex);
        String secondToLast = closing.get(closing.size() - 2);
        if (!crossedThisTurn(state, playerId, rowIndex, secondToLast)) return false;

        return canCrossLock(state, playerId, rowIndex);
    }

    // Lock eligibility:
    //
    //  • The LAST closing cell always enables locking — permanent or pending.
    //
    //  • The SECOND-TO-LAST closing cell (Longo only) enables locking ONLY when it was
    //    crossed in the CURRENT turn (pending cross). Once the turn ends without a lock
    //    declaration the cell becomes permanent and loses its locking power; from that
    //    point only the last cell can trigger a lock.
    //
    // For standard Qwixx there is only one closing cell, so the second-to-last branch
    // is never reached.
    protected boolean canCrossLock(GameState state, UUID playerId, int rowIndex) {
        if (rowIsNotLockable(state, playerId, rowIndex)) return false;
        Set<String> allCrosses = allCrossesForPlayer(state, playerId, rowIndex);
        if (!hasEnoughNonClosingCrosses(state, playerId, rowIndex, allCrosses)) return false;
        return hasEligibleClosingCellCrossed(state, playerId, rowIndex, allCrosses);
    }

    private boolean hasEligibleClosingCellCrossed(GameState state, UUID playerId, int rowIndex, Set<String> allCrosses) {
        List<String> closing                = getClosingCells(state, playerId, rowIndex);
        boolean lastCellCrossed             = allCrosses.contains(closing.getLast());
        boolean secondToLastCrossedThisTurn = hasMultipleClosingCells(state, playerId, rowIndex)
                && crossedThisTurn(state, playerId, rowIndex, closing.get(closing.size() - 2));
        return lastCellCrossed || secondToLastCrossedThisTurn;
    }

    private boolean hasMultipleClosingCells(GameState state, UUID playerId, int rowIndex) {
        return getClosingCells(state, playerId, rowIndex).size() > 1;
    }

    /** Returns true when the row has no lock, is already locked, or is already closed. */
    protected boolean rowIsNotLockable(GameState state, UUID playerId, int rowIndex) {
        Row row = getRow(state, playerId, rowIndex);
        if (row.lock() == null) return true;
        RowState rowState = getRowState(getProgress(state, playerId), rowIndex);
        if (rowState.lockCrossed()) return true;
        return state.isRowClosed(rowIndex);
    }

    /** Permanent crosses in a row plus any pending cross from the current turn's undo buffer. */
    protected Set<String> allCrossesForPlayer(GameState state, UUID playerId, int rowIndex) {
        RowState rowState = getRowState(getProgress(state, playerId), rowIndex);
        Set<String> all = new HashSet<>(rowState.crossedCells());
        all.addAll(getPendingCrossesInRow(state, playerId, rowIndex));
        return all;
    }

    protected void markLockCrossed(GameState state, UUID playerId, int rowIndex) {
        SheetProgress prog = getProgress(state, playerId);
        RowState current   = getRowState(prog, rowIndex);
        prog.updateRowState(rowIndex, new RowState(current.crossedCells(), true));
    }

    protected void closeRowGlobally(GameState state, UUID playerId, int rowIndex) {
        BoardState board = state.boardState();
        board.closedRows().put(rowIndex, playerId);
        board.activeDice().removeIf(d -> d.color() == getLockColor(state, playerId, rowIndex));
    }

    protected Map<Integer, Set<String>> crossCellWithAutoTags(GameState state, UUID playerId, int rowIndex, String cellId) {
        return cellCrosser.cross(state, playerId, rowIndex, cellId);
    }

    private void restoreToSnapshot(GameState state, TurnState turn, UUID playerId) {
        SheetProgress snapshot = turn.moveStartProgress().get(playerId);
        if (snapshot != null) state.boardState().sheetProgress().put(playerId, deepCopy(snapshot));
    }

    private SheetProgress deepCopy(SheetProgress p) {
        Map<Integer, RowState> copy = new HashMap<>();
        for (var entry : p.rowStates().entrySet()) {
            copy.put(entry.getKey(), copyRowState(entry.getValue()));
        }
        return new SheetProgress(copy, p.punishments());
    }

    private static RowState copyRowState(RowState source) {
        return new RowState(new HashSet<>(source.crossedCells()), source.lockCrossed());
    }

    protected SheetLayout getLayout(GameState state, UUID playerId) {
        return state.sheetLayouts().get(playerId);
    }

    protected SheetProgress getProgress(GameState state, UUID playerId) {
        return state.boardState().sheetProgress().get(playerId);
    }

    protected Row getRow(GameState state, UUID playerId, int rowIndex) {
        return getLayout(state, playerId).rows().get(rowIndex);
    }

    protected LockCell getLock(GameState state, UUID playerId, int rowIndex) {
        return getRow(state, playerId, rowIndex).lock();
    }

    protected Color getLockColor(GameState state, UUID playerId, int rowIndex) {
        return getLock(state, playerId, rowIndex).color();
    }

    protected List<String> getClosingCells(GameState state, UUID playerId, int rowIndex) {
        return getLock(state, playerId, rowIndex).closingCells();
    }

    protected String getLastClosingCell(GameState state, UUID playerId, int rowIndex) {
        return getLock(state, playerId, rowIndex).closingCells().getLast();
    }

    protected int getMinCrossesRequired() {
        return 5;
    }

    protected boolean playerHasCrossedAClosingCell(GameState state, UUID playerId, int rowIndex, Set<String> crosses) {
        return getClosingCells(state, playerId, rowIndex).stream().anyMatch(crosses::contains);
    }

    protected boolean rowHasPendingClosure(GameState state, int rowIndex) {
        return state.pendingClosures().containsKey(rowIndex);
    }

    protected boolean crossedThisTurn(GameState state, UUID playerId, int rowIndex, String cellId) {
        return getPendingCrossesInRow(state, playerId, rowIndex).contains(cellId);
    }

    protected boolean hasEnoughNonClosingCrosses(GameState state, UUID playerId, int rowIndex, Set<String> crosses) {
        List<String> closing = getClosingCells(state, playerId, rowIndex);
        return crosses.stream().filter(id -> !closing.contains(id)).count() >= getMinCrossesRequired();
    }

    protected int rightmostCrossedPosition(Row row, RowState rowState) {
        return CellCrosser.rightmostCrossedPosition(row, rowState);
    }

    private void requirePhase(TurnState turn, TurnPhase expected) {
        if (turn.phase() != expected)
            throw new IllegalMoveException("expected phase " + expected + " but was " + turn.phase());
    }

    private void requireActivePlayer(TurnState turn, UUID playerId) {
        if (!playerId.equals(turn.activePlayerId()))
            throw new IllegalMoveException("player " + playerId + " is not the active player");
    }

    // ── Undo buffer accessors ─────────────────────────────────────────────────

    protected void savePendingCrosses(TurnState turn, UUID playerId, Map<Integer, Set<String>> crosses) {
        turn.undoBuffer().put(playerId, crosses);
    }

    protected void clearPendingCrosses(TurnState turn, UUID playerId) {
        turn.undoBuffer().remove(playerId);
    }

    protected boolean hasPendingCrosses(TurnState turn, UUID playerId) {
        return turn.undoBuffer().containsKey(playerId);
    }

    protected Map<Integer, Set<String>> getPendingCrosses(TurnState turn, UUID playerId) {
        return turn.undoBuffer().get(playerId);
    }

    protected Set<String> getPendingCrossesInRow(GameState state, UUID playerId, int rowIndex) {
        TurnState turn = state.turnState();
        if (turn == null) return Set.of();
        Map<Integer, Set<String>> buffer = turn.undoBuffer().get(playerId);
        if (buffer == null) return Set.of();
        return buffer.getOrDefault(rowIndex, Set.of());
    }

    // ── Named predicates ──────────────────────────────────────────────────────

    private boolean hasMaxPunishments(SheetProgress progress) {
        return progress.punishments() >= MAX_PUNISHMENTS;
    }


    private boolean activePlayerCouldClaimAnyPendingRow(GameState state, UUID activeId) {
        return state.pendingClosures().entrySet().stream()
                .anyMatch(e -> activePlayerCouldAlsoLockRow(state, activeId, e.getKey(), e.getValue()));
    }

    private boolean activePlayerCouldAlsoLockRow(GameState state, UUID activeId, int rowIndex, UUID declarant) {
        return !declarant.equals(activeId) && couldActivePlayerLockRow(state, activeId, rowIndex);
    }

    private boolean activePlayerRevertingToMove(boolean isActive, TurnState turn) {
        return isActive && turn.phase() == TurnPhase.PASSIVE_MOVE;
    }

    private boolean passivePlayerRevertingAfterEarlyEndTurn(boolean isActive, TurnState turn, UUID playerId) {
        return !isActive
                && (turn.phase() == TurnPhase.ACTIVE_MOVE || turn.phase() == TurnPhase.PASSIVE_MOVE)
                && !turn.passivePlayerQueue().contains(playerId);
    }

    // ── Named operations ──────────────────────────────────────────────────────

    private void restorePassivesToQueue(GameState state, TurnState turn) {
        UUID activeId = turn.activePlayerId();
        for (UUID pid : state.players()) {
            if (!pid.equals(activeId) && !turn.passivePlayerQueue().contains(pid)) {
                turn.passivePlayerQueue().add(pid);
            }
        }
    }

    private void applyRowClosure(GameState state, int rowIndex, UUID declarant) {
        if (state.isRowClosed(rowIndex)) return;
        markLockCrossed(state, declarant, rowIndex);
        for (UUID pid : state.players()) {
            if (otherPlayerAlsoQualifiesForLockCross(state, pid, declarant, rowIndex)) {
                markLockCrossed(state, pid, rowIndex);
            }
        }
        closeRowGlobally(state, declarant, rowIndex);
    }

    private boolean otherPlayerAlsoQualifiesForLockCross(GameState state, UUID pid, UUID declarant, int rowIndex) {
        return !pid.equals(declarant)
                && !getRowState(getProgress(state, pid), rowIndex).lockCrossed()
                && canCrossLock(state, pid, rowIndex);
    }
}