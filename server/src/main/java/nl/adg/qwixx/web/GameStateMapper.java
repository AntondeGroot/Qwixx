package nl.adg.qwixx.web;

import nl.adg.qwixx.data.RollResult;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;

import java.util.ArrayList;
import java.util.HashMap;
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
                                    p != null ? p.name() : id.toString());
                        })
                        .toList(),
                mapSheetProgress(board),
                mapTurnState(turn),
                state.gameOver(),
                state.version())
                .closedRows(mapClosedRows(board))
                .activeDiceColors(mapActiveDiceColors(board));
    }

    private static Map<String, nl.adg.qwixx.generated.model.SheetProgress> mapSheetProgress(BoardState board) {
        Map<String, nl.adg.qwixx.generated.model.SheetProgress> result = new HashMap<>();
        board.sheetProgress().forEach((playerId, sp) ->
                result.put(playerId.toString(), mapSheetProgress(sp)));
        return result;
    }

    private static nl.adg.qwixx.generated.model.SheetProgress mapSheetProgress(SheetProgress sp) {
        Map<String, nl.adg.qwixx.generated.model.RowState> rowStates = new HashMap<>();
        sp.rowStates().forEach((rowIndex, rs) ->
                rowStates.put(rowIndex.toString(), mapRowState(rs)));
        return new nl.adg.qwixx.generated.model.SheetProgress(rowStates, sp.punishments());
    }

    private static nl.adg.qwixx.generated.model.RowState mapRowState(RowState rs) {
        return new nl.adg.qwixx.generated.model.RowState(
                new ArrayList<>(rs.crossedCells()),
                rs.lockCrossed());
    }

    private static Map<String, String> mapClosedRows(BoardState board) {
        Map<String, String> result = new HashMap<>();
        board.closedRows().forEach((rowIndex, playerId) ->
                result.put(rowIndex.toString(), playerId.toString()));
        return result;
    }

    private static java.util.List<nl.adg.qwixx.generated.model.Color> mapActiveDiceColors(BoardState board) {
        return board.activeDice().stream()
                .filter(die -> die.color() != nl.adg.qwixx.data.Color.WHITE)
                .map(die -> nl.adg.qwixx.generated.model.Color.fromValue(die.color().name()))
                .toList();
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
}