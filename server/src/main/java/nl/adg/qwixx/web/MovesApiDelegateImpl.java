package nl.adg.qwixx.web;

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
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.game.SessionNotFoundException;
import nl.adg.qwixx.generated.api.MovesApiDelegate;
import nl.adg.qwixx.generated.model.MoveRequest;
import nl.adg.qwixx.generated.model.MoveResponse;
import nl.adg.qwixx.generated.model.MoveResult;
import nl.adg.qwixx.rules.IllegalMoveException;
import nl.adg.qwixx.state.GameState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MovesApiDelegateImpl implements MovesApiDelegate {

    @Autowired
    private SseEmitterRegistry sseRegistry;

    @Override
    public ResponseEntity<MoveResponse> makeMove(String sessionId, String playerId,
            MoveRequest req) {
        GameSession session = require(sessionId);
        UUID pid = parsePlayerId(playerId);
        GameAction action = toAction(pid, req, session);

        Map<Integer, UUID> closedBefore = new HashMap<>(
                session.currentState().boardState().closedRows());

        GameState newState = session.applyAction(action,
                intermediate -> sseRegistry.emit(sessionId, intermediate, session));
        sseRegistry.emit(sessionId, newState, session);

        MoveResult result = newState.gameOver() ? MoveResult.GAME_OVER : MoveResult.ACCEPTED;

        MoveResponse response = new MoveResponse().result(result);

        if (action instanceof CrossCellAction cross) {
            response.crossedCellId(cross.cellId());
        }

        newState.boardState().closedRows().entrySet().stream()
                .filter(e -> !closedBefore.containsKey(e.getKey()))
                .findFirst()
                .ifPresent(e -> response
                        .lockClosed(true)
                        .lockedRowId(e.getKey().toString()));

        return ResponseEntity.ok(response);
    }

    private GameAction toAction(UUID pid, MoveRequest req, GameSession session) {
        return switch (req.getMoveType()) {
            case ROLL              -> new RollAction(pid);
            case CROSS_WHITE_WHITE -> new CrossCellAction(
                    pid, parseRowIndex(req.getRowId(), session, pid), req.getCellId(), DiceCombination.WHITE_WHITE);
            case CROSS_COLOR_DIE   -> new CrossCellAction(
                    pid, parseRowIndex(req.getRowId(), session, pid), req.getCellId(), DiceCombination.WHITE_COLOR);
            case TAKE_PUNISHMENT   -> new TakePunishmentAction(pid);
            case PASS              -> new EndTurnAction(pid);
            case DECLARE_LOCK_INTENT -> new DeclareLockIntentAction(pid, parseRowIndex(req.getRowId(), session, pid));
            case RESET_TURN        -> new ResetTurnAction(pid);
            case GIVE_UP           -> new GiveUpAction(pid);
            case UNDO_LAST_CROSS   -> new UndoLastCrossAction(pid);
            case CROSS_LOCK        -> throw new IllegalMoveException("CROSS_LOCK is no longer a valid move type");
        };
    }

    private static int parseRowIndex(String rowId, GameSession session, UUID playerId) {
        if (rowId == null) throw new IllegalMoveException("rowId is required for cross moves");
        SheetLayout layout = session.currentState().sheetLayouts().get(playerId);
        if (layout == null) throw new IllegalMoveException("no layout found for player: " + playerId);
        List<Row> rows = layout.rows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(rowId)) return i;
        }
        throw new IllegalMoveException("invalid rowId: " + rowId);
    }

    private static UUID parsePlayerId(String playerId) {
        try {
            return UUID.fromString(playerId);
        } catch (IllegalArgumentException e) {
            throw new IllegalMoveException("invalid playerId: " + playerId);
        }
    }

    private static GameSession require(String sessionId) {
        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) throw new SessionNotFoundException(sessionId);
        return session;
    }
}
