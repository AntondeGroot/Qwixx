package nl.adg.qwixx.web;

import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.state.GameState;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SseEmitterRegistry {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> sessions = new ConcurrentHashMap<>();

    SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        sessions.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(sessionId, emitter));
        emitter.onTimeout(()    -> remove(sessionId, emitter));
        emitter.onError(e       -> remove(sessionId, emitter));
        return emitter;
    }

    /** Convenience overload — maps the domain state to DTO and emits to all subscribers. */
    public void emit(String sessionId, GameState state, GameSession session) {
        emit(sessionId, GameStateMapper.toDto(state, session));
    }

    /** Emits any serialisable object (e.g. LobbyState) to all subscribers of the given key. */
    public void emitObject(String key, Object payload) {
        List<SseEmitter> list = sessions.get(key);
        if (list == null || list.isEmpty()) return;

        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .data(payload, MediaType.APPLICATION_JSON);

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(event);
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        list.removeAll(dead);
    }

    void emit(String sessionId, nl.adg.qwixx.generated.model.GameState dto) {
        List<SseEmitter> list = sessions.get(sessionId);
        if (list == null || list.isEmpty()) return;

        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .data(dto, MediaType.APPLICATION_JSON);

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(event);
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        list.removeAll(dead);
    }

    /** Sends a comment every 30 s to prevent nginx/proxies from closing idle connections. */
    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        SseEmitter.SseEventBuilder ping = SseEmitter.event().comment("ping");
        List<SseEmitter> dead = new ArrayList<>();
        sessions.values().stream()
                .flatMap(Collection::stream)
                .forEach(emitter -> {
                    try {
                        emitter.send(ping);
                    } catch (Exception e) {
                        dead.add(emitter);
                    }
                });
        dead.forEach(emitter ->
                sessions.values().forEach(list -> list.remove(emitter)));
    }

    private void remove(String sessionId, SseEmitter emitter) {
        List<SseEmitter> list = sessions.get(sessionId);
        if (list != null) list.remove(emitter);
    }
}