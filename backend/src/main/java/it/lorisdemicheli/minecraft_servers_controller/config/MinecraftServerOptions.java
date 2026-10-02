package it.lorisdemicheli.minecraft_servers_controller.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "it.lorisdemicheli.minecraft-servers")
public class MinecraftServerOptions {

  /** Namespace where servers are created. Must be the namespace of the shared volume claim. */
  private String namespace = "minecraft-servers";
  private String baseDomain;
  /** Needed only for CurseForge modpacks. */
  private String curseForgeApiKey;
  private String serverImage = "itzg/minecraft-server";
  /** Time a server gets to save the world on stop before being killed. */
  private int terminationGracePeriodSeconds = 120;
  private List<String> corsAllowedOrigins = List.of("http://localhost:4200");

  private MinecraftServerSecurityOptions security = new MinecraftServerSecurityOptions();
  private MinecraftServerStorageOptions storage = new MinecraftServerStorageOptions();

  @Getter
  @Setter
  public static class MinecraftServerSecurityOptions {
    private String username;
    private String password;
    private String jwtSecret;
    private Long jwtExpirationMs;
  }

  @Getter
  @Setter
  public static class MinecraftServerStorageOptions {
    /** Where the shared volume is mounted in the controller (a plain folder in local dev). */
    private String dataDir = "./data";
    /** Name of the PersistentVolumeClaim mounted by the servers (one subPath per server). */
    private String claimName = "minecraft-servers-data";
    /** UID/GID the server processes and the controller run as (itzg default: 1000). */
    private int uid = 1000;
    private int gid = 1000;
    /** Deleted servers stay in the trash for this long before being purged. */
    private int trashRetentionHours = 24;
    /** Creating a server is refused when the volume has less free space than this. */
    private int minFreeGb = 2;
  }
}
