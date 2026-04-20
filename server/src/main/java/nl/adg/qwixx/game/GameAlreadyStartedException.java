package nl.adg.qwixx.game;

public class GameAlreadyStartedException extends RuntimeException {
    public GameAlreadyStartedException(String sessionId) {
        super("game already started: " + sessionId);
    }
}