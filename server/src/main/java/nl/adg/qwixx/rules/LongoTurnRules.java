package nl.adg.qwixx.rules;

import static nl.adg.qwixx.rules.CellCrosser.getRowState;
import static nl.adg.qwixx.rules.CellCrosser.isReachableCell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.LongoVariantData;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;

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
        if (mayUseBonusNumber(state, playerId)) {
            actions.addAll(bonusCellActions(state, playerId, actions));
        }
        return actions;
    }

    private boolean mayUseBonusNumber(GameState state, UUID playerId) {
        TurnState turn = Objects.requireNonNull(state.turnState());
        if (playerId.equals(turn.activePlayerId())) {
            return turn.phase() == TurnPhase.ACTIVE_MOVE && !turn.activeTurnState().hasActed();
        }
        // Passive player: offered bonus cell when they haven't yet acted this turn.
        return TurnHelper.isPassiveInQueue(turn, playerId)
                && !TurnHelper.hasAlreadyActed(turn, playerId);
    }

    private List<GameAction> bonusCellActions(GameState state, UUID playerId, List<GameAction> existing) {
        if (!whiteSumMatchesBonusNumber(state, playerId)) return List.of();

        SheetLayout layout     = getLayout(state, playerId);
        SheetProgress progress = getProgress(state, playerId);

        OptionalInt fewest = IntStream.range(0, layout.rows().size())
                .filter(i -> !state.isRowClosed(i))
                .map(i -> getRowState(progress, i).crossedCells().size())
                .min();
        if (fewest.isEmpty()) return List.of();
        int minCrosses = fewest.getAsInt();

        List<GameAction> actions = new ArrayList<>();
        for (int i = 0; i < layout.rows().size(); i++) {
            if (state.isRowClosed(i)) continue;
            if (getRowState(progress, i).crossedCells().size() != minCrosses) continue;
            leftmostBonusCellAction(playerId, i, layout.rows().get(i), getRowState(progress, i))
                    .filter(bonus -> !existing.contains(bonus))
                    .ifPresent(actions::add);
        }
        return actions;
    }

    private boolean whiteSumMatchesBonusNumber(GameState state, UUID playerId) {
        if (!(state.variantData() instanceof LongoVariantData vd)) return false;
        List<Integer> bonuses = vd.bonusNumbersPerPlayer().getOrDefault(playerId, List.of());
        if (bonuses.isEmpty()) return false;
        int whiteSum = Objects.requireNonNull(state.turnState()).currentRoll().white1() + Objects.requireNonNull(state.turnState()).currentRoll().white2();
        if (bonuses.contains(whiteSum)) return true;
        // After an x-change cross, the effective WW may differ from the actual roll sum.
        Integer effectiveWW = Objects.requireNonNull(state.turnState()).xChangeEffectiveWW().get(playerId);
        return effectiveWW != null && bonuses.contains(effectiveWW);
    }

    private Optional<CrossCellAction> leftmostBonusCellAction(UUID playerId, int rowIndex, Row row, RowState rowState) {
        int rightmost = rightmostCrossedPosition(row, rowState);
        return row.cells().stream()
                .filter(c -> isReachableCell(c, rightmost, rowState.crossedCells()))
                .min(Comparator.comparingInt(Cell::position))
                .map(cell -> new CrossCellAction(playerId, rowIndex, cell.id(), DiceCombination.WHITE_WHITE));
    }
}
