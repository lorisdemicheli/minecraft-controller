package it.lorisdemicheli.minecraft_servers_controller.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.config.TestOptions;
import it.lorisdemicheli.minecraft_servers_controller.exception.TooManyRequestsException;

class LoginAttemptLimiterTest {

  Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
  Clock clock = new Clock() {
    @Override
    public Instant instant() {
      return now[0];
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  };

  LoginAttemptLimiter limiter() {
    MinecraftServerOptions o = TestOptions.valid();
    o.getSecurity().setLoginMaxAttempts(3);
    o.getSecurity().setLoginWindow(Duration.ofMinutes(5));
    return new LoginAttemptLimiter(o, clock);
  }

  @Test
  void blocksAfterTooManyFailuresUntilTheWindowEnds() {
    LoginAttemptLimiter l = limiter();
    for (int i = 0; i < 3; i++) {
      assertDoesNotThrow(() -> l.check("1.2.3.4"));
      l.failed("1.2.3.4");
    }
    assertThrows(TooManyRequestsException.class, () -> l.check("1.2.3.4"));
    assertTrue(l.retryAfterSeconds("1.2.3.4") > 0);
    assertDoesNotThrow(() -> l.check("5.6.7.8"), "other clients are not affected");

    now[0] = now[0].plus(Duration.ofMinutes(5));
    assertEquals(0, l.retryAfterSeconds("1.2.3.4"));
    assertDoesNotThrow(() -> l.check("1.2.3.4"));
  }

  @Test
  void successResetsTheCounter() {
    LoginAttemptLimiter l = limiter();
    l.failed("c");
    l.failed("c");
    l.succeeded("c");
    l.failed("c");
    l.failed("c");
    assertDoesNotThrow(() -> l.check("c"));
  }
}
