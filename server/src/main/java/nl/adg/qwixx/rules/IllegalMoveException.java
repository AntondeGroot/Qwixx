package nl.adg.qwixx.rules;

public class IllegalMoveException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;
    public IllegalMoveException(String message) {
        super(message);
    }
}