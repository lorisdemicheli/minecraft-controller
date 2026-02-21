package it.lorisdemicheli.minecraft_servers_controller.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

  private final MinecraftServerOptions serverOptions;
  private final PasswordEncoder passwordEncoder;

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    if (!serverOptions.getSecurity().getUsername().equals(username)) {
      throw new UsernameNotFoundException("Utente non trovato: " + username);
    }

    return User.builder() //
        .username(serverOptions.getSecurity().getUsername()) //
        .password(passwordEncoder.encode(serverOptions.getSecurity().getPassword())) //
        .build();
  }
}
