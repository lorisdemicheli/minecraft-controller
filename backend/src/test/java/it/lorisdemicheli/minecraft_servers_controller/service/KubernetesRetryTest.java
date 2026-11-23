package it.lorisdemicheli.minecraft_servers_controller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.kubernetes.client.openapi.ApiException;

class KubernetesRetryTest {

  private final List<Duration> sleeps = new ArrayList<>();
  private final KubernetesRetry retry = new KubernetesRetry(4, Duration.ofMillis(100),
      Duration.ofMillis(1000), sleeps::add, () -> 0.5);

  private static ApiException error(int code) {
    return new ApiException(code, "boom");
  }

  @Test
  void retriesTransientErrorsWithGrowingBackoff() throws ApiException {
    AtomicInteger calls = new AtomicInteger();
    String result = retry.run("x", () -> {
      if (calls.incrementAndGet() < 3) {
        throw error(503);
      }
      return "ok";
    });
    assertEquals("ok", result);
    assertEquals(3, calls.get());
    // equal jitter with random=0.5: 3/4 of the exponential step
    assertEquals(List.of(Duration.ofMillis(75), Duration.ofMillis(150)), sleeps);
  }

  @Test
  void transientCodes() {
    for (int code : new int[] {0, 429, 500, 502, 503, 504}) {
      assertTrue(KubernetesRetry.isTransient(error(code)), "code " + code);
    }
    for (int code : new int[] {400, 401, 403, 404, 409, 422}) {
      assertTrue(!KubernetesRetry.isTransient(error(code)), "code " + code);
    }
  }

  @Test
  void neverRetriesValidationAuthorizationOrNotFound() {
    for (int code : new int[] {400, 401, 403, 404, 409, 422}) {
      AtomicInteger calls = new AtomicInteger();
      ApiException e = assertThrows(ApiException.class, () -> retry.run("x", () -> {
        calls.incrementAndGet();
        throw error(code);
      }));
      assertEquals(code, e.getCode());
      assertEquals(1, calls.get(), "code " + code);
    }
    assertTrue(sleeps.isEmpty());
  }

  @Test
  void givesUpAfterMaxAttemptsWithTheLastError() {
    AtomicInteger calls = new AtomicInteger();
    ApiException last = error(500);
    ApiException e = assertThrows(ApiException.class, () -> retry.run("x", () -> {
      calls.incrementAndGet();
      throw last;
    }));
    assertSame(last, e);
    assertEquals(4, calls.get());
    assertEquals(3, sleeps.size());
  }

  @Test
  void backoffIsCapped() {
    for (int attempt = 1; attempt < 30; attempt++) {
      assertTrue(retry.delay(attempt, error(500)).toMillis() <= 1000);
    }
  }

  @Test
  void retryAfterHeaderIsHonouredWithinTheCap() {
    ApiException throttled =
        new ApiException("slow down", 429, Map.of("Retry-After", List.of("1")), "");
    assertEquals(Duration.ofMillis(1000), retry.delay(1, throttled));
  }

  @Test
  void interruptionStopsRetrying() {
    KubernetesRetry interrupted = new KubernetesRetry(4, Duration.ofMillis(1), Duration.ofMillis(1),
        d -> {
          throw new InterruptedException();
        }, () -> 0);
    AtomicInteger calls = new AtomicInteger();
    assertThrows(ApiException.class, () -> interrupted.run("x", () -> {
      calls.incrementAndGet();
      throw error(503);
    }));
    assertEquals(1, calls.get());
    assertTrue(Thread.interrupted()); // flag restored (and cleared for the next tests)
  }
}
