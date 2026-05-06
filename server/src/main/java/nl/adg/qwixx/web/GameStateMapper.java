package nl.adg.qwixx.web;

import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.RollResult;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.LongoVariantData;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

class GameStateMapper {

    private GameStateMapper() {}

    static nl.adg.qwixx.generated.model.GameState toDto(GameState state, GameSession session) {
        Map<UUID, Player> playerIndex = session.players().stream()
                .collect(Collectors.toMap(Player::id, Function.identity()));

        BoardState board = state.boardState();
        TurnState  turn  = state.turnState();

        return new nl.adg.qwixx.generated.model.GameState(
                state.players().stream()
                        .map(id -> {
                            Player p = playerIndex.get(id);
                            return new nl.adg.qwixx.generated.model.Player(
                                    id.toString(),
                                    p != null ? p.name() : id.toString())
                                    .profilePic(p != null ? p.profilePic() : null);
                        })
                        .toList(),
                mapSheetProgress(state),
                mapSheetLayouts(state),
                state.gameOver(),
                state.version())
                .turnState(mapTurnState(turn))
                .closedRows(mapClosedRows(state))
                .activeDiceColors(mapActiveDiceColors(board))
                .bonusNumbers(mapBonusNumbers(state))
                .rowClosureRequests(mapRowClosureRequests(state));
    }

    private static Map<String, nl.adg.qwixx.generated.model.SheetProgress> mapSheetProgress(GameState state) {
        Map<String, nl.adg.qwixx.generated.model.SheetProgress> result = new HashMap<>();
        state.boardState().sheetProgress().forEach((playerId, sp) -> {
            SheetLayout layout = state.sheetLayouts().get(playerId);
            result.put(playerId.toString(), mapSheetProgress(sp, layout));
        });
        return result;
    }

    private static nl.adg.qwixx.generated.model.SheetProgress mapSheetProgress(SheetProgress sp, SheetLayout layout) {
        Map<String, nl.adg.qwixx.generated.model.RowState> rowStates = new HashMap<>();
        sp.rowStates().forEach((rowIndex, rs) -> {
            String rowId = layout.rows().get(rowIndex).id();
            rowStates.put(rowId, mapRowState(rs));
        });
        return new nl.adg.qwixx.generated.model.SheetProgress(rowStates, sp.punishments());
    }

    private static nl.adg.qwixx.generated.model.RowState mapRowState(RowState rs) {
        return new nl.adg.qwixx.generated.model.RowState(
                new ArrayList<>(rs.crossedCells()),
                rs.lockCrossed());
    }

    private static Map<String, String> mapClosedRows(GameState state) {
        Map<String, String> result = new HashMap<>();
        BoardState board = state.boardState();
        if (board.closedRows().isEmpty()) return result;
        SheetLayout anyLayout = state.sheetLayouts().values().iterator().next();
        board.closedRows().forEach((rowIndex, playerId) -> {
            String rowId = anyLayout.rows().get(rowIndex).id();
            result.put(rowId, playerId.toString());
        });
        return result;
    }

    private static java.util.List<nl.adg.qwixx.generated.model.Color> mapActiveDiceColors(BoardState board) {
        return board.activeDice().stream()
                .filter(die -> die.color() != nl.adg.qwixx.data.Color.WHITE)
                .map(die -> nl.adg.qwixx.generated.model.Color.fromValue(die.color().name()))
                .toList();
    }

    private static Map<String, List<Integer>> mapBonusNumbers(GameState state) {
        if (!(state.variantData() instanceof LongoVariantData longo)) return null;
        Map<String, List<Integer>> result = new HashMap<>();
        longo.bonusNumbersPerPlayer().forEach((id, nums) -> result.put(id.toString(), nums));
        return result;
    }

    private static nl.adg.qwixx.generated.model.TurnState mapTurnState(TurnState turn) {
        if (turn == null) return null;
        nl.adg.qwixx.generated.model.TurnState dto = new nl.adg.qwixx.generated.model.TurnState(
                turn.activePlayerId().toString(),
                mapPhase(turn.phase()))
                .passivePlayerQueue(turn.passivePlayerQueue().stream()
                        .map(UUID::toString)
                        .toList());

        if (turn.currentRoll() != null) dto.setCurrentRoll(mapRollResult(turn.currentRoll()));

        ActiveTurnState ats = turn.activeTurnState();
        if (ats != null) {
            dto.setWhiteWhiteUsed(ats.whiteWhiteUsed());
            dto.setColorDieUsed(ats.colorDieUsed());
        }

        if (!turn.undoBuffer().isEmpty()) {
            Map<String, List<String>> pending = new java.util.HashMap<>();
            turn.undoBuffer().forEach((pid, rowMap) -> {
                List<String> cellIds = rowMap.values().stream()
                        .flatMap(java.util.Set::stream)
                        .toList();
                pending.put(pid.toString(), cellIds);
            });
            dto.setPendingCrosses(pending);
        }

        return dto;
    }

