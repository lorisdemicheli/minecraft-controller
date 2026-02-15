package it.lorisdemicheli.server_controller.config;

import java.util.Optional;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "it.lorisdemicheli.server-controller")
public interface ServerControllerConfig {

  @WithDefault("minecraft-servers")
  public String getNamespace();
  @WithDefault("domainexample.it")
  public String getBaseDomain();
  public Optional<String> getCurseForgeApiKey(); // Necessario per modpack CurseForge
  @WithDefault("admin")
  public String getPassword();
}
