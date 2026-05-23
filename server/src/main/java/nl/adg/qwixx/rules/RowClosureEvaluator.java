package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.ClosureNotification;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static nl.adg.qwixx.rules.CellCrosser.getRowState;

/**
 * Static helpers for row-closure and lock-eligibility decisions.
 * All methods are package-private so both StandardTurnRules and its subclasses
 * can delegate to them without duplicating the logic.
 */
class RowClosureEvaluator {

    private RowClosureEvaluator() {}

    // ── Action generation ─────────────────────────────────────────────────────

    static List<GameAction> declareLockIntentActions(GameState state, UUID playerId, int minCrossesRequired) {
        List<GameAction> actions = new ArrayList<>();
        SheetLayout layout = getLayout(state, playerId);
        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (canOfferLockDeclaration(state, playerId, rowIndex, minCrossesRequired)) {
                actions.add(new DeclareLockIntentAction(playerId, rowIndex));
            }
        }
        return actions;
    }

    private static boolean canOfferLockDeclaration(GameState state, UUID playerId, int rowIndex, int minCrossesRequired) {
        if (state.isRowClosed(rowIndex)) return false;
        if (rowHasPendingClosure(state, rowIndex)) return false;
        return canDeclareViaNonFinalClosingCell(state, playerId, rowIndex, minCrossesRequired);
    }

    // ── Lock eligibility ──────────────────────────────────────────────────────

    /**
     * Whether the player may cross the lock cell for this row.
     * True when the row is lockable, the player has enough non-closing crosses,
     * and an eligible closing cell has been crossed (permanently or this turn).
     */
    static boolean canCrossLock(GameState state, UUID playerId, int rowIndex, int minCrossesRequired) {
        if (rowIsNotLockable(state, playerId, rowIndex)) return false;
        Set<String> allCrosses = allCrossesForPlayer(state, playerId, rowIndex);
        if (!hasEnoughNonClosingCrosses(state, playerId, rowIndex, allCrosses, minCrossesRequired)) return false;
        return hasEligibleClosingCellCrossed(state, playerId, rowIndex, allCrosses);
    }

    /**
     * True when a non-final closing cell was crossed this turn and the player qualifies to lock.
     * This is the Longo "15"/"3" explicit YES scenario.
     */
    static boolean canDeclareViaNonFinalClosingCell(GameState state, UUID playerId, int rowIndex, int minCrossesRequired) {
        if (rowIsNotLockable(state, playerId, rowIndex)) return false;
        if (!hasMultipleClosingCells(state, playerId, rowIndex)) return false;

        List<String> closing = getClosingCells(state, playerId, rowIndex);
        String secondToLast = closing.get(closing.size() - 2);
        if (!crossedThisTurn(state, playerId, rowIndex, secondToLast)) return false;

        return canCrossLock(state, playerId, rowIndex, minCrossesRequired);
    }

    static boolean rowIsNotLockable(GameState state, UUID playerId, int rowIndex) {
        Row row = getRow(state, playerId, rowIndex);
        if (row.lock() == null) return true;
        RowState rowState = getRowState(getProgress(state, playerId), rowIndex);
        if (rowState.lockCrossed()) return true;
        return state.isRowClosed(rowIndex);
    }

    static Set<String> allCrossesForPlayer(GameState state, UUID playerId, int rowIndex) {
        RowState rowState = getRowState(getProgress(state, playerId), rowIndex);
        Set<String> all = new HashSet<>(rowState.crossedCells());
        all.addAll(getPendingCrossesInRow(state, playerId, rowIndex));
        return all;
    }

    static boolean hasEnoughNonClosingCrosses(GameState state, UUID playerId, int rowIndex, Set<String> crosses, int minCrossesRequired) {
        List<String> closing = getClosingCells(state, playerId, rowIndex);
        return crosses.stream().filter(id -> !closing.contains(id)).count() >= minCrossesRequired;
    }

    static boolean playerHasCrossedAClosingCell(GameState state, UUID playerId, int rowIndex, Set<String> crosses) {
        return getClosingCells(state, playerId, rowIndex).stream().anyMatch(crosses::contains);
    }

    private static boolean hasEligibleClosingCellCrossed(GameState state, UUID playerId, int rowIndex, Set<String> allCrosses) {
        List<String> closing = getClosingCells(state, playerId, rowIndex);
        boolean lastCellCrossed = allCrosses.contains(closing.getLast());
        boolean secondToLastCrossedThisTurn = hasMultipleClosingCells(state, playerId, rowIndex)
                && crossedThisTurn(state, playerId, rowIndex, closing.get(closing.size() - 2));
        return lastCellCrossed || secondToLastCrossedThisTurn;
    }

    private static boolean hasMultipleClosingCells(GameState state, UUID playerId, int rowIndex) {
        return getClosingCells(state, playerId, rowIndex).size() > 1;
    }

    // ── Auto-detect closing intent ────────────────────────────────────────────

    // Auto-detect closing intent for a player who crossed the LAST closing cell this turn.
    // The second-to-last closing cell (Longo "15"/"3") requires an explicit YES from the client.
    static void autoDetectClosingIntent(GameState state, TurnState turn, UUID playerId, int minCrossesRequired) {
        if (!turn.undoBuffer().containsKey(playerId)) return;

        SheetLayout layout = getLayout(state, playerId);
        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (qualifiesForAutoClose(state, playerId, rowIndex, minCrossesRequired)) {
                recordClosureIntent(state, playerId, rowIndex);
            }
        }
    }

    private static boolean qualifiesForAutoClose(GameState state, UUID playerId, int rowIndex, int minCrossesRequired) {
        if (state.isRowClosed(rowIndex)) return false;
        if (rowHasPendingClosure(state, rowIndex)) return false;
        if (getRow(state, playerId, rowIndex).lock() == null) return false;
        if (!crossedThisTurn(state, playerId, rowIndex, getLastClosingCell(state, playerId, rowIndex))) return false;
        return canCrossLock(state, playerId, rowIndex, minCrossesRequired);
    }

    // ── Active-player re-queue check ──────────────────────────────────────────

    /**
     * Returns true when the active player could lock at least one pending row IF they
     * crossed its closing cell. Used to decide whether to delay EVALUATE and give the
     * active player a final look.
     */
    static boolean activePlayerCouldClaimAnyPendingRow(GameState state, UUID activeId, int minCrossesRequired) {
        return state.pendingClosures().entrySet().stream()
                .anyMatch(e -> !e.getValue().equals(activeId)
                        && couldActivePlayerLockRow(state, activeId, e.getKey(), minCrossesRequired));
    }

    /**
     * Returns true when the active player could lock {@code rowIndex} IF they crossed
     * the closing cell this turn — i.e. they have enough permanent+pending non-closing
     * crosses but have not yet crossed the last closing cell.
     */
    private static boolean couldActivePlayerLockRow(GameState state, UUID activeId, int rowIndex, int minCrossesRequired) {
        if (rowIsNotLockable(state, activeId, rowIndex)) return false;
        if (activeId.equals(state.pendingClosures().get(rowIndex))) return false;
        Set<String> allCrosses = allCrossesForPlayer(state, activeId, rowIndex);
        if (allCrosses.contains(getLastClosingCell(state, activeId, rowIndex))) return false;
        return hasEnoughNonClosingCrosses(state, activeId, rowIndex, allCrosses, minCrossesRequired);
    }

    // ── Row closure application ───────────────────────────────────────────────

    static void applyRowClosure(GameState state, int rowIndex, UUID declarant, int minCrossesRequired) {
        if (state.isRowClosed(rowIndex)) return;
        markLockCrossed(state, declarant, rowIndex);
        for (UUID pid : state.players()) {
            if (otherPlayerAlsoQualifiesForLockCross(state, pid, declarant, rowIndex, minCrossesRequired)) {
                markLockCrossed(state, pid, rowIndex);
            }
        }
        closeRowGlobally(state, declarant, rowIndex);
    }

    private static boolean otherPlayerAlsoQualifiesForLockCross(GameState state, UUID pid, UUID declarant, int rowIndex, int minCrossesRequired) {
        return !pid.equals(declarant)
                && !getRowState(getProgress(state, pid), rowIndex).lockCrossed()
                && canCrossLock(state, pid, rowIndex, minCrossesRequired);
    }

    static void markLockCrossed(GameState state, UUID playerId, int rowIndex) {
        SheetProgress prog = getProgress(state, playerId);
        RowState current   = getRowState(prog, rowIndex);
        prog.updateRowState(rowIndex, new RowState(current.crossedCells(), true));
    }

    static void closeRowGlobally(GameState state, UUID playerId, int rowIndex) {
        BoardState board = state.boardState();
        board.closedRows().put(rowIndex, playerId);
        board.activeDice().removeIf(d -> d.color() == getLockColor(state, playerId, rowIndex));
    }

    // ── Closure intent ────────────────────────────────────────────────────────

    static void recordClosureIntent(GameState state, UUID declarerId, int rowIndex) {
        state.pendingClosures().put(rowIndex, declarerId);
        state.closureNotifications().add(new ClosureNotification(declarerId, getLockColor(state, declarerId, rowIndex)));
    }

    static boolean rowHasPendingClosure(GameState state, int rowIndex) {
        return state.pendingClosures().containsKey(rowIndex);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    static void requireMayDeclareIntent(GameState state, TurnState turn, UUID declarerId, int rowIndex, boolean isActive, int minCrossesRequired) {
        if (isActive) {
            if (turn.phase() != TurnPhase.ACTIVE_MOVE)
                throw new IllegalMoveException("active can only declare closing intent in ACTIVE_MOVE");
        } else {
            if (turn.phase() != TurnPhase.ACTIVE_MOVE && turn.phase() != TurnPhase.PASSIVE_MOVE)
                throw new IllegalMoveException("passive can only declare closing intent in ACTIVE_MOVE or PASSIVE_MOVE");
            if (!turn.passivePlayerQueue().contains(declarerId))
                throw new IllegalMoveException("player not in passive queue");
        }
        if (!canCrossLock(state, declarerId, rowIndex, minCrossesRequired))
            throw new IllegalMoveException("lock pre-conditions not met");
        if (rowHasPendingClosure(state, rowIndex))
            throw new IllegalMoveException("row is already declared for closure this turn");
    }

    // ── Pending-cross queries ─────────────────────────────────────────────────

    static boolean crossedThisTurn(GameState state, UUID playerId, int rowIndex, String cellId) {
        return getPendingCrossesInRow(state, playerId, rowIndex).contains(cellId);
    }

    static Set<String> getPendingCrossesInRow(GameState state, UUID playerId, int rowIndex) {
        TurnState turn = state.turnState();
        if (turn == null) return Set.of();
        Map<Integer, Set<String>> buffer = turn.undoBuffer().get(playerId);
        if (buffer == null) return Set.of();
        return buffer.getOrDefault(rowIndex, Set.of());
    }

    // ── Private state accessors ───────────────────────────────────────────────

    private static SheetLayout getLayout(GameState state, UUID playerId) {
        return state.sheetLayouts().get(playerId);
    }

    private static SheetProgress getProgress(GameState state, UUID playerId) {
        return state.boardState().sheetProgress().get(playerId);
    }

    private static Row getRow(GameState state, UUID playerId, int rowIndex) {
        return getLayout(state, playerId).rows().get(rowIndex);
    }

    private static LockCell getLock(GameState state, UUID playerId, int rowIndex) {
        return getRow(state, playerId, rowIndex).lock();
    }

    private static Color getLockColor(GameState state, UUID playerId, int rowIndex) {
        return getLock(state, playerId, rowIndex).color();
    }

    private static List<String> getClosingCells(GameState state, UUID playerId, int rowIndex) {
        return getLock(state, playerId, rowIndex).closingCells();
    }

    private static String getLastClosingCell(GameState state, UUID playerId, int rowIndex) {
        return getLock(state, playerId, rowIndex).closingCells().getLast();
    }
}