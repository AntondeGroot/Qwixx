package nl.adg.qwixx.web;

import nl.adg.qwixx.game.GameAlreadyStartedException;
import nl.adg.qwixx.game.GameNotStartedException;
import nl.adg.qwixx.game.SessionNotFoundException;
import nl.adg.qwixx.generated.model.ErrorResponse;
import nl.adg.qwixx.rules.IllegalMoveException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SessionNotFoundException ex) {
        return response(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({GameAlreadyStartedException.class, GameNotStartedException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex) {
        return response(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalMoveException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalMoveException ex) {
        return response(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(message, status.value()));
    }
}