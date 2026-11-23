package it.lorisdemicheli.minecraft_servers_controller.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.exception.TooManyRequestsException;

/**
 * Slows down password guessing: after {@code loginMaxAttempts} failed logins from one client
 * within {@code loginWindow}, further attempts get 429 until the window expires. In memory (one
 * replica, nothing to persist); a successful login resets the client's counter.
 */
@Component
public class LoginAttemptLimiter {

  private static final int MAX_TRACKED_CLIENTS = 10_000;

  private record Window(Instant start, int failures) {
  }

  private final ConcurrentHashMap<String, Window> failures = new ConcurrentHashMap<>();
  private final int maxAttempts;
  private final Duration window;
  private final Clock clock;

  public LoginAttemptLimiter(MinecraftServerOptions options, Clock clock) {
    this.maxAttempts = options.getSecurity().getLoginMaxAttempts();
    this.window = options.getSecurity().getLoginWindow();
    this.clock = clock;
  }

  /** @throws TooManyRequestsException if the client must wait before trying again */
  public void check(String client) {
    Window w = failures.get(client);
    if (w != null && !expired(w) && w.failures() >= maxAttempts) {
      long retryAfter = Duration.between(clock.instant(), w.start().plus(window)).toSeconds() + 1;
      throw new TooManyRequestsException(
          "Too many failed logins: try again in " + retryAfter + " seconds");
    }
  }

  public void failed(String client) {
    if (failures.size() > MAX_TRACKED_CLIENTS) {
      failures.values().removeIf(this::expired);
    }
    failures.compute(client, (k, w) -> w == null || expired(w)
        ? new Window(clock.instant(), 1)
        : new Window(w.start(), w.failures() + 1));
  }

  public void succeeded(String client) {
    failures.remove(client);
  }

  /** Seconds until the client may try again (for the Retry-After header), 0 if it may now. */
  public long retryAfterSeconds(String client) {
    Window w = failures.get(client);
    if (w == null || expired(w) || w.failures() < maxAttempts) {
      return 0;
    }
    return Duration.between(clock.instant(), w.start().plus(window)).toSeconds() + 1;
  }

  private boolean expired(Window w) {
    return !w.start().plus(window).isAfter(clock.instant());
  }
}
