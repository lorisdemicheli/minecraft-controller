package it.lorisdemicheli.minecraft_servers_controller.security;

import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Authenticates requests carrying {@code Authorization: Bearer <jwt>}. An invalid token leaves the
 * request unauthenticated; the reason is kept in a request attribute so that the 401 answer can
 * tell an expired session from a bad token.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

  /** Request attribute holding the {@link JwtComponent.Rejection} of a refused token. */
  public static final String REJECTION_ATTRIBUTE = JwtAuthFilter.class.getName() + ".rejection";

  private static final String BEARER = "Bearer ";

  private final JwtComponent jwt;
  private final CustomUserDetailsService userDetailsService;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {

    String authHeader = request.getHeader("Authorization");

    if (authHeader != null && authHeader.regionMatches(true, 0, BEARER, 0, BEARER.length())
        && SecurityContextHolder.getContext().getAuthentication() == null) {
      JwtComponent.Validation validation = jwt.validate(authHeader.substring(BEARER.length()).trim());
      Optional<String> username = validation.username();
      if (username.isPresent()) {
        try {
          UserDetails userDetails = userDetailsService.loadUserByUsername(username.get());
          UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
              userDetails, null, userDetails.getAuthorities());
          auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
          SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (UsernameNotFoundException e) {
          // token of a user that no longer exists (username changed): unauthenticated -> 401
          request.setAttribute(REJECTION_ATTRIBUTE, JwtComponent.Rejection.REVOKED);
        }
      } else {
        request.setAttribute(REJECTION_ATTRIBUTE, validation.rejection());
      }
    }

    filterChain.doFilter(request, response);
  }

  /** SSE responses are written in async dispatches: they must stay authenticated. */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }
}
