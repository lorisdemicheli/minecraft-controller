package it.lorisdemicheli.minecraft_servers_controller.config;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * The one JSON shape of every error answer of the API:
 *
 * <pre>
 * {"timestamp": "...", "status": 404, "error": "Not Found", "message": "Server x not found"}
 * </pre>
 *
 * {@code message} is always written for the client: no stack traces, no Kubernetes response
 * bodies, no file system paths outside the server. 5xx answers carry an {@code errorId} that is
 * also in the server log.
 */
public final class ApiErrors {

  private ApiErrors() {}

  public static Map<String, Object> body(HttpStatusCode status, String message, String errorId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("status", status.value());
    body.put("error", reason(status));
    if (message != null && !message.isBlank()) {
      body.put("message", message);
    }
    if (errorId != null) {
      body.put("errorId", errorId);
    }
    return body;
  }

  public static ResponseEntity<Object> response(HttpStatusCode status, String message) {
    return response(status, message, null);
  }

  public static ResponseEntity<Object> response(HttpStatusCode status, String message,
      String errorId) {
    // a preset content type skips negotiation: errors of SSE endpoints are JSON too
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
        .body(body(status, message, errorId));
  }

  /** For filters and security handlers, which run outside of Spring MVC. */
  public static void write(HttpServletResponse response, ObjectMapper mapper,
      HttpStatusCode status, String message) throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    mapper.writeValue(response.getOutputStream(), body(status, message, null));
  }

  private static String reason(HttpStatusCode status) {
    var resolved = org.springframework.http.HttpStatus.resolve(status.value());
    return resolved == null ? "Error" : resolved.getReasonPhrase();
  }
}
