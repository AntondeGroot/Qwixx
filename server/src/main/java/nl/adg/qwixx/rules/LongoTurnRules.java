package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.LongoVariantData;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

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
        if (activePlayerHasNotActed(state, playerId)) {
            addBonusCellAction(state, playerId, actions);
        }
        return actions;
    }

    private boolean activePlayerHasNotActed(GameState state, UUID playerId) {
        return state.turnState().phase() == TurnPhase.ACTIVE_MOVE
                && playerId.equals(state.turnState().activePlayerId())
                && !state.turnState().activeTurnState().hasActed();
    }

    private void addBonusCellAction(GameState state, UUID playerId, List<GameAction> actions) {
        if (!whiteSumMatchesBonusNumber(state, playerId)) return;

        SheetLayout layout   = getLayout(state, playerId);
        SheetProgress progress = getProgress(state, playerId);

        OptionalInt fewest = IntStream.range(0, layout.rows().size())
                .filter(i -> !state.isRowClosed(i))
                .map(i -> getRowState(progress, i).crossedCells().size())
                .min();
        if (fewest.isEmpty()) return;
        int minCrosses = fewest.getAsInt();

        // Offer the leftmost available cell for every row tied at the minimum count.
        for (int i = 0; i < layout.rows().size(); i++) {
            if (state.isRowClosed(i)) continue;
            if (getRowState(progress, i).crossedCells().size() != minCrosses) continue;
            leftmostBonusCellAction(playerId, i, layout.rows().get(i), getRowState(progress, i))
                    .filter(bonus -> !actions.contains(bonus))
                    .ifPresent(actions::add);
        }
    }

    private boolean whiteSumMatchesBonusNumber(GameState state, UUID playerId) {
        if (!(state.variantData() instanceof LongoVariantData vd)) return false;
        List<Integer> bonuses = vd.bonusNumbersPerPlayer().getOrDefault(playerId, List.of());
        if (bonuses.isEmpty()) return false;
        int whiteSum = state.turnState().currentRoll().white1() + state.turnState().currentRoll().white2();
        return bonuses.contains(whiteSum);
    }

    private Optional<CrossCellAction> leftmostBonusCellAction(UUID playerId, int rowIndex, Row row, RowState rowState) {
        int rightmost = rightmostCrossedPosition(row, rowState);
        return row.cells().stream()
                .filter(c -> c.position() > rightmost && !rowState.crossedCells().contains(c.id()))
                .min(Comparator.comparingInt(Cell::position))
                .map(cell -> new CrossCellAction(playerId, rowIndex, cell.id(), DiceCombination.WHITE_WHITE));
    }
}