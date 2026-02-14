package it.lorisdemicheli.server_controller.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigMapping(prefix = "it.lorisdemicheli.server-controller")
public class ServerControllerConfig {

  @WithDefault("minecraft-servers")
  private String namespace;
  @WithDefault("domainexample.it")
  private String baseDomain;
  private String curseForgeApiKey; // Necessario per modpack CurseForge
  private String password;
}
