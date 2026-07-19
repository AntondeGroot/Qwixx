package nl.adg.qwixx.rules;

import static nl.adg.qwixx.rules.CellCrosser.getRowState;
import static nl.adg.qwixx.rules.CellCrosser.isReachableCell;
import static nl.adg.qwixx.rules.RowClosureEvaluator.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.RollResult;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;

public class StandardTurnRules implements TurnRules {

    static final int MAX_PUNISHMENTS = 4;

    private final DiceRoller diceRoller;
    private final CellCrosser cellCrosser;

    public StandardTurnRules() {
        this.diceRoller  = new DiceRoller(new Random());
        this.cellCrosser = new CellCrosser();
    }

    public StandardTurnRules(Random random) {
        this.diceRoller  = new DiceRoller(random);
        this.cellCrosser = new CellCrosser();
    }

    @Override
    public List<GameAction> getValidActions(GameState state, UUID playerId) {
        if (state.gameOver()) return List.of();

        TurnState turn = Objects.requireNonNull(state.turnState());
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

        // Lucky Cross is available at any point during the turn (before or after regular moves).
        if (!turn.luckyCrossUsed().contains(playerId)) {
            actions.addAll(luckyCrossActions(state, playerId));
        }

        if (turn.activeTurnState().luckyNumberUsed()) {
            // After a Lucky Number cross the active player may only confirm or undo — no more crosses.
            actions.add(new GiveUpAction(playerId));
            actions.add(new ResetTurnAction(playerId));
            actions.add(new EndTurnAction(playerId));
            return actions;
        }

        actions.addAll(crossCellActions(state, playerId, true));
        actions.addAll(declareLockIntentActions(state, playerId, getMinCrossesRequired()));
        actions.add(new GiveUpAction(playerId));
        actions.add(new ResetTurnAction(playerId));

        // Lucky Number is only available before any other action this turn.
        if (!turn.activeTurnState().hasActed()) {
            actions.addAll(luckyNumberCellActions(state, playerId));
        }

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
        // Lucky Cross is available at any point (before or after the white+white move).
        if (!turn.luckyCrossUsed().contains(playerId)) {
            actions.addAll(luckyCrossActions(state, playerId));
        }
        actions.addAll(declareLockIntentActions(state, playerId, getMinCrossesRequired()));
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
                .anyMatch(p -> p.punishments() >= MAX_PUNISHMENTS);
    }

    // ── Action handlers ───────────────────────────────────────────────────────

    private void applyRoll(GameState state, RollAction action) {
        TurnState turn = Objects.requireNonNull(state.turnState());
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

    private void applyCrossCell(GameState state, CrossCellAction action) {
        TurnState turn   = Objects.requireNonNull(state.turnState());
        UUID playerId    = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        if (state.isRowClosed(action.rowIndex()))
            throw new IllegalMoveException("row is closed");

        if (isActive) {
            if (turn.phase() != TurnPhase.ACTIVE_MOVE)
                throw new IllegalMoveException("expected phase ACTIVE_MOVE but was " + turn.phase());
        } else if (!isLuckyCrossCell(findCell(state, playerId, action.rowIndex(), action.cellId()))) {
            requirePassiveMayCrossCell(turn, playerId);
        }

        Cell cell = findCell(state, playerId, action.rowIndex(), action.cellId());
        CellTag.XChange xChangeTag = CellCrosser.findXChangeTag(cell);

        if (xChangeTag != null) {
            applyXChangeCross(state, turn, playerId, action, xChangeTag);
        } else if (isActive && isLuckyNumberCell(cell)) {
            applyLuckyNumberCross(state, turn, playerId, action);
        } else if (isLuckyCrossCell(cell)) {
            applyLuckyCross(state, turn, playerId, action, isActive);
        } else {
            applyRegularCross(state, turn, playerId, action, isActive);
        }
    }

    private static boolean isLuckyNumberCell(Cell cell) {
        if (cell == null) return false;
        return cell.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyNumber);
    }

    private void applyLuckyNumberCross(GameState state, TurnState turn, UUID playerId, CrossCellAction action) {
        if (turn.activeTurnState().hasActed()) {
            throw new IllegalMoveException("Lucky Number can only be used before making any other move this turn");
        }
        Map<Integer, Set<String>> crossed = crossCellWithAutoTags(state, playerId, action.rowIndex(), action.cellId());
        savePendingCrosses(turn, playerId, crossed);
        turn.activeTurnState().setLuckyNumberUsed();
    }

