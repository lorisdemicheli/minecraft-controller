package it.lorisdemicheli.minecraft_servers_controller.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable()) // Disabilitato per semplicità nei test API
        .authorizeHttpRequests(auth -> auth.requestMatchers( //
            "/v3/api-docs/**", //
            "/swagger-ui/**", //
            "/swagger-ui.html", //
            "/swagger-resources/**", //
            "/webjars/**" //
        ).permitAll().anyRequest().authenticated()).formLogin(form -> form //
            .loginProcessingUrl("/api/auth/login") //
            .successHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_OK)) //
            .failureHandler((req, res, exp) -> res.setStatus(HttpServletResponse.SC_UNAUTHORIZED)) //
            .permitAll() //
        ).exceptionHandling(ex -> ex //
            .authenticationEntryPoint(
                (req, res, authException) -> res.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
        .logout(logout -> logout //
            .logoutUrl("/api/auth/logout") //
            .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_OK)) //
            .deleteCookies("JSESSIONID") //
            .invalidateHttpSession(true) //
        );

    return http.build();
  }

  @Bean
  UserDetailsService userDetailsService(MinecraftServerOptions options) {
    return new InMemoryUserDetailsManager(User.builder() //
        .username("admin") //
        .password("{noop}" + options.getPassword()) //
        .build());
  }
}
