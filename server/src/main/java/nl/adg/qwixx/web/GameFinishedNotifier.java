package nl.adg.qwixx.web;

import jakarta.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class GameFinishedNotifier {

    private static final Logger logger = LoggerFactory.getLogger(GameFinishedNotifier.class);

    @SuppressWarnings("PMD.LooseCoupling")
    private final ConcurrentHashMap<String, String> callbacks = new ConcurrentHashMap<>();
    private final RestTemplate restTemplate = new RestTemplate();

    public void register(String sessionId, @Nullable String callbackUrl) {
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            callbacks.put(sessionId, callbackUrl);
        }
    }

    public void checkAndNotify(String sessionId, GameSession session) {
        if (session.status() != SessionStatus.FINISHED) return;
        String url = callbacks.remove(sessionId);
        if (url == null) return;
        try {
            restTemplate.postForObject(url, null, Void.class);
            logger.info("Game-finished callback sent for session {}", sessionId);
        } catch (Exception e) {
            logger.warn("Game-finished callback failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}
