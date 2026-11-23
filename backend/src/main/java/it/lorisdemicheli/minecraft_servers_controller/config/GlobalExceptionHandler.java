package it.lorisdemicheli.minecraft_servers_controller.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.KubernetesUnavailableException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceAlreadyExistsException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ServerException;
import it.lorisdemicheli.minecraft_servers_controller.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps exceptions to HTTP answers in the {@link ApiErrors} format.
 *
 * <table>
 * <tr><td>400</td><td>{@link InvalidRequestException}, malformed requests</td></tr>
 * <tr><td>401</td><td>bad credentials</td></tr>
 * <tr><td>403</td><td>access denied</td></tr>
 * <tr><td>404</td><td>{@link ResourceNotFoundException}</td></tr>
 * <tr><td>409</td><td>{@link ConflictException}, {@link ResourceAlreadyExistsException}</td></tr>
 * <tr><td>413</td><td>upload too large</td></tr>
 * <tr><td>429</td><td>{@link TooManyRequestsException}</td></tr>
 * <tr><td>500</td><td>{@link ServerException} (its message), anything else (generic message)</td></tr>
 * <tr><td>503</td><td>{@link KubernetesUnavailableException}</td></tr>
 * </table>
 *
 * Client errors are logged at debug level, server errors with their stack trace and an id that is
 * returned to the client.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler({ResourceAlreadyExistsException.class, ConflictException.class})
  public ResponseEntity<Object> handleConflict(RuntimeException ex) {
    return client(HttpStatus.CONFLICT, ex);
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex) {
    return client(HttpStatus.NOT_FOUND, ex);
  }

  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<Object> handleInvalidRequest(InvalidRequestException ex) {
    return client(HttpStatus.BAD_REQUEST, ex);
  }

  @ExceptionHandler(TypeMismatchException.class)
  public ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex) {
    String name = ex.getPropertyName() == null ? "parameter" : ex.getPropertyName();
    log.debug("Bad request: {}", ex.getMessage());
    return ApiErrors.response(HttpStatus.BAD_REQUEST, "Invalid value for " + name);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Object> handleTooLarge(MaxUploadSizeExceededException ex) {
    log.debug("Upload too large: {}", ex.getMessage());
    return ApiErrors.response(HttpStatus.PAYLOAD_TOO_LARGE, "The file is too large");
  }

  @ExceptionHandler(TooManyRequestsException.class)
  public ResponseEntity<Object> handleTooManyRequests(TooManyRequestsException ex) {
    return client(HttpStatus.TOO_MANY_REQUESTS, ex);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<Object> handleAuthentication(AuthenticationException ex) {
    return ApiErrors.response(HttpStatus.UNAUTHORIZED, "Invalid credentials");
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
    return ApiErrors.response(HttpStatus.FORBIDDEN, "Access denied");
  }

  @ExceptionHandler(KubernetesUnavailableException.class)
  public ResponseEntity<Object> handleUnavailable(KubernetesUnavailableException ex) {
    log.warn("{}", ex.getMessage());
    return ApiErrors.response(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
  }

  /** Messages of {@link ServerException} are written for the client; the cause is only logged. */
  @ExceptionHandler(ServerException.class)
  public ResponseEntity<Object> handleServerException(ServerException ex) {
    String errorId = errorId();
    log.error("Operation failed [errorId={}]: {}", errorId, ex.getMessage(), ex);
    return ApiErrors.response(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), errorId);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleGenericException(Exception ex) {
    if (isClientDisconnectException(ex)) {
      log.trace("Client disconnected: {}", ex.getMessage());
      return null; // nothing can be written any more
    }
    // Spring MVC's own exceptions (missing parameter, unreadable body, 404 path, 405...)
    if (ex instanceof ErrorResponse spring && spring.getStatusCode().is4xxClientError()) {
      log.debug("Bad request: {}", ex.getMessage());
      String detail = spring.getBody().getDetail();
      return ApiErrors.response(spring.getStatusCode(), detail);
    }
    String errorId = errorId();
    if (ex instanceof UncheckedIOException) {
      log.error("File system error [errorId={}]", errorId, ex);
      return ApiErrors.response(HttpStatus.INTERNAL_SERVER_ERROR,
          "File system error, see the controller log", errorId);
    }
    log.error("Unexpected error [errorId={}]", errorId, ex);
    return ApiErrors.response(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", errorId);
  }

  private static ResponseEntity<Object> client(HttpStatus status, Exception ex) {
    log.debug("{}: {}", status.value(), ex.getMessage());
    return ApiErrors.response(status, ex.getMessage());
  }

  private static String errorId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private static boolean isClientDisconnectException(Throwable ex) {
    if (ex instanceof AsyncRequestNotUsableException) {
      return true;
    }
    Throwable cause = ex;
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
