package it.lorisdemicheli.minecraft_servers_controller.config;

/**
 * Labels are only used to <em>select</em> resources. The configuration of a server lives in a single
 * annotation (label values cannot hold URLs and are limited to 63 characters).
 */
public final class MinecraftServerLabel {

  public static final String LABEL_PREFIX = "it.lorisdemicheli/";

  public static final String LABEL_SERVER_NAME = LABEL_PREFIX + "app";
  public static final String LABEL_MANAGED_BY = "managed-by";
  public static final String MANAGED_BY_VALUE = "minecraft-controller";

  /** JSON of {@code ServerConfig}. */
  public static final String ANNOTATION_CONFIG = LABEL_PREFIX + "config";
  /** itzg/mc-router discovers servers through this annotation on their Service. */
  public static final String ANNOTATION_ROUTER = "mc-router.itzg.me/externalServerName";

  private MinecraftServerLabel() {}
}
