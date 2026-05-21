package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class CellCrosser {

    private final DiceRoller diceRoller;

    CellCrosser(DiceRoller diceRoller) {
        this.diceRoller = diceRoller;
    }

    Map<Integer, Set<String>> cross(GameState state, UUID playerId, int rowIndex, String cellId) {
        Map<Integer, Set<String>> crossed = new HashMap<>();
        crossRecursive(state, playerId, rowIndex, cellId, crossed, false);
        return crossed;
    }

    List<GameAction> crossCellActions(GameState state, UUID playerId, boolean isActive, int minCrosses) {
        List<GameAction> actions   = new ArrayList<>();
        TurnState turn             = state.turnState();
        var roll                   = turn.currentRoll();
        ActiveTurnState ats        = isActive ? turn.activeTurnState() : null;
        SheetLayout layout         = state.sheetLayouts().get(playerId);
        var progress               = state.boardState().sheetProgress().get(playerId);
        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            if (state.isRowClosed(rowIndex)) continue;
            Row row           = layout.rows().get(rowIndex);
            RowState rowState = getRowState(progress, rowIndex);
            int rightmost     = rightmostCrossedPosition(row, rowState);

            for (Cell cell : row.cells()) {
                if (!isReachableCell(cell, rightmost, rowState.crossedCells())) continue;

                if (cell.isClosingEligible() && row.lock() != null) {
                    long alreadyCrossedRequired = row.lock().closingCells().stream()
                            .filter(id -> rowState.crossedCells().contains(id))
                            .count();
                    long normalCrossed = rowState.crossedCells().size() - alreadyCrossedRequired;
                    if (normalCrossed < minCrosses) continue;
                }

                DiceCombination combo = isActive
                        ? DiceRoller.resolveActiveCombo(roll, cell, ats, state.boardState().activeDice())
                        : (DiceRoller.matchesWhiteWhite(roll, cell) ? DiceCombination.WHITE_WHITE : null);

                if (combo != null) {
                    actions.add(new CrossCellAction(playerId, rowIndex, cell.id(), combo));
                }
            }
        }
        return actions;
    }

    static boolean isReachableCell(Cell cell, int rightmost, Set<String> crossedCells) {
        return cell.position() > rightmost && !crossedCells.contains(cell.id());
    }

    static int rightmostCrossedPosition(Row row, RowState rowState) {
        if (rowState.crossedCells().isEmpty()) return -1;
        return row.cells().stream()
                .filter(c -> rowState.crossedCells().contains(c.id()))
                .mapToInt(Cell::position)
                .max()
                .orElse(-1);
    }

    static Optional<Map.Entry<Integer, Cell>> findCellById(SheetLayout layout, String cellId) {
        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            for (Cell cell : layout.rows().get(rowIndex).cells()) {
                if (cell.id().equals(cellId)) return Optional.of(Map.entry(rowIndex, cell));
            }
        }
        return Optional.empty();
    }

    static RowState getRowState(SheetProgress progress, int rowIndex) {
        return progress.rowStates().getOrDefault(rowIndex, new RowState(Set.of(), false));
    }

    private void crossRecursive(GameState state, UUID playerId, int rowIndex, String cellId,
                                Map<Integer, Set<String>> crossed, boolean isAuto) {
        SheetLayout layout  = state.sheetLayouts().get(playerId);
        SheetProgress prog  = state.boardState().sheetProgress().get(playerId);
        Row row             = layout.rows().get(rowIndex);
        RowState rowState   = getRowState(prog, rowIndex);

        Cell cell = findCellById(layout, cellId).map(Map.Entry::getValue).orElse(null);
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
        findCellById(layout, targetCellId).ifPresent(e ->
                crossRecursive(state, playerId, e.getKey(), targetCellId, crossed, true));
    }
}