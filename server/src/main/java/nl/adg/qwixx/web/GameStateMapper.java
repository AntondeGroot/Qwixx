package nl.adg.qwixx.web;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.RollResult;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.generated.model.*;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.LongoVariantData;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;

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
                .closureNotifications(mapClosureNotifications(state, session))
                .pendingClosures(mapPendingClosures(state))
                .availableMoves(mapAvailableMoves(state, session))
                .seeOtherCards(session.settings().seeOtherCards())
                .doubleA(session.settings().doubleA())
                .doubleB(session.settings().doubleB());
    }

    @Nullable
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private static Map<String, List<nl.adg.qwixx.generated.model.AvailableMove>> mapAvailableMoves(
            GameState state, GameSession session) {
        if (state.turnState() == null || state.gameOver()) return null;

        Map<String, List<nl.adg.qwixx.generated.model.AvailableMove>> result = new HashMap<>();
        for (UUID playerId : state.players()) {
            List<GameAction> actions = session.rules().getValidActions(state, playerId);
            nl.adg.qwixx.state.SheetLayout layout = state.sheetLayout(playerId);
            List<nl.adg.qwixx.generated.model.AvailableMove> moves = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            // White+white moves first (preferred when a cell matches both WW and color die).
            for (GameAction action : actions) {
                if (!(action instanceof CrossCellAction cross)) continue;
                if (cross.combination() != DiceCombination.WHITE_WHITE) continue;
                Cell cell = findCellInLayout(layout, cross.rowIndex(), cross.cellId());
                nl.adg.qwixx.generated.model.MoveType moveType = isLuckyCrossCell(cell)
                        ? nl.adg.qwixx.generated.model.MoveType.CROSS_LUCKY_CROSS
                        : nl.adg.qwixx.generated.model.MoveType.CROSS_WHITE_WHITE;
                String key = cross.cellId() + ':' + moveType;
                if (seen.add(key)) {
                    moves.add(new nl.adg.qwixx.generated.model.AvailableMove(cross.cellId(), moveType));
                }
            }

            // Color die moves second.
            for (GameAction action : actions) {
                if (!(action instanceof CrossCellAction cross)) continue;
                if (cross.combination() != DiceCombination.WHITE_COLOR) continue;
                String key = cross.cellId() + ':' + nl.adg.qwixx.generated.model.MoveType.CROSS_COLOR_DIE;
                if (seen.add(key)) {
                    moves.add(new nl.adg.qwixx.generated.model.AvailableMove(
                            cross.cellId(), nl.adg.qwixx.generated.model.MoveType.CROSS_COLOR_DIE));
                }
            }

            result.put(playerId.toString(), moves);
        }
        return result;
    }

    @Nullable
    private static Cell findCellInLayout(nl.adg.qwixx.state.SheetLayout layout, int rowIndex, String cellId) {
        List<Row> rows = layout.rows();
        if (rowIndex < 0 || rowIndex >= rows.size()) return null;
        return rows.get(rowIndex).cells().stream()
                .filter(c -> c.id().equals(cellId))
                .findFirst()
                .orElse(null);
    }

    private static boolean isLuckyCrossCell(@Nullable Cell cell) {
        return cell != null && cell.tags() != null
                && cell.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyCross);
    }

    private static Map<String, nl.adg.qwixx.generated.model.SheetProgress> mapSheetProgress(GameState state) {
        Map<String, nl.adg.qwixx.generated.model.SheetProgress> result = new HashMap<>();
        state.boardState().sheetProgress().forEach((playerId, sp) -> {
            SheetLayout layout = state.sheetLayout(playerId);
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
        // Use every player's layout so that each client finds its own row ID in the map.
        // In SAME_CARDS mode all layouts share the same Row objects (same IDs), so
        // duplicate puts are harmless. In DIFFERENT_CARDS mode each player has unique row
        // IDs and only the matching entry makes isRowClosed() return true for that player.
        board.closedRows().forEach((rowIndex, closingPlayerId) ->
                state.sheetLayouts().forEach((layoutPlayerId, layout) ->
                        result.put(layout.rows().get(rowIndex).id(), closingPlayerId.toString())));
        return result;
    }

    private static List<nl.adg.qwixx.generated.model.Color> mapActiveDiceColors(BoardState board) {
        return board.activeDice().stream()
                .filter(die -> die.color() != nl.adg.qwixx.data.Color.WHITE)
                .map(die -> nl.adg.qwixx.generated.model.Color.fromValue(die.color().name()))
                .toList();
    }

    @Nullable
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private static Map<String, List<Integer>> mapBonusNumbers(GameState state) {
        if (!(state.variantData() instanceof LongoVariantData longo)) return null;
        Map<String, List<Integer>> result = new HashMap<>();
        longo.bonusNumbersPerPlayer().forEach((id, nums) -> result.put(id.toString(), nums));
        return result;
    }

    @Nullable
    private static nl.adg.qwixx.generated.model.TurnState mapTurnState(@Nullable TurnState turn) {
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
            if (ats.luckyNumberUsed()) dto.setLuckyNumberUsed(true);
        }

        if (!turn.undoBuffer().isEmpty()) {
            Map<String, List<String>> pending = new HashMap<>();
            turn.undoBuffer().forEach((pid, rowMap) -> {
                List<String> cellIds = rowMap.values().stream()
                        .flatMap(Set::stream)
                        .toList();
                pending.put(pid.toString(), cellIds);
            });
            dto.setPendingCrosses(pending);
        }

        if (!turn.xChangeEffectiveWW().isEmpty()) {
            Map<String, Integer> effWW = new HashMap<>();
            turn.xChangeEffectiveWW().forEach((pid, v) -> effWW.put(pid.toString(), v));
            dto.setEffectiveWhiteWhite(effWW);
        }

        if (!turn.luckyCrossUsed().isEmpty()) {
            dto.setLuckyCrossUsed(turn.luckyCrossUsed().stream().map(UUID::toString).toList());
        }

        return dto;
    }

    private static nl.adg.qwixx.generated.model.TurnPhase mapPhase(TurnPhase phase) {
        return switch (phase) {
            case ROLL         -> nl.adg.qwixx.generated.model.TurnPhase.ROLL;
            case ACTIVE_MOVE  -> nl.adg.qwixx.generated.model.TurnPhase.ACTIVE_MOVE;
            case PASSIVE_MOVE -> nl.adg.qwixx.generated.model.TurnPhase.PASSIVE_MOVE;
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
        List<Row> domainRows = layout.rows();
        List<SheetRow> rows = new ArrayList<>();
        for (Row row : domainRows) {
            rows.add(mapRow(row, domainRows));
        }
        return new nl.adg.qwixx.generated.model.SheetLayout(rows);
    }

    private static SheetRow mapRow(Row row, List<Row> allRows) {
        List<SheetCell> cells = row.cells().stream()
                .map(GameStateMapper::mapCell)
                .toList();
        SheetRow dto = new SheetRow(row.id(), cells)
                .lock(mapLock(row.lock()))
                .bonusRow(row.isBonusRow())
                .luckyRow(row.isLuckyRow())
                .bonusBar(row.isBonusBar())
                .bonusBStrip(row.isBonusBStrip())
                .luckyTarget(row.isLuckyRow() ? row.luckyTarget() : null);
        if (row.isBonusRow()) {
            int upper = row.upperRowIndex();
            int lower = row.lowerRowIndex();
            if (upper >= 0) dto.setUpperNeighbourRowId(allRows.get(upper).id());
            if (lower >= 0) dto.setLowerNeighbourRowId(allRows.get(lower).id());
        }
        return dto;
    }

    private static SheetCell mapCell(Cell cell) {
        nl.adg.qwixx.generated.model.Color color =
                nl.adg.qwixx.generated.model.Color.fromValue(cell.color().name());
        List<nl.adg.qwixx.generated.model.CellTag> tags = cell.tags().stream()
                .map(GameStateMapper::mapTag)
                .toList();
        return new SheetCell(
                cell.id(), cell.position(), cell.displayValue(), color, cell.isClosingEligible(), tags);
    }

    @Nullable
    private static LockConfig mapLock(@Nullable LockCell lock) {
        if (lock == null) return null;
        nl.adg.qwixx.generated.model.Color color =
                nl.adg.qwixx.generated.model.Color.fromValue(lock.color().name());
        return new LockConfig(
                lock.id(), color, lock.minCrosses(), new ArrayList<>(lock.closingCells()));
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
            case CellTag.DoubleTwin dt ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.DOUBLE_TWIN)
                            .target(dt.primary());
            case CellTag.BonusBox ignored ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.BONUS_BOX);
            case CellTag.BonusB b ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.BONUS_B)
                            .bonusKind(nl.adg.qwixx.generated.model.CellTag.BonusKindEnum.fromValue(b.kind().name()));
            case CellTag.BonusPoints b ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.BONUS_POINTS)
                            .amount(b.amount());
            case CellTag.SecondaryColor sc ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.SECONDARY_COLOR)
                            .secondaryColor(sc.color().name());
            case CellTag.XChange xc ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.X_CHANGE)
                            .valueA(xc.a())
                            .valueB(xc.b());
            case CellTag.LuckyNumber ln ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.LUCKY_NUMBER)
                            .amount(ln.bonusPoints());
            case CellTag.LuckyCross ignored ->
                    new nl.adg.qwixx.generated.model.CellTag(
                            nl.adg.qwixx.generated.model.CellTag.TypeEnum.LUCKY_CROSS);
        };
    }

    private static Map<String, List<String>> mapPendingClosures(GameState state) {
        Map<String, List<String>> result = new HashMap<>();
        state.pendingClosures().forEach((rowIndex, declarantIds) -> {
            UUID any = declarantIds.iterator().next();
            SheetLayout layout = state.sheetLayouts().get(any);
            if (layout != null) {
                result.put(layout.rows().get(rowIndex).id(),
                        declarantIds.stream().map(UUID::toString).toList());
            }
        });
        return result;
    }

    private static List<ClosureNotification> mapClosureNotifications(
            GameState state, GameSession session) {
        Map<UUID, Player> playerIndex = session.players().stream()
                .collect(Collectors.toMap(Player::id, Function.identity()));
        return state.closureNotifications().stream()
                .map(req -> {
                    Player p = playerIndex.get(req.playerId());
                    String name = p != null ? p.name() : req.playerId().toString();
                    return new ClosureNotification(
                            name,
                            nl.adg.qwixx.generated.model.Color.fromValue(req.rowColor().name()));
                })
                .toList();
    }
}
