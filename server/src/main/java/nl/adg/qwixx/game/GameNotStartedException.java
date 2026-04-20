package nl.adg.qwixx.game;

public class GameNotStartedException extends RuntimeException {
    public GameNotStartedException(String sessionId) {
        super("game not started: " + sessionId);
    }
}