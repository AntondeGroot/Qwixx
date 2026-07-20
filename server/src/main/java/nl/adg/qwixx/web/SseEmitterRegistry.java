package nl.adg.qwixx.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.state.GameState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseEmitterRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SseEmitterRegistry.class);

    @SuppressWarnings("PMD.LooseCoupling")
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> sessions = new ConcurrentHashMap<>();

    /**
     * One single-thread sender per session. Callers map the state to a DTO on their own thread (under
     * the game lock, so the snapshot is consistent) and hand off the actual socket write here, so a
     * slow client can never stall game logic. Single-threaded per session keeps each client's frames
     * in order; the client also de-dupes by {@code GameState.version} as a safety net.
     */
    private final Map<String, ExecutorService> senders = new ConcurrentHashMap<>();

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
        broadcast(key, SseEmitter.event().data(payload, MediaType.APPLICATION_JSON));
    }

    void emit(String sessionId, nl.adg.qwixx.generated.model.GameState dto) {
        broadcast(sessionId, SseEmitter.event().data(dto, MediaType.APPLICATION_JSON));
    }

    /** Sends a comment every 30 s to prevent nginx/proxies from closing idle connections. */
    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        SseEmitter.SseEventBuilder ping = SseEmitter.event().comment("ping");
        sessions.keySet().forEach(key -> broadcast(key, ping));
    }

    private void broadcast(String key, SseEmitter.SseEventBuilder event) {
        List<SseEmitter> list = sessions.get(key);
        if (list == null || list.isEmpty()) return;
        // Dispatch the socket writes off the caller's thread (and off any game-session lock it holds).
        try {
            senderFor(key).execute(() -> deliver(key, event));
        } catch (RejectedExecutionException e) {
            // Sender was shut down (session ended) between the check and here — nothing to deliver.
            logger.debug("SSE sender for {} already shut down; dropping frame", key);
        }
    }

    private void deliver(String key, SseEmitter.SseEventBuilder event) {
        List<SseEmitter> list = sessions.get(key);
        if (list == null || list.isEmpty()) return;
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

    private ExecutorService senderFor(String key) {
        return senders.computeIfAbsent(key, k -> Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sse-sender-" + k);
            t.setDaemon(true);
            return t;
        }));
    }

    /** Number of active SSE subscribers currently connected for a session key. */
    public int subscriberCount(String sessionId) {
        List<SseEmitter> list = sessions.get(sessionId);
        return list == null ? 0 : list.size();
    }

    // shutdown() (non-blocking, lets queued frames flush) is the right cleanup here; PMD's
    // CloseResource only recognizes close(), which would block the caller until termination.
    @SuppressWarnings("PMD.CloseResource")
    void completeAll(String sessionId) {
        List<SseEmitter> list = sessions.remove(sessionId);
        if (list != null) list.forEach(SseEmitter::complete);
        ExecutorService sender = senders.remove(sessionId);
        if (sender != null) sender.shutdown(); // lets any queued frames flush, then stops the thread
    }

    private void remove(String sessionId, SseEmitter emitter) {
        List<SseEmitter> list = sessions.get(sessionId);
        if (list != null) list.remove(emitter);
    }
}
