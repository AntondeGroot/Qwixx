package nl.adg.qwixx.web;

import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.exception.GameNotStartedException;
import nl.adg.qwixx.game.exception.SessionNotFoundException;
import nl.adg.qwixx.generated.api.GamestatesApiDelegate;
import nl.adg.qwixx.generated.model.GameState;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class GameStatesApiDelegateImpl implements GamestatesApiDelegate {

    // Game state changes with every player action; caching even for one poll cycle (2 s)
    // can hide lock-intent requests and other real-time updates from passive players.
    private static final CacheControl NO_STORE = CacheControl.noStore();

    @Override
    public ResponseEntity<GameState> getGameState(
            String sessionId, Long stateVersion) {
        GameSession session = require(sessionId);
        var state = session.currentState();
        if (state == null) throw new GameNotStartedException(sessionId);

        if (stateVersion != null && stateVersion == state.version()) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(NO_STORE)
                    .build();
        }

        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(GameStateMapper.toDto(state, session));
    }

    private GameSession require(String sessionId) {
        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) throw new SessionNotFoundException(sessionId);
        return session;
    }
}
