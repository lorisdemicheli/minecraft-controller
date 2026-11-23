package it.lorisdemicheli.minecraft_servers_controller.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.lorisdemicheli.minecraft_servers_controller.config.ApiErrors;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.security.JwtComponent;
import it.lorisdemicheli.minecraft_servers_controller.security.LoginAttemptLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Login of the single admin. Failed attempts are throttled per client address (behind the Ingress
 * the address comes from X-Forwarded-For, trusted only from private networks). Passwords and
 * tokens are never logged.
 */
@Slf4j
@RestController
@Tag(name = "AUTH")
@RequiredArgsConstructor
public class AuthController {

  private final AuthenticationManager authenticationManager;
  private final JwtComponent jwt;
  private final LoginAttemptLimiter limiter;

  @PostMapping(path = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest http) {
    String client = http.getRemoteAddr();
    long retryAfter = limiter.retryAfterSeconds(client);
    if (retryAfter > 0) {
      log.warn("Login throttled: client={}", client);
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter))
          .contentType(MediaType.APPLICATION_JSON)
          .body(ApiErrors.body(HttpStatus.TOO_MANY_REQUESTS,
              "Too many failed logins: try again in " + retryAfter + " seconds", null));
    }
    if (request == null || isBlank(request.username()) || isBlank(request.password())) {
      throw new InvalidRequestException("username and password are required");
    }
    try {
      Authentication auth = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.username(), request.password()));
      limiter.succeeded(client);
      log.info("Login succeeded: user={} client={}", safe(auth.getName()), client);
      return ResponseEntity.ok(new LoginResponse(jwt.generateToken(auth.getName())));
    } catch (AuthenticationException e) {
      limiter.failed(client);
      log.warn("Login failed: user={} client={}", safe(request.username()), client);
      return ApiErrors.response(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }
  }

  /** Usernames come from the client: no line breaks or huge values in the log. */
  private static String safe(String value) {
    String clean = value.replaceAll("[\\p{Cntrl}]", "?");
    return clean.length() > 64 ? clean.substring(0, 64) + "..." : clean;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  public record LoginRequest(String username, String password) {
    /** Never print the password (records include every component in toString). */
    @Override
    public String toString() {
      return "LoginRequest[username=" + username + "]";
    }
  }

  public record LoginResponse(String token) {
    @Override
    public String toString() {
      return "LoginResponse[token=***]";
    }
  }
}
