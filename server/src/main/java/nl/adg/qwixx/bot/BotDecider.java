package nl.adg.qwixx.bot;

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
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class BotDecider {

    // ── Named aliases to DEFAULT — kept so test imports remain unchanged ─────
    static final double SKIP_PENALTY     = BotProfile.DEFAULT.skipPenalty();
    static final double RARITY_BONUS     = BotProfile.DEFAULT.rarityBonus();
    static final double LOCK_BONUS       = BotProfile.DEFAULT.lockBonus();
    static final double PUNISHMENT_LOSS  = BotProfile.DEFAULT.punishmentLoss();
    static final double PASSIVE_THRESHOLD = BotProfile.DEFAULT.passiveThreshold();
    static final int    MAX_PUNISHMENTS  = BotProfile.DEFAULT.maxPunishments();

    private static final Random RAND    = new Random();
    private static final double EPSILON = 1e-9;

    private BotDecider() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /** Decides the best action using the given profile. */
    public static GameAction decide(GameState state, UUID botId,
                                    List<GameAction> validActions, BotProfile profile) {
        List<GameAction> usable = validActions.stream()
                .filter(a -> !(a instanceof GiveUpAction)
                          && !(a instanceof ResetTurnAction)
                          && !(a instanceof UndoLastCrossAction))
                .toList();
        if (usable.isEmpty()) usable = validActions;

        for (GameAction a : usable) if (a instanceof RollAction) return a;
        for (GameAction a : usable) if (a instanceof DeclareLockIntentAction) return a;

        boolean isPassive = !botId.equals(state.turnState().activePlayerId());
        int     center    = diceCenter(state, botId);

        // Full-turn lookahead: active player before either die is used
        if (!isPassive) {
            ActiveTurnState activePlayer = state.turnState().activeTurnState();
            if (!activePlayer.whiteWhiteUsed() && !activePlayer.colorDieUsed()) {
                return decideTwoMove(state, botId, usable, center, profile);
            }
        }

        return decideSingleMove(state, botId, usable, isPassive, center, profile);
    }

    /** Convenience overload that uses {@link BotProfile#DEFAULT}. */
    public static GameAction decide(GameState state, UUID botId, List<GameAction> validActions) {
        return decide(state, botId, validActions, BotProfile.DEFAULT);
    }

    // ── Loss function ─────────────────────────────────────────────────────────

    /**
     * Pure loss function — lower is better. Directly unit-testable without game state.
     *
     * @param gap            cells skipped before this cell in the current cross
     * @param previousSkips  cells already permanently skipped earlier in this row
     * @param displayValue   numeric value shown on the cell
     * @param diceCenter     most probable sum for the game's dice (7 for d6+d6, 9 for d8+d8)
     * @param nearLock       true when this row is within striking distance of a lock
     * @param profile        parameter set driving all weights
     */
    static double loss(int gap, int previousSkips, int displayValue,
                       int diceCenter, boolean nearLock, BotProfile profile) {
        int rarity = Math.abs(displayValue - diceCenter);
        double gapPenalty = gap > 0 ? profile.skipPenalty() * Math.pow(gap + previousSkips, 1.5) : 0;
        return gapPenalty - rarity * profile.rarityBonus() - (nearLock ? profile.lockBonus() : 0);
    }

    /** Convenience overload using {@link BotProfile#DEFAULT} — keeps existing tests unchanged. */
    static double loss(int gap, int previousSkips, int displayValue, int diceCenter, boolean nearLock) {
        return loss(gap, previousSkips, displayValue, diceCenter, nearLock, BotProfile.DEFAULT);
    }

    // ── Decision helpers ──────────────────────────────────────────────────────

    /**
     * Two-move lookahead for the active player when neither die has been used.
     * Scores each candidate first action as: loss(first) + best available second bonus,
     * where the second bonus is only counted when it's negative (i.e. beneficial).
     * Same-row pairs are adjusted for the gap/previousSkips change caused by the first cross.
     */
    private static GameAction decideTwoMove(GameState state, UUID botId,
                                             List<GameAction> usable, int center, BotProfile profile) {
        List<CrossCellAction> wwOptions = usable.stream()
                .filter(a -> a instanceof CrossCellAction c && c.combination() == DiceCombination.WHITE_WHITE)
                .map(a -> (CrossCellAction) a).toList();
        List<CrossCellAction> wcOptions = usable.stream()
                .filter(a -> a instanceof CrossCellAction c && c.combination() == DiceCombination.WHITE_COLOR)
                .map(a -> (CrossCellAction) a).toList();

        CrossCellAction bestFirst     = null;
        double          bestScore     = Double.MAX_VALUE;
        double          bestFirstLoss = Double.MAX_VALUE;
        int             bestCount     = 0;

        List<CrossCellAction> allCrosses = usable.stream()
                .filter(a -> a instanceof CrossCellAction).map(a -> (CrossCellAction) a).toList();

        for (CrossCellAction first : allCrosses) {
            double firstLoss = cellLoss(state, botId, first, center, profile);
            List<CrossCellAction> opposite =
                    first.combination() == DiceCombination.WHITE_WHITE ? wcOptions : wwOptions;

            // Best beneficial second move (only counts when its loss is negative)
            double bestSecondBonus = 0.0;
            for (CrossCellAction second : opposite) {
                if (!validOrdering(state, botId, first, second)) continue;
                double secondLoss = cellLossAfterCross(state, botId, second, center, profile, first);
                if (secondLoss < bestSecondBonus) bestSecondBonus = secondLoss;
            }

            double score = firstLoss + bestSecondBonus;
            if (score < bestScore - EPSILON) {
                bestScore = score; bestFirstLoss = firstLoss; bestFirst = first; bestCount = 1;
            } else if (score <= bestScore + EPSILON) {
                bestCount++;
                if (RAND.nextInt(bestCount) == 0) { bestFirst = first; bestFirstLoss = firstLoss; }
            }
        }

        if (bestFirst != null) {
            if (bestFirstLoss <= profile.punishmentLoss()) return bestFirst;
            int punishments = state.boardState().sheetProgress().get(botId).punishments();
            if (punishments < profile.maxPunishments()) {
                for (GameAction a : usable) if (a instanceof TakePunishmentAction) return a;
            }
            return bestFirst;
        }

        for (GameAction a : usable) if (a instanceof EndTurnAction) return a;
        return usable.get(0);
    }

    /** Greedy single-move decision — used for passive players and the second move of a turn. */
    private static GameAction decideSingleMove(GameState state, UUID botId,
                                               List<GameAction> usable, boolean isPassive,
                                               int center, BotProfile profile) {
        CrossCellAction best      = null;
        double          bestLoss  = Double.MAX_VALUE;
        int             bestCount = 0;

        for (GameAction a : usable) {
            if (!(a instanceof CrossCellAction cross)) continue;
            double l = cellLoss(state, botId, cross, center, profile);
            if (l < bestLoss - EPSILON) {
                bestLoss = l; best = cross; bestCount = 1;
            } else if (l <= bestLoss + EPSILON) {
                bestCount++;
                if (RAND.nextInt(bestCount) == 0) best = cross;
            }
        }

        if (best != null) {
            if (isPassive) {
                if (bestLoss <= profile.passiveThreshold()) return best;
            } else {
                if (bestLoss <= profile.punishmentLoss()) return best;
                int punishments = state.boardState().sheetProgress().get(botId).punishments();
                if (punishments < profile.maxPunishments()) {
                    for (GameAction a : usable) if (a instanceof TakePunishmentAction) return a;
                }
                return best;
            }
        }

        for (GameAction a : usable) if (a instanceof EndTurnAction) return a;
        return usable.get(0);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static double cellLoss(GameState state, UUID botId,
                                   CrossCellAction action, int center, BotProfile profile) {
        SheetLayout   layout = state.sheetLayouts().get(botId);
        SheetProgress prog   = state.boardState().sheetProgress().get(botId);
        Row  row  = layout.rows().get(action.rowIndex());
        Cell cell = row.cells().stream()
                .filter(c -> c.id().equals(action.cellId())).findFirst().orElseThrow();

        double totalLoss = singleCellLoss(action.rowIndex(), cell, row, prog, center,
                nearLock(state, botId, action.rowIndex(), profile), profile);

        totalLoss += tagAdjustments(cell, center, layout, prog, state, botId, profile);
        return totalLoss;
    }

    /**
     * Loss of {@code action} assuming {@code priorCross} has already been applied this turn.
     * If they are in different rows the result is identical to {@link #cellLoss}.
     * If they are in the same row the gap and previousSkips are computed against the
     * virtual post-prior-cross row state.
     * Invalid orderings (second cell behind first in same row) return {@link Double#MAX_VALUE}.
     */
    private static double cellLossAfterCross(GameState state, UUID botId,
                                              CrossCellAction action, int center, BotProfile profile,
                                              CrossCellAction priorCross) {
        if (priorCross.rowIndex() != action.rowIndex()) {
            return cellLoss(state, botId, action, center, profile);
        }

        SheetLayout   layout = state.sheetLayouts().get(botId);
        SheetProgress prog   = state.boardState().sheetProgress().get(botId);
        Row  row      = layout.rows().get(action.rowIndex());
        Cell cell     = row.cells().stream().filter(c -> c.id().equals(action.cellId())).findFirst().orElseThrow();
        Cell priorCell = row.cells().stream().filter(c -> c.id().equals(priorCross.cellId())).findFirst().orElseThrow();

        if (cell.position() <= priorCell.position()) return Double.MAX_VALUE; // invalid ordering

        RowState rowState         = prog.rowStates().getOrDefault(action.rowIndex(), new RowState(Set.of(), false));
        int originalRightmost     = row.cells().stream()
                .filter(c -> rowState.crossedCells().contains(c.id()))
                .mapToInt(Cell::position).max().orElse(-1);
        int virtualRightmost      = Math.max(originalRightmost, priorCell.position());
        int virtualCrossedCount   = rowState.crossedCells().size() + 1;

        int gap           = Math.max(0, cell.position() - virtualRightmost - 1);
        int previousSkips = virtualRightmost >= 0 ? (virtualRightmost + 1) - virtualCrossedCount : 0;

        double baseLoss = loss(gap, previousSkips, parseDisplayValue(cell, center), center,
                nearLock(state, botId, action.rowIndex(), profile), profile);
        return baseLoss + tagAdjustments(cell, center, layout, prog, state, botId, profile);
    }

    /** Returns true when {@code second} can validly follow {@code first} in one turn. */
    private static boolean validOrdering(GameState state, UUID botId,
                                          CrossCellAction first, CrossCellAction second) {
        if (first.rowIndex() != second.rowIndex()) return true;
        Row row = state.sheetLayouts().get(botId).rows().get(first.rowIndex());
        int firstPos  = row.cells().stream().filter(c -> c.id().equals(first.cellId()))
                .mapToInt(Cell::position).findFirst().orElse(0);
        int secondPos = row.cells().stream().filter(c -> c.id().equals(second.cellId()))
                .mapToInt(Cell::position).findFirst().orElse(0);
        return secondPos > firstPos;
    }

    private static double tagAdjustments(Cell cell, int center, SheetLayout layout,
                                          SheetProgress prog, GameState state, UUID botId,
                                          BotProfile profile) {
        double adjust = 0;
        for (CellTag tag : cell.tags()) {
            switch (tag) {
                case CellTag.AutoCross autoCross ->
                        adjust += autoCrossLoss(autoCross.target(), layout, prog, center, state, botId, profile);
                case CellTag.BonusPoints bonus ->
                        adjust -= bonus.amount();
                case CellTag.DoubleCross ignored -> {
                    int displayValue = parseDisplayValue(cell, center);
                    adjust -= Math.abs(displayValue - center) * profile.rarityBonus();
                }
                case CellTag.ExtraBucket ignored ->
                        adjust -= profile.rarityBonus();
                case CellTag.SecondaryColor ignored -> {
                    int displayValue = parseDisplayValue(cell, center);
                    adjust -= Math.abs(displayValue - center) * profile.rarityBonus();
                }
                case CellTag.XChange xc -> {
                    // Prefer cells where the closer value to the center dice mean is matched.
                    int bestMatch = Math.min(Math.abs(xc.a() - center), Math.abs(xc.b() - center));
                    adjust += (profile.rarityBonus() - bestMatch) * 0.5;
                }
                case CellTag.LuckyNumber luckyNumber ->
                        // Treat luckyNumber bonus points like any other bonus: subtract to make cell more attractive.
                        adjust -= luckyNumber.bonusPoints();
                case CellTag.LuckyCross _ -> {} // no tag-level adjustment; base position score still applies
            }
        }
        return adjust;
    }

    private static double autoCrossLoss(String targetId, SheetLayout layout, SheetProgress prog,
                                        int center, GameState state, UUID botId, BotProfile profile) {
        for (int rowIdx = 0; rowIdx < layout.rows().size(); rowIdx++) {
            Row row = layout.rows().get(rowIdx);
            for (Cell cell : row.cells()) {
                if (cell.id().equals(targetId)) {
                    return singleCellLoss(rowIdx, cell, row, prog, center,
                            nearLock(state, botId, rowIdx, profile), profile);
                }
            }
        }
        return 0;
    }

    private static double singleCellLoss(int rowIndex, Cell cell, Row row,
                                         SheetProgress prog, int center,
                                         boolean nearLockRow, BotProfile profile) {
        RowState rowState = prog.rowStates().getOrDefault(rowIndex, new RowState(Set.of(), false));
        int rightmost = row.cells().stream()
                .filter(c -> rowState.crossedCells().contains(c.id()))
                .mapToInt(Cell::position)
                .max().orElse(-1);
        int gap           = Math.max(0, cell.position() - rightmost - 1);
        int previousSkips = rightmost >= 0 ? (rightmost + 1) - rowState.crossedCells().size() : 0;

        return loss(gap, previousSkips, parseDisplayValue(cell, center), center, nearLockRow, profile);
    }

    private static int parseDisplayValue(Cell cell, int center) {
        try { return Integer.parseInt(cell.displayValue()); }
        catch (NumberFormatException e) { return center; }
    }

    // Midpoint of the value range: 7 for standard (2–12, d6+d6), 9 for Longo (2–16, d8+d8).
    private static int diceCenter(GameState state, UUID botId) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Row row : state.sheetLayouts().get(botId).rows()) {
            for (Cell c : row.cells()) {
                try {
                    int v = Integer.parseInt(c.displayValue());
                    if (v < min) min = v;
                    if (v > max) max = v;
                } catch (NumberFormatException ignored) {}
            }
        }
        return (min == Integer.MAX_VALUE) ? 7 : (min + max) / 2;
    }

    private static boolean nearLock(GameState state, UUID botId, int rowIndex, BotProfile profile) {
        Row row = state.sheetLayouts().get(botId).rows().get(rowIndex);
        if (row.lock() == null) return false;
        LockCell lock = row.lock();

        SheetProgress prog  = state.boardState().sheetProgress().get(botId);
        RowState rowState   = prog.rowStates().getOrDefault(rowIndex, new RowState(Set.of(), false));
        Set<String> crossed = rowState.crossedCells();

        long missingClosing = lock.closingCells().stream().filter(id -> !crossed.contains(id)).count();
        long crossedClosing = lock.closingCells().size() - missingClosing;
        long normalNeeded   = Math.max(0, lock.minCrosses() - (crossed.size() - crossedClosing));

        return (missingClosing + normalNeeded) <= profile.nearLockRemaining();
    }
}