package nl.adg.qwixx.web;

import nl.adg.qwixx.game.GameNotStartedException;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.SessionNotFoundException;
import nl.adg.qwixx.generated.api.GamestatesApiDelegate;
import nl.adg.qwixx.state.GameState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class GameStatesApiDelegateImpl implements GamestatesApiDelegate {

    @Override
    public ResponseEntity<nl.adg.qwixx.generated.model.GameState> getGameState(
            String sessionId, Long stateVersion) {
        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) throw new SessionNotFoundException(sessionId);

        GameState state = session.currentState();
        if (state == null) throw new GameNotStartedException(sessionId);

        if (stateVersion != null && stateVersion == state.version()) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }

        return ResponseEntity.ok(GameStateMapper.toDto(state, session));
    }
}