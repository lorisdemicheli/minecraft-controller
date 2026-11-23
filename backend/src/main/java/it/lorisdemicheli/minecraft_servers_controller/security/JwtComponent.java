package it.lorisdemicheli.minecraft_servers_controller.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import lombok.extern.slf4j.Slf4j;

/**
 * Issues and validates the admin's tokens (HS256, stateless).
 *
 * <p><b>Revocation without a database.</b> Every token carries a {@code cv} claim, an HMAC of the
 * current username and password keyed with the JWT secret. Changing the password (or the username)
 * changes the expected value, so every token issued before the rotation is refused. Rotating the
 * JWT secret invalidates all tokens as well. Neither the password nor the secret can be derived
 * from the claim.
 */
@Slf4j
@Component
public class JwtComponent {

  static final String ISSUER = "minecraft-controller";
  static final String CREDENTIALS_CLAIM = "cv";
  private static final long DEFAULT_EXPIRATION_MS = 24 * 60 * 60 * 1000L;
  private static final long CLOCK_SKEW_SECONDS = 30;
  /** Real tokens are ~300 bytes: anything much bigger is not ours. */
  private static final int MAX_TOKEN_LENGTH = 4096;

  /** Why a token was refused (never contains the token itself). */
  public enum Rejection {
    EXPIRED, INVALID, REVOKED
  }

  public record Validation(Optional<String> username, Rejection rejection) {
    static Validation ok(String username) {
      return new Validation(Optional.of(username), null);
    }

    static Validation rejected(Rejection rejection) {
      return new Validation(Optional.empty(), rejection);
    }
  }

  private final SecretKey key;
  private final long expirationMs;
  private final String credentialsVersion;
  private final Clock clock;
  private final JwtParser parser;

  public JwtComponent(MinecraftServerOptions options, Clock clock) {
    var security = options.getSecurity();
    String secret = security.getJwtSecret();
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(
          "it.lorisdemicheli.minecraft-servers.security.jwt-secret must be set (at least 32 bytes)");
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.expirationMs =
        security.getJwtExpirationMs() == null ? DEFAULT_EXPIRATION_MS : security.getJwtExpirationMs();
    this.credentialsVersion =
        credentialsVersion(secret, security.getUsername(), security.getPassword());
    this.clock = clock;
    this.parser = Jwts.parserBuilder() //
        .setSigningKey(key) //
        .requireIssuer(ISSUER) //
        .setAllowedClockSkewSeconds(CLOCK_SKEW_SECONDS) //
        .setClock(() -> Date.from(clock.instant())) //
        .build();
  }

  public String generateToken(String username) {
    Instant now = clock.instant();
    return Jwts.builder() //
        .setIssuer(ISSUER) //
        .setSubject(username) //
        .setId(UUID.randomUUID().toString()) //
        .setIssuedAt(Date.from(now)) //
        .setExpiration(Date.from(now.plusMillis(expirationMs))) //
        .claim(CREDENTIALS_CLAIM, credentialsVersion) //
        .signWith(key, SignatureAlgorithm.HS256) //
        .compact();
  }

  public long expirationMs() {
    return expirationMs;
  }

  /** Parses the token once: signature, algorithm, issuer, expiry and credentials version. */
  public Validation validate(String token) {
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      return Validation.rejected(Rejection.INVALID);
    }
    try {
      Jws<Claims> jws = parser.parseClaimsJws(token);
      if (!SignatureAlgorithm.HS256.getValue().equals(jws.getHeader().getAlgorithm())) {
        return Validation.rejected(Rejection.INVALID);
      }
      Claims claims = jws.getBody();
      String version = claims.get(CREDENTIALS_CLAIM, String.class);
      if (version == null || !MessageDigest.isEqual(version.getBytes(StandardCharsets.UTF_8),
          credentialsVersion.getBytes(StandardCharsets.UTF_8))) {
        log.debug("Token refused: issued for previous credentials");
        return Validation.rejected(Rejection.REVOKED);
      }
      String subject = claims.getSubject();
      return subject == null || subject.isBlank() ? Validation.rejected(Rejection.INVALID)
          : Validation.ok(subject);
    } catch (ExpiredJwtException e) {
      log.debug("Token refused: expired");
      return Validation.rejected(Rejection.EXPIRED);
    } catch (JwtException | IllegalArgumentException e) {
      log.debug("Token refused: {}", e.getClass().getSimpleName());
      return Validation.rejected(Rejection.INVALID);
    }
  }

  public boolean isValid(String token) {
    return validate(token).username().isPresent();
  }

  static String credentialsVersion(String secret, String username, String password) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(("credentials\0" + username + "\0" + password)
          .getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 not available", e);
    }
  }
}
