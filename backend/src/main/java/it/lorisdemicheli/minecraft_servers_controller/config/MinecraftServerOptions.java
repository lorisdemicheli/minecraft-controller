package it.lorisdemicheli.minecraft_servers_controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "minecraft")
public class MinecraftServerOptions {
  private String namespace = "minecraft-servers";
  private String baseDomain = "lorisdemicheli.it";
  private String curseForgeApiKey; // Necessario per modpack CurseForge
}
