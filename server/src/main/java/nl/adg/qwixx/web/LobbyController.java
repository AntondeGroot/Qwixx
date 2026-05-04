package nl.adg.qwixx.web;

import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.SessionNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Lobby state shared between all players while choosing options for the next game.
 * Any player can update the proposed options; all players poll this to stay in sync.
 */
@RestController
@RequestMapping("/games")
public class LobbyController {

    record LobbyState(List<PlayerEntry> players, Map<String, Object> proposedOptions) {}
    record PlayerEntry(String id, String name) {}

    @GetMapping("/{sessionId}/lobby")
    public ResponseEntity<LobbyState> getLobby(@PathVariable String sessionId) {
        GameSession session = require(sessionId);
        List<PlayerEntry> players = session.players().stream()
                .map(p -> new PlayerEntry(p.id().toString(), p.name()))
                .toList();
        return ResponseEntity.ok(new LobbyState(players, session.proposedOptions()));
    }

    @PutMapping("/{sessionId}/lobby")
    public ResponseEntity<Void> updateLobby(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {

        GameSession session = require(sessionId);
        @SuppressWarnings("unchecked")
        Map<String, Object> opts = body.containsKey("gameOptions")
                ? (Map<String, Object>) body.get("gameOptions")
                : body;
        session.setProposedOptions(opts);
        return ResponseEntity.ok().build();
    }

    private GameSession require(String sessionId) {
        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) throw new SessionNotFoundException(sessionId);
        return session;
    }
}
