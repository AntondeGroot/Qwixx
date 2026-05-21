package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.LongoVariantData;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class LongoTurnRules extends StandardTurnRules {

    public LongoTurnRules() {
        super();
    }

    public LongoTurnRules(Random random) {
        super(random);
    }

    @Override
    protected int getMinCrossesRequired() {
        return 6;
    }

    @Override
    public List<GameAction> getValidActions(GameState state, UUID playerId) {
        List<GameAction> actions = new ArrayList<>(super.getValidActions(state, playerId));
        if (state.turnState().phase() == TurnPhase.ACTIVE_MOVE
                && playerId.equals(state.turnState().activePlayerId())) {
            ActiveTurnState activePlayer = state.turnState().activeTurnState();
            if (!activePlayer.hasActed()) {
                addBonusCellAction(state, playerId, actions);
            }
        }
        return actions;
    }

    private void addBonusCellAction(GameState state, UUID playerId, List<GameAction> actions) {
        if (!(state.variantData() instanceof LongoVariantData vd)) return;
        List<Integer> playerBonuses = vd.bonusNumbersPerPlayer().getOrDefault(playerId, List.of());
        if (playerBonuses.isEmpty()) return;

        int whiteSum = state.turnState().currentRoll().white1() + state.turnState().currentRoll().white2();
        if (!playerBonuses.contains(whiteSum)) return;

        SheetLayout layout            = getLayout(state, playerId);
        SheetProgress progress        = getProgress(state, playerId);
        Map<Integer, UUID> closedRows = state.boardState().closedRows();

        // Find the minimum cross count across all non-closed rows.
        int fewest = Integer.MAX_VALUE;
        for (int i = 0; i < layout.rows().size(); i++) {
            if (closedRows.containsKey(i)) continue;
            int crosses = getRowState(progress, i).crossedCells().size();
            if (crosses < fewest) fewest = crosses;
        }
        if (fewest == Integer.MAX_VALUE) return;

        // Offer the leftmost available cell for EVERY row that ties at the minimum count.
        // When two rows share the same fewest-crosses count the player may choose either one.
        final int minCrosses = fewest;
        for (int i = 0; i < layout.rows().size(); i++) {
            if (closedRows.containsKey(i)) continue;
            if (getRowState(progress, i).crossedCells().size() != minCrosses) continue;

            final int targetRow = i;
            Row      row        = layout.rows().get(targetRow);
            RowState rowState   = getRowState(progress, targetRow);
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