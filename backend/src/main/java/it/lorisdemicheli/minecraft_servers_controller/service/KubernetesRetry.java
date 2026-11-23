package it.lorisdemicheli.minecraft_servers_controller.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import io.kubernetes.client.openapi.ApiException;
import lombok.extern.slf4j.Slf4j;

/**
 * Retries a Kubernetes call that failed with a <em>transient</em> error (no answer, timeout, 429,
 * 500, 502, 503, 504), with exponential backoff and jitter. Validation, authorization, not found
 * and conflict errors are never retried.
 *
 * <p>Only use it for calls that are safe to repeat (reads, deletes, setting the replicas): a create
 * whose answer got lost may have been applied, and only the caller can check that.
 */
@Slf4j
final class KubernetesRetry {

  @FunctionalInterface
  interface ApiCall<T> {
    T run() throws ApiException;
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final int maxAttempts;
  private final Duration initialBackoff;
  private final Duration maxBackoff;
  private final Sleeper sleeper;
  private final DoubleSupplier random;

  KubernetesRetry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
    this(maxAttempts, initialBackoff, maxBackoff, d -> Thread.sleep(d),
        () -> ThreadLocalRandom.current().nextDouble());
  }

  KubernetesRetry(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Sleeper sleeper,
      DoubleSupplier random) {
    this.maxAttempts = Math.max(1, maxAttempts);
    this.initialBackoff = initialBackoff;
    this.maxBackoff = maxBackoff;
    this.sleeper = sleeper;
    this.random = random;
  }

  <T> T run(String what, ApiCall<T> call) throws ApiException {
    for (int attempt = 1;; attempt++) {
      try {
        return call.run();
      } catch (ApiException e) {
        if (!isTransient(e) || attempt >= maxAttempts) {
          throw e;
        }
        Duration delay = delay(attempt, e);
        log.debug("Transient Kubernetes error on {} (code {}), attempt {}/{}, retrying in {} ms",
            what, e.getCode(), attempt, maxAttempts, delay.toMillis());
        try {
          sleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  static boolean isTransient(ApiException e) {
    return switch (e.getCode()) {
      case 0, 429, 500, 502, 503, 504 -> true; // 0 = no HTTP answer (I/O error, timeout)
      default -> false;
    };
  }

  /**
   * "Equal jitter": half of the exponential step is fixed, half random, so retries of several
   * callers spread out but never fire immediately. A Retry-After header (429) wins, within the cap.
   */
  Duration delay(int attempt, ApiException e) {
    long cap = maxBackoff.toMillis();
    long step = Math.min(cap, initialBackoff.toMillis() << Math.min(attempt - 1, 20));
    long millis = step / 2 + (long) (random.getAsDouble() * (step / 2.0));
    Long retryAfter = retryAfterMillis(e);
    if (retryAfter != null) {
      millis = Math.max(millis, Math.min(retryAfter, cap));
    }
    return Duration.ofMillis(millis);
  }

  private static Long retryAfterMillis(ApiException e) {
    Map<String, List<String>> headers = e.getResponseHeaders();
    if (headers == null) {
      return null;
    }
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      if ("retry-after".equalsIgnoreCase(header.getKey()) && header.getValue() != null
          && !header.getValue().isEmpty()) {
        try {
          return Long.parseLong(header.getValue().get(0).trim()) * 1000;
        } catch (NumberFormatException ignored) {
          return null; // HTTP-date form: not worth parsing here
        }
      }
    }
    return null;
  }
}
