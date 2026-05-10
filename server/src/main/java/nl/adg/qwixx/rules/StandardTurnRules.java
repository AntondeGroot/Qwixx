package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.CrossLockAction;
import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.EndTurnAction;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.action.GiveUpAction;
import nl.adg.qwixx.action.ResetTurnAction;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.action.TakePunishmentAction;
import nl.adg.qwixx.action.UndoLastCrossAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.GameState;
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
import java.util.Optional;
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

            case LOCK_PENDING -> lockPendingActions(state, playerId, turn, isActive);

            case EVALUATE -> List.of();
        };
    }

    private List<GameAction> activeMoveActions(GameState state, UUID playerId, TurnState turn) {
        List<GameAction> actions = new ArrayList<>();
        addReachableCells(state, playerId, actions, true);
        addLockIntents(state, playerId, actions);
        actions.add(new GiveUpAction(playerId));
        actions.add(new ResetTurnAction(playerId));
        ActiveTurnState ats = turn.activeTurnState();
        if (ats.whiteWhiteUsed() || ats.colorDieUsed()) {
            actions.add(new EndTurnAction(playerId));
        }
        return actions;
    }

    private List<GameAction> passiveActions(GameState state, UUID playerId, TurnState turn) {
        if (!TurnHelper.isPassiveInQueue(turn, playerId)) return List.of();
        List<GameAction> actions = new ArrayList<>();
        if (!TurnHelper.hasAlreadyActed(turn, playerId)) {
            addReachableCells(state, playerId, actions, false);
            addPassiveLockIntents(state, playerId, actions);
        } else if (TurnHelper.hasPendingCross(turn, playerId)) {
            addLockIntents(state, playerId, actions);
            actions.add(new ResetTurnAction(playerId));
        }
        actions.add(new EndTurnAction(playerId));
        return actions;
    }

    private List<GameAction> lockPendingActions(GameState state, UUID playerId, TurnState turn, boolean isActive) {
        List<GameAction> actions = new ArrayList<>();
        boolean isDeclarant = playerId.equals(turn.pendingLockDeclarerId());
        if (isDeclarant) {
            if (TurnHelper.allNonActiveAcknowledged(state)) {
                actions.add(new CrossLockAction(playerId, turn.pendingLockRowIndex()));
            }
            if (isActive) actions.add(new GiveUpAction(playerId));
            actions.add(new ResetTurnAction(playerId));
        } else if (!turn.lockAcknowledged().contains(playerId)) {
            if (isActive) {
                // Active player (non-declarant) retains full active options while in LOCK_PENDING:
                // they may still use white+white and/or the color die before acknowledging.
                addReachableCells(state, playerId, actions, true);
                if (TurnHelper.hasPendingCross(turn, playerId)) {
                    actions.add(new UndoLastCrossAction(playerId));
                }
                // EndTurn is always available — it is the acknowledgement for the active player.
                actions.add(new EndTurnAction(playerId));
                actions.add(new GiveUpAction(playerId));
            } else {
                // Passive non-declarant: white+white only (unchanged behavior).
                if (TurnHelper.hasPendingCross(turn, playerId)) {
                    actions.add(new UndoLastCrossAction(playerId));
                } else {
                    addReachableCells(state, playerId, actions, false);
                }
                actions.add(new EndTurnAction(playerId));
                if (canCrossLock(state, playerId, turn.pendingLockRowIndex())) {
                    actions.add(new CrossLockAction(playerId, turn.pendingLockRowIndex()));
                }
            }
        }
        return actions;
    }

    @Override
    public GameState apply(GameState state, GameAction action) {
        switch (action) {
            case RollAction a              -> applyRoll(state, a);
            case CrossCellAction a         -> applyCrossCell(state, a);
            case DeclareLockIntentAction a -> applyDeclareLockIntent(state, a);
            case CrossLockAction a         -> applyCrossLock(state, a);
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
        turn.setLockAcknowledged(new HashSet<>());

        turn.setPhase(TurnPhase.ACTIVE_MOVE);
    }

    private void applyCrossCell(GameState state, CrossCellAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        if (state.boardState().closedRows().containsKey(action.rowIndex()))
            throw new IllegalMoveException("row is closed");

        boolean actingAsPassive = !isActive;

        if (actingAsPassive) {
            requirePassiveMayCrossCell(turn, playerId);
        } else {
            // Declarant in LOCK_PENDING must use CrossLockAction, not CrossCellAction.
            if (turn.phase() == TurnPhase.LOCK_PENDING && playerId.equals(turn.pendingLockDeclarerId()))
                throw new IllegalMoveException("declarant must use CrossLockAction in LOCK_PENDING");
            if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.LOCK_PENDING)
                throw new IllegalMoveException("expected phase ACTIVE_MOVE or LOCK_PENDING but was " + turn.phase());
        }

        Map<Integer, Set<String>> crossed = crossCellWithAutoTags(state, playerId, action.rowIndex(), action.cellId());
        turn.undoBuffer().put(playerId, crossed);

        if (!actingAsPassive) {
            recordActiveDiceUsage(turn.activeTurnState(), action.combination());
        } else {
            turn.passivesActed().add(playerId);
            acknowledgePassiveByCellCrossInLockPending(state, turn, playerId);
        }
    }

    private void requirePassiveMayCrossCell(TurnState turn, UUID playerId) {
        if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.PASSIVE_MOVE
                && turn.phase() != TurnPhase.LOCK_PENDING)
            throw new IllegalMoveException("expected phase ACTIVE_MOVE, PASSIVE_MOVE, or LOCK_PENDING but was " + turn.phase());
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

    // In LOCK_PENDING a passive crossing any cell implicitly acknowledges the lock —
    // there is no reason for them to undo afterward. Promote the cross to permanent.
    private void acknowledgePassiveByCellCrossInLockPending(GameState state, TurnState turn, UUID playerId) {
        if (turn.phase() != TurnPhase.LOCK_PENDING) return;
        if (playerId.equals(turn.pendingLockDeclarerId())) return;
        if (turn.lockAcknowledged().contains(playerId)) return;
        turn.undoBuffer().remove(playerId);
        turn.lockAcknowledged().add(playerId);
        if (TurnHelper.allNonActiveAcknowledged(state)) {
            applyCrossLock(state, new CrossLockAction(turn.pendingLockDeclarerId(), turn.pendingLockRowIndex()));
        }
    }

    private void applyDeclareLockIntent(GameState state, DeclareLockIntentAction action) {
        TurnState turn = state.turnState();
        UUID declarerId = action.playerId();
        boolean isActive = declarerId.equals(turn.activePlayerId());

        if (isActive) {
            requirePhase(turn, TurnPhase.ACTIVE_MOVE);
            if (!canCrossLock(state, declarerId, action.rowIndex()))
                throw new IllegalMoveException("lock pre-conditions not met");
        } else {
            validateAndPreparePassiveDeclarant(state, turn, declarerId, action.rowIndex());
        }

        turn.setPendingLockRowIndex(action.rowIndex());
        turn.setPendingLockDeclarerId(declarerId);
        turn.setLockAcknowledged(new HashSet<>());

        List<UUID> toAcknowledge = new ArrayList<>(state.players());
        toAcknowledge.remove(declarerId);
        turn.setPassivePlayerQueue(toAcknowledge);

        turn.setPhase(TurnPhase.LOCK_PENDING);

        // Auto-resolve when nobody else needs to acknowledge (single-player or all already acted)
        if (TurnHelper.allNonActiveAcknowledged(state)) {
            applyCrossLock(state, new CrossLockAction(declarerId, action.rowIndex()));
        }
    }

    private void validateAndPreparePassiveDeclarant(GameState state, TurnState turn, UUID declarerId, int rowIndex) {
        if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.PASSIVE_MOVE)
            throw new IllegalMoveException("passive can only declare lock intent in ACTIVE_MOVE or PASSIVE_MOVE");
        if (!turn.passivePlayerQueue().contains(declarerId))
            throw new IllegalMoveException("player not in passive queue");
        // Allow if the pending cross is itself the qualifying cell (concurrent lock blocked the declaration).
        boolean qualifiesFromPendingCross = TurnHelper.hasPendingCross(turn, declarerId)
                && canCrossLock(state, declarerId, rowIndex);
        if (!qualifiesFromPendingCross && TurnHelper.hasAlreadyActed(turn, declarerId))
            throw new IllegalMoveException("passive player already acted this turn");
        if (!canPassiveDeclareIntent(state, declarerId, rowIndex))
            throw new IllegalMoveException("passive lock pre-conditions not met");
        if (!canCrossLock(state, declarerId, rowIndex))
            crossClosingCellForPassive(state, declarerId, rowIndex);
        turn.passivesActed().add(declarerId);
        turn.passivePlayerQueue().remove(declarerId);
    }

    private void applyCrossLock(GameState state, CrossLockAction action) {
        TurnState turn    = state.turnState();
        UUID playerId     = action.playerId();
        int rowIndex      = action.rowIndex();
        boolean isDeclarant = playerId.equals(turn.pendingLockDeclarerId());

        requirePhase(turn, TurnPhase.LOCK_PENDING);
        if (!canCrossLock(state, playerId, rowIndex))
            throw new IllegalMoveException("lock pre-conditions not met");

        markLockCrossed(state, playerId, rowIndex);

        if (isDeclarant) {
            if (!TurnHelper.allNonActiveAcknowledged(state))
                throw new IllegalMoveException("not all players have acknowledged yet");
            closeRowGlobally(state, playerId, rowIndex);
            advanceTurnAfterLockClose(state);
        } else {
            turn.lockAcknowledged().add(playerId);
        }
    }

    private void applyUndoLastCross(GameState state, UndoLastCrossAction action) {
        TurnState turn = state.turnState();
        requirePhase(turn, TurnPhase.LOCK_PENDING);

        UUID playerId = action.playerId();
        // For the declarant, "undo the cross" naturally means "cancel the whole lock declaration".
        // Treat it identically to RESET_TURN so both the pending-cell click and the explicit
        // reset button produce the same result regardless of which action the client sends.
        if (playerId.equals(turn.pendingLockDeclarerId())) {
            applyResetTurn(state, new ResetTurnAction(playerId));
            return;
        }
        Map<Integer, Set<String>> lastCross = turn.undoBuffer().get(playerId);
        if (lastCross == null) throw new IllegalMoveException("no cross to undo");

        SheetProgress progress = state.boardState().sheetProgress().get(playerId);
        for (var entry : lastCross.entrySet()) {
            int rowIndex = entry.getKey();
            RowState current = rowStateOf(progress, rowIndex);
            Set<String> updated = new HashSet<>(current.crossedCells());
            updated.removeAll(entry.getValue());
            progress.updateRowState(rowIndex, new RowState(updated, current.lockCrossed()));
        }

        turn.undoBuffer().remove(playerId);
        turn.passivesActed().remove(playerId); // cross is undone — player may act again
        // For the active player, also reset the active-turn die-usage flags so they
        // can re-cross with the same combination after undoing.
        if (playerId.equals(turn.activePlayerId()) && turn.activeTurnState() != null) {
            turn.activeTurnState().reset();
        }
    }

    private void applyGiveUp(GameState state, GiveUpAction action) {
        TurnState turn = state.turnState();
        requireActivePlayer(turn, action.playerId());
        if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.LOCK_PENDING)
            throw new IllegalMoveException("GiveUpAction not valid in phase " + turn.phase());

        UUID playerId = action.playerId();
        restoreToSnapshot(state, turn, playerId);
        state.boardState().sheetProgress().get(playerId).addPunishment();

        // When a passive holds the lock intent, the active giving up does NOT abort it.
        // The give-up counts as acknowledgement so the passive's lock can still close.
        if (turn.phase() == TurnPhase.LOCK_PENDING && !playerId.equals(turn.pendingLockDeclarerId())) {
            completePassiveLockAfterGiveUp(state, playerId);
            return;
        }

        // Active declared the lock (if in LOCK_PENDING) or is simply in ACTIVE_MOVE.
        // Determine which passives still need their passive move.
        // In ACTIVE_MOVE, passivePlayerQueue already tracks who hasn't acted yet.
        // In LOCK_PENDING, passivePlayerQueue was repurposed for acknowledgement tracking
        // so we rebuild it: all non-active players who haven't acknowledged AND haven't acted.
        List<UUID> pendingPassives;
        if (turn.phase() == TurnPhase.LOCK_PENDING) {
            pendingPassives = new ArrayList<>(state.players());
            pendingPassives.remove(playerId);
            pendingPassives.removeAll(turn.lockAcknowledged());
            pendingPassives.removeIf(pid -> turn.passivesActed().contains(pid));
        } else {
            pendingPassives = new ArrayList<>(turn.passivePlayerQueue());
        }

        TurnHelper.clearPendingLock(turn);

        if (pendingPassives.isEmpty()) {
            evaluate(state);
        } else {
            state.rowClosureRequests().clear();
            turn.setPassivePlayerQueue(pendingPassives);
            turn.setPhase(TurnPhase.PASSIVE_MOVE);
        }
    }

    private void completePassiveLockAfterGiveUp(GameState state, UUID playerId) {
        TurnState turn = state.turnState();
        turn.undoBuffer().remove(playerId); // sheet was restored; clear stale pending cross
        turn.lockAcknowledged().add(playerId);
        if (TurnHelper.allNonActiveAcknowledged(state)) {
            UUID declarerId = turn.pendingLockDeclarerId();
            int rowIndex    = turn.pendingLockRowIndex();
            markLockCrossed(state, declarerId, rowIndex);
            closeRowGlobally(state, declarerId, rowIndex);

            List<UUID> remainingPassives = new ArrayList<>(state.players());
            remainingPassives.remove(playerId);
            remainingPassives.removeAll(turn.lockAcknowledged());
            remainingPassives.removeIf(pid -> turn.passivesActed().contains(pid));

            TurnHelper.clearPendingLock(turn);
            state.rowClosureRequests().clear();

            if (remainingPassives.isEmpty()) {
                evaluate(state);
            } else {
                turn.setPassivePlayerQueue(remainingPassives);
                turn.setPhase(TurnPhase.PASSIVE_MOVE);
            }
        }
    }

    private void applyResetTurn(GameState state, ResetTurnAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        restoreToSnapshot(state, turn, playerId);

        turn.undoBuffer().remove(playerId);
        turn.passivesActed().remove(playerId);

        if (isActive) {
            state.rowClosureRequests().clear();
            if (turn.activeTurnState() != null) turn.activeTurnState().reset();
            if (turn.phase() == TurnPhase.LOCK_PENDING) {
                TurnHelper.clearPendingLock(turn);
                turn.setPhase(TurnPhase.ACTIVE_MOVE);
            }
        } else if (turn.phase() == TurnPhase.LOCK_PENDING
                   && playerId.equals(turn.pendingLockDeclarerId())) {
            // Passive declarant cancelling their lock intent
            state.rowClosureRequests().clear();
            TurnHelper.clearPendingLock(turn);
            // Re-add this player to the passive queue so they can act again
            List<UUID> remainingPassives = TurnHelper.unactedPassives(state, turn.activePlayerId());
            turn.setPassivePlayerQueue(remainingPassives);
            turn.setPhase(TurnPhase.ACTIVE_MOVE);
        }
    }

    private void applyEndTurn(GameState state, EndTurnAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        switch (turn.phase()) {
            case ACTIVE_MOVE  -> endTurnInActiveMove(state, turn, playerId, isActive);
            case PASSIVE_MOVE -> endTurnInPassiveMove(state, turn, playerId);
            case LOCK_PENDING -> acknowledgeLock(state, turn, playerId);
            default -> throw new IllegalMoveException("EndTurnAction not valid in phase " + turn.phase());
        }
    }

    private void endTurnInActiveMove(GameState state, TurnState turn, UUID playerId, boolean isActive) {
        if (isActive) {
            ActiveTurnState ats = turn.activeTurnState();
            if (!ats.whiteWhiteUsed() && !ats.colorDieUsed())
                throw new IllegalMoveException("must make at least one move before ending turn");
            turn.undoBuffer().remove(playerId);
            if (turn.passivePlayerQueue().isEmpty()) evaluate(state);
            else turn.setPhase(TurnPhase.PASSIVE_MOVE);
        } else {
            if (!turn.passivePlayerQueue().contains(playerId))
                throw new IllegalMoveException("player not in passive queue");
            turn.passivePlayerQueue().remove(playerId);
            // Keep undoBuffer intact: if the active later declares a lock intent,
            // this player will be re-invited and must still be able to undo their cross.
        }
    }

    private void endTurnInPassiveMove(GameState state, TurnState turn, UUID playerId) {
        if (!turn.passivePlayerQueue().contains(playerId))
            throw new IllegalMoveException("player not in passive queue");
        turn.passivePlayerQueue().remove(playerId);
        // Keep undoBuffer intact: same reason as ACTIVE_MOVE passive EndTurn above.
        if (turn.passivePlayerQueue().isEmpty()) evaluate(state);
    }

    private void acknowledgeLock(GameState state, TurnState turn, UUID playerId) {
        if (playerId.equals(turn.pendingLockDeclarerId()))
            throw new IllegalMoveException("declarant cannot EndTurn during LOCK_PENDING");
        if (turn.lockAcknowledged().contains(playerId))
            throw new IllegalMoveException("already acknowledged");
        // Verify the declarant can still close BEFORE mutating lockAcknowledged — a failed
        // applyCrossLock after mutation would leave the game frozen.
        if (TurnHelper.isLastMissingAcknowledgement(turn, playerId)
                && !canCrossLock(state, turn.pendingLockDeclarerId(), turn.pendingLockRowIndex())) {
            throw new IllegalMoveException("declarant no longer satisfies lock pre-conditions");
        }
        turn.undoBuffer().remove(playerId);
        turn.lockAcknowledged().add(playerId);
        if (TurnHelper.allNonActiveAcknowledged(state)) {
            applyCrossLock(state, new CrossLockAction(turn.pendingLockDeclarerId(), turn.pendingLockRowIndex()));
        }
    }

    private void evaluate(GameState state) {
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

    private void advanceTurnAfterLockClose(GameState state) {
        TurnState turn = state.turnState();
        TurnHelper.clearPendingLock(turn);

        UUID active = turn.activePlayerId();
        List<UUID> remainingPassives = TurnHelper.unactedPassives(state, active);
        reinvitePassivesWhosePendingCrossNowQualifiesForLock(state, active, remainingPassives);

        boolean activeCanClose  = canCloseAnyRow(state, active);
        boolean passiveCanClose = remainingPassives.stream()
                .anyMatch(pid -> canPassiveDeclareIntentAnyRow(state, pid) || canCloseAnyRow(state, pid));

        if (activeCanClose || passiveCanClose) {
            turn.setPassivePlayerQueue(remainingPassives);
            turn.setPhase(activeCanClose ? TurnPhase.ACTIVE_MOVE : TurnPhase.PASSIVE_MOVE);
        } else {
            evaluate(state);
        }
    }

    private void reinvitePassivesWhosePendingCrossNowQualifiesForLock(
            GameState state, UUID active, List<UUID> passives) {
        for (UUID pid : state.players()) {
            if (!pid.equals(active) && !passives.contains(pid)
                    && TurnHelper.hasPendingCross(state.turnState(), pid)
                    && canCloseAnyRow(state, pid)) {
                passives.add(pid);
            }
        }
    }

    private void addLockIntents(GameState state, UUID playerId, List<GameAction> actions) {
        SheetLayout layout        = state.sheetLayouts().get(playerId);
        Map<Integer, UUID> closed = state.boardState().closedRows();

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (closed.containsKey(rowIndex)) continue;
            if (canCrossLock(state, playerId, rowIndex)) {
                actions.add(new DeclareLockIntentAction(playerId, rowIndex));
            }
        }
    }

    private void addPassiveLockIntents(GameState state, UUID playerId, List<GameAction> actions) {
        SheetLayout layout        = state.sheetLayouts().get(playerId);
        Map<Integer, UUID> closed = state.boardState().closedRows();

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (closed.containsKey(rowIndex)) continue;
            if (canPassiveDeclareIntent(state, playerId, rowIndex)) {
                actions.add(new DeclareLockIntentAction(playerId, rowIndex));
            }
        }
    }

    private boolean canPassiveDeclareIntent(GameState state, UUID passive, int rowIndex) {
        if (canCrossLock(state, passive, rowIndex)) return true;

        TurnState turn = state.turnState();
        var roll = turn.currentRoll();
        if (roll == null) return false;

        SheetLayout layout = state.sheetLayouts().get(passive);
        Row row = layout.rows().get(rowIndex);
        if (row.lock() == null) return false;
        LockCell lock = row.lock();

        SheetProgress prog = state.boardState().sheetProgress().get(passive);
        RowState rowState = rowStateOf(prog, rowIndex);
        if (rowState.lockCrossed()) return false;
        if (state.boardState().closedRows().containsKey(rowIndex)) return false;

        String whiteWhiteValue = String.valueOf(roll.white1() + roll.white2());
        Optional<Cell> match = uncrossedRequiredCellMatchingRoll(lock, row, rowState, whiteWhiteValue);
        if (match.isEmpty()) return false;
        long alreadyCrossedRequired = lock.requiredCells().stream()
                .filter(rowState.crossedCells()::contains).count();
        long normalCrossed = rowState.crossedCells().size() - alreadyCrossedRequired;
        return normalCrossed + 1 >= lock.minCrosses();
    }

    private void crossClosingCellForPassive(GameState state, UUID passive, int rowIndex) {
        TurnState turn = state.turnState();
        var roll = turn.currentRoll();
        SheetLayout layout = state.sheetLayouts().get(passive);
        Row row = layout.rows().get(rowIndex);
        LockCell lock = row.lock();
        SheetProgress prog = state.boardState().sheetProgress().get(passive);
        RowState rowState = rowStateOf(prog, rowIndex);
        String whiteWhiteValue = String.valueOf(roll.white1() + roll.white2());

        Cell reqCell = uncrossedRequiredCellMatchingRoll(lock, row, rowState, whiteWhiteValue)
                .orElseThrow(() -> new IllegalMoveException(
                        "passive lock: closing cell not found — white+white=" + whiteWhiteValue + " row=" + rowIndex));
        Map<Integer, Set<String>> crossed = crossCellWithAutoTags(state, passive, rowIndex, reqCell.id());
        turn.undoBuffer().put(passive, crossed);
    }

    protected boolean canCrossLock(GameState state, UUID playerId, int rowIndex) {
        if (rowIsNotLockable(state, playerId, rowIndex)) return false;
        LockCell lock     = state.sheetLayouts().get(playerId).rows().get(rowIndex).lock();
        Set<String> allCrosses = allCrossesForPlayer(state, playerId, rowIndex);
        return allCrosses.containsAll(lock.requiredCells());
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

    private boolean canCloseAnyRow(GameState state, UUID playerId) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        Map<Integer, UUID> closed = state.boardState().closedRows();
        for (int i = 0; i < layout.rows().size(); i++) {
            if (!closed.containsKey(i) && canCrossLock(state, playerId, i)) return true;
        }
        return false;
    }

    private boolean canPassiveDeclareIntentAnyRow(GameState state, UUID playerId) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        Map<Integer, UUID> closed = state.boardState().closedRows();
        for (int i = 0; i < layout.rows().size(); i++) {
            if (!closed.containsKey(i) && canPassiveDeclareIntent(state, playerId, i)) return true;
        }
        return false;
    }

    private Optional<Cell> uncrossedRequiredCellMatchingRoll(LockCell lock, Row row, RowState rowState, String whiteSum) {
        return lock.requiredCells().stream()
                .filter(id -> !rowState.crossedCells().contains(id))
                .flatMap(id -> row.cells().stream().filter(c -> c.id().equals(id)).limit(1))
                .filter(c -> c.displayValue().equals(whiteSum))
                .findFirst();
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