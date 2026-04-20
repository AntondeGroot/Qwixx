package nl.adg.qwixx.game;

public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(String sessionId) {
        super("game session not found: " + sessionId);
    }
}