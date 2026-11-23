package it.lorisdemicheli.minecraft_servers_controller.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

/**
 * All the application configuration ({@code it.lorisdemicheli.minecraft-servers.*}). It is
 * validated once at startup: a wrong value stops the application with a message listing every
 * problem, instead of failing later on the first request.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = MinecraftServerOptions.PREFIX)
public class MinecraftServerOptions implements InitializingBean {

  public static final String PREFIX = "it.lorisdemicheli.minecraft-servers";

  private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");
  private static final Pattern DNS_SUBDOMAIN =
      Pattern.compile("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?");
  private static final Duration MIN_JWT_LIFETIME = Duration.ofMinutes(1);
  private static final Duration MAX_JWT_LIFETIME = Duration.ofDays(30);

  /** Namespace where servers are created. Must be the namespace of the shared volume claim. */
  private String namespace = "minecraft-servers";
  private String baseDomain;
  /** Needed only for CurseForge modpacks. */
  private String curseForgeApiKey;
  /**
   * Secret (in {@link #namespace}) holding the CurseForge key. When set, server pods read the key
   * from it instead of having it in clear text in their StatefulSet.
   */
  private SecretRef curseForgeApiKeySecret = new SecretRef();
  /** Base URL of the CurseForge API (the search is proxied so that the key never reaches browsers). */
  private String curseForgeApiUrl = "https://api.curseforge.com";
  private String serverImage = "itzg/minecraft-server";
  /** Time a server gets to save the world on stop before being killed. */
  private int terminationGracePeriodSeconds = 120;
  private List<String> corsAllowedOrigins = List.of("http://localhost:4200");

  private MinecraftServerSecurityOptions security = new MinecraftServerSecurityOptions();
  private MinecraftServerStorageOptions storage = new MinecraftServerStorageOptions();
  private KubernetesOptions kubernetes = new KubernetesOptions();
  private ConsoleOptions console = new ConsoleOptions();
  private ServerResourcesOptions serverResources = new ServerResourcesOptions();
  private ServerPodSecurityOptions serverSecurity = new ServerPodSecurityOptions();
  private LifecycleOptions lifecycle = new LifecycleOptions();

  @Getter
  @Setter
  public static class SecretRef {
    private String name;
    private String key = "curseforge-api-key";

    public boolean isSet() {
      return name != null && !name.isBlank();
    }
  }

  @Getter
  @Setter
  public static class MinecraftServerSecurityOptions {
    private String username;
    private String password;
    private String jwtSecret;
    private Long jwtExpirationMs;
    /** Failed logins accepted from one client within {@link #loginWindow} before answering 429. */
    private int loginMaxAttempts = 10;
    private Duration loginWindow = Duration.ofMinutes(5);
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
    /**
     * Deleted servers stay in the trash for this long before being purged. {@code 0} disables the
     * automatic purge (the trash is then emptied by hand).
     */
    private int trashRetentionHours = 24;
    /** Creating a server or uploading is refused when the volume has less free space than this. */
    private int minFreeGb = 2;
  }

  @Getter
  @Setter
  public static class KubernetesOptions {
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration writeTimeout = Duration.ofSeconds(30);
    /** Whole deadline of a command run inside a server pod (stdout, stderr and exit). */
    private Duration execTimeout = Duration.ofSeconds(20);
    /** Output kept in memory per stream of an exec; the rest is read and discarded. */
    private int execMaxOutputBytes = 1024 * 1024;
    /** Attempts (first one included) of an idempotent call failing with a transient error. */
    private int retryMaxAttempts = 4;
    private Duration retryInitialBackoff = Duration.ofMillis(200);
    private Duration retryMaxBackoff = Duration.ofSeconds(5);
  }

  @Getter
  @Setter
  public static class ConsoleOptions {
    private Duration infoPollInterval = Duration.ofSeconds(10);
    /** The shared info polling keeps running this long after its last viewer left. */
    private Duration infoStreamGracePeriod = Duration.ofSeconds(10);
    private Duration logPollInterval = Duration.ofMillis(500);
    /** SSE comment sent on idle log streams so that proxies do not close them. */
    private Duration heartbeatInterval = Duration.ofSeconds(15);
    /** Longer log lines are cut (and marked) before being sent. */
    private int maxLogLineLength = 8192;
  }

  /**
   * How the size the user picks (cpu in milli cpu, memory = Java heap in MB) becomes the
   * container's requests and limits. The defaults keep requests equal to limits (Guaranteed QoS),
   * as in previous versions.
   */
  @Getter
  @Setter
  public static class ServerResourcesOptions {
    /** CPU request as a percentage of the chosen cpu (the limit). */
    private int cpuRequestPercent = 100;
    /** Without a CPU limit a server can use idle CPU of the node (no throttling). */
    private boolean cpuLimitEnabled = true;
    /** Memory request as a percentage of the memory limit. */
    private int memoryRequestPercent = 100;
    /** Room for metaspace, threads, direct buffers... on top of the heap. */
    private int memoryOverheadPercent = 15;
    private int memoryOverheadMinMb = 256;
  }

  @Getter
  @Setter
  public static class ServerPodSecurityOptions {
    /**
     * Runs the Minecraft container directly as {@code storage.uid} with every capability dropped.
     * Off by default: the itzg image normally starts as root and switches user itself.
     */
    private boolean runAsNonRoot = false;
  }

  @Getter
  @Setter
  public static class LifecycleOptions {
    /** How often half-finished creations and deletions are looked for and completed. */
    private Duration reconcileInterval = Duration.ofMinutes(1);
    /** A server Service without StatefulSet is removed only once older than this. */
    private Duration orphanGracePeriod = Duration.ofMinutes(10);
  }

  // ---------------------------------------------------------------- validation

  @Override
  public void afterPropertiesSet() {
    validate();
  }

  /** Throws {@link IllegalStateException} listing every invalid property. */
  public void validate() {
    List<String> errors = new ArrayList<>();

    if (namespace == null || !DNS_LABEL.matcher(namespace).matches()) {
      errors.add("namespace must be a valid Kubernetes namespace name");
    }
    if (isBlank(baseDomain)) {
      errors.add("base-domain is required");
    }
    if (isBlank(serverImage)) {
      errors.add("server-image is required");
    }
    if (terminationGracePeriodSeconds < 0) {
      errors.add("termination-grace-period-seconds must be >= 0");
    }

    if (isBlank(security.username)) {
      errors.add("security.username is required");
    }
    if (isBlank(security.password)) {
      errors.add("security.password is required");
    }
    if (security.jwtSecret == null || security.jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      errors.add("security.jwt-secret is required (at least 32 bytes)");
    }
    if (security.jwtExpirationMs != null && (security.jwtExpirationMs < MIN_JWT_LIFETIME.toMillis()
        || security.jwtExpirationMs > MAX_JWT_LIFETIME.toMillis())) {
      errors.add("security.jwt-expiration-ms must be between " + MIN_JWT_LIFETIME.toMillis()
          + " and " + MAX_JWT_LIFETIME.toMillis());
    }
    if (security.loginMaxAttempts < 1) {
      errors.add("security.login-max-attempts must be >= 1");
    }
    positive(errors, "security.login-window", security.loginWindow);

    if (isBlank(storage.dataDir)) {
      errors.add("storage.data-dir is required");
    }
    if (storage.claimName == null || !DNS_SUBDOMAIN.matcher(storage.claimName).matches()) {
      errors.add("storage.claim-name must be a valid PersistentVolumeClaim name");
    }
    if (storage.uid < 0 || storage.gid < 0) {
      errors.add("storage.uid and storage.gid must be >= 0");
    }
    if (storage.trashRetentionHours < 0) {
      errors.add("storage.trash-retention-hours must be >= 0 (0 disables the purge)");
    }
    if (storage.minFreeGb < 0) {
      errors.add("storage.min-free-gb must be >= 0");
    }

    positive(errors, "kubernetes.connect-timeout", kubernetes.connectTimeout);
    positive(errors, "kubernetes.read-timeout", kubernetes.readTimeout);
    positive(errors, "kubernetes.write-timeout", kubernetes.writeTimeout);
    positive(errors, "kubernetes.exec-timeout", kubernetes.execTimeout);
    positive(errors, "kubernetes.retry-initial-backoff", kubernetes.retryInitialBackoff);
    positive(errors, "kubernetes.retry-max-backoff", kubernetes.retryMaxBackoff);
    if (kubernetes.execMaxOutputBytes < 1024) {
      errors.add("kubernetes.exec-max-output-bytes must be >= 1024");
    }
    if (kubernetes.retryMaxAttempts < 1 || kubernetes.retryMaxAttempts > 10) {
      errors.add("kubernetes.retry-max-attempts must be between 1 and 10");
    }

    positive(errors, "console.info-poll-interval", console.infoPollInterval);
    if (console.infoStreamGracePeriod == null || console.infoStreamGracePeriod.isNegative()) {
      errors.add("console.info-stream-grace-period must be >= 0");
    }
    positive(errors, "console.log-poll-interval", console.logPollInterval);
    positive(errors, "console.heartbeat-interval", console.heartbeatInterval);
    if (console.maxLogLineLength < 256) {
      errors.add("console.max-log-line-length must be >= 256");
    }

    percent(errors, "server-resources.cpu-request-percent", serverResources.cpuRequestPercent);
    percent(errors, "server-resources.memory-request-percent", serverResources.memoryRequestPercent);
    if (serverResources.memoryOverheadPercent < 0 || serverResources.memoryOverheadPercent > 200) {
      errors.add("server-resources.memory-overhead-percent must be between 0 and 200");
    }
    if (serverResources.memoryOverheadMinMb < 0) {
      errors.add("server-resources.memory-overhead-min-mb must be >= 0");
    }

    positive(errors, "lifecycle.reconcile-interval", lifecycle.reconcileInterval);
    positive(errors, "lifecycle.orphan-grace-period", lifecycle.orphanGracePeriod);

    if (!errors.isEmpty()) {
      throw new IllegalStateException(
          "Invalid configuration (" + PREFIX + ".*):\n  - " + String.join("\n  - ", errors));
    }
  }

  private static void positive(List<String> errors, String name, Duration value) {
    if (value == null || value.isZero() || value.isNegative()) {
      errors.add(name + " must be a positive duration");
    }
  }

  private static void percent(List<String> errors, String name, int value) {
    if (value < 1 || value > 100) {
      errors.add(name + " must be between 1 and 100");
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
