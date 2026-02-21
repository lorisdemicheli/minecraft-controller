package it.lorisdemicheli.minecraft_servers_controller.security;

import java.security.Key;
import java.util.Date;
import org.springframework.stereotype.Component;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtComponent {

  private final MinecraftServerOptions options;

  private Key key() {
    return Keys.hmacShaKeyFor(options.getSecurity().getJwtSecret().getBytes());
  }

  public String generateToken(String username) {
    return Jwts.builder().setSubject(username).setIssuedAt(new Date())
        .setExpiration(new Date(System.currentTimeMillis() + options.getSecurity().getJwtExpirationMs()))
        .signWith(key(), SignatureAlgorithm.HS256).compact();
  }

  public String extractUsername(String token) {
    return Jwts.parserBuilder().setSigningKey(key()).build().parseClaimsJws(token).getBody()
        .getSubject();
  }

  public boolean isValid(String token) {
    try {
      Jwts.parserBuilder().setSigningKey(key()).build().parseClaimsJws(token);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
