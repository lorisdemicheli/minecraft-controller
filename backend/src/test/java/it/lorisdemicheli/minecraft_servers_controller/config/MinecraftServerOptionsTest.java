package it.lorisdemicheli.minecraft_servers_controller.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MinecraftServerOptionsTest {

  @Test
  void validConfigurationPasses() {
    assertDoesNotThrow(() -> TestOptions.valid().validate());
  }

  @Test
  void everyProblemIsReportedAtOnce() {
    MinecraftServerOptions o = TestOptions.valid();
    o.setBaseDomain("");
    o.getSecurity().setPassword(null);
    o.getSecurity().setJwtSecret("short");
    o.getSecurity().setJwtExpirationMs(1000L);
    o.getStorage().setTrashRetentionHours(-1);
    o.getKubernetes().setExecTimeout(Duration.ZERO);
    o.getServerResources().setCpuRequestPercent(0);
    o.setNamespace("Not_A_Namespace");

    IllegalStateException e = assertThrows(IllegalStateException.class, o::validate);
    for (String property : new String[] {"base-domain", "security.password", "security.jwt-secret",
        "jwt-expiration-ms", "trash-retention-hours", "exec-timeout", "cpu-request-percent",
        "namespace"}) {
      assertTrue(e.getMessage().contains(property), property + " in: " + e.getMessage());
    }
  }

  @Test
  void secretsAreNeverPartOfTheMessage() {
    MinecraftServerOptions o = TestOptions.valid();
    o.getSecurity().setJwtSecret("tiny-secret-value");
    IllegalStateException e = assertThrows(IllegalStateException.class, o::validate);
    assertTrue(!e.getMessage().contains("tiny-secret-value"));
  }
}
