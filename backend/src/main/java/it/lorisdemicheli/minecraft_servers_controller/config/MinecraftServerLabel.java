package it.lorisdemicheli.minecraft_servers_controller.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MinecraftServerLabel {

  public final static String LABEL_PREFIX = "it.lorisdemicheli/";

  public final static String LABEL_SERVER_NAME = LABEL_PREFIX + "app";
  public final static String LABEL_SERVER_TYPE = LABEL_PREFIX + "server-type";
  public final static String LABEL_SERVER_CPU = LABEL_PREFIX + "cpu";
  public final static String LABEL_SERVER_MEMORY = LABEL_PREFIX + "memory";

  public final static String LABEL_SERVER_MINECRAFT_EULA = LABEL_PREFIX + "eula";
  public final static String LABEL_SERVER_MINECRAFT_VERSION = LABEL_PREFIX + "version";
  public final static String LABEL_SERVER_MODRINTH_PROJECT_ID =
      LABEL_PREFIX + "modrinth-project-id";
  public final static String LABEL_SERVER_CURSEFORGE_URL = LABEL_PREFIX + "curseforge-url";

  public final static String LABEL_MANAGED_BY = "managed-by";
}
