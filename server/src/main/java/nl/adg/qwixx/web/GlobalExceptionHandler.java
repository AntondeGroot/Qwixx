package nl.adg.qwixx.web;

import jakarta.servlet.http.HttpServletRequest;
import nl.adg.qwixx.game.exception.GameAlreadyStartedException;
import nl.adg.qwixx.game.exception.GameNotFinishedException;
import nl.adg.qwixx.game.exception.GameNotStartedException;
import nl.adg.qwixx.game.exception.SessionNotFoundException;
import nl.adg.qwixx.generated.model.ErrorResponse;
import nl.adg.qwixx.rules.IllegalMoveException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SessionNotFoundException ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler({GameAlreadyStartedException.class, GameNotStartedException.class,
                        GameNotFinishedException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalMoveException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalMoveException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) throws Exception {
        // Let Spring Boot handle framework exceptions (e.g. NoResourceFoundException → SPA fallback)
        if (ex instanceof org.springframework.web.ErrorResponse) throw ex;
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request);
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String message, HttpServletRequest request) {
        // SSE connections have text/event-stream locked in — no JSON converter available.
        // Return a body-less status so Spring doesn't try to serialize ErrorResponse as SSE.
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return ResponseEntity.status(status).build();
        }
        return ResponseEntity.status(status).body(new ErrorResponse(message, status.value()));
    }
}
