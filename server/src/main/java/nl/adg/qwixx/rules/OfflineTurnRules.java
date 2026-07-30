package nl.adg.qwixx.rules;

import static nl.adg.qwixx.rules.CellCrosser.bonusPrerequisiteMet;
import static nl.adg.qwixx.rules.CellCrosser.getRowState;
import static nl.adg.qwixx.rules.CellCrosser.isReachableCell;
import static nl.adg.qwixx.rules.RowClosureEvaluator.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.action.TakePunishmentAction;
import nl.adg.qwixx.action.UncrossCellAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;

public class OfflineTurnRules extends StandardTurnRules {

    public OfflineTurnRules() {
        super();
    }

    public OfflineTurnRules(int minCrossesToLock) {
        super(new Random(), minCrossesToLock);
    }

    @Override
    public List<GameAction> getValidActions(GameState state, UUID playerId) {
        if (state.gameOver()) return List.of();

        SheetLayout layout            = state.sheetLayout(playerId);
        SheetProgress progress        = state.sheetProgress(playerId);
        List<GameAction> actions = new ArrayList<>();

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            Row row           = layout.rows().get(rowIndex);
            RowState rowState = getRowState(progress, rowIndex);

            if (!state.isRowClosed(rowIndex)) {
                int rightmost = rightmostCrossedPosition(row, rowState);
                for (Cell cell : row.cells()) {
                    if (isReachableCell(cell, rightmost, rowState.crossedCells())
                            && (!row.isBonusRow() || bonusPrerequisiteMet(layout, progress, row, cell))) {
                        actions.add(new CrossCellAction(playerId, rowIndex, cell.id(), DiceCombination.WHITE_WHITE));
                    }
                }
            }

            if (canCrossLock(state, playerId, rowIndex)) {
                actions.add(new DeclareLockIntentAction(playerId, rowIndex));
            }
        }

        actions.add(new TakePunishmentAction(playerId));
        return actions;
    }

    @Override
    public GameState apply(GameState state, GameAction action) {
        switch (action) {
            case CrossCellAction a         -> applyOfflineCrossCell(state, a);
            case DeclareLockIntentAction a -> applyOfflineDeclareClose(state, a);
            case TakePunishmentAction a    -> applyOfflinePunishment(state, a);
            case UncrossCellAction a       -> applyOfflineUncrossCell(state, a);
            default -> throw new IllegalMoveException(
                    action.getClass().getSimpleName() + " is not valid in offline mode");
        }
        state.incrementVersion();
        return state;
    }

    // Cell crossing without phase check; closed rows still block crossing (dice are gone).
    private void applyOfflineCrossCell(GameState state, CrossCellAction action) {
        if (state.isRowClosed(action.rowIndex()))
            throw new IllegalMoveException("cannot cross a cell in a globally closed row");
        SheetLayout layout = state.sheetLayout(action.playerId());
        Row row = layout.rows().get(action.rowIndex());
        if (row.isBonusRow()) {
            SheetProgress progress = state.sheetProgress(action.playerId());
            Cell cell = row.cells().stream()
                    .filter(c -> c.id().equals(action.cellId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalMoveException("unknown cell: " + action.cellId()));
            if (!bonusPrerequisiteMet(layout, progress, row, cell))
                throw new IllegalMoveException("bonus prerequisite not met");
        }
        crossCellWithAutoTags(state, action.playerId(), action.rowIndex(), action.cellId());
    }

    // Lock closing without phase check — immediately marks lock and closes row.
    // A player may lock their own card even if another player has already globally closed that row.
    private void applyOfflineDeclareClose(GameState state, DeclareLockIntentAction action) {
        if (!canCrossLock(state, action.playerId(), action.rowIndex()))
            throw new IllegalMoveException("lock pre-conditions not met");
        markLockCrossed(state, action.playerId(), action.rowIndex());
        closeRowGlobally(state, action.playerId(), action.rowIndex());
        if (isGameOver(state)) state.setGameOver(true);
    }

    private void applyOfflinePunishment(GameState state, TakePunishmentAction action) {
        state.sheetProgress(action.playerId()).addPunishment();
        if (isGameOver(state)) state.setGameOver(true);
    }

    // Undo an accidental cross: remove the single cell. If it had closed/locked the row, reopen it
    // (clear the lock cross and the global closure). Auto-crosses the original cross triggered are
    // left in place — matching the offline undo scope. gameOver is recomputed so reopening a row can
    // lift a game-over that only this closure had reached.
    private void applyOfflineUncrossCell(GameState state, UncrossCellAction action) {
        UUID playerId     = action.playerId();
        int rowIndex      = action.rowIndex();
        SheetProgress prog = state.sheetProgress(playerId);
        RowState rowState  = getRowState(prog, rowIndex);
        if (!rowState.crossedCells().contains(action.cellId()))
            throw new IllegalMoveException("cell is not crossed: " + action.cellId());

        Set<String> updated = new HashSet<>(rowState.crossedCells());
        updated.remove(action.cellId());

        Row row = state.sheetLayout(playerId).rows().get(rowIndex);
        boolean reopen = rowState.lockCrossed() && row.hasLock()
                && row.lock().closingCells().contains(action.cellId());
        prog.updateRowState(rowIndex, new RowState(updated, reopen ? false : rowState.lockCrossed()));

        if (reopen && playerId.equals(state.boardState().closedRows().get(rowIndex))) {
            state.boardState().closedRows().remove(rowIndex);
        }
        state.setGameOver(isGameOver(state));
    }

    // No closedRows check: a player qualifies to lock based only on their own progress.
    // Any ONE closing cell is sufficient (same semantics as online mode).
    @Override
    protected boolean canCrossLock(GameState state, UUID playerId, int rowIndex) {
        SheetLayout layout = state.sheetLayout(playerId);
        SheetProgress prog = state.sheetProgress(playerId);
        Row row            = layout.rows().get(rowIndex);

        if (!row.hasLock()) return false;
        RowState rowState = getRowState(prog, rowIndex);
        if (rowState.lockCrossed()) return false;
        if (!hasEnoughNonClosingCrosses(state, playerId, rowIndex, rowState.crossedCells(), getMinCrossesRequired())) return false;
        return playerHasCrossedAClosingCell(state, playerId, rowIndex, rowState.crossedCells());
    }

}
