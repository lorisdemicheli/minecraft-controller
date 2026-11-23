package it.lorisdemicheli.minecraft_servers_controller.health;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import lombok.RequiredArgsConstructor;

/**
 * Readiness of the controller: the shared data volume must be mounted and writable. Kubernetes
 * API reachability is deliberately not part of any probe: an API outage must neither restart the
 * controller (liveness) nor hide its error messages behind the Ingress (readiness).
 */
@Component("dataVolume")
@RequiredArgsConstructor
public class DataVolumeHealthIndicator implements HealthIndicator {

  private final MinecraftServerOptions options;

  @Override
  public Health health() {
    Path dir = Path.of(options.getStorage().getDataDir());
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      return Health.down().withDetail("reason", "data directory missing").build();
    }
    if (!Files.isWritable(dir)) {
      return Health.down().withDetail("reason", "data directory not writable").build();
    }
    return Health.up().build();
  }
}
