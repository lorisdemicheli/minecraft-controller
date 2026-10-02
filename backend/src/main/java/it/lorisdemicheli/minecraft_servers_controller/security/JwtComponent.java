package it.lorisdemicheli.minecraft_servers_controller.security;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import org.springframework.stereotype.Component;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;

@Component
public class JwtComponent {

  private static final long DEFAULT_EXPIRATION_MS = 24 * 60 * 60 * 1000L;

  private final Key key;
  private final long expirationMs;

  public JwtComponent(MinecraftServerOptions options) {
    var security = options.getSecurity();
    String secret = security.getJwtSecret();
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(
          "it.lorisdemicheli.minecraft-servers.security.jwt-secret must be set (at least 32 characters)");
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.expirationMs =
        security.getJwtExpirationMs() == null ? DEFAULT_EXPIRATION_MS : security.getJwtExpirationMs();
  }

  public String generateToken(String username) {
    return Jwts.builder().setSubject(username).setIssuedAt(new Date())
        .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
        .signWith(key, SignatureAlgorithm.HS256).compact();
  }

  public String extractUsername(String token) {
    return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody()
        .getSubject();
  }

  public boolean isValid(String token) {
    try {
      Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