    private static nl.adg.qwixx.generated.model.TurnPhase mapPhase(TurnPhase phase) {
        return switch (phase) {
            case ROLL         -> nl.adg.qwixx.generated.model.TurnPhase.ROLL;
            case ACTIVE_MOVE  -> nl.adg.qwixx.generated.model.TurnPhase.ACTIVE_MOVE;
            case PASSIVE_MOVE -> nl.adg.qwixx.generated.model.TurnPhase.PASSIVE_MOVE;
            case LOCK_PENDING -> nl.adg.qwixx.generated.model.TurnPhase.LOCK_PENDING;
            case EVALUATE     -> nl.adg.qwixx.generated.model.TurnPhase.EVALUATE;
        };
    }

    private static nl.adg.qwixx.generated.model.RollResult mapRollResult(RollResult rr) {
        Map<String, Integer> coloredDice = new HashMap<>();
        rr.coloredDice().forEach((color, value) -> coloredDice.put(color.name(), value));
        return new nl.adg.qwixx.generated.model.RollResult(rr.white1(), rr.white2(), coloredDice);
    }

    private static Map<String, nl.adg.qwixx.generated.model.SheetLayout> mapSheetLayouts(GameState state) {
        Map<String, nl.adg.qwixx.generated.model.SheetLayout> result = new HashMap<>();
        state.sheetLayouts().forEach((playerId, layout) ->
                result.put(playerId.toString(), mapSheetLayout(layout)));
        return result;
    }

    static nl.adg.qwixx.generated.model.SheetLayout toSheetLayoutDto(SheetLayout layout) {
        return mapSheetLayout(layout);
    }

    private static nl.adg.qwixx.generated.model.SheetLayout mapSheetLayout(SheetLayout layout) {
        List<nl.adg.qwixx.generated.model.SheetRow> rows = layout.rows().stream()
                .map(GameStateMapper::mapRow)
                .toList();
        return new nl.adg.qwixx.generated.model.SheetLayout(rows);
    }

    private static nl.adg.qwixx.generated.model.SheetRow mapRow(Row row) {
        List<nl.adg.qwixx.generated.model.SheetCell> cells = row.cells().stream()
                .map(GameStateMapper::mapCell)
                .toList();
        return new nl.adg.qwixx.generated.model.SheetRow(row.id(), cells, mapLock(row.lock()));
    }

    private static nl.adg.qwixx.generated.model.SheetCell mapCell(Cell cell) {
        nl.adg.qwixx.generated.model.Color color =
                nl.adg.qwixx.generated.model.Color.fromValue(cell.color().name());
        List<nl.adg.qwixx.generated.model.CellTag> tags = cell.tags().stream()
                .map(GameStateMapper::mapTag)
                .toList();
        return new nl.adg.qwixx.generated.model.SheetCell(
                cell.id(), cell.position(), cell.displayValue(), color, cell.isClosingEligible(), tags);
    }

    private static nl.adg.qwixx.generated.model.LockConfig mapLock(LockCell lock) {
        if (lock == null) return null;
        nl.adg.qwixx.generated.model.Color color =
                nl.adg.qwixx.generated.model.Color.fromValue(lock.color().name());
        return new nl.adg.qwixx.generated.model.LockConfig(
                lock.id(), color, lock.minCrosses(), new ArrayList<>(lock.requiredCells()));
    }

    private static nl.adg.qwixx.generated.model.CellTag mapTag(CellTag tag) {
        return switch (tag) {
            case CellTag.ExtraBucket ignored ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.EXTRA_BUCKET);
            case CellTag.DoubleCross ignored ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.DOUBLE_CROSS);
            case CellTag.AutoCross a ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.AUTO_CROSS)
                            .target(a.target());
            case CellTag.BonusPoints b ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.BONUS_POINTS)
                            .amount(b.amount());
        };
    }

    private static List<nl.adg.qwixx.generated.model.RowClosureRequest> mapRowClosureRequests(GameState state) {
        return state.rowClosureRequests().stream()
                .map(req -> new nl.adg.qwixx.generated.model.RowClosureRequest(
                        req.playerName(),
                        nl.adg.qwixx.generated.model.Color.fromValue(req.rowColor().name())))
                .toList();
    }
}