    private void applyLuckyCross(GameState state, TurnState turn, UUID playerId,
                                 CrossCellAction action, boolean isActive) {
        if (turn.luckyCrossUsed().contains(playerId)) {
            throw new IllegalMoveException("Lucky Cross already used this turn");
        }
        // Validate that the 1+5 combo permits crossing this row.
        Row row = getLayout(state, playerId).rows().get(action.rowIndex());
        Color rowColor = rowColor(row);
        Set<Color> eligible = luckyCrossEligibleColors(turn.currentRoll());
        if (eligible != null && (rowColor == null || !eligible.contains(rowColor))) {
            throw new IllegalMoveException("1+5 combo does not allow a Lucky Cross in this row");
        }
        crossCellWithAutoTags(state, playerId, action.rowIndex(), action.cellId());
        turn.luckyCrossUsed().add(playerId);
        if (isActive) turn.activeTurnState().setLuckyCrossUsed();
    }

    private void applyXChangeCross(GameState state, TurnState turn, UUID playerId,
                                   CrossCellAction action, CellTag.XChange xChangeTag) {
        Map<Integer, Set<String>> crossed = crossCellWithAutoTags(state, playerId, action.rowIndex(), action.cellId());
        savePendingCrosses(turn, playerId, crossed);
        int actualWW    = turn.currentRoll().white1() + turn.currentRoll().white2();
        int effectiveWW = (actualWW == xChangeTag.a()) ? xChangeTag.b() : xChangeTag.a();
        turn.xChangeEffectiveWW().put(playerId, effectiveWW);
    }

    private void applyRegularCross(GameState state, TurnState turn, UUID playerId,
                                   CrossCellAction action, boolean isActive) {
        Map<Integer, Set<String>> crossed = crossCellWithAutoTags(state, playerId, action.rowIndex(), action.cellId());
        if (turn.xChangeEffectiveWW().containsKey(playerId)) {
            mergeWithPreviousXChangeCross(turn.undoBuffer().get(playerId), crossed);
            turn.xChangeEffectiveWW().remove(playerId);
        }
        savePendingCrosses(turn, playerId, crossed);
        if (isActive) {
            recordActiveDiceUsage(turn.activeTurnState(), action.combination());
        } else {
            turn.passivesActed().add(playerId);
        }
    }

    private static void mergeWithPreviousXChangeCross(Map<Integer, Set<String>> xChangeCross,
                                                      Map<Integer, Set<String>> regularCross) {
        if (xChangeCross == null) return;
        for (var entry : xChangeCross.entrySet()) {
            regularCross.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
        }
    }

    private Cell findCell(GameState state, UUID playerId, int rowIndex, String cellId) {
        return getLayout(state, playerId).rows().get(rowIndex).cells().stream()
                .filter(c -> c.id().equals(cellId)).findFirst().orElse(null);
    }

    private void requirePassiveMayCrossCell(TurnState turn, UUID playerId) {
        if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.PASSIVE_MOVE)
            throw new IllegalMoveException("expected phase ACTIVE_MOVE or PASSIVE_MOVE but was " + turn.phase());
        if (!turn.passivePlayerQueue().contains(playerId))
            throw new IllegalMoveException("player not in passive queue");
        // X-change-only pending cross doesn't consume the WW slot; the next regular cross is still allowed.
        if (TurnHelper.hasPendingCross(turn, playerId) && !turn.xChangeEffectiveWW().containsKey(playerId))
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
        TurnState turn   = Objects.requireNonNull(state.turnState());
        UUID declarerId  = action.playerId();
        int rowIndex     = action.rowIndex();
        boolean isActive = declarerId.equals(turn.activePlayerId());

        requireMayDeclareIntent(state, turn, declarerId, rowIndex, isActive, getMinCrossesRequired());

        boolean isFirstDeclaration = state.pendingClosures().isEmpty();
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

