package it.lorisdemicheli.minecraft_servers_controller.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;

/** Single admin account taken from configuration. The password is hashed once, at startup. */
@Service
public class CustomUserDetailsService implements UserDetailsService {

  private final UserDetails admin;

  public CustomUserDetailsService(MinecraftServerOptions options, PasswordEncoder passwordEncoder) {
    var security = options.getSecurity();
    if (isBlank(security.getUsername()) || isBlank(security.getPassword())) {
      throw new IllegalStateException(
          "it.lorisdemicheli.minecraft-servers.security.username and .password must be set");
    }
    this.admin = User.builder() //
        .username(security.getUsername()) //
        .password(passwordEncoder.encode(security.getPassword())) //
        .build();
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    if (!admin.getUsername().equals(username)) {
      throw new UsernameNotFoundException("Utente non trovato: " + username);
    }
    return admin;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
