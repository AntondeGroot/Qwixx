package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.CrossLockAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.action.TakePunishmentAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OfflineTurnRules extends StandardTurnRules {

    @Override
    public List<GameAction> getValidActions(GameState state, UUID playerId) {
        if (state.gameOver()) return List.of();

        SheetLayout layout            = state.sheetLayouts().get(playerId);
        SheetProgress progress        = state.boardState().sheetProgress().get(playerId);
        Map<Integer, UUID> closedRows = state.boardState().closedRows();
        List<GameAction> actions      = new ArrayList<>();

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            Row row           = layout.rows().get(rowIndex);
            RowState rowState = rowStateOf(progress, rowIndex);

            if (!closedRows.containsKey(rowIndex)) {
                int rightmost = rightmostCrossedPosition(row, rowState);
                for (Cell cell : row.cells()) {
                    if (cell.position() > rightmost && !rowState.crossedCells().contains(cell.id())) {
                        actions.add(new CrossCellAction(playerId, rowIndex, cell.id(), DiceCombination.WHITE_WHITE));
                    }
                }
            }

            if (canCrossLock(state, playerId, rowIndex)) {
                actions.add(new CrossLockAction(playerId, rowIndex));
            }
        }

        actions.add(new TakePunishmentAction(playerId));
        return actions;
    }

    @Override
    public GameState apply(GameState state, GameAction action) {
        switch (action) {
            case CrossCellAction a      -> applyOfflineCrossCell(state, a);
            case CrossLockAction a      -> applyOfflineCrossLock(state, a);
            case TakePunishmentAction a -> applyOfflinePunishment(state, a);
            default -> throw new IllegalMoveException(
                    action.getClass().getSimpleName() + " is not valid in offline mode");
        }
        state.incrementVersion();
        return state;
    }

    // Cell crossing without phase check; closed rows still block crossing (dice are gone).
    private void applyOfflineCrossCell(GameState state, CrossCellAction action) {
        if (state.boardState().closedRows().containsKey(action.rowIndex()))
            throw new IllegalMoveException("cannot cross a cell in a globally closed row");
        crossCellWithAutoTags(state, action.playerId(), action.rowIndex(), action.cellId());
    }

    // Lock crossing without phase check.
    // A player may lock their own card even if another player has already globally closed that row.
    private void applyOfflineCrossLock(GameState state, CrossLockAction action) {
        if (!canCrossLock(state, action.playerId(), action.rowIndex()))
            throw new IllegalMoveException("lock pre-conditions not met");
        markLockCrossed(state, action.playerId(), action.rowIndex());
        closeRowGlobally(state, action.playerId(), action.rowIndex());
        if (isGameOver(state)) state.setGameOver(true);
    }

    private void applyOfflinePunishment(GameState state, TakePunishmentAction action) {
        state.boardState().sheetProgress().get(action.playerId()).addPunishment();
        if (isGameOver(state)) state.setGameOver(true);
    }

    // No closedRows check: a player qualifies to lock based only on their own progress.
    // allMatch is correct for both standard (1 required cell) and longo (2 required cells).
    @Override
    protected boolean canCrossLock(GameState state, UUID playerId, int rowIndex) {
        SheetLayout layout = state.sheetLayouts().get(playerId);
        SheetProgress prog = state.boardState().sheetProgress().get(playerId);
        Row row            = layout.rows().get(rowIndex);

        if (row.lock() == null) return false;
        LockCell lock     = row.lock();
        RowState rowState = rowStateOf(prog, rowIndex);
        if (rowState.lockCrossed()) return false;
        if (rowState.crossedCells().size() < lock.minCrosses()) return false;
        return lock.requiredCells().stream().allMatch(id -> rowState.crossedCells().contains(id));
    }
}