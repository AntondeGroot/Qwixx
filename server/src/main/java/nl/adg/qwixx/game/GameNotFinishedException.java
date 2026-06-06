package nl.adg.qwixx.game;

public class GameNotFinishedException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;
    public GameNotFinishedException(String sessionId) {
        super("game not finished: " + sessionId);
    }
}