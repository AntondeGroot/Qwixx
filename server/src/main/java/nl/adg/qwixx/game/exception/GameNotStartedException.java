package nl.adg.qwixx.game.exception;

public class GameNotStartedException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;
    public GameNotStartedException(String sessionId) {
        super("game not started: " + sessionId);
    }
}
