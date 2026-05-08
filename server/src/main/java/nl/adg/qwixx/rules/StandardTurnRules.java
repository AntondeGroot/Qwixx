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
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.RollResult;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class StandardTurnRules implements TurnRules {

    static final int MAX_PUNISHMENTS = 4;

    private final Random random;

    public StandardTurnRules() {
        this.random = new Random();
    }

    public StandardTurnRules(Random random) {
        this.random = random;
    }

    // -------------------------------------------------------------------------
    // TurnRules interface
    // -------------------------------------------------------------------------

    @Override
    public List<GameAction> getValidActions(GameState state, UUID playerId) {
        if (state.gameOver()) return List.of();

        TurnState turn = state.turnState();
        boolean isActive = playerId.equals(turn.activePlayerId());

        return switch (turn.phase()) {
            case ROLL -> isActive ? List.of(new RollAction(playerId)) : List.of();

            case ACTIVE_MOVE -> {
                if (isActive) {
                    List<GameAction> actions = new ArrayList<>();
                    addReachableCells(state, playerId, actions, true);
                    addLockIntents(state, playerId, actions);
                    actions.add(new GiveUpAction(playerId));
                    actions.add(new ResetTurnAction(playerId));
                    ActiveTurnState ats = turn.activeTurnState();
                    if (ats.whiteWhiteUsed() || ats.colorDieUsed()) {
                        actions.add(new EndTurnAction(playerId));
                    }
                    yield actions;
                } else if (turn.passivePlayerQueue().contains(playerId)) {
                    List<GameAction> actions = new ArrayList<>();
                    boolean hasCrossed = turn.undoBuffer().containsKey(playerId);
                    boolean hasActed   = hasCrossed || turn.passivesActed().contains(playerId);
                    if (!hasActed) {
                        addReachableCells(state, playerId, actions, false);
                        addPassiveLockIntents(state, playerId, actions);
                    } else if (hasCrossed) {
                        actions.add(new ResetTurnAction(playerId));
                    }
                    actions.add(new EndTurnAction(playerId));
                    yield actions;
                } else {
                    yield List.of();
                }
            }

            case PASSIVE_MOVE -> {
                if (isActive) yield List.of();
                if (!turn.passivePlayerQueue().contains(playerId)) yield List.of();
                List<GameAction> actions = new ArrayList<>();
                boolean hasCrossed = turn.undoBuffer().containsKey(playerId);
                boolean hasActed   = hasCrossed || turn.passivesActed().contains(playerId);
                if (!hasActed) {
                    addReachableCells(state, playerId, actions, false);
                    addPassiveLockIntents(state, playerId, actions);
                }
                if (hasCrossed) {
                    actions.add(new ResetTurnAction(playerId));
                }
                actions.add(new EndTurnAction(playerId));
                yield actions;
            }

            case LOCK_PENDING -> {
                List<GameAction> actions = new ArrayList<>();
                boolean isDeclarant = playerId.equals(turn.pendingLockDeclarerId());
                if (isDeclarant) {
                    if (allNonActiveAcknowledged(state)) {
                        actions.add(new CrossLockAction(playerId, turn.pendingLockRowIndex()));
                    }
                    if (isActive) actions.add(new GiveUpAction(playerId));
                    actions.add(new ResetTurnAction(playerId));
                } else if (!turn.lockAcknowledged().contains(playerId)) {
                    if (turn.undoBuffer().containsKey(playerId)) {
                        // Passive already crossed — offer undo so they can reconsider before deciding.
                        actions.add(new UndoLastCrossAction(playerId));
                    } else {
                        // No cross yet — offer white+white cells so the player can act or pass.
                        addReachableCells(state, playerId, actions, false);
                    }
                    actions.add(new EndTurnAction(playerId));
                    if (canCrossLock(state, playerId, turn.pendingLockRowIndex())) {
                        actions.add(new CrossLockAction(playerId, turn.pendingLockRowIndex()));
                    }
                    // Active player can give up (counts as acknowledging the passive's lock intent)
                    if (isActive) actions.add(new GiveUpAction(playerId));
                }
                yield actions;
            }

            case EVALUATE -> List.of();
        };
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
            case TakePunishmentAction a    -> throw new IllegalMoveException("TakePunishmentAction only valid in offline mode");
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

    // -------------------------------------------------------------------------
    // Action handlers
    // -------------------------------------------------------------------------

    private void applyRoll(GameState state, RollAction action) {
        TurnState turn = state.turnState();
        requirePhase(turn, TurnPhase.ROLL);
        requireActivePlayer(turn, action.playerId());

        RollResult roll = rollDice(state.boardState().activeDice());
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
        onAfterRoll(state, roll);
    }

    protected void onAfterRoll(GameState state, RollResult roll) {}

    private void applyCrossCell(GameState state, CrossCellAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        if (state.boardState().closedRows().containsKey(action.rowIndex()))
            throw new IllegalMoveException("row is closed");

        if (isActive) {
            requirePhase(turn, TurnPhase.ACTIVE_MOVE);
        } else {
            if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.PASSIVE_MOVE
                    && turn.phase() != TurnPhase.LOCK_PENDING)
                throw new IllegalMoveException("expected phase ACTIVE_MOVE, PASSIVE_MOVE, or LOCK_PENDING but was " + turn.phase());
            if (!turn.passivePlayerQueue().contains(playerId))
                throw new IllegalMoveException("player not in passive queue");
            if (turn.undoBuffer().containsKey(playerId))
                throw new IllegalMoveException("passive player already made a white+white cross this turn");
        }

        Map<Integer, Set<String>> crossed = crossCellWithAutoTags(state, playerId, action.rowIndex(), action.cellId());
        turn.undoBuffer().put(playerId, crossed);

        if (!isActive) {
            turn.passivesActed().add(playerId);

            // In LOCK_PENDING a passive crossing any cell implicitly acknowledges the lock —
            // there is no reason for them to undo afterward.  Promote the cross to permanent
            // and acknowledge, mirroring what EndTurnAction does.
            if (turn.phase() == TurnPhase.LOCK_PENDING
                    && !playerId.equals(turn.pendingLockDeclarerId())
                    && !turn.lockAcknowledged().contains(playerId)) {
                turn.undoBuffer().remove(playerId);
                turn.lockAcknowledged().add(playerId);
                if (allNonActiveAcknowledged(state)) {
                    applyCrossLock(state,
                            new CrossLockAction(turn.pendingLockDeclarerId(), turn.pendingLockRowIndex()));
                }
            }
        }

        if (isActive) {
            ActiveTurnState ats = turn.activeTurnState();
            if (action.combination() == DiceCombination.WHITE_WHITE) {
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
            if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.PASSIVE_MOVE)
                throw new IllegalMoveException("passive can only declare lock intent in ACTIVE_MOVE or PASSIVE_MOVE");
            if (!turn.passivePlayerQueue().contains(declarerId))
                throw new IllegalMoveException("player not in passive queue");
            if (turn.undoBuffer().containsKey(declarerId) || turn.passivesActed().contains(declarerId))
                throw new IllegalMoveException("passive player already acted this turn");
            if (!canPassiveDeclareIntent(state, declarerId, action.rowIndex()))
                throw new IllegalMoveException("passive lock pre-conditions not met");
            // Cross the closing cell using white+white if it is not yet crossed
            if (!canCrossLock(state, declarerId, action.rowIndex()))
                crossClosingCellForPassive(state, declarerId, action.rowIndex());
            turn.passivesActed().add(declarerId);
            turn.passivePlayerQueue().remove(declarerId);
        }

        turn.setPendingLockRowIndex(action.rowIndex());
        turn.setPendingLockDeclarerId(declarerId);
        turn.setLockAcknowledged(new HashSet<>());

        // All players except the declarant must acknowledge
        List<UUID> toAcknowledge = new ArrayList<>(state.players());
        toAcknowledge.remove(declarerId);
        turn.setPassivePlayerQueue(toAcknowledge);

        turn.setPhase(TurnPhase.LOCK_PENDING);

        // Auto-resolve when nobody else needs to acknowledge (single-player or all already acted)
        if (allNonActiveAcknowledged(state)) {
            applyCrossLock(state, new CrossLockAction(declarerId, action.rowIndex()));
        }
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
            if (!allNonActiveAcknowledged(state))
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
    }

    private void applyGiveUp(GameState state, GiveUpAction action) {
        TurnState turn = state.turnState();
        requireActivePlayer(turn, action.playerId());
        if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.LOCK_PENDING)
            throw new IllegalMoveException("GiveUpAction not valid in phase " + turn.phase());

        UUID playerId = action.playerId();
        SheetProgress snapshot = turn.moveStartProgress().get(playerId);
        if (snapshot != null) state.boardState().sheetProgress().put(playerId, deepCopy(snapshot));
        state.boardState().sheetProgress().get(playerId).addPunishment();

        // When a passive holds the lock intent, the active giving up does NOT abort it.
        // The give-up counts as acknowledgement so the passive's lock can still close.
        if (turn.phase() == TurnPhase.LOCK_PENDING && !playerId.equals(turn.pendingLockDeclarerId())) {
            turn.undoBuffer().remove(playerId); // sheet was restored; clear stale pending cross
            turn.lockAcknowledged().add(playerId);
            if (allNonActiveAcknowledged(state)) {
                UUID declarerId = turn.pendingLockDeclarerId();
                int rowIndex    = turn.pendingLockRowIndex();
                markLockCrossed(state, declarerId, rowIndex);
                closeRowGlobally(state, declarerId, rowIndex);

                List<UUID> remainingPassives = new ArrayList<>(state.players());
                remainingPassives.remove(playerId);
                remainingPassives.removeAll(turn.lockAcknowledged());
                remainingPassives.removeIf(pid -> turn.passivesActed().contains(pid));

                turn.setPendingLockRowIndex(null);
                turn.setPendingLockDeclarerId(null);
                turn.setLockAcknowledged(new HashSet<>());
                state.rowClosureRequests().clear();

                if (remainingPassives.isEmpty()) {
                    evaluate(state);
                } else {
                    turn.setPassivePlayerQueue(remainingPassives);
                    turn.setPhase(TurnPhase.PASSIVE_MOVE);
                }
            }
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

        turn.setPendingLockRowIndex(null);
        turn.setPendingLockDeclarerId(null);
        turn.setLockAcknowledged(new HashSet<>());

        if (pendingPassives.isEmpty()) {
            evaluate(state);
        } else {
            state.rowClosureRequests().clear();
            turn.setPassivePlayerQueue(pendingPassives);
            turn.setPhase(TurnPhase.PASSIVE_MOVE);
        }
    }

    private void applyResetTurn(GameState state, ResetTurnAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        SheetProgress snapshot = turn.moveStartProgress().get(playerId);
        if (snapshot != null) state.boardState().sheetProgress().put(playerId, deepCopy(snapshot));

        turn.undoBuffer().remove(playerId);
        turn.passivesActed().remove(playerId);

        if (isActive) {
            state.rowClosureRequests().clear();
            if (turn.activeTurnState() != null) turn.activeTurnState().reset();
            if (turn.phase() == TurnPhase.LOCK_PENDING) {
                turn.setPendingLockRowIndex(null);
                turn.setPendingLockDeclarerId(null);
                turn.setLockAcknowledged(new HashSet<>());
                turn.setPhase(TurnPhase.ACTIVE_MOVE);
            }
        } else if (turn.phase() == TurnPhase.LOCK_PENDING
                   && playerId.equals(turn.pendingLockDeclarerId())) {
            // Passive declarant cancelling their lock intent
            state.rowClosureRequests().clear();
            turn.setPendingLockRowIndex(null);
            turn.setPendingLockDeclarerId(null);
            turn.setLockAcknowledged(new HashSet<>());
            // Re-add this player to the passive queue so they can act again
            List<UUID> remainingPassives = new ArrayList<>(state.players());
            remainingPassives.remove(turn.activePlayerId());
            remainingPassives.removeIf(pid -> turn.passivesActed().contains(pid));
            turn.setPassivePlayerQueue(remainingPassives);
            turn.setPhase(TurnPhase.ACTIVE_MOVE);
        }
    }

    private void applyEndTurn(GameState state, EndTurnAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        switch (turn.phase()) {
            case ACTIVE_MOVE -> {
                if (isActive) {
                    ActiveTurnState ats = turn.activeTurnState();
                    if (!ats.whiteWhiteUsed() && !ats.colorDieUsed())
                        throw new IllegalMoveException("must make at least one move before ending turn");
                    turn.undoBuffer().remove(playerId);
                    if (turn.passivePlayerQueue().isEmpty()) {
                        evaluate(state);
                    } else {
                        turn.setPhase(TurnPhase.PASSIVE_MOVE);
                    }
                } else {
                    if (!turn.passivePlayerQueue().contains(playerId))
                        throw new IllegalMoveException("player not in passive queue");
                    turn.passivePlayerQueue().remove(playerId);
                    // Keep undoBuffer intact: if the active later declares a lock intent,
                    // this player will be re-invited and must still be able to undo their cross.
                }
            }
            case PASSIVE_MOVE -> {
                if (!turn.passivePlayerQueue().contains(playerId))
                    throw new IllegalMoveException("player not in passive queue");
                turn.passivePlayerQueue().remove(playerId);
                // Keep undoBuffer intact: same reason as ACTIVE_MOVE passive EndTurn above.
                if (turn.passivePlayerQueue().isEmpty()) evaluate(state);
            }
            case LOCK_PENDING -> {
                if (playerId.equals(turn.pendingLockDeclarerId()))
                    throw new IllegalMoveException("declarant cannot EndTurn during LOCK_PENDING");
                if (turn.lockAcknowledged().contains(playerId))
                    throw new IllegalMoveException("already acknowledged");
                // If this acknowledgement would trigger the auto-close, verify the declarant
                // can actually cross the lock BEFORE mutating lockAcknowledged.  A failed
                // applyCrossLock after the mutation would leave the game in a frozen state.
                boolean isLastAcknowledger = turn.passivePlayerQueue().stream()
                        .filter(pid -> !turn.lockAcknowledged().contains(pid))
                        .allMatch(pid -> pid.equals(playerId));
                if (isLastAcknowledger
                        && !canCrossLock(state, turn.pendingLockDeclarerId(), turn.pendingLockRowIndex())) {
                    throw new IllegalMoveException("declarant no longer satisfies lock pre-conditions");
                }
                turn.undoBuffer().remove(playerId);
                turn.lockAcknowledged().add(playerId);
                // When the last non-declarant acknowledges, close the row immediately.
                if (allNonActiveAcknowledged(state)) {
                    applyCrossLock(state, new CrossLockAction(turn.pendingLockDeclarerId(), turn.pendingLockRowIndex()));
                }
            }
            default -> throw new IllegalMoveException("EndTurnAction not valid in phase " + turn.phase());
        }
    }

    // -------------------------------------------------------------------------
    // EVALUATE: runs synchronously after the last move of a turn
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Cell reachability helpers
    // -------------------------------------------------------------------------

    private void addReachableCells(GameState state, UUID playerId, List<GameAction> actions, boolean isActive) {
        TurnState turn             = state.turnState();
        RollResult roll            = turn.currentRoll();
        ActiveTurnState ats        = isActive ? turn.activeTurnState() : null;
        SheetProgress progress     = state.boardState().sheetProgress().get(playerId);
        SheetLayout layout         = state.sheetLayouts().get(playerId);
        Map<Integer, UUID> closed  = state.boardState().closedRows();

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (closed.containsKey(rowIndex)) continue;
            Row row           = layout.rows().get(rowIndex);
            RowState rowState = rowStateOf(progress, rowIndex);
            int rightmost     = rightmostCrossedPosition(row, rowState);

            for (Cell cell : row.cells()) {
                if (cell.position() <= rightmost) continue;
                if (rowState.crossedCells().contains(cell.id())) continue;

                if (cell.isClosingEligible() && row.lock() != null) {
                    LockCell lock = row.lock();
                    long alreadyCrossedRequired = lock.requiredCells().stream()
                            .filter(id -> rowState.crossedCells().contains(id))
                            .count();
                    long normalCrossed = rowState.crossedCells().size() - alreadyCrossedRequired;
                    if (normalCrossed + 1 < lock.minCrosses()) continue;
                }

                DiceCombination combo = isActive
                        ? resolveActiveCombo(roll, cell, ats, state.boardState().activeDice())
                        : (matchesWhiteWhite(roll, cell) ? DiceCombination.WHITE_WHITE : null);

                if (combo != null) {
                    actions.add(new CrossCellAction(playerId, rowIndex, cell.id(), combo));
                }
            }
        }
    }

    private DiceCombination resolveActiveCombo(RollResult roll, Cell cell, ActiveTurnState ats, List<Die> activeDice) {
        if (ats.colorDieUsed()) return null;

        if (!ats.whiteWhiteUsed() && matchesWhiteWhite(roll, cell)) {
            return DiceCombination.WHITE_WHITE;
        }

        Color color = cell.color();
        Integer colorValue = roll.coloredDice().get(color);
        if (colorValue != null && matchesWhiteColor(roll, cell, colorValue)) {
            return DiceCombination.WHITE_COLOR;
        }

        return null;
    }

    private boolean matchesWhiteWhite(RollResult roll, Cell cell) {
        return String.valueOf(roll.white1() + roll.white2()).equals(cell.displayValue());
    }

    private boolean matchesWhiteColor(RollResult roll, Cell cell, int colorValue) {
        String display = cell.displayValue();
        return String.valueOf(roll.white1() + colorValue).equals(display)
                || String.valueOf(roll.white2() + colorValue).equals(display);
    }

    protected int rightmostCrossedPosition(Row row, RowState rowState) {
        if (rowState.crossedCells().isEmpty()) return -1;
        return row.cells().stream()
                .filter(c -> rowState.crossedCells().contains(c.id()))
                .mapToInt(Cell::position)
                .max()
                .orElse(-1);
    }

    // -------------------------------------------------------------------------
    // Post-lock turn continuation
    // -------------------------------------------------------------------------

    private void advanceTurnAfterLockClose(GameState state) {
        TurnState turn = state.turnState();
        turn.setPendingLockRowIndex(null);
        turn.setPendingLockDeclarerId(null);
        turn.setLockAcknowledged(new HashSet<>());

        UUID active = turn.activePlayerId();

        // Only continue the turn if another row can actually be closed this turn.
        // Regular cell-crosses do not justify a continuation — the standard turn flow
        // (active cross → EndTurn → PASSIVE_MOVE → evaluate) already handles those.
        List<GameAction> activeLocks = new ArrayList<>();
        addLockIntents(state, active, activeLocks);
        boolean activeCanClose = !activeLocks.isEmpty();

        List<UUID> remainingPassives = new ArrayList<>(state.players());
        remainingPassives.remove(active);
        remainingPassives.removeIf(pid -> turn.passivesActed().contains(pid));

        boolean passiveCanClose = false;
        for (UUID pid : remainingPassives) {
            List<GameAction> passiveLocks = new ArrayList<>();
            addPassiveLockIntents(state, pid, passiveLocks);
            if (!passiveLocks.isEmpty()) { passiveCanClose = true; break; }
        }

        if (activeCanClose || passiveCanClose) {
            turn.setPassivePlayerQueue(remainingPassives);
            turn.setPhase(activeCanClose ? TurnPhase.ACTIVE_MOVE : TurnPhase.PASSIVE_MOVE);
        } else {
            evaluate(state);
        }
    }

    // -------------------------------------------------------------------------
    // Lock helpers
    // -------------------------------------------------------------------------

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
        RollResult roll = turn.currentRoll();
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

        for (String reqId : lock.requiredCells()) {
            if (rowState.crossedCells().contains(reqId)) continue;
            Cell reqCell = row.cells().stream()
                    .filter(c -> c.id().equals(reqId)).findFirst().orElse(null);
            if (reqCell != null && reqCell.displayValue().equals(whiteWhiteValue)) {
                long alreadyCrossedRequired = lock.requiredCells().stream()
                        .filter(rowState.crossedCells()::contains).count();
                long normalCrossed = rowState.crossedCells().size() - alreadyCrossedRequired;
                if (normalCrossed + 1 >= lock.minCrosses()) return true;
            }
        }
        return false;
    }

    private void crossClosingCellForPassive(GameState state, UUID passive, int rowIndex) {
        TurnState turn = state.turnState();
        RollResult roll = turn.currentRoll();
        SheetLayout layout = state.sheetLayouts().get(passive);
        Row row = layout.rows().get(rowIndex);
        LockCell lock = row.lock();
        SheetProgress prog = state.boardState().sheetProgress().get(passive);
        RowState rowState = rowStateOf(prog, rowIndex);
        String whiteWhiteValue = String.valueOf(roll.white1() + roll.white2());

        for (String reqId : lock.requiredCells()) {
            if (rowState.crossedCells().contains(reqId)) continue;
            Cell reqCell = row.cells().stream()
                    .filter(c -> c.id().equals(reqId)).findFirst().orElse(null);
            if (reqCell != null && reqCell.displayValue().equals(whiteWhiteValue)) {
                Map<Integer, Set<String>> crossed = crossCellWithAutoTags(state, passive, rowIndex, reqId);
                turn.undoBuffer().put(passive, crossed);
                return;
            }
        }
        // Should never reach here: canPassiveDeclareIntent was true so the cell must exist.
        // Throwing here (before any other state is mutated) is safer than a silent no-op
        // that would later leave canCrossLock=false inside an already-mutated LOCK_PENDING.
        throw new IllegalMoveException("passive lock: closing cell not found — white+white=" + whiteWhiteValue + " row=" + rowIndex);
    }

    protected boolean canCrossLock(GameState state, UUID playerId, int rowIndex) {
        SheetLayout layout  = state.sheetLayouts().get(playerId);
        SheetProgress prog  = state.boardState().sheetProgress().get(playerId);
        Row row             = layout.rows().get(rowIndex);

        if (row.lock() == null) return false;
        LockCell lock       = row.lock();
        RowState rowState   = rowStateOf(prog, rowIndex);
        if (rowState.lockCrossed()) return false;
        if (state.boardState().closedRows().containsKey(rowIndex)) return false;

        // Count both permanent and pending crosses
        Set<String> allCrosses = new HashSet<>(rowState.crossedCells());
        TurnState turn = state.turnState();
        if (turn != null && turn.undoBuffer().containsKey(playerId)) {
            Set<String> pendingInRow = turn.undoBuffer().get(playerId).getOrDefault(rowIndex, new HashSet<>());
            allCrosses.addAll(pendingInRow);
        }

        return lock.requiredCells().stream().allMatch(allCrosses::contains);
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

    private boolean allNonActiveAcknowledged(GameState state) {
        TurnState turn = state.turnState();
        // Only wait for players who were still in the passive queue when lock intent
        // was declared.  Players who EndTurned during ACTIVE_MOVE already left the
        // queue and can never appear in lockAcknowledged — requiring them would
        // permanently freeze the game in LOCK_PENDING.
        return turn.lockAcknowledged().containsAll(turn.passivePlayerQueue());
    }

    // -------------------------------------------------------------------------
    // Cross a cell + follow AutoCross tags
    // -------------------------------------------------------------------------

    protected Map<Integer, Set<String>> crossCellWithAutoTags(GameState state, UUID playerId, int rowIndex, String cellId) {
        Map<Integer, Set<String>> crossed = new HashMap<>();
        crossCellRecursive(state, playerId, rowIndex, cellId, crossed, false);
        return crossed;
    }

    private void crossCellRecursive(GameState state, UUID playerId, int rowIndex, String cellId,
                                     Map<Integer, Set<String>> crossed, boolean isAuto) {
        SheetLayout layout  = state.sheetLayouts().get(playerId);
        SheetProgress prog  = state.boardState().sheetProgress().get(playerId);
        Row row             = layout.rows().get(rowIndex);
        RowState rowState   = rowStateOf(prog, rowIndex);

        Cell cell = row.cells().stream().filter(c -> c.id().equals(cellId)).findFirst().orElse(null);
        if (cell == null) return;
        if (rowState.crossedCells().contains(cellId)) return;

        if (!isAuto) {
            int rightmost = rightmostCrossedPosition(row, rowState);
            if (cell.position() <= rightmost)
                throw new IllegalMoveException("cell does not satisfy the progression check");
        }

        Set<String> newCrossed = new HashSet<>(rowState.crossedCells());
        newCrossed.add(cellId);
        prog.updateRowState(rowIndex, new RowState(newCrossed, rowState.lockCrossed()));
        crossed.computeIfAbsent(rowIndex, k -> new HashSet<>()).add(cellId);

        for (CellTag tag : cell.tags()) {
            if (tag instanceof CellTag.AutoCross autoCross) {
                findAndCrossAutoTarget(state, playerId, autoCross.target(), crossed);
            }
        }
    }

    private void findAndCrossAutoTarget(GameState state, UUID playerId, String targetCellId,
                                         Map<Integer, Set<String>> crossed) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            for (Cell cell : layout.rows().get(rowIndex).cells()) {
                if (cell.id().equals(targetCellId)) {
                    crossCellRecursive(state, playerId, rowIndex, targetCellId, crossed, true);
                    return;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Dice rolling
    // -------------------------------------------------------------------------

    private RollResult rollDice(List<Die> activeDice) {
        int[] whites = activeDice.stream()
                .filter(d -> d.color() == Color.WHITE)
                .mapToInt(d -> random.nextInt(d.faces()) + 1)
                .toArray();

        Map<Color, Integer> colored = new EnumMap<>(Color.class);
        for (Die die : activeDice) {
            if (die.color() != Color.WHITE) {
                colored.put(die.color(), random.nextInt(die.faces()) + 1);
            }
        }

        return new RollResult(whites[0], whites[1], colored);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

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

    private void requirePhase(TurnState turn, TurnPhase expected) {
        if (turn.phase() != expected)
            throw new IllegalMoveException("expected phase " + expected + " but was " + turn.phase());
    }

    private void requireActivePlayer(TurnState turn, UUID playerId) {
        if (!playerId.equals(turn.activePlayerId()))
            throw new IllegalMoveException("player " + playerId + " is not the active player");
    }
}