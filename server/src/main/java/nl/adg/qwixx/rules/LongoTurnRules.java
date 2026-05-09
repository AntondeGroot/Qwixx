package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.LongoVariantData;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class LongoTurnRules extends StandardTurnRules {

    public LongoTurnRules() {
        super();
    }

    public LongoTurnRules(Random random) {
        super(random);
    }

    @Override
    public List<GameAction> getValidActions(GameState state, UUID playerId) {
        List<GameAction> actions = new ArrayList<>(super.getValidActions(state, playerId));
        if (state.turnState().phase() == TurnPhase.ACTIVE_MOVE
                && playerId.equals(state.turnState().activePlayerId())) {
            ActiveTurnState ats = state.turnState().activeTurnState();
            if (!ats.whiteWhiteUsed() && !ats.colorDieUsed()) {
                addBonusCellAction(state, playerId, actions);
            }
        }
        return actions;
    }

    // Longo lock eligibility:
    //
    //  • The LAST required cell (e.g. "16" ascending / "2" descending) always enables
    //    locking — whether it is already a permanent cross or the current pending cross.
    //
    //  • The SECOND-TO-LAST required cell (e.g. "15" / "3") enables locking ONLY when it
    //    was crossed in the CURRENT turn (i.e. it is in the undo buffer / pending crosses).
    //    Once the turn ends without a lock declaration the cell becomes a permanent cross and
    //    loses its locking power; from that point only the last cell can trigger a lock.
    @Override
    protected boolean canCrossLock(GameState state, UUID playerId, int rowIndex) {
        if (rowIsNotLockable(state, playerId, rowIndex)) return false;

        LockCell lock     = state.sheetLayouts().get(playerId).rows().get(rowIndex).lock();
        RowState rowState = rowStateOf(state.boardState().sheetProgress().get(playerId), rowIndex);
        if (rowState.crossedCells().size() < lock.minCrosses()) return false;

        Set<String> allCrosses  = allCrossesForPlayer(state, playerId, rowIndex);
        Set<String> pendingInRow = pendingCrossesInRow(state, playerId, rowIndex);

        List<String> required   = lock.requiredCells();
        String lastRequired     = required.get(required.size() - 1);

        // Last required cell always enables locking (permanent or pending).
        if (allCrosses.contains(lastRequired)) return true;

        // Second-to-last required cell enables locking only while it is still a pending
        // cross (crossed this turn).  Once the turn ends the window closes.
        if (required.size() > 1) {
            String secondLast = required.get(required.size() - 2);
            return pendingInRow.contains(secondLast);
        }
        return false;
    }

    private Set<String> pendingCrossesInRow(GameState state, UUID playerId, int rowIndex) {
        TurnState turn = state.turnState();
        if (turn == null || !turn.undoBuffer().containsKey(playerId)) return new HashSet<>();
        return turn.undoBuffer().get(playerId).getOrDefault(rowIndex, new HashSet<>());
    }

    private void addBonusCellAction(GameState state, UUID playerId, List<GameAction> actions) {
        if (!(state.variantData() instanceof LongoVariantData vd)) return;
        List<Integer> playerBonuses = vd.bonusNumbersPerPlayer().getOrDefault(playerId, List.of());
        if (playerBonuses.isEmpty()) return;

        int whiteSum = state.turnState().currentRoll().white1() + state.turnState().currentRoll().white2();
        if (!playerBonuses.contains(whiteSum)) return;

        SheetLayout layout            = state.sheetLayouts().get(playerId);
        SheetProgress progress        = state.boardState().sheetProgress().get(playerId);
        Map<Integer, UUID> closedRows = state.boardState().closedRows();

        // Find the minimum cross count across all non-closed rows.
        int fewest = Integer.MAX_VALUE;
        for (int i = 0; i < layout.rows().size(); i++) {
            if (closedRows.containsKey(i)) continue;
            int crosses = rowStateOf(progress, i).crossedCells().size();
            if (crosses < fewest) fewest = crosses;
        }
        if (fewest == Integer.MAX_VALUE) return;

        // Offer the leftmost available cell for EVERY row that ties at the minimum count.
        // When two rows share the same fewest-crosses count the player may choose either one.
        final int minCrosses = fewest;
        for (int i = 0; i < layout.rows().size(); i++) {
            if (closedRows.containsKey(i)) continue;
            if (rowStateOf(progress, i).crossedCells().size() != minCrosses) continue;

            final int targetRow = i;
            Row      row        = layout.rows().get(targetRow);
            RowState rowState   = rowStateOf(progress, targetRow);
            int      rightmost  = rightmostCrossedPosition(row, rowState);

            row.cells().stream()
                    .filter(c -> c.position() > rightmost && !rowState.crossedCells().contains(c.id()))
                    .min(Comparator.comparingInt(Cell::position))
                    .map(cell -> new CrossCellAction(playerId, targetRow, cell.id(), DiceCombination.WHITE_WHITE))
                    .filter(bonus -> !actions.contains(bonus))
                    .ifPresent(actions::add);
        }
    }
}