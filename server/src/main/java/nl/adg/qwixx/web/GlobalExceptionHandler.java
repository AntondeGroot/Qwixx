package nl.adg.qwixx.web;

import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import nl.adg.qwixx.game.exception.GameAlreadyStartedException;
import nl.adg.qwixx.game.exception.GameNotFinishedException;
import nl.adg.qwixx.game.exception.GameNotStartedException;
import nl.adg.qwixx.game.exception.SessionNotFoundException;
import nl.adg.qwixx.generated.model.ErrorResponseDto;
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
    public ResponseEntity<ErrorResponseDto> handleNotFound(SessionNotFoundException ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler({GameAlreadyStartedException.class, GameNotStartedException.class,
                        GameNotFinishedException.class})
    public ResponseEntity<ErrorResponseDto> handleConflict(RuntimeException ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalMoveException.class)
    public ResponseEntity<ErrorResponseDto> handleBadRequest(IllegalMoveException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponseDto> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpected(Exception ex, HttpServletRequest request) throws Exception {
        // Let Spring Boot handle framework exceptions (e.g. NoResourceFoundException → SPA fallback)
        if (ex instanceof org.springframework.web.ErrorResponse) throw ex;
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request);
    }

    private static ResponseEntity<ErrorResponseDto> response(
            HttpStatus status, @Nullable String message, HttpServletRequest request) {
        // SSE connections have text/event-stream locked in — no JSON converter available.
        // Return a body-less status so Spring doesn't try to serialize ErrorResponseDto as SSE.
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return ResponseEntity.status(status).build();
        }
        String body = Objects.requireNonNullElse(message, status.getReasonPhrase());
        return ResponseEntity.status(status).body(new ErrorResponseDto(body, status.value()));
    }
}
