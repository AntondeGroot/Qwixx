package nl.adg.qwixx.web;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.QwixxGameOptions;
import nl.adg.qwixx.game.SessionNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Lobby state shared between all players while choosing options for the next game.
 * Any player can update the proposed options; all players subscribe via SSE.
 */
@RestController
@RequestMapping("/games")
public class LobbyController {

    record LobbyState(List<PlayerEntry> players, Map<String, Object> proposedOptions, int maxPlayers) {}
    record PlayerEntry(String id, String name) {}

    @Autowired
    private SseEmitterRegistry sseRegistry;

    static final String LOBBY_PREFIX = "lobby:";

    @GetMapping("/{sessionId}/lobby")
    public ResponseEntity<LobbyState> getLobby(@PathVariable String sessionId) {
        return ResponseEntity.ok(buildLobbyState(require(sessionId)));
    }

    @GetMapping(value = "/{sessionId}/lobby/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLobby(@PathVariable String sessionId, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        GameSession session = require(sessionId);
        SseEmitter emitter = sseRegistry.subscribe(LOBBY_PREFIX + sessionId);
        try {
            emitter.send(SseEmitter.event()
                    .data(buildLobbyState(session), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
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
        emitLobby(sessionId, session);
        return ResponseEntity.ok().build();
    }

    void emitLobby(String sessionId, GameSession session) {
        sseRegistry.emitObject(LOBBY_PREFIX + sessionId, buildLobbyState(session));
    }

    private LobbyState buildLobbyState(GameSession session) {
        List<PlayerEntry> players = session.humanPlayers().stream()
                .map(p -> new PlayerEntry(p.id().toString(), p.name()))
                .toList();
        // Current settings act as the baseline; proposed options override individual keys.
        Map<String, Object> effectiveOptions = new LinkedHashMap<>(QwixxGameOptions.toMap(session.settings()));
        effectiveOptions.putAll(session.proposedOptions());
        return new LobbyState(players, effectiveOptions, session.maxPlayers());
    }

    private GameSession require(String sessionId) {
        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) throw new SessionNotFoundException(sessionId);
        return session;
    }
}
