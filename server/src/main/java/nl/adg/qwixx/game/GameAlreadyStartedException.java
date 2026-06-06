package nl.adg.qwixx.game;

public class GameAlreadyStartedException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;
    public GameAlreadyStartedException(String sessionId) {
        super("game already started: " + sessionId);
    }
    public GameAlreadyStartedException(String sessionId, Throwable cause) {
        super("game already started: " + sessionId, cause);
    }
}
