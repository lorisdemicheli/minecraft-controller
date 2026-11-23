package it.lorisdemicheli.minecraft_servers_controller.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import it.lorisdemicheli.minecraft_servers_controller.config.TestOptions;

class CustomUserDetailsServiceTest {

  PasswordEncoder encoder = new BCryptPasswordEncoder(4);
  CustomUserDetailsService service =
      new CustomUserDetailsService(TestOptions.valid(), encoder);

  @Test
  void loadsTheConfiguredAdminWithAHashedPassword() {
    UserDetails user = service.loadUserByUsername("admin");
    assertEquals("admin", user.getUsername());
    assertTrue(encoder.matches("secret-password", user.getPassword()));
  }

  @Test
  void unknownUsersAreRejected() {
    assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("someone"));
  }

  /**
   * Spring Security erases the credentials of the user it just authenticated. When the service
   * handed out one shared instance, the first successful login wiped the password and every later
   * login failed until the application was restarted.
   */
  @Test
  void aLoginErasingTheCredentialsDoesNotLockTheAdminOut() {
    UserDetails first = service.loadUserByUsername("admin");
    ((CredentialsContainer) first).eraseCredentials();

    UserDetails second = service.loadUserByUsername("admin");
    assertNotSame(first, second);
    assertNotNull(second.getPassword());
    assertTrue(encoder.matches("secret-password", second.getPassword()));
  }
}
