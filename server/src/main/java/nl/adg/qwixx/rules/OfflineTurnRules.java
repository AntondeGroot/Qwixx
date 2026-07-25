package nl.adg.qwixx.rules;

import static nl.adg.qwixx.rules.CellCrosser.bonusPrerequisiteMet;
import static nl.adg.qwixx.rules.CellCrosser.getRowState;
import static nl.adg.qwixx.rules.CellCrosser.isReachableCell;
import static nl.adg.qwixx.rules.RowClosureEvaluator.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.action.TakePunishmentAction;
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
