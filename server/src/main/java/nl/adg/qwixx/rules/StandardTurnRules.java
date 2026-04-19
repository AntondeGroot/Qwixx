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
                if (!isActive) yield List.of();
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
            }

            case PASSIVE_MOVE -> {
                if (isActive) yield List.of();
                if (!turn.passivePlayerQueue().contains(playerId)) yield List.of();
                List<GameAction> actions = new ArrayList<>();
                boolean hasCrossed = turn.undoBuffer().containsKey(playerId);
                if (!hasCrossed) {
                    addReachableCells(state, playerId, actions, false);
                }
                if (hasCrossed) {
                    actions.add(new ResetTurnAction(playerId));
                }
                actions.add(new EndTurnAction(playerId));
                yield actions;
            }

            case LOCK_PENDING -> {
                List<GameAction> actions = new ArrayList<>();
                if (isActive) {
                    if (allNonActiveAcknowledged(state)) {
                        actions.add(new CrossLockAction(playerId, turn.pendingLockRowIndex()));
                    }
                    actions.add(new GiveUpAction(playerId));
                    actions.add(new ResetTurnAction(playerId));
                } else if (!turn.lockAcknowledged().contains(playerId)) {
                    if (turn.undoBuffer().containsKey(playerId)) {
                        actions.add(new UndoLastCrossAction(playerId));
                    }
                    actions.add(new EndTurnAction(playerId));
                    if (canCrossLock(state, playerId, turn.pendingLockRowIndex())) {
                        actions.add(new CrossLockAction(playerId, turn.pendingLockRowIndex()));
                    }
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
        snap.put(action.playerId(), deepCopy(state.boardState().sheetProgress().get(action.playerId())));
        turn.setMoveStartProgress(snap);
        turn.setUndoBuffer(new HashMap<>());
        turn.setLockAcknowledged(new HashSet<>());

        turn.setPhase(TurnPhase.ACTIVE_MOVE);
    }

    private void applyCrossCell(GameState state, CrossCellAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        if (isActive) {
            requirePhase(turn, TurnPhase.ACTIVE_MOVE);
        } else {
            requirePhase(turn, TurnPhase.PASSIVE_MOVE);
            if (!turn.passivePlayerQueue().contains(playerId))
                throw new IllegalMoveException("player not in passive queue");
        }

        Map<Integer, Set<String>> crossed = crossCellWithAutoTags(state, playerId, action.rowIndex(), action.cellId());
        turn.undoBuffer().put(playerId, crossed);

        if (isActive) {
            if (action.combination() == DiceCombination.WHITE_WHITE) {
                turn.activeTurnState().setWhiteWhiteUsed();
            } else {
                turn.activeTurnState().setColorDieUsed();
            }
        }
    }

    private void applyDeclareLockIntent(GameState state, DeclareLockIntentAction action) {
        TurnState turn = state.turnState();
        requirePhase(turn, TurnPhase.ACTIVE_MOVE);
        requireActivePlayer(turn, action.playerId());
        if (!canCrossLock(state, action.playerId(), action.rowIndex()))
            throw new IllegalMoveException("lock pre-conditions not met");

        turn.setPendingLockRowIndex(action.rowIndex());
        turn.setLockAcknowledged(new HashSet<>());
        turn.setPhase(TurnPhase.LOCK_PENDING);
    }

    private void applyCrossLock(GameState state, CrossLockAction action) {
        TurnState turn     = state.turnState();
        UUID playerId      = action.playerId();
        int rowIndex       = action.rowIndex();
        boolean isActive   = playerId.equals(turn.activePlayerId());

        requirePhase(turn, TurnPhase.LOCK_PENDING);
        if (!canCrossLock(state, playerId, rowIndex))
            throw new IllegalMoveException("lock pre-conditions not met");

        markLockCrossed(state, playerId, rowIndex);

        if (isActive) {
            if (!allNonActiveAcknowledged(state))
                throw new IllegalMoveException("not all players have acknowledged yet");
            closeRowGlobally(state, playerId, rowIndex);
            evaluate(state);
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
        turn.lockAcknowledged().add(playerId);
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

        evaluate(state);
    }

    private void applyResetTurn(GameState state, ResetTurnAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        SheetProgress snapshot = turn.moveStartProgress().get(playerId);
        if (snapshot != null) state.boardState().sheetProgress().put(playerId, deepCopy(snapshot));

        turn.undoBuffer().remove(playerId);

        if (isActive && turn.activeTurnState() != null) {
            turn.activeTurnState().reset();
        }
    }

    private void applyEndTurn(GameState state, EndTurnAction action) {
        TurnState turn = state.turnState();
        UUID playerId  = action.playerId();

        switch (turn.phase()) {
            case ACTIVE_MOVE -> {
                requireActivePlayer(turn, playerId);
                ActiveTurnState ats = turn.activeTurnState();
                if (!ats.whiteWhiteUsed() && !ats.colorDieUsed())
                    throw new IllegalMoveException("must make at least one move before ending turn");

                // snapshot passive players before their move phase starts
                Map<UUID, SheetProgress> snap = new HashMap<>(turn.moveStartProgress());
                for (UUID passive : turn.passivePlayerQueue()) {
                    snap.put(passive, deepCopy(state.boardState().sheetProgress().get(passive)));
                }
                turn.setMoveStartProgress(snap);
                turn.setUndoBuffer(new HashMap<>());

                if (turn.passivePlayerQueue().isEmpty()) {
                    evaluate(state);
                } else {
                    turn.setPhase(TurnPhase.PASSIVE_MOVE);
                }
            }
            case PASSIVE_MOVE -> {
                if (!turn.passivePlayerQueue().contains(playerId))
                    throw new IllegalMoveException("player not in passive queue");
                turn.passivePlayerQueue().remove(playerId);
                turn.undoBuffer().remove(playerId);
                if (turn.passivePlayerQueue().isEmpty()) evaluate(state);
            }
            case LOCK_PENDING -> {
                if (playerId.equals(turn.activePlayerId()))
                    throw new IllegalMoveException("active player cannot EndTurn during LOCK_PENDING");
                if (turn.lockAcknowledged().contains(playerId))
                    throw new IllegalMoveException("already acknowledged");
                turn.lockAcknowledged().add(playerId);
            }
            default -> throw new IllegalMoveException("EndTurnAction not valid in phase " + turn.phase());
        }
    }

    // -------------------------------------------------------------------------
    // EVALUATE: runs synchronously after the last move of a turn
    // -------------------------------------------------------------------------

    private void evaluate(GameState state) {
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

    private int rightmostCrossedPosition(Row row, RowState rowState) {
        if (rowState.crossedCells().isEmpty()) return -1;
        return row.cells().stream()
                .filter(c -> rowState.crossedCells().contains(c.id()))
                .mapToInt(Cell::position)
                .max()
                .orElse(-1);
    }

    // -------------------------------------------------------------------------
    // Lock helpers
    // -------------------------------------------------------------------------

    private void addLockIntents(GameState state, UUID playerId, List<GameAction> actions) {
        TurnState turn            = state.turnState();
        SheetLayout layout        = state.sheetLayouts().get(playerId);
        Map<Integer, UUID> closed = state.boardState().closedRows();

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (closed.containsKey(rowIndex)) continue;
            if (canCrossLock(state, playerId, rowIndex)) {
                actions.add(new DeclareLockIntentAction(playerId, rowIndex));
            }
        }
    }

    private boolean canCrossLock(GameState state, UUID playerId, int rowIndex) {
        SheetLayout layout  = state.sheetLayouts().get(playerId);
        SheetProgress prog  = state.boardState().sheetProgress().get(playerId);
        Row row             = layout.rows().get(rowIndex);

        if (row.lock() == null) return false;
        LockCell lock       = row.lock();
        RowState rowState   = rowStateOf(prog, rowIndex);
        if (rowState.lockCrossed()) return false;
        if (state.boardState().closedRows().containsKey(rowIndex)) return false;
        if (rowState.crossedCells().size() < lock.minCrosses()) return false;
        return lock.requiredCells().stream().anyMatch(id -> rowState.crossedCells().contains(id));
    }

    private void markLockCrossed(GameState state, UUID playerId, int rowIndex) {
        SheetProgress prog = state.boardState().sheetProgress().get(playerId);
        RowState current   = rowStateOf(prog, rowIndex);
        prog.updateRowState(rowIndex, new RowState(current.crossedCells(), true));
    }

    private void closeRowGlobally(GameState state, UUID playerId, int rowIndex) {
        BoardState board    = state.boardState();
        board.closedRows().put(rowIndex, playerId);

        SheetLayout layout  = state.sheetLayouts().get(playerId);
        Color lockColor     = layout.rows().get(rowIndex).lock().color();
        board.activeDice().removeIf(d -> d.color() == lockColor);
    }

    private boolean allNonActiveAcknowledged(GameState state) {
        TurnState turn = state.turnState();
        Set<UUID> nonActive = new HashSet<>(state.players());
        nonActive.remove(turn.activePlayerId());
        return turn.lockAcknowledged().containsAll(nonActive);
    }

    // -------------------------------------------------------------------------
    // Cross a cell + follow AutoCross tags
    // -------------------------------------------------------------------------

    private Map<Integer, Set<String>> crossCellWithAutoTags(GameState state, UUID playerId, int rowIndex, String cellId) {
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

    private RowState rowStateOf(SheetProgress progress, int rowIndex) {
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