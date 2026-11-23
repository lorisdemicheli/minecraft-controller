package it.lorisdemicheli.minecraft_servers_controller.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import it.lorisdemicheli.minecraft_servers_controller.security.JwtAuthFilter;
import it.lorisdemicheli.minecraft_servers_controller.security.JwtComponent;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  /**
   * Health and metrics are only served on the management port (not routed by the Ingress), see
   * {@code management.server.port}.
   */
  private static final String[] PUBLIC_PATHS = {"/login", "/error", "/swagger-ui/**",
      "/swagger-ui.html", "/v3/api-docs/**", "/v3/api-docs.yaml", "/actuator/health/**",
      "/actuator/health", "/actuator/prometheus", "/actuator/info"};

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
      CorsConfigurationSource corsConfigurationSource, ObjectMapper objectMapper)
      throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource))
        // stateless bearer tokens, no cookies: CSRF does not apply
        .csrf(csrf -> csrf.disable()) //
        .formLogin(form -> form.disable()) //
        .httpBasic(basic -> basic.disable()) //
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth -> auth.requestMatchers(PUBLIC_PATHS).permitAll().anyRequest().authenticated())
        .exceptionHandling(errors -> errors
            // 401 (not 403) without a valid token, so the console knows it must log in again
            .authenticationEntryPoint((request, response, e) -> ApiErrors.write(response,
                objectMapper, HttpStatus.UNAUTHORIZED, unauthorizedMessage(request)))
            .accessDeniedHandler((request, response, e) -> ApiErrors.write(response, objectMapper,
                HttpStatus.FORBIDDEN, "Access denied")))
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  private static String unauthorizedMessage(HttpServletRequest request) {
    Object rejection = request.getAttribute(JwtAuthFilter.REJECTION_ATTRIBUTE);
    if (rejection == JwtComponent.Rejection.EXPIRED) {
      return "Session expired: log in again";
    }
    if (rejection == JwtComponent.Rejection.REVOKED) {
      return "Credentials changed: log in again";
    }
    return rejection == null ? "Authentication required" : "Invalid token";
  }

  @Bean
  OpenAPI openAPI() {
    return new OpenAPI() //
        .info(new Info() //
            .title("Minecraft Controller API") //
            .version("1.0")) //
        .addSecurityItem(new SecurityRequirement().addList("bearer")) //
        .components(new Components() //
            .addSecuritySchemes("bearer", new SecurityScheme() //
                .name("bearer") //
                .type(SecurityScheme.Type.HTTP) //
                .scheme("bearer") //
                .bearerFormat("JWT")));
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(MinecraftServerOptions options) {
    CorsConfiguration configuration = new CorsConfiguration();

    configuration.setAllowedOrigins(options.getCorsAllowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept",
        "Cache-Control", "Last-Event-ID"));
    configuration.setExposedHeaders(List.of("Content-Disposition", "Retry-After", "Location"));
    // bearer tokens travel in a header, no cookies: credentials mode is not needed
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  @Bean
  AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
    return config.getAuthenticationManager();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
