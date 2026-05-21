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
        addReachableCells(state, playerId, actions, true);
        addClosingIntents(state, playerId, actions);
        actions.add(new GiveUpAction(playerId));
        actions.add(new ResetTurnAction(playerId));
        ActiveTurnState activePlayer = turn.activeTurnState();
        if (activePlayer.hasActed()) {
            actions.add(new EndTurnAction(playerId));
        }
        return actions;
    }

    private List<GameAction> passiveActions(GameState state, UUID playerId, TurnState turn) {
        if (!TurnHelper.isPassiveInQueue(turn, playerId)) return List.of();
        List<GameAction> actions = new ArrayList<>();
        if (!TurnHelper.hasAlreadyActed(turn, playerId)) {
            addReachableCells(state, playerId, actions, false);
            addClosingIntents(state, playerId, actions);
        } else {
            // Already acted (cross, declaration, or both): can declare more intents, then reset or pass.
            addClosingIntents(state, playerId, actions);
            actions.add(new ResetTurnAction(playerId));
        }
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
        if (state.boardState().closedRows().size() >= 2) return true;
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

        Map<UUID, SheetProgress> snap = new HashMap<>();
        for (UUID pid : state.players()) {
            snap.put(pid, deepCopy(getProgress(state, pid)));
        }
        turn.setMoveStartProgress(snap);
        turn.setUndoBuffer(new HashMap<>());

        turn.setPhase(TurnPhase.ACTIVE_MOVE);
    }

    private void applyCrossCell(GameState state, CrossCellAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        if (state.boardState().closedRows().containsKey(action.rowIndex()))
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

    private void recordActiveDiceUsage(ActiveTurnState ats, DiceCombination combination) {
        if (combination == DiceCombination.WHITE_WHITE) {
            if (ats.colorDieUsed())
                throw new IllegalMoveException("white+white is not allowed after the color die has been used");
            if (ats.whiteWhiteUsed())
                throw new IllegalMoveException("white+white already used this turn");
            ats.setWhiteWhiteUsed();
        } else {
            if (ats.colorDieUsed())
                throw new IllegalMoveException("color die already used this turn");
            ats.setColorDieUsed();
        }
    }

    // DECLARE_LOCK_INTENT: record closing intent without changing phase.
    // The row closes at EVALUATE once all players have made their moves.
    // For the second-to-last closing cell (Longo "15"/"3"): explicit YES action from client.
    // For the last closing cell ("16"/"2", standard "12"): auto-detected at each player's EndTurn.
    private void applyDeclareLockIntent(GameState state, DeclareLockIntentAction action) {
        TurnState turn = state.turnState();
        UUID declarerId = action.playerId();
        int rowIndex    = action.rowIndex();
        boolean isActive = declarerId.equals(turn.activePlayerId());

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

        state.pendingClosures().put(rowIndex, declarerId);

        // Capture before adding so we can tell whether this is the turn's first declaration.
        boolean isFirstDeclaration = state.rowClosureRequests().isEmpty();

        Color rowColor = getLockColor(state, declarerId, rowIndex);
        state.rowClosureRequests().add(new RowClosureRequest(declarerId, rowColor));

        if (isActive) {
            // Re-queue passive players who already left the queue, but ONLY on the first
            // declaration of the turn. After a RESET_TURN the prior request from the canceled
            // declaration is cleared but later declarations (from other players) remain, so
            // isFirstDeclaration = false — preventing players who have fully acted from being
            // forced to act a second time.
            if (isFirstDeclaration) {
                reQueueEjectedPassivePlayers(state, turn);
            }
        } else {
            turn.passivesActed().add(declarerId);
        }
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

        List<UUID> pendingPassives = new ArrayList<>(turn.passivePlayerQueue());

        if (pendingPassives.isEmpty()) {
            evaluate(state);
        } else {
            turn.setPassivePlayerQueue(pendingPassives);
            turn.setPhase(TurnPhase.PASSIVE_MOVE);
        }
    }

    private void applyResetTurn(GameState state, ResetTurnAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        // Special case: active player reverts their EndTurn while passives are still acting
        // (or after being re-added to the passive queue for a final-look notification).
        // Restore the snapshot so the pending cross is fully cleared — the player should be
        // able to redo their move from scratch without having to undo the cross manually.
        if (activePlayerRevertingToMove(isActive, turn)) {
            turn.passivePlayerQueue().remove(playerId); // in case they were re-added for final look
            restoreToSnapshot(state, turn, playerId);
            cancelPlayerClosingIntents(state, playerId);
            clearPendingCrosses(turn, playerId);
            if (turn.activeTurnState() != null) turn.activeTurnState().reset();
            turn.setPhase(TurnPhase.ACTIVE_MOVE);
            return;
        }

        // Special case: passive player reverts their EndTurn while the turn is still active.
        // Passives may EndTurn during ACTIVE_MOVE (before the active player passes) or during
        // PASSIVE_MOVE; in both cases they leave the queue. Restore from the turn-start snapshot
        // (the undo buffer was cleared on EndTurn) and put them back in the passive queue.
        if (passivePlayerRevertingAfterEarlyEndTurn(isActive, turn, playerId)) {
            restoreToSnapshot(state, turn, playerId);
            cancelPlayerClosingIntents(state, playerId);
            clearPendingCrosses(turn, playerId);
            turn.passivesActed().remove(playerId);
            turn.passivePlayerQueue().add(playerId);
            return;
        }

        restoreToSnapshot(state, turn, playerId);
        cancelPlayerClosingIntents(state, playerId);

        clearPendingCrosses(turn, playerId);
        turn.passivesActed().remove(playerId);

        if (isActive && turn.activeTurnState() != null) turn.activeTurnState().reset();
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
            ActiveTurnState activePlayer = turn.activeTurnState();
            if (!activePlayer.hasActed())
                throw new IllegalMoveException("must make at least one move before ending turn");
            autoDetectClosingIntent(state, turn, playerId);
            if (turn.passivePlayerQueue().isEmpty()) {
                evaluate(state);
                // Clear after evaluate so canCrossLock can still see this player's pending
                // crosses when deciding whether non-declarant players qualify for the lock cross.
                clearPendingCrosses(turn, playerId);
            } else {
                // Don't clear: evaluate runs later (last passive's endTurn), at which point
                // evaluate() replaces the whole TurnState — naturally discarding all buffers.
                turn.setPhase(TurnPhase.PASSIVE_MOVE);
            }
        } else {
            if (!turn.passivePlayerQueue().contains(playerId))
                throw new IllegalMoveException("player not in passive queue");
            autoDetectClosingIntent(state, turn, playerId);
            turn.passivePlayerQueue().remove(playerId);
        }
    }

    private void endTurnInPassiveMove(GameState state, TurnState turn, UUID playerId) {
        if (!turn.passivePlayerQueue().contains(playerId))
            throw new IllegalMoveException("player not in passive queue");
        autoDetectClosingIntent(state, turn, playerId);
        turn.passivePlayerQueue().remove(playerId);
        if (turn.passivePlayerQueue().isEmpty()) {
            UUID activeId = turn.activePlayerId();
            if (playerId.equals(activeId)) {
                // The active player was re-queued for a final look and is now passing.
                // Run evaluate directly — no further re-check.
                evaluate(state);
            } else if (activePlayerCouldClaimAnyPendingRow(state, activeId)) {
                // Before evaluating, give the active player a last look so they can
                // revert (RESET_TURN) or proceed (PASS) on the newly declared rows.
                turn.passivePlayerQueue().add(activeId);
            } else {
                evaluate(state);
            }
        }
        // Clear after evaluate so that canCrossLock can still read this player's pending
        // crosses when deciding whether non-declarant players qualify for the lock cross.
        clearPendingCrosses(turn, playerId);
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
                state.pendingClosures().put(rowIndex, playerId);
                state.rowClosureRequests().add(new RowClosureRequest(playerId, getLockColor(state, playerId, rowIndex)));
            }
        }
    }

    private boolean qualifiesForAutoClose(GameState state, UUID playerId, int rowIndex) {
        if (state.boardState().closedRows().containsKey(rowIndex)) return false;
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

        if (isGameOver(state)) {
            state.setGameOver(true);
            return;
        }

        List<UUID> players = state.players();
        UUID active = state.turnState().activePlayerId();
        int idx = players.indexOf(active);
        UUID next = players.get((idx + 1) % players.size());

        TurnState nextTurn = new TurnState();
        nextTurn.setActivePlayerId(next);
        nextTurn.setPhase(TurnPhase.ROLL);
        state.setTurnState(nextTurn);
    }

    private void addReachableCells(GameState state, UUID playerId, List<GameAction> actions, boolean isActive) {
        cellCrosser.addReachableCells(state, playerId, actions, isActive, getMinCrossesRequired());
    }

    // Offer DECLARE_LOCK_INTENT when the player can explicitly declare closing intent:
    // 1. Player already qualifies via a PERMANENT last closing cell (crossed in a previous turn).
    // 2. Player just crossed the second-to-last closing cell this turn (Longo YES/NO modal).
    // The last closing cell crossed THIS turn is auto-detected at EndTurn — no explicit intent needed.
    private void addClosingIntents(GameState state, UUID playerId, List<GameAction> actions) {
        SheetLayout layout        = getLayout(state, playerId);
        Map<Integer, UUID> closed = state.boardState().closedRows();

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (closed.containsKey(rowIndex)) continue;
            if (rowHasPendingClosure(state, rowIndex)) continue;
            if (canDeclareViaPermanentLastCell(state, playerId, rowIndex)
                    || canDeclareViaSecondToLastCell(state, playerId, rowIndex)) {
                actions.add(new DeclareLockIntentAction(playerId, rowIndex));
            }
        }
    }

    // True when the player qualifies to close based on a PERMANENTLY crossed last closing cell
    // (from any previous turn). Pending crosses (undo buffer) are excluded — those are handled
    // by auto-detection at EndTurn.
    protected boolean canDeclareViaPermanentLastCell(GameState state, UUID playerId, int rowIndex) {
        if (rowIsNotLockable(state, playerId, rowIndex)) return false;
        RowState rowState = getRowState(getProgress(state, playerId), rowIndex);
        if (!hasEnoughNonClosingCrosses(state, playerId, rowIndex, rowState.crossedCells())) return false;
        return rowState.crossedCells().contains(getLastClosingCell(state, playerId, rowIndex));
    }

    // True when the second-to-last closing cell is a pending cross this turn AND the player qualifies.
    // This is the Longo "15"/"3" explicit YES scenario.
    protected boolean canDeclareViaSecondToLastCell(GameState state, UUID playerId, int rowIndex) {
        if (rowIsNotLockable(state, playerId, rowIndex)) return false;
        List<String> closing = getClosingCells(state, playerId, rowIndex);
        if (closing.size() < 2) return false;

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

        List<String> closing = getClosingCells(state, playerId, rowIndex);
        if (allCrosses.contains(closing.getLast())) return true;

        if (closing.size() > 1) {
            // Only relevant for Longo
            String secondLast = closing.get(closing.size() - 2);
            return crossedThisTurn(state, playerId, rowIndex, secondLast);
        }
        return false;
    }

    /** Returns true when the row has no lock, is already locked, or is already closed. */
    protected boolean rowIsNotLockable(GameState state, UUID playerId, int rowIndex) {
        Row row = getRow(state, playerId, rowIndex);
        if (row.lock() == null) return true;
        RowState rowState = getRowState(getProgress(state, playerId), rowIndex);
        if (rowState.lockCrossed()) return true;
        return state.boardState().closedRows().containsKey(rowIndex);
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
            copy.put(entry.getKey(),
                    new RowState(new HashSet<>(entry.getValue().crossedCells()),
                                 entry.getValue().lockCrossed()));
        }
        return new SheetProgress(copy, p.punishments());
    }

    protected SheetLayout getLayout(GameState state, UUID playerId) {
        return state.sheetLayouts().get(playerId);
    }

    protected SheetProgress getProgress(GameState state, UUID playerId) {
        return state.boardState().sheetProgress().get(playerId);
    }

    protected RowState getRowState(SheetProgress progress, int rowIndex) {
        return progress.rowStates().getOrDefault(rowIndex, new RowState(new HashSet<>(), false));
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

    protected Set<String> getPendingCrossesInRow(TurnState turn, UUID playerId, int rowIndex) {
        Map<Integer, Set<String>> buffer = turn.undoBuffer().get(playerId);
        return buffer != null ? buffer.getOrDefault(rowIndex, new HashSet<>()) : new HashSet<>();
    }

    protected Set<String> getPendingCrossesInRow(GameState state, UUID playerId, int rowIndex) {
        TurnState turn = state.turnState();
        return turn != null ? getPendingCrossesInRow(turn, playerId, rowIndex) : new HashSet<>();
    }

    // ── Named predicates ──────────────────────────────────────────────────────

    private boolean hasMaxPunishments(SheetProgress progress) {
        return progress.punishments() >= MAX_PUNISHMENTS;
    }


    private boolean activePlayerCouldClaimAnyPendingRow(GameState state, UUID activeId) {
        return state.pendingClosures().entrySet().stream()
                .anyMatch(e -> !e.getValue().equals(activeId)
                               && couldActivePlayerLockRow(state, activeId, e.getKey()));
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

    private void reQueueEjectedPassivePlayers(GameState state, TurnState turn) {
        UUID activeId = turn.activePlayerId();
        for (UUID pid : state.players()) {
            if (!pid.equals(activeId) && !turn.passivePlayerQueue().contains(pid)) {
                turn.passivePlayerQueue().add(pid);
            }
        }
    }

    private void applyRowClosure(GameState state, int rowIndex, UUID declarant) {
        if (state.boardState().closedRows().containsKey(rowIndex)) return;
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