package nl.adg.qwixx.rules;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.data.BonusBKind;
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
        TurnState turn             = Objects.requireNonNull(state.turnState());
        var roll                   = turn.currentRoll();
        ActiveTurnState ats        = isActive ? turn.activeTurnState() : null;
        SheetLayout layout         = state.sheetLayout(playerId);
        var progress               = state.sheetProgress(playerId);
        // Pre-turn snapshot used for Big Points bonus prerequisite check.
        Map<UUID, SheetProgress> startProgress = turn.moveStartProgress();
        // Effective white+white after an x-change cross; null means use the actual roll sum.
        Integer effectiveWW        = turn.xChangeEffectiveWW().get(playerId);

        for (int rowIndex = 0; rowIndex < layout.rows().size(); rowIndex++) {
            Row row = layout.rows().get(rowIndex);

            // Lucky Number and Lucky Cross rows/cells are handled by the turn-rules layer.
            if (row.isLuckyRow()) continue;

            // Bonus A bar cells are never directly clickable — they are auto-crossed by the chain.
            if (row.isBonusBar()) continue;

            // Bonus B strip cells are never directly clickable — they are auto-crossed on pair completion.
            if (row.isBonusBStrip()) continue;

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
                    combo = DiceRoller.resolveActiveCombo(roll, cell, Objects.requireNonNull(ats), state.boardState().activeDice());
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
    @Nullable
    private static DiceCombination resolveWithEffectiveWW(RollResult roll, Cell cell, @Nullable ActiveTurnState ats, boolean isActive, int effectiveWW) {
        String target = String.valueOf(effectiveWW);
        if (isActive) {
            Objects.requireNonNull(ats);
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
    @Nullable
    static CellTag.XChange findXChangeTag(@Nullable Cell cell) {
        if (cell == null) return null;
        for (CellTag tag : cell.tags()) {
            if (tag instanceof CellTag.XChange xc) return xc;
        }
        return null;
    }

    @Nullable
    private static DiceCombination resolveXChangeCombo(RollResult roll, CellTag.XChange xc, @Nullable ActiveTurnState ats, boolean isActive) {
        int ww = roll.white1() + roll.white2();
        if (ww != xc.a() && ww != xc.b()) return null;
        if (isActive && ats != null && ats.whiteWhiteUsed()) return null;
        return DiceCombination.WHITE_WHITE;
    }

    // Bonus cells respond to white+white OR either neighbouring colour die.
    @Nullable
    private static DiceCombination resolveBonusCombo(RollResult roll, Cell cell, @Nullable ActiveTurnState ats, boolean isActive) {
        if (!isActive) {
            return DiceRoller.matchesWhiteWhite(roll, cell) ? DiceCombination.WHITE_WHITE : null;
        }
        Objects.requireNonNull(ats);
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
    static boolean bonusPrerequisiteMet(SheetLayout layout, @Nullable SheetProgress startProgress, Row bonusRow, Cell cell) {
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

    @Nullable
    static CellTag.DoubleTwin findTwinTag(@Nullable Cell cell) {
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
        SheetLayout layout  = state.sheetLayout(playerId);
        SheetProgress prog  = state.sheetProgress(playerId);
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

        boolean isBonusBox = false;
        BonusBKind bonusBKind = null;
        for (CellTag tag : cell.tags()) {
            if (tag instanceof CellTag.AutoCross autoCross) {
                findAndCrossAutoTarget(state, playerId, autoCross.target(), crossed);
            } else if (tag instanceof CellTag.BonusBox) {
                isBonusBox = true;
            } else if (tag instanceof CellTag.BonusB(BonusBKind kind)) {
                bonusBKind = kind;
            }
        }
        // Bonus A: crossing a bonus box consumes the next bonus-bar cell and forces a cross in
        // that colour's row. Done after the cell is recorded so the chain sees the updated state.
        if (isBonusBox) triggerBonusBar(state, playerId, crossed);
        // Bonus B: crossing a bonus box may complete its pair, which fires the kind's bonus.
        if (bonusBKind != null) triggerBonusB(state, playerId, bonusBKind, crossed);
    }

    private void findAndCrossAutoTarget(GameState state, UUID playerId, String targetCellId,
                                        Map<Integer, Set<String>> crossed) {
        SheetLayout layout = state.sheetLayout(playerId);
        findCellById(layout, targetCellId).ifPresent(e ->
                crossRecursive(state, playerId, e.getKey(), targetCellId, crossed, true));
    }

    // Consume the next open bonus-bar cell; its colour forces a cross of the next box in that row.
    // Forced crosses recurse through crossRecursive, so a bonus box crossed this way chains again.
    private void triggerBonusBar(GameState state, UUID playerId, Map<Integer, Set<String>> crossed) {
        SheetLayout layout = state.sheetLayout(playerId);
        SheetProgress prog = state.sheetProgress(playerId);

        int barIndex = -1;
        Row bar = null;
        for (int i = 0; i < layout.rows().size(); i++) {
            if (layout.rows().get(i).isBonusBar()) { barIndex = i; bar = layout.rows().get(i); break; }
        }
        if (bar == null) return;

        // The bonus bar follows the normal row-progression rule: only the next cell to the RIGHT
        // of the right-most crossed cell can be consumed. Forfeits crossed further right therefore
        // make the cells between them unavailable (skipped), exactly like a regular colour row.
        RowState barState = getRowState(prog, barIndex);
        int rightmost = rightmostCrossedPosition(bar, barState);
        Cell barCell = bar.cells().stream()
                .filter(c -> c.position() > rightmost && !barState.crossedCells().contains(c.id()))
                .findFirst().orElse(null);
        if (barCell == null) return; // no cell left to the right of the right-most cross

        Set<String> newBar = new HashSet<>(barState.crossedCells());
        newBar.add(barCell.id());
        prog.updateRowState(barIndex, new RowState(newBar, barState.lockCrossed()));
        crossed.computeIfAbsent(barIndex, k -> new HashSet<>()).add(barCell.id());

        // The bonus-bar cell's colour picks the row that receives the forced cross.
        Color color = barCell.color();
        for (int i = 0; i < layout.rows().size(); i++) {
            Row row = layout.rows().get(i);
            if (row.isBonusBar() || row.lock() == null || row.cells().isEmpty()) continue;
            if (row.cells().get(0).color() != color) continue;
            if (state.isRowClosed(i)) return; // row locked → forfeited already, nothing to cross
            Cell forced = nextForcedBox(row, getRowState(prog, i));
            if (forced != null) crossRecursive(state, playerId, i, forced.id(), crossed, true);
            return;
        }
    }

    // Bonus B: once both boxes of a kind are crossed, mark its strip indicator and fire the
    // immediate effect. Score-time kinds (double / +13 / no-penalty) only mark the indicator.
    private void triggerBonusB(GameState state, UUID playerId, BonusBKind kind,
                               Map<Integer, Set<String>> crossed) {
        SheetLayout layout = state.sheetLayout(playerId);
        SheetProgress prog = state.sheetProgress(playerId);

        Cell stripCell = null;
        int stripIndex = -1;
        boolean sawBox = false;
        boolean pairComplete = true;
        for (int i = 0; i < layout.rows().size(); i++) {
            Row row = layout.rows().get(i);
            for (Cell c : row.cells()) {
                for (CellTag t : c.tags()) {
                    if (t instanceof CellTag.BonusB(BonusBKind k) && k == kind) {
                        if (row.isBonusBStrip()) {
                            stripCell = c;
                            stripIndex = i;
                        } else {
                            sawBox = true;
                            if (!getRowState(prog, i).crossedCells().contains(c.id())) pairComplete = false;
                        }
                    }
                }
            }
        }
        if (!sawBox || stripCell == null || !pairComplete) return;

        RowState stripState = getRowState(prog, stripIndex);
        if (stripState.crossedCells().contains(stripCell.id())) return; // already fired

        Set<String> newStrip = new HashSet<>(stripState.crossedCells());
        newStrip.add(stripCell.id());
        prog.updateRowState(stripIndex, new RowState(newStrip, stripState.lockCrossed()));
        crossed.computeIfAbsent(stripIndex, k -> new HashSet<>()).add(stripCell.id());

        switch (kind) {
            case FEWEST_TWO -> crossInFewestRow(state, playerId, 2, crossed);
            case ONE_EACH   -> crossInEachRow(state, playerId, crossed);
            default         -> { /* DOUBLE_FEWEST / PLUS_13 / NO_PENALTY apply at score time */ }
        }
    }

    // Cross the next {@code count} boxes in the single open colour row with the fewest crosses.
    private void crossInFewestRow(GameState state, UUID playerId, int count,
                                  Map<Integer, Set<String>> crossed) {
        SheetLayout layout = state.sheetLayout(playerId);
        SheetProgress prog = state.sheetProgress(playerId);
        int rowIndex = fewestColourRow(state, playerId);
        if (rowIndex < 0) return;
        for (int n = 0; n < count; n++) {
            Cell forced = nextForcedBox(layout.rows().get(rowIndex), getRowState(prog, rowIndex));
            if (forced == null) return; // no room before the lock → remaining crosses are forfeited
            crossRecursive(state, playerId, rowIndex, forced.id(), crossed, true);
        }
    }

    // Cross the next box in every open colour row.
    private void crossInEachRow(GameState state, UUID playerId, Map<Integer, Set<String>> crossed) {
        SheetLayout layout = state.sheetLayout(playerId);
        SheetProgress prog = state.sheetProgress(playerId);
        for (int i = 0; i < layout.rows().size(); i++) {
            Row row = layout.rows().get(i);
            if (row.lock() == null || state.isRowClosed(i)) continue;
            Cell forced = nextForcedBox(row, getRowState(prog, i));
            if (forced != null) crossRecursive(state, playerId, i, forced.id(), crossed, true);
        }
    }

    // The open colour row (identified by having a lock) with the fewest crosses; ties → lowest index.
    private int fewestColourRow(GameState state, UUID playerId) {
        SheetLayout layout = state.sheetLayout(playerId);
        SheetProgress prog = state.sheetProgress(playerId);
        int best = -1;
        int bestCount = Integer.MAX_VALUE;
        for (int i = 0; i < layout.rows().size(); i++) {
            Row row = layout.rows().get(i);
            if (row.lock() == null || state.isRowClosed(i)) continue;
            int count = getRowState(prog, i).crossedCells().size();
            if (count < bestCount) { bestCount = count; best = i; }
        }
        return best;
    }

    // The next box a forced bonus cross lands on: the left-most reachable, non-closing cell.
    // (The lock cell is excluded so row-closing stays with the turn-rules layer.)
    @Nullable
    private static Cell nextForcedBox(Row row, RowState rowState) {
        int rightmost = rightmostCrossedPosition(row, rowState);
        Cell best = null;
        for (Cell c : row.cells()) {
            if (c.isClosingEligible() || findTwinTag(c) != null) continue;
            if (c.position() <= rightmost || rowState.crossedCells().contains(c.id())) continue;
            if (best == null || c.position() < best.position()) best = c;
        }
        return best;
    }
}
