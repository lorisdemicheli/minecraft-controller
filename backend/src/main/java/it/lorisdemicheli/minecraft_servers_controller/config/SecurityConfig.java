package it.lorisdemicheli.minecraft_servers_controller.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        // 1. Disabilita CSRF (spesso necessario per testare API REST facilmente)
        .csrf(csrf -> csrf.disable())

        // 2. Configura i permessi delle rotte
        .authorizeHttpRequests(auth -> auth
            // Permetti l'accesso libero a Swagger e documentazione OpenAPI
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                "/swagger-resources/**", "/webjars/**")
            .permitAll()

            // Tutto il resto richiede autenticazione
            .anyRequest().authenticated())

        // 3. Usa l'autenticazione Basic (username e password nel browser/header)
        .httpBasic(Customizer.withDefaults());

    return http.build();
  }

  @Bean
  UserDetailsService userDetailsService() {
    // 4. Configurazione Utenze In-Memory
    // NOTA: {noop} indica a Spring che la password è in chiaro (solo per sviluppo!)
    UserDetails admin =
        User.builder().username("admin").password("{noop}admin123").roles("ADMIN").build();

    UserDetails user =
        User.builder().username("user").password("{noop}user123").roles("USER").build();

    return new InMemoryUserDetailsManager(admin, user);
  }
}
