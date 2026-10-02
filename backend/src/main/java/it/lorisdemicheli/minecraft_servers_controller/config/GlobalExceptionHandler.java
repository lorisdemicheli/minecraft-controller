package it.lorisdemicheli.minecraft_servers_controller.config;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceAlreadyExistsException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler({ResourceAlreadyExistsException.class, ConflictException.class})
  public ResponseEntity<Object> handleConflict(RuntimeException ex) {
    return error(HttpStatus.CONFLICT, ex);
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex) {
    return error(HttpStatus.NOT_FOUND, ex);
  }

  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<Object> handleInvalidRequest(InvalidRequestException ex) {
    return error(HttpStatus.BAD_REQUEST, ex);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Object> handleTooLarge(MaxUploadSizeExceededException ex) {
    return error(HttpStatus.valueOf(413), ex);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
    // Disconnessione client su SSE: non loggare, non rispondere
    if (isClientDisconnectException(ex)) {
      return null; // Spring ignorerà la risposta
    }
    log.error("Errore interno: ", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
        Map.of("error", "Errore interno del server", "message", String.valueOf(ex.getMessage())));
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(Map.of("error", "Credenziali errate"));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Accesso negato"));
  }

  private static ResponseEntity<Object> error(HttpStatus status, Exception ex) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("timestamp", LocalDateTime.now());
    body.put("status", status.value());
    body.put("error", status.getReasonPhrase());
    if (ex.getMessage() != null) {
      body.put("message", ex.getMessage());
    }
    return new ResponseEntity<>(body, status);
  }

  private boolean isClientDisconnectException(Throwable ex) {
    if (ex instanceof AsyncRequestNotUsableException) {
      return true;
    }
    Throwable cause = ex.getCause();
    while (cause != null) {
      if (cause instanceof IOException ioEx) {
        String msg = ioEx.getMessage();
        if (msg != null && (msg.contains("Connection reset") || msg.contains("Broken pipe"))) {
          return true;
        }
      }
      cause = cause.getCause();
    }
    return false;
  }
}
