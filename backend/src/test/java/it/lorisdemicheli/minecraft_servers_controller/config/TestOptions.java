package it.lorisdemicheli.minecraft_servers_controller.config;

import java.time.Duration;

/** Valid options for unit tests. */
public final class TestOptions {

  private TestOptions() {}

  public static MinecraftServerOptions valid() {
    MinecraftServerOptions o = new MinecraftServerOptions();
    o.setNamespace("mc");
    o.setBaseDomain("example.test");
    o.getSecurity().setUsername("admin");
    o.getSecurity().setPassword("secret-password");
    o.getSecurity().setJwtSecret("0123456789abcdef0123456789abcdef");
    o.getKubernetes().setExecTimeout(Duration.ofSeconds(2));
    o.getKubernetes().setRetryInitialBackoff(Duration.ofMillis(1));
    o.getKubernetes().setRetryMaxBackoff(Duration.ofMillis(2));
    o.getStorage().setMinFreeGb(0);
    return o;
  }
}
