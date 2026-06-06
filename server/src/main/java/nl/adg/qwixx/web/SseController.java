package nl.adg.qwixx.web;

import jakarta.servlet.http.HttpServletResponse;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.SessionNotFoundException;
import nl.adg.qwixx.state.GameState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class SseController {

    @Autowired
    private SseEmitterRegistry registry;

    @GetMapping(value = "/gamestates/{sessionId}/stream",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable String sessionId, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) throw new SessionNotFoundException(sessionId);

        SseEmitter emitter = registry.subscribe(sessionId);

        // Push current state immediately so the client has it without waiting for the next action.
        GameState state = session.currentState();
        if (state != null) {
            try {
                emitter.send(SseEmitter.event()
                        .data(GameStateMapper.toDto(state, session),
                              MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }

        return emitter;
    }
}
