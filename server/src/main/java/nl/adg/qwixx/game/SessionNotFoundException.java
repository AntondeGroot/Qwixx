package nl.adg.qwixx.game;

public class SessionNotFoundException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;
    public SessionNotFoundException(String sessionId) {
        super("game session not found: " + sessionId);
    }
    public SessionNotFoundException(String sessionId, Throwable cause) {
        super("game session not found: " + sessionId, cause);
    }
}
