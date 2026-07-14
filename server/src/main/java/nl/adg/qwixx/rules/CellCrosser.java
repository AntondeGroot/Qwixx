package nl.adg.qwixx.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.RollResult;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnState;

class CellCrosser {

    CellCrosser() {}

    Map<Integer, Set<String>> cross(GameState state, UUID playerId, int rowIndex, String cellId) {
        Map<Integer, Set<String>> crossed = new HashMap<>();
        crossRecursive(state, playerId, rowIndex, cellId, crossed, false);
        return crossed;
    }

    @SuppressWarnings("PMD.NullAssignment")
    List<GameAction> crossCellActions(GameState state, UUID playerId, boolean isActive, int minCrosses) {
        List<GameAction> actions   = new ArrayList<>();
        TurnState turn             = state.turnState();
        var roll                   = turn.currentRoll();
        ActiveTurnState ats        = isActive ? turn.activeTurnState() : null;
        SheetLayout layout         = state.sheetLayouts().get(playerId);
        var progress               = state.boardState().sheetProgress().get(playerId);
        // Pre-turn snapshot used for Big Points bonus prerequisite check.
        Map<UUID, SheetProgress> startProgress = turn.moveStartProgress();
        // Effective white+white after an x-change cross; null means use the actual roll sum.
        Integer effectiveWW        = turn.xChangeEffectiveWW().get(playerId);

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            Row row = layout.rows().get(rowIndex);

            // Lucky Number and Lucky Cross rows/cells are handled by the turn-rules layer.
            if (row.isLuckyRow()) continue;

            // Bonus rows are never globally closed, but skip closed normal rows.
            if (!row.isBonusRow() && state.isRowClosed(rowIndex)) continue;

            RowState rowState = getRowState(progress, rowIndex);
            int rightmost     = rightmostCrossedPosition(row, rowState);

            for (Cell cell : row.cells()) {
                // Double A/B twin cells are not part of the left-to-right progression; they are
                // offered only once their primary is crossed and is the row's rightmost cross.
                CellTag.DoubleTwin twinTag = findTwinTag(cell);
                boolean reachable = twinTag != null
                        ? twinReachable(row, rowState, cell)
                        : isReachableCell(cell, rightmost, rowState.crossedCells());
                if (!reachable) continue;

                // Lock-eligibility guard (only for normal rows with a lock).
                if (cell.isClosingEligible() && row.lock() != null) {
                    long alreadyCrossedRequired = row.lock().closingCells().stream()
                            .filter(id -> rowState.crossedCells().contains(id))
                            .count();
                    long normalCrossed = rowState.crossedCells().size() - alreadyCrossedRequired;
                    if (normalCrossed < minCrosses) continue;
                }

                // Bonus rows: the display value must have been permanently crossed in a
                // neighbouring coloured row before this turn started.
                if (row.isBonusRow()) {
                    SheetProgress snap = startProgress != null ? startProgress.get(playerId) : null;
                    if (!bonusPrerequisiteMet(layout, snap, row, cell)) continue;
                }

                // Lucky Cross fields are bonus cells reachable only via the 1+5 combo path.
                if (cell.tags() != null && cell.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyCross)) continue;

                CellTag.XChange xChange = findXChangeTag(cell);
                DiceCombination combo;
                if (xChange != null) {
                    if (effectiveWW != null) continue; // x-change already applied this turn
                    combo = resolveXChangeCombo(roll, xChange, ats, isActive);
                } else if (effectiveWW != null && !row.isBonusRow()) {
                    // Effective WW overrides actual WW for normal cells; bonus rows use their own logic.
                    combo = resolveWithEffectiveWW(roll, cell, ats, isActive, effectiveWW);
                } else if (row.isBonusRow()) {
                    combo = resolveBonusCombo(roll, cell, ats, isActive);
                } else if (isActive) {
                    combo = DiceRoller.resolveActiveCombo(roll, cell, ats, state.boardState().activeDice());
                } else {
                    combo = DiceRoller.matchesWhiteWhite(roll, cell) ? DiceCombination.WHITE_WHITE : null;
                }

                if (combo != null) {
                    actions.add(new CrossCellAction(playerId, rowIndex, cell.id(), combo));
                }
            }
        }
        return actions;
    }

    // Resolves combo for a normal cell when effectiveWW is active.
    // WW slot targets effectiveWW value; color die is still independent.
    private static DiceCombination resolveWithEffectiveWW(RollResult roll, Cell cell, ActiveTurnState ats, boolean isActive, int effectiveWW) {
        String target = String.valueOf(effectiveWW);
        if (isActive) {
            if (!ats.whiteWhiteUsed() && !ats.colorDieUsed() && cell.displayValue().equals(target)) {
                return DiceCombination.WHITE_WHITE;
            }
            if (!ats.colorDieUsed()) {
                Integer colorValue = roll.coloredDice().get(cell.color());
                if (colorValue != null && DiceRoller.matchesWhiteColor(roll, cell, colorValue)) {
                    return DiceCombination.WHITE_COLOR;
                }
            }
            return null;
        } else {
            return cell.displayValue().equals(target) ? DiceCombination.WHITE_WHITE : null;
        }
    }

    // X-Change cells respond only to white+white sum matching either value in the pair.
    static CellTag.XChange findXChangeTag(Cell cell) {
        if (cell == null) return null;
        for (CellTag tag : cell.tags()) {
            if (tag instanceof CellTag.XChange xc) return xc;
        }
        return null;
    }

    private static DiceCombination resolveXChangeCombo(RollResult roll, CellTag.XChange xc, ActiveTurnState ats, boolean isActive) {
        int ww = roll.white1() + roll.white2();
        if (ww != xc.a() && ww != xc.b()) return null;
        if (isActive && ats != null && ats.whiteWhiteUsed()) return null;
        return DiceCombination.WHITE_WHITE;
    }

    // Bonus cells respond to white+white OR either neighbouring colour die.
    private static DiceCombination resolveBonusCombo(RollResult roll, Cell cell, ActiveTurnState ats, boolean isActive) {
        if (!isActive) {
            return DiceRoller.matchesWhiteWhite(roll, cell) ? DiceCombination.WHITE_WHITE : null;
        }
        if (!ats.whiteWhiteUsed() && DiceRoller.matchesWhiteWhite(roll, cell)) return DiceCombination.WHITE_WHITE;
        if (!ats.colorDieUsed() && matchesBonusColorDie(roll, cell)) return DiceCombination.WHITE_COLOR;
        return null;
    }

    // Returns true if white + primaryColor or white + secondaryColor equals the bonus cell's value.
    private static boolean matchesBonusColorDie(RollResult roll, Cell cell) {
        Integer pv = roll.coloredDice().get(cell.color());
        if (pv != null && DiceRoller.matchesWhiteColor(roll, cell, pv)) return true;
        for (CellTag tag : cell.tags()) {
            if (tag instanceof CellTag.SecondaryColor sc) {
                Integer sv = roll.coloredDice().get(sc.color());
                if (sv != null && DiceRoller.matchesWhiteColor(roll, cell, sv)) return true;
            }
        }
        return false;
    }

    // A bonus cell may only be offered when its display value is permanently crossed
    // in at least one neighbouring coloured row (checked against the pre-turn snapshot).
    static boolean bonusPrerequisiteMet(SheetLayout layout, SheetProgress startProgress, Row bonusRow, Cell cell) {
        if (startProgress == null) return false;
        String value = cell.displayValue();
        for (int neighborIndex : new int[]{bonusRow.upperRowIndex(), bonusRow.lowerRowIndex()}) {
            if (neighborIndex < 0) continue;
            RowState neighborState = getRowState(startProgress, neighborIndex);
            boolean crossed = layout.rows().get(neighborIndex).cells().stream()
                    .anyMatch(c -> c.displayValue().equals(value) && neighborState.crossedCells().contains(c.id()));
            if (crossed) return true;
        }
        return false;
    }

    static boolean isReachableCell(Cell cell, int rightmost, Set<String> crossedCells) {
        return cell.position() > rightmost && !crossedCells.contains(cell.id());
    }

    static CellTag.DoubleTwin findTwinTag(Cell cell) {
        if (cell == null || cell.tags() == null) return null;
        for (CellTag tag : cell.tags()) {
            if (tag instanceof CellTag.DoubleTwin dt) return dt;
        }
        return null;
    }

    // A Double A/B twin is crossable only when: it isn't already crossed, its primary IS crossed,
    // and the primary is the row's right-most crossed cell (nothing to the primary's right crossed).
    static boolean twinReachable(Row row, RowState rowState, Cell twin) {
        Set<String> crossed = rowState.crossedCells();
        if (crossed.contains(twin.id())) return false;
        CellTag.DoubleTwin tag = findTwinTag(twin);
        if (tag == null) return false;
        Cell primary = row.cells().stream()
                .filter(c -> c.id().equals(tag.primary()))
                .findFirst().orElse(null);
        if (primary == null || !crossed.contains(primary.id())) return false;
        return primary.position() == rightmostCrossedPosition(row, rowState);
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

        CellTag.DoubleTwin twinTag = findTwinTag(cell);
        if (!isAuto) {
            if (twinTag != null) {
                if (!twinReachable(row, rowState, cell))
                    throw new IllegalMoveException("double twin is not yet reachable");
            } else {
                int rightmost = rightmostCrossedPosition(row, rowState);
                if (cell.position() <= rightmost)
                    throw new IllegalMoveException("cell does not satisfy the progression check");
            }
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