    private void applyUndoLastCross(GameState state, UndoLastCrossAction action) {
        TurnState turn = Objects.requireNonNull(state.turnState());
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
                state.closureNotifications().removeIf(r -> r.playerId().equals(playerId));
            }
        }

        clearPendingCrosses(turn, playerId);
        turn.passivesActed().remove(playerId);
        turn.xChangeEffectiveWW().remove(playerId);
    }

    private void applyGiveUp(GameState state, GiveUpAction action) {
        TurnState turn = Objects.requireNonNull(state.turnState());
        requireActivePlayer(turn, action.playerId());
        if (turn.phase() != TurnPhase.ACTIVE_MOVE)
            throw new IllegalMoveException("GiveUpAction not valid in phase " + turn.phase());

        UUID playerId = action.playerId();
        restoreToSnapshot(state, turn, playerId);
        cancelPlayerRowClosure(state, playerId);
        getProgress(state, playerId).addPunishment();

        evaluateOrTransitionToPassiveMove(state, turn);
    }

    private void applyResetTurn(GameState state, ResetTurnAction action) {
        TurnState turn   = Objects.requireNonNull(state.turnState());
        UUID playerId    = action.playerId();
        boolean isActive = playerId.equals(turn.activePlayerId());

        revertPlayerProgress(state, turn, playerId);
        turn.passivesActed().remove(playerId);
        turn.luckyCrossUsed().remove(playerId);
        if (isActive && turn.activeTurnState() != null) turn.activeTurnState().reset();

        if (activePlayerWasPassive(isActive, turn)) {
            turn.passivePlayerQueue().remove(playerId); // in case re-added for final look
            turn.setPhase(TurnPhase.ACTIVE_MOVE);
        } else if (passivePlayerHadEndedTurn(isActive, turn, playerId)) {
            turn.passivePlayerQueue().add(playerId);
        }
        // else: passive still in queue — no queue change needed
    }

    private void revertPlayerProgress(GameState state, TurnState turn, UUID playerId) {
        restoreToSnapshot(state, turn, playerId);
        cancelPlayerRowClosure(state, playerId);
        clearPendingCrosses(turn, playerId);
        turn.xChangeEffectiveWW().remove(playerId);
    }

    /** Removes all pending closures and notifications declared by this player this turn. */
    private void cancelPlayerRowClosure(GameState state, UUID playerId) {
        state.pendingClosures().entrySet().removeIf(e -> e.getValue().equals(playerId));
        state.closureNotifications().removeIf(r -> r.playerId().equals(playerId));
    }

    private void applyEndTurn(GameState state, EndTurnAction action) {
        TurnState turn = Objects.requireNonNull(state.turnState());
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
            autoDetectClosingIntent(state, turn, playerId, getMinCrossesRequired());
            evaluateOrTransitionToPassiveMove(state, turn);
        } else {
            if (!turn.passivePlayerQueue().contains(playerId))
                throw new IllegalMoveException("player not in passive queue");
            autoDetectClosingIntent(state, turn, playerId, getMinCrossesRequired());
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
        autoDetectClosingIntent(state, turn, playerId, getMinCrossesRequired());
        turn.passivePlayerQueue().remove(playerId);
        if (turn.passivePlayerQueue().isEmpty()) {
            handleEmptyPassiveQueue(state, turn, playerId);
        }
        clearPendingCrosses(turn, playerId);
    }

    private void handleEmptyPassiveQueue(GameState state, TurnState turn, UUID playerId) {
        UUID activeId = turn.activePlayerId();
        if (!playerId.equals(activeId) && activePlayerCouldClaimAnyPendingRow(state, activeId, getMinCrossesRequired())) {
            // Give the active player a last look so they can revert or pass on newly declared rows.
            turn.passivePlayerQueue().add(activeId);
        } else {
            evaluate(state);
        }
    }

    private void evaluate(GameState state) {
        for (var entry : new HashMap<>(state.pendingClosures()).entrySet()) {
            applyRowClosure(state, entry.getKey(), entry.getValue(), getMinCrossesRequired());
        }

        state.pendingClosures().clear();
        state.closureNotifications().clear();
        Objects.requireNonNull(state.turnState()).undoBuffer().clear();

        if (isGameOver(state)) {
            state.setGameOver(true);
            return;
        }

        advanceToNextPlayer(state);
    }

    private void advanceToNextPlayer(GameState state) {
        List<UUID> players = state.players();
        UUID active = Objects.requireNonNull(state.turnState()).activePlayerId();
        UUID next = players.get((players.indexOf(active) + 1) % players.size());

        TurnState nextTurn = new TurnState();
        nextTurn.setActivePlayerId(next);
        nextTurn.setPhase(TurnPhase.ROLL);
        state.setTurnState(nextTurn);
    }

    // ── Snapshot / restore ────────────────────────────────────────────────────

    private Map<UUID, SheetProgress> snapshotProgress(GameState state) {
        Map<UUID, SheetProgress> snap = new HashMap<>();
        for (UUID pid : state.players()) {
            snap.put(pid, deepCopy(getProgress(state, pid)));
        }
        return snap;
    }

    private void restoreToSnapshot(GameState state, TurnState turn, UUID playerId) {
        SheetProgress snapshot = turn.moveStartProgress().get(playerId);
        if (snapshot != null) state.boardState().sheetProgress().put(playerId, deepCopy(snapshot));
    }

    private SheetProgress deepCopy(SheetProgress p) {
        Map<Integer, RowState> copy = new HashMap<>();
        for (var entry : p.rowStates().entrySet()) {
            copy.put(entry.getKey(), new RowState(new HashSet<>(entry.getValue().crossedCells()), entry.getValue().lockCrossed()));
        }
        return new SheetProgress(copy, p.punishments());
    }

    // ── Cell crossing ─────────────────────────────────────────────────────────

    private List<GameAction> crossCellActions(GameState state, UUID playerId, boolean isActive) {
        return cellCrosser.crossCellActions(state, playerId, isActive, getMinCrossesRequired());
    }

    protected Map<Integer, Set<String>> crossCellWithAutoTags(GameState state, UUID playerId, int rowIndex, String cellId) {
        return cellCrosser.cross(state, playerId, rowIndex, cellId);
    }

    // ── Lucky Number ──────────────────────────────────────────────────────────────

    /**
     * Returns a CrossCellAction for the leftmost uncrossed Lucky Number cell when the
     * prerequisite is met: white1 + white2 + any active coloured die = luckyTarget.
     */
    protected List<GameAction> luckyNumberCellActions(GameState state, UUID playerId) {
        TurnState turn = Objects.requireNonNull(state.turnState());
        var roll = turn.currentRoll();
        if (roll == null) return List.of();

        SheetLayout layout     = getLayout(state, playerId);
        SheetProgress progress = getProgress(state, playerId);

        for (int i = 0; i < layout.rows().size(); i++) {
            Row row = layout.rows().get(i);
            if (!row.isLuckyRow()) continue;
            boolean prereqMet = roll.coloredDice().values().stream()
                    .anyMatch(colored -> roll.white1() + roll.white2() + colored == row.luckyTarget());
            if (!prereqMet) return List.of();
            RowState rowState = getRowState(progress, i);
            int rightmost     = rightmostCrossedPosition(row, rowState);
            final int rowIndex = i;
            return row.cells().stream()
                    .filter(c -> isReachableCell(c, rightmost, rowState.crossedCells()))
                    .min(Comparator.comparingInt(Cell::position))
                    .map(cell -> (GameAction) new CrossCellAction(
                            playerId, rowIndex, cell.id(), DiceCombination.WHITE_WHITE))
                    .map(List::of)
                    .orElse(List.of());
        }
        return List.of();
    }

    // ── Lucky Cross ───────────────────────────────────────────────────────────────

    /**
     * Returns the eligible row colors for the Lucky Cross bonus given the current roll.
     * Returns null when white+white shows 1+5 (free choice — any colored row).
     * Returns an empty set when no 1+5 combo is present at all.
     */
    protected Set<Color> luckyCrossEligibleColors(RollResult roll) {
        if (roll == null) return Set.of();
        int w1 = roll.white1(), w2 = roll.white2();
        Set<Color> eligible = EnumSet.noneOf(Color.class);
        boolean freeChoice = false;

        // White + White → free choice of any row
        if ((w1 == 1 && w2 == 5) || (w1 == 5 && w2 == 1)) freeChoice = true;

        // White + Colored → that colored row
        for (var e : roll.coloredDice().entrySet()) {
            int v = e.getValue();
            if ((w1 == 1 && v == 5) || (w1 == 5 && v == 1) ||
                (w2 == 1 && v == 5) || (w2 == 5 && v == 1)) {
                eligible.add(e.getKey());
            }
        }

        // Colored + Colored → either of those two rows
        List<Map.Entry<Color, Integer>> entries = new ArrayList<>(roll.coloredDice().entrySet());
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                int vi = entries.get(i).getValue(), vj = entries.get(j).getValue();
                if ((vi == 1 && vj == 5) || (vi == 5 && vj == 1)) {
                    eligible.add(entries.get(i).getKey());
                    eligible.add(entries.get(j).getKey());
                }
            }
        }

        return freeChoice ? null : eligible; // null = all colors eligible
    }

    /**
     * Returns a CrossCellAction for the next available Lucky Cross cell in each eligible row.
     * Available to every player (active and passive) once per turn when the 1+5 combo is present.
     */
    protected List<GameAction> luckyCrossActions(GameState state, UUID playerId) {
        TurnState turn = Objects.requireNonNull(state.turnState());
        RollResult roll = turn.currentRoll();
        if (roll == null) return List.of();

        Set<Color> eligible = luckyCrossEligibleColors(roll);
        if (eligible != null && eligible.isEmpty()) return List.of();

        SheetLayout layout = getLayout(state, playerId);
        SheetProgress progress = getProgress(state, playerId);
        List<GameAction> actions = new ArrayList<>();

        for (int i = 0; i < layout.rows().size(); i++) {
            Row row = layout.rows().get(i);
            if (!hasLuckyCrossCells(row)) continue;
            if (state.isRowClosed(i)) continue;

            if (eligible != null) {
                Color rc = rowColor(row);
                if (rc == null || !eligible.contains(rc)) continue;
            }

            RowState rowState = CellCrosser.getRowState(progress, i);
            int rightmost = rightmostCrossedPosition(row, rowState);
            final int rowIndex = i;

            Optional<Cell> next = row.cells().stream()
                    .filter(c -> isLuckyCrossCell(c) && isReachableCell(c, rightmost, rowState.crossedCells()))
                    .min(Comparator.comparingInt(Cell::position));
            next.map(cell -> (GameAction) new CrossCellAction(
                    playerId, rowIndex, cell.id(), DiceCombination.WHITE_WHITE))
                .ifPresent(actions::add);
        }
        return actions;
    }

    protected static boolean isLuckyCrossCell(Cell cell) {
        return cell != null && cell.tags() != null
                && cell.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyCross);
    }

    private static boolean hasLuckyCrossCells(Row row) {
        return row.cells().stream().anyMatch(StandardTurnRules::isLuckyCrossCell);
    }

    private static Color rowColor(Row row) {
        return row.cells().stream()
                .filter(c -> !isLuckyCrossCell(c))
                .map(Cell::color)
                .findFirst()
                .orElse(null);
    }

    // ── Lock eligibility delegates (protected for subclasses and tests) ────────

    protected boolean canCrossLock(GameState state, UUID playerId, int rowIndex) {
        return RowClosureEvaluator.canCrossLock(state, playerId, rowIndex, getMinCrossesRequired());
    }

    // ── Undo buffer ───────────────────────────────────────────────────────────

    private void savePendingCrosses(TurnState turn, UUID playerId, Map<Integer, Set<String>> crosses) {
        turn.undoBuffer().put(playerId, crosses);
    }

    private void clearPendingCrosses(TurnState turn, UUID playerId) {
        turn.undoBuffer().remove(playerId);
    }

    private Map<Integer, Set<String>> getPendingCrosses(TurnState turn, UUID playerId) {
        return turn.undoBuffer().get(playerId);
    }

    // ── State accessors for subclasses ────────────────────────────────────────

    protected SheetLayout getLayout(GameState state, UUID playerId) {
        return state.sheetLayouts().get(playerId);
    }

    protected SheetProgress getProgress(GameState state, UUID playerId) {
        return state.boardState().sheetProgress().get(playerId);
    }

    protected int getMinCrossesRequired() {
        return 5;
    }

    protected int rightmostCrossedPosition(Row row, RowState rowState) {
        return CellCrosser.rightmostCrossedPosition(row, rowState);
    }

    // ── Predicates ────────────────────────────────────────────────────────────

    private boolean activePlayerWasPassive(boolean isActive, TurnState turn) {
        return isActive && turn.phase() == TurnPhase.PASSIVE_MOVE;
    }

    private boolean passivePlayerHadEndedTurn(boolean isActive, TurnState turn, UUID playerId) {
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

    // ── Guards ────────────────────────────────────────────────────────────────

    private void requirePhase(TurnState turn, TurnPhase expected) {
        if (turn.phase() != expected)
            throw new IllegalMoveException("expected phase " + expected + " but was " + turn.phase());
    }

    private void requireActivePlayer(TurnState turn, UUID playerId) {
        if (!playerId.equals(turn.activePlayerId()))
            throw new IllegalMoveException("player " + playerId + " is not the active player");
    }
}
