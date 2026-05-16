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
                    ? activeMoveActions(state, playerId, turn)
                    : passiveActions(state, playerId, turn);

            case PASSIVE_MOVE -> passiveActions(state, playerId, turn);

            case EVALUATE -> List.of();
        };
    }

    private List<GameAction> activeMoveActions(GameState state, UUID playerId, TurnState turn) {
        List<GameAction> actions = new ArrayList<>();
        addReachableCells(state, playerId, actions, true);
        addClosingIntents(state, playerId, actions);
        actions.add(new GiveUpAction(playerId));
        actions.add(new ResetTurnAction(playerId));
        ActiveTurnState ats = turn.activeTurnState();
        if (ats.whiteWhiteUsed() || ats.colorDieUsed() || state.pendingClosures().containsValue(playerId)) {
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
            case TakePunishmentAction _ -> throw new IllegalMoveException("TakePunishmentAction only valid in offline mode");
        }
        state.incrementVersion();
        return state;
    }

    @Override
    public boolean isGameOver(GameState state) {
        if (state.boardState().closedRows().size() >= 2) return true;
        return state.boardState().sheetProgress().values().stream()
                .anyMatch(p -> p.punishments() >= MAX_PUNISHMENTS);
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
            snap.put(pid, deepCopy(state.boardState().sheetProgress().get(pid)));
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
        turn.undoBuffer().put(playerId, crossed);

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
        if (state.pendingClosures().containsKey(rowIndex))
            throw new IllegalMoveException("row is already declared for closure this turn");

        state.pendingClosures().put(rowIndex, declarerId);

        Color rowColor = state.sheetLayouts().get(declarerId).rows().get(rowIndex).lock().color();
        state.rowClosureRequests().add(new RowClosureRequest(declarerId, rowColor));

        if (!isActive) {
            turn.passivesActed().add(declarerId);
        }
    }

    private void applyUndoLastCross(GameState state, UndoLastCrossAction action) {
        TurnState turn = state.turnState();
        if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.PASSIVE_MOVE)
            throw new IllegalMoveException("UndoLastCrossAction not valid in phase " + turn.phase());

        UUID playerId = action.playerId();
        Map<Integer, Set<String>> lastCross = turn.undoBuffer().get(playerId);
        if (lastCross == null) throw new IllegalMoveException("no cross to undo");

        // For the active player, undo = full reset so dice usage flags are also cleared.
        if (playerId.equals(turn.activePlayerId())) {
            applyResetTurn(state, new ResetTurnAction(playerId));
            return;
        }

        SheetProgress progress = state.boardState().sheetProgress().get(playerId);
        for (var entry : lastCross.entrySet()) {
            int idx = entry.getKey();
            RowState current = rowStateOf(progress, idx);
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

        turn.undoBuffer().remove(playerId);
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
        state.boardState().sheetProgress().get(playerId).addPunishment();

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
        if (isActive && turn.phase() == TurnPhase.PASSIVE_MOVE) {
            turn.passivePlayerQueue().remove(playerId); // in case they were re-added for final look
            restoreToSnapshot(state, turn, playerId);
            cancelPlayerClosingIntents(state, playerId);
            turn.undoBuffer().remove(playerId);
            if (turn.activeTurnState() != null) turn.activeTurnState().reset();
            turn.setPhase(TurnPhase.ACTIVE_MOVE);
            return;
        }

        // Special case: passive player reverts their EndTurn while the turn is still active.
        // Passives may EndTurn during ACTIVE_MOVE (before the active player passes) or during
        // PASSIVE_MOVE; in both cases they leave the queue. Restore from the turn-start snapshot
        // (the undo buffer was cleared on EndTurn) and put them back in the passive queue.
        if (!isActive
                && (turn.phase() == TurnPhase.ACTIVE_MOVE || turn.phase() == TurnPhase.PASSIVE_MOVE)
                && !turn.passivePlayerQueue().contains(playerId)) {
            restoreToSnapshot(state, turn, playerId);
            cancelPlayerClosingIntents(state, playerId);
            turn.undoBuffer().remove(playerId);
            turn.passivesActed().remove(playerId);
            turn.passivePlayerQueue().add(playerId);
            return;
        }

        restoreToSnapshot(state, turn, playerId);
        cancelPlayerClosingIntents(state, playerId);

        turn.undoBuffer().remove(playerId);
        turn.passivesActed().remove(playerId);

        if (isActive) {
            if (turn.activeTurnState() != null) turn.activeTurnState().reset();
        }
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
            ActiveTurnState ats = turn.activeTurnState();
            if (!ats.whiteWhiteUsed() && !ats.colorDieUsed() && !state.pendingClosures().containsValue(playerId))
                throw new IllegalMoveException("must make at least one move before ending turn");
            autoDetectClosingIntent(state, turn, playerId);
            if (turn.passivePlayerQueue().isEmpty()) {
                evaluate(state);
                // Clear after evaluate so canCrossLock can still see this player's pending
                // crosses when deciding whether non-declarant players qualify for the lock cross.
                turn.undoBuffer().remove(playerId);
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
            } else {
                // Before evaluating, check whether the active player could still claim any of the
                // newly declared rows (not yet crossed the closing cell, but enough crosses to qualify).
                // If so, re-add them to the passive queue so they can revert (RESET_TURN) or proceed (PASS).
                boolean activeCouldLock = state.pendingClosures().entrySet().stream()
                        .anyMatch(e -> !e.getValue().equals(activeId)
                                       && couldActivePlayerLockRow(state, activeId, e.getKey()));
                if (activeCouldLock) {
                    turn.passivePlayerQueue().add(activeId);
                    // Don't evaluate yet — active player must respond to the notification first.
                } else {
                    evaluate(state);
                }
            }
        }
        // Clear after evaluate so that canCrossLock can still read this player's pending
        // crosses when deciding whether non-declarant players qualify for the lock cross.
        turn.undoBuffer().remove(playerId);
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
        LockCell lock = state.sheetLayouts().get(activeId).rows().get(rowIndex).lock();
        Set<String> allCrosses = allCrossesForPlayer(state, activeId, rowIndex);
        // If the player already has the LAST closing cell crossed they already qualify at
        // evaluate — re-queuing would interrupt the game without benefit.
        // (Earlier closing cells like Longo "15" may be crossed from a prior turn; in that
        // case the player might still want to also cross "16" this turn, so we allow re-queue.)
        String lastClosingCell = lock.closingCells().get(lock.closingCells().size() - 1);
        if (allCrosses.contains(lastClosingCell)) return false;
        // Re-queue if crossing the last closing cell would give them enough crosses to qualify.
        return allCrosses.size() + 1 >= lock.minCrosses();
    }

    // Auto-detect closing intent for a player who crossed the LAST closing cell this turn.
    // The second-to-last closing cell (Longo "15"/"3") requires an explicit YES from the client.
    private void autoDetectClosingIntent(GameState state, TurnState turn, UUID playerId) {
        Map<Integer, Set<String>> playerBuffer = turn.undoBuffer().get(playerId);
        if (playerBuffer == null) return;

        SheetLayout layout = state.sheetLayouts().get(playerId);
        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (state.boardState().closedRows().containsKey(rowIndex)) continue;
            if (state.pendingClosures().containsKey(rowIndex)) continue;

            Row row = layout.rows().get(rowIndex);
            if (row.lock() == null) continue;
            LockCell lock = row.lock();

            String lastClosingCell = lock.closingCells().get(lock.closingCells().size() - 1);
            Set<String> pendingInRow = playerBuffer.getOrDefault(rowIndex, Set.of());
            if (!pendingInRow.contains(lastClosingCell)) continue;

            if (!canCrossLock(state, playerId, rowIndex)) continue;

            state.pendingClosures().put(rowIndex, playerId);
            Color rowColor = row.lock().color();
            state.rowClosureRequests().add(new RowClosureRequest(playerId, rowColor));
        }
    }

    private void evaluate(GameState state) {
        // Apply all pending row closures: mark lock ✓ for declarant (and any other qualifier),
        // close row globally, and remove the colored die.
        for (var entry : new HashMap<>(state.pendingClosures()).entrySet()) {
            int rowIndex   = entry.getKey();
            UUID declarant = entry.getValue();
            if (state.boardState().closedRows().containsKey(rowIndex)) continue;

            markLockCrossed(state, declarant, rowIndex);
            for (UUID pid : state.players()) {
                if (!pid.equals(declarant)
                        && !rowStateOf(state.boardState().sheetProgress().get(pid), rowIndex).lockCrossed()
                        && canCrossLock(state, pid, rowIndex)) {
                    markLockCrossed(state, pid, rowIndex);
                }
            }
            closeRowGlobally(state, declarant, rowIndex);
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
        cellCrosser.addReachableCells(state, playerId, actions, isActive);
    }

    // Offer DECLARE_LOCK_INTENT when the player can explicitly declare closing intent:
    // 1. Player already qualifies via a PERMANENT last closing cell (crossed in a previous turn).
    // 2. Player just crossed the second-to-last closing cell this turn (Longo YES/NO modal).
    // The last closing cell crossed THIS turn is auto-detected at EndTurn — no explicit intent needed.
    private void addClosingIntents(GameState state, UUID playerId, List<GameAction> actions) {
        SheetLayout layout        = state.sheetLayouts().get(playerId);
        Map<Integer, UUID> closed = state.boardState().closedRows();

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (closed.containsKey(rowIndex)) continue;
            if (state.pendingClosures().containsKey(rowIndex)) continue;
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
        LockCell lock = state.sheetLayouts().get(playerId).rows().get(rowIndex).lock();
        RowState rowState = rowStateOf(state.boardState().sheetProgress().get(playerId), rowIndex);
        if (rowState.crossedCells().size() < lock.minCrosses()) return false;
        String lastClosing = lock.closingCells().get(lock.closingCells().size() - 1);
        return rowState.crossedCells().contains(lastClosing);
    }

    // True when the second-to-last closing cell is a pending cross this turn AND the player qualifies.
    // This is the Longo "15"/"3" explicit YES scenario.
    protected boolean canDeclareViaSecondToLastCell(GameState state, UUID playerId, int rowIndex) {
        if (rowIsNotLockable(state, playerId, rowIndex)) return false;
        LockCell lock = state.sheetLayouts().get(playerId).rows().get(rowIndex).lock();
        List<String> closing = lock.closingCells();
        if (closing.size() < 2) return false;

        String secondToLast = closing.get(closing.size() - 2);
        TurnState turn = state.turnState();
        if (turn == null) return false;
        Map<Integer, Set<String>> playerBuffer = turn.undoBuffer().get(playerId);
        if (playerBuffer == null) return false;
        if (!playerBuffer.getOrDefault(rowIndex, Set.of()).contains(secondToLast)) return false;

        return canCrossLock(state, playerId, rowIndex);
    }

    protected boolean canCrossLock(GameState state, UUID playerId, int rowIndex) {
        if (rowIsNotLockable(state, playerId, rowIndex)) return false;
        LockCell lock     = state.sheetLayouts().get(playerId).rows().get(rowIndex).lock();
        Set<String> allCrosses = allCrossesForPlayer(state, playerId, rowIndex);
        return lock.closingCells().stream().anyMatch(allCrosses::contains);
    }

    /** Returns true when the row has no lock, is already locked, or is already closed. */
    protected boolean rowIsNotLockable(GameState state, UUID playerId, int rowIndex) {
        Row row = state.sheetLayouts().get(playerId).rows().get(rowIndex);
        if (row.lock() == null) return true;
        RowState rowState = rowStateOf(state.boardState().sheetProgress().get(playerId), rowIndex);
        if (rowState.lockCrossed()) return true;
        return state.boardState().closedRows().containsKey(rowIndex);
    }

    /** Permanent crosses in a row plus any pending cross from the current turn's undo buffer. */
    protected Set<String> allCrossesForPlayer(GameState state, UUID playerId, int rowIndex) {
        RowState rowState = rowStateOf(state.boardState().sheetProgress().get(playerId), rowIndex);
        Set<String> all = new HashSet<>(rowState.crossedCells());
        TurnState turn = state.turnState();
        if (turn != null && turn.undoBuffer().containsKey(playerId)) {
            all.addAll(turn.undoBuffer().get(playerId).getOrDefault(rowIndex, new HashSet<>()));
        }
        return all;
    }

    protected void markLockCrossed(GameState state, UUID playerId, int rowIndex) {
        SheetProgress prog = state.boardState().sheetProgress().get(playerId);
        RowState current   = rowStateOf(prog, rowIndex);
        prog.updateRowState(rowIndex, new RowState(current.crossedCells(), true));
    }

    protected void closeRowGlobally(GameState state, UUID playerId, int rowIndex) {
        BoardState board    = state.boardState();
        board.closedRows().put(rowIndex, playerId);

        SheetLayout layout  = state.sheetLayouts().get(playerId);
        Color lockColor     = layout.rows().get(rowIndex).lock().color();
        board.activeDice().removeIf(d -> d.color() == lockColor);
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

    protected RowState rowStateOf(SheetProgress progress, int rowIndex) {
        return progress.rowStates().getOrDefault(rowIndex, new RowState(new HashSet<>(), false));
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
}
