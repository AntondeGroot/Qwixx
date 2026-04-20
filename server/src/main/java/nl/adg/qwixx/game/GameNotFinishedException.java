package nl.adg.qwixx.game;

public class GameNotFinishedException extends RuntimeException {
    public GameNotFinishedException(String sessionId) {
        super("game not finished: " + sessionId);
    }
}