package it.lorisdemicheli.minecraft_servers_controller.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.config.TestOptions;
import it.lorisdemicheli.minecraft_servers_controller.security.JwtComponent.Rejection;

class JwtComponentTest {

  static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
  MinecraftServerOptions options = TestOptions.valid();

  JwtComponent jwt(Instant now) {
    return new JwtComponent(options, Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void validTokenGivesTheUsername() {
    String token = jwt(NOW).generateToken("admin");
    assertEquals("admin", jwt(NOW).validate(token).username().orElseThrow());
    assertTrue(jwt(NOW).isValid(token));
  }

  @Test
  void expiredTokenIsRefusedAsExpired() {
    options.getSecurity().setJwtExpirationMs(Duration.ofHours(1).toMillis());
    String token = jwt(NOW).generateToken("admin");
    assertTrue(jwt(NOW.plus(Duration.ofMinutes(59))).isValid(token));
    assertTrue(jwt(NOW.plus(Duration.ofMinutes(60).plusSeconds(10))).isValid(token), "clock skew");
    assertEquals(Rejection.EXPIRED,
        jwt(NOW.plus(Duration.ofMinutes(62))).validate(token).rejection());
  }

  @Test
  void malformedAndTamperedTokensAreInvalid() {
    String token = jwt(NOW).generateToken("admin");
    String[] parts = token.split("\\.");
    String forgedPayload = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"sub\":\"root\",\"iss\":\"minecraft-controller\"}".getBytes());
    for (String bad : new String[] {"", "garbage", "a.b.c", parts[0] + "." + forgedPayload + "." + parts[2],
        token.substring(0, token.length() - 2), "x".repeat(10_000)}) {
      assertEquals(Rejection.INVALID, jwt(NOW).validate(bad).rejection(), bad);
    }
    assertEquals(Rejection.INVALID, jwt(NOW).validate(null).rejection());
  }

  @Test
  void unsignedTokenIsRefused() {
    String none = Jwts.builder().setSubject("admin").setIssuer(JwtComponent.ISSUER)
        .setExpiration(Date.from(NOW.plusSeconds(600))).compact();
    assertFalse(jwt(NOW).isValid(none));
  }

  @Test
  void tokenSignedWithAnotherSecretIsRefused() {
    String other = Jwts.builder().setSubject("admin").setIssuer(JwtComponent.ISSUER)
        .setExpiration(Date.from(NOW.plusSeconds(600)))
        .signWith(Keys.hmacShaKeyFor("another-secret-another-secret-123".getBytes(StandardCharsets.UTF_8)),
            SignatureAlgorithm.HS256)
        .compact();
    assertEquals(Rejection.INVALID, jwt(NOW).validate(other).rejection());
  }

  @Test
  void tokenWithoutOurIssuerIsRefused() {
    String foreign = Jwts.builder().setSubject("admin").setIssuer("someone-else")
        .setExpiration(Date.from(NOW.plusSeconds(600)))
        .signWith(Keys.hmacShaKeyFor(options.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8)),
            SignatureAlgorithm.HS256)
        .compact();
    assertFalse(jwt(NOW).isValid(foreign));
  }

  @Test
  void passwordRotationRevokesPreviousTokens() {
    String before = jwt(NOW).generateToken("admin");
    options.getSecurity().setPassword("a-brand-new-password");
    JwtComponent rotated = jwt(NOW);
    assertEquals(Rejection.REVOKED, rotated.validate(before).rejection());
    assertTrue(rotated.isValid(rotated.generateToken("admin")));
  }

  @Test
  void secretRotationRevokesPreviousTokens() {
    String before = jwt(NOW).generateToken("admin");
    options.getSecurity().setJwtSecret("ffffffffffffffffffffffffffffffff");
    assertFalse(jwt(NOW).isValid(before));
  }

  @Test
  void credentialsClaimRevealsNothing() {
    String version = JwtComponent.credentialsVersion("0123456789abcdef0123456789abcdef", "admin",
        "secret-password");
    assertFalse(version.contains("secret"));
    assertEquals(22, version.length());
  }

  @Test
  void shortSecretIsRefusedAtStartup() {
    options.getSecurity().setJwtSecret("too-short");
    assertThrows(IllegalStateException.class, () -> jwt(NOW));
  }
}
