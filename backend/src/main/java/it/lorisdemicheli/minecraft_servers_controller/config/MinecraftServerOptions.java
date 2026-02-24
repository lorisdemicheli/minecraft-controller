package it.lorisdemicheli.minecraft_servers_controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "it.lorisdemicheli.minecraft-servers")
public class MinecraftServerOptions {
  private String namespace;
  private String baseDomain;
  private String curseForgeApiKey; // Necessario per modpack CurseForge

  private MinecraftServerSecurityOptions security;

  @Getter
  @Setter
  public static class MinecraftServerSecurityOptions {
    private String username;
    private String password;
    private String jwtSecret;
    private Long jwtExpirationMs;
  }
}